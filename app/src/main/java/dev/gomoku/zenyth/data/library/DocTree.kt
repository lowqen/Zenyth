package dev.gomoku.zenyth.data.library

import java.io.InputStream
import java.io.OutputStream

/** A document in a [DocTree] — one row of one listing. */
data class DocEntry(
    /** Opaque id (a relative path for files, a document id for SAF). Renaming may change it. */
    val id: String,
    val name: String,
    val size: Long,
    val mtime: Long,
    val isDir: Boolean,
)

/**
 * The few folder operations a library source needs (library L5 design §3),
 * over a plain directory ([FileDocTree]) or a SAF document tree
 * ([ContentDocTree]). Small on purpose: [TreeSource] builds the atomic
 * replace, companions, trash and recovery on top once, and tests swap in a
 * fake tree that behaves like a SAF provider. Calls block — callers move them
 * off the main thread.
 *
 * Directories: `null` is the tree root; a subdirectory is named by its id.
 */
interface DocTree {
    /**
     * Whether [replace] can swap a file over another in one step (POSIX
     * rename). SAF cannot: its rename never overwrites — it picks another
     * name (` (1)`), so [TreeSource] replaces in three recorded steps.
     */
    val atomicReplace: Boolean

    /** All children of [dir] in one query. */
    fun children(dir: String?): List<DocEntry>

    /** A subdirectory of the root by name, made when [create]. */
    fun dir(name: String, create: Boolean): String?

    /** Makes an empty file. The provider may pick another name if taken — read the result. */
    fun create(dir: String?, name: String): DocEntry

    fun openInput(id: String): InputStream

    /** Truncates, and syncs to storage on close. */
    fun openOutput(id: String): OutputStream

    /** Never overwrites — returns what the document is now called (maybe not [name]). */
    fun rename(id: String, name: String): DocEntry

    fun delete(id: String)

    /** Moves into another directory of the tree, keeping the name; null when the provider cannot. */
    fun move(id: String, fromDir: String?, toDir: String?): DocEntry?

    /** Only when [atomicReplace]: renames [id] to [name], replacing what is there. */
    fun replace(id: String, name: String): DocEntry =
        throw UnsupportedOperationException("replace")
}
