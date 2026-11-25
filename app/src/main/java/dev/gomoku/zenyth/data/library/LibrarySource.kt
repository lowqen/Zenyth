package dev.gomoku.zenyth.data.library

import android.os.ParcelFileDescriptor
import dev.gomoku.zenyth.domain.library.FileStat
import dev.gomoku.zenyth.domain.library.Fingerprint
import dev.gomoku.zenyth.domain.library.LibraryManifest
import dev.gomoku.zenyth.domain.library.ManifestCodec
import dev.gomoku.zenyth.domain.library.yxdb.Yxdb
import kotlinx.coroutines.flow.StateFlow
import java.io.InputStream
import java.io.OutputStream

enum class SourceKind { APP, SAF }

/** Can the app reach the folder — a SAF grant can be lost (revoked, folder gone). */
enum class SourceAccess { GRANTED, LOST }

/**
 * The temp file of an atomic write ([LibrarySource.writeAtomic]). The caller
 * writes it, reads it back to check (record count, size) and returns; throwing
 * leaves the real file untouched. [docId] lets the job process (L5-3) open it
 * by descriptor instead of a stream.
 */
class StagedFile(private val tree: DocTree, val docId: String) {
    /** Truncates; syncs to storage on close. */
    fun openOutput(): OutputStream = tree.openOutput(docId)
    fun openInput(): InputStream = tree.openInput(docId)

    /** For the job process: `wt` to write, then `r` (opened after) to read back. */
    fun openFd(mode: String): ParcelFileDescriptor = tree.openFd(docId, mode)
}

/**
 * Where library files live (design §3.1, web `source.ts`): app storage
 * ([AppDirSource]) or a SAF folder. A source knows files and its sidecar only —
 * which entry is attached, and making or choosing entries, sit above it.
 */
interface LibrarySource {
    val id: String
    val kind: SourceKind
    /** Chip label (folder name); empty for app storage. */
    val label: String
    val access: StateFlow<SourceAccess>

    /** Files in the source (journals included) — not the trash, the sidecar, or in-flight temp files. */
    suspend fun list(): List<FileStat>
    suspend fun stat(name: String): FileStat?
    suspend fun openRead(name: String): InputStream?
    /** A descriptor for the library job process ([dev.gomoku.zenyth.data.library.job.LibraryJobClient]); null when absent. */
    suspend fun openFd(name: String, mode: String = "r"): ParcelFileDescriptor?
    /** Size + FNV-1a 64 of the last 4 KB — reads the tail only. */
    suspend fun fingerprint(name: String): Fingerprint?

    /**
     * Writes [name] whole or not at all: [write] fills a temp file, which then
     * replaces [name]. A stale `<name>.journal` belongs to the replaced bytes,
     * so it is removed unless [dropJournal] is false. Returns the file as the
     * provider now lists it (its own mtime — recorded so the PC side does not
     * measure again).
     */
    suspend fun writeAtomic(name: String, dropJournal: Boolean = true, write: suspend (StagedFile) -> Unit): FileStat

    /** Removes a file and its companions (journal, engine backups). Absent is fine. */
    suspend fun delete(name: String)

    /** Never overwrites; companions follow; a half-done rename is undone. Returns the new name. */
    suspend fun rename(from: String, to: String): String

    /** Into `.zenyth-trash/<ms>-<name>` with companions; returns the trash name. */
    suspend fun trash(name: String): String

    /** Back from the trash as [name] (must be free). Returns the name. */
    suspend fun restore(trashName: String, name: String): String

    /** Deletes from the trash for good (absent is fine). */
    suspend fun purge(trashName: String)

    /** Deletes trash files whose `<ms>-` prefix is older than [before]; returns how many. */
    suspend fun sweepTrash(before: Long): Int

    /** Finishes or undoes atomic replaces cut short (design §3.3 recovery table). */
    suspend fun recover()

    suspend fun readManifest(): LibraryManifest
    suspend fun writeManifest(m: LibraryManifest)
}

/** Checks a temp file before recovery commits it — "the header count equals the records". */
fun interface ReplaceVerifier {
    fun ok(name: String, open: () -> InputStream): Boolean

    companion object {
        /** `.db`: a whole yixindb (LZ4 checksums too); the sidecar: parses; anything else: yes. */
        val Default = ReplaceVerifier { name, open ->
            when {
                name.endsWith(".db", ignoreCase = true) -> runCatching {
                    open().use { s ->
                        val r = Yxdb.read(s, 0, { _, _, _, _, _, _ -> true }, verify = true)
                        r.count >= 0 && r.read >= r.count
                    }
                }.getOrDefault(false)
                name == dev.gomoku.zenyth.domain.library.LibraryEntries.MANIFEST_FILE -> runCatching {
                    !ManifestCodec.parse(open().use { it.readBytes().decodeToString() }).broken
                }.getOrDefault(false)
                else -> true
            }
        }
    }
}
