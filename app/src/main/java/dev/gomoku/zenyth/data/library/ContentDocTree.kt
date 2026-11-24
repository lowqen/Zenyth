package dev.gomoku.zenyth.data.library

import android.content.ContentResolver
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * A SAF folder (`OpenDocumentTree`) as a [DocTree] (design §3.3). Every call is
 * a binder round trip, so a listing is one child query with every column.
 *
 * - Renaming never overwrites: `ExternalStorageProvider` answers a taken name
 *   with ` (1)` — [rename] returns what the document is now called.
 * - Files are made as `application/octet-stream`, which keeps the display name
 *   whole (no extension added).
 * - Reads go through a file descriptor, so a tail read seeks (`skip` = lseek).
 */
class ContentDocTree(private val resolver: ContentResolver, val treeUri: Uri) : DocTree {
    override val atomicReplace: Boolean get() = false

    private val rootId: String = DocumentsContract.getTreeDocumentId(treeUri)

    fun uriOf(id: String): Uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id)

    private fun dirId(dir: String?) = dir ?: rootId

    private val columns = arrayOf(
        Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_SIZE,
        Document.COLUMN_LAST_MODIFIED, Document.COLUMN_MIME_TYPE, Document.COLUMN_FLAGS,
    )

    private fun Cursor.entry(): DocEntry = DocEntry(
        id = getString(0),
        name = getString(1) ?: "",
        size = if (isNull(2)) 0L else getLong(2),
        mtime = if (isNull(3)) 0L else getLong(3),
        isDir = getString(4) == Document.MIME_TYPE_DIR,
    )

    override fun children(dir: String?): List<DocEntry> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, dirId(dir))
        val out = ArrayList<DocEntry>()
        resolver.query(uri, columns, null, null, null)?.use { c -> while (c.moveToNext()) out += c.entry() }
            ?: throw IOException("cannot list $uri")
        return out
    }

    private fun query(id: String): Pair<DocEntry, Int>? =
        resolver.query(uriOf(id), columns, null, null, null)?.use { c ->
            if (c.moveToFirst()) c.entry() to (if (c.isNull(5)) 0 else c.getInt(5)) else null
        }

    private fun stat(id: String): DocEntry = query(id)?.first ?: throw FileNotFoundException(id)

    override fun dir(name: String, create: Boolean): String? {
        children(null).firstOrNull { it.isDir && it.name == name }?.let { return it.id }
        if (!create) return null
        val uri = DocumentsContract.createDocument(resolver, uriOf(rootId), Document.MIME_TYPE_DIR, name)
            ?: throw IOException("mkdir $name")
        return DocumentsContract.getDocumentId(uri)
    }

    override fun create(dir: String?, name: String): DocEntry {
        val uri = DocumentsContract.createDocument(resolver, uriOf(dirId(dir)), "application/octet-stream", name)
            ?: throw IOException("create $name")
        return stat(DocumentsContract.getDocumentId(uri))
    }

    override fun openInput(id: String): InputStream {
        val pfd = resolver.openFileDescriptor(uriOf(id), "r") ?: throw FileNotFoundException(id)
        return ParcelFileDescriptor.AutoCloseInputStream(pfd)
    }

    override fun openOutput(id: String): OutputStream {
        val uri = uriOf(id)
        // "wt" truncates; a few providers only know "w" (and then truncate on their own).
        val pfd = runCatching { resolver.openFileDescriptor(uri, "wt") }.getOrNull()
            ?: resolver.openFileDescriptor(uri, "w") ?: throw FileNotFoundException(id)
        // AutoCloseOutputStream owns the descriptor (closing it twice would trip fdsan).
        return object : ParcelFileDescriptor.AutoCloseOutputStream(pfd) {
            private var closed = false
            override fun close() {
                if (closed) return
                closed = true
                try {
                    flush()
                    runCatching { fd.sync() }   // some providers' pipes cannot sync
                } finally {
                    super.close()
                }
            }
        }
    }

    override fun rename(id: String, name: String): DocEntry {
        val renamed = DocumentsContract.renameDocument(resolver, uriOf(id), name)
        return stat(renamed?.let { DocumentsContract.getDocumentId(it) } ?: id)
    }

    override fun delete(id: String) {
        if (!DocumentsContract.deleteDocument(resolver, uriOf(id))) throw IOException("delete $id")
    }

    override fun move(id: String, fromDir: String?, toDir: String?): DocEntry? {
        val flags = query(id)?.second ?: return null
        if ((flags and Document.FLAG_SUPPORTS_MOVE) == 0) return null
        val moved = DocumentsContract.moveDocument(resolver, uriOf(id), uriOf(dirId(fromDir)), uriOf(dirId(toDir)))
            ?: return null
        return stat(DocumentsContract.getDocumentId(moved))
    }
}
