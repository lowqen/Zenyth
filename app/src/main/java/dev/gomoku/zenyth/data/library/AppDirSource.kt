package dev.gomoku.zenyth.data.library

import java.io.File

/**
 * «이 기기» — `filesDir/library/` (design §3.2). Files are `<uuid>.db`: the
 * storage is invisible, so the name carries no meaning (like the web browser
 * source's `lib/<id>`). The same `zenyth-library.json` sits here, so one
 * sidecar · reconcile path serves both kinds of source.
 *
 * The engine opens these by path with no copy ([fileOf]); `staging/` (SAF
 * copies the engine holds, L5-4) and `.migrated` are not items — a directory
 * and a dot file, which listing skips.
 */
class AppDirSource(val dir: File) : TreeSource(ID, SourceKind.APP, "", FileDocTree(dir)) {
    /** Absolute path for `YXSETDATABASE`. ASCII only — `<uuid>.db`. */
    fun fileOf(name: String): File = File(dir, name)

    companion object {
        const val ID = "app"
        const val DIR = "library"

        /** The file name an app-storage entry with this id gets. */
        fun fileNameOf(id: String) = "$id.db"
    }
}
