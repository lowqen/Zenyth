package dev.gomoku.zenyth.domain.library

/** A file in a source — size and modified time (ms) from one listing. */
data class FileStat(val name: String, val size: Long, val mtime: Long)

/**
 * Sidecar entries ↔ the files in the folder — Zenyth-web `folderSync.reconcile`
 * line for line (library L5 design §4), so a folder settles the same way
 * whichever app opens it:
 *
 * 1. An entry whose file is there stays — if size or time changed (another
 *    program wrote it) the fingerprint is measured again, and a different tail
 *    drops the stats (they are counted again).
 * 2. An entry whose file is gone is matched by fingerprint (size + last 4 KB)
 *    against unclaimed `.db` files — renamed outside the app.
 * 3. Still none: `missingSince` (hidden), dropped after 30 days. A new DB never
 *    saved (no fingerprint) is left alone — the engine creates it on first save.
 * 4. Leftover `.db` files become new entries, title from the file name, cover
 *    `auto`. Engine backups (`<stem>_bak1.db` beside `<stem>.db`) are not items.
 * 5. An id another source uses (a folder copied whole) gets a new one.
 */
object Reconciler {

    class Result(val entries: List<LibraryEntry>, val changed: Boolean)

    suspend fun reconcile(
        entries: List<LibraryEntry>,
        files: List<FileStat>,
        fingerprint: suspend (String) -> Fingerprint?,
        takenIds: Set<String> = emptySet(),
        now: Long = System.currentTimeMillis(),
    ): Result {
        var changed = false
        val key = LibraryFileNames::key
        val lowerNames = files.mapTo(HashSet()) { key(it.name) }
        val dbFiles = files.filter { LibraryFileNames.isDbFileName(it.name) && !LibraryFileNames.isEngineBackup(it.name, lowerNames) }
        val byName = HashMap<String, FileStat>()
        for (f in dbFiles) byName[key(f.name)] = f
        val claimed = HashSet<String>()
        val fps = HashMap<String, Fingerprint?>()
        suspend fun fpOf(name: String): Fingerprint? {
            if (!fps.containsKey(name)) fps[name] = fingerprint(name)
            return fps[name]
        }

        val rows = ArrayList<LibraryEntry>()
        val pending = ArrayList<LibraryEntry>()
        val seen = HashSet<String>()
        for (e in entries) {
            var row = e
            if (row.id in takenIds || row.id in seen) {
                row = row.copy(id = LibraryEntries.newId())
                changed = true
            }
            seen += row.id
            if (row.trashedAt != null) {
                rows += row
                continue
            }
            val ms = row.missingSince
            if (ms != null && now - ms > LibraryEntries.MISSING_DAYS * LibraryEntries.DAY_MS) {
                changed = true
                continue
            }
            val f = byName[key(row.file)]
            if (f == null || key(f.name) in claimed) {
                pending += row
                continue
            }
            claimed += key(f.name)
            if (f.name != row.file) {
                row = row.copy(file = f.name)
                changed = true
            }
            if (row.missingSince != null) {
                row = row.copy(missingSince = null)
                changed = true
            }
            val fp0 = row.fingerprint
            if (fp0 == null || fp0.size != f.size || fp0.mtime != f.mtime) {
                val fp = fpOf(f.name)
                if (fp != null) {
                    if (fp0 == null || !fp0.sameFile(fp)) row = row.copy(statsJson = null)
                    row = row.copy(fingerprint = fp)
                    changed = true
                }
            }
            rows += row
        }

        // 2. Lost entries ↔ unclaimed files, by fingerprint.
        fun free() = dbFiles.filter { key(it.name) !in claimed }
        for (p in pending) {
            var row = p
            val want = row.fingerprint
            if (want != null) {
                for (f in free()) {
                    if (f.size != want.size) continue
                    val fp = fpOf(f.name)
                    if (fp == null || !fp.sameFile(want)) continue
                    claimed += key(f.name)
                    row = row.copy(file = f.name, fingerprint = fp, missingSince = null)
                    changed = true
                    break
                }
            }
            // 3. Still none — only an entry that was saved once is "missing".
            if (key(row.file) !in claimed && row.fingerprint != null && row.missingSince == null) {
                row = row.copy(missingSince = now)
                changed = true
            }
            rows += row
        }

        // 4. New files.
        for (f in free()) {
            val title = Regex("\\.db$", RegexOption.IGNORE_CASE).replace(f.name, "").ifEmpty { f.name }
            var row = LibraryEntries.newEntry(title, f.name, LibraryCover.AUTO, now)
            if (row.id in takenIds) row = row.copy(id = LibraryEntries.newId())
            val fp = fpOf(f.name)
            row = row.copy(fingerprint = fp, updatedAt = if (f.mtime != 0L) f.mtime else now)
            rows += row
            changed = true
        }
        return Result(rows, changed)
    }
}
