package dev.gomoku.zenyth.data.library

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.gomoku.zenyth.domain.library.LibraryEntries
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

private val Context.librarySourcesStore by preferencesDataStore(name = "library_sources")

/**
 * The library's sources: app storage always, plus the SAF folders the user
 * picked (design §3.3). Folders are kept in DataStore `library_sources`
 * (tree URI · label); at load each is checked against the persisted grants —
 * a missing one is [SourceAccess.LOST] and the chip row offers «폴더 다시 허용»
 * ([reopenIntentUri] seeds the picker with the same folder).
 *
 * Grants are limited (128, 512 on API 30+), so removing a folder releases its.
 */
@Singleton
class LibrarySources @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    @Serializable
    private data class Row(val id: String, val uri: String, val label: String, val addedAt: Long)

    private val key = stringPreferencesKey("folders")
    private val json = Json { ignoreUnknownKeys = true }
    private val rows = ListSerializer(Row.serializer())
    private val lock = Mutex()

    val app: AppDirSource = AppDirSource(File(context.filesDir, AppDirSource.DIR))

    private val folderAccess = HashMap<String, MutableStateFlow<SourceAccess>>()
    private val _sources = MutableStateFlow<List<LibrarySource>>(listOf(app))
    val sources: StateFlow<List<LibrarySource>> = _sources.asStateFlow()

    fun sourceOf(id: String): LibrarySource? = _sources.value.firstOrNull { it.id == id }

    private suspend fun readRows(): List<Row> =
        context.librarySourcesStore.data.first()[key]?.let { runCatching { json.decodeFromString(rows, it) }.getOrNull() }.orEmpty()

    private suspend fun writeRows(list: List<Row>) {
        context.librarySourcesStore.edit { it[key] = json.encodeToString(rows, list) }
    }

    private fun granted(uri: Uri): Boolean =
        context.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission && it.isWritePermission }

    private fun sourceFor(row: Row): TreeSource {
        val uri = Uri.parse(row.uri)
        val access = folderAccess.getOrPut(row.id) { MutableStateFlow(SourceAccess.GRANTED) }
        access.value = if (granted(uri)) SourceAccess.GRANTED else SourceAccess.LOST
        return TreeSource(row.id, SourceKind.SAF, row.label, ContentDocTree(context.contentResolver, uri), access)
    }

    /** Reads the folder list and checks every grant (app start, and back from the picker). */
    suspend fun load(): List<LibrarySource> = lock.withLock {
        val list = readRows()
        val built = withContext(Dispatchers.IO) { list.map { sourceFor(it) } }
        _sources.value = listOf(app) + built
        _sources.value
    }

    /**
     * Adds a folder from `OpenDocumentTree` — takes the persistable grant first.
     * A folder already listed (same URI) is granted again and returned.
     */
    suspend fun addFolder(treeUri: Uri): LibrarySource = lock.withLock {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        context.contentResolver.takePersistableUriPermission(treeUri, flags)
        val list = readRows()
        val existing = list.firstOrNull { it.uri == treeUri.toString() }
        val row = existing ?: Row("saf-" + LibraryEntries.newId(), treeUri.toString(), labelOf(treeUri), System.currentTimeMillis())
        if (existing == null) writeRows(list + row)
        val src = withContext(Dispatchers.IO) { sourceFor(row) }
        _sources.value = _sources.value.filter { it.id != row.id } + src
        src
    }

    /** «라이브러리에서 빼기» — forgets the folder and releases its grant. Files are untouched. */
    suspend fun removeFolder(id: String) = lock.withLock {
        val list = readRows()
        val row = list.firstOrNull { it.id == id } ?: return@withLock
        writeRows(list - row)
        runCatching {
            context.contentResolver.releasePersistableUriPermission(
                Uri.parse(row.uri),
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
        folderAccess.remove(id)
        _sources.value = _sources.value.filter { it.id != id }
    }

    /** For `EXTRA_INITIAL_URI` when asking for a lost folder again. */
    fun reopenIntentUri(id: String): Uri? {
        val tree = ((sourceOf(id) as? TreeSource)?.tree as? ContentDocTree)?.treeUri ?: return null
        return DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
    }

    private fun labelOf(treeUri: Uri): String {
        val doc = DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))
        return runCatching {
            context.contentResolver.query(doc, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull() ?: treeUri.lastPathSegment.orEmpty().substringAfterLast('/').substringAfterLast(':')
    }
}
