package dev.gomoku.zenyth.domain.library

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Test

/** Zenyth-web `folderSync.test.ts` — sidecar entries ↔ folder files (library L5-2). */
class ReconcilerTest {

    private val now = 1_800_000_000_000L
    private val counted = JsonObject(mapOf("records" to JsonPrimitive(3)))

    private fun fp(size: Long, tail: String, mtime: Long = 1) = Fingerprint(size, mtime, tail)
    private fun file(name: String, size: Long, mtime: Long = 1) = FileStat(name, size, mtime)

    private fun entry(
        id: String, file: String,
        fingerprint: Fingerprint? = null, stats: JsonObject? = null, title: String = id,
        missingSince: Long? = null, trashedAt: Long? = null, trashFile: String? = null,
    ) = LibraryEntry(
        id = id, file = file, title = title, cover = LibraryCover(LibraryCover.ECLIPSE, seed = 1),
        fingerprint = fingerprint, statsJson = stats, createdAt = 1, updatedAt = 1,
        missingSince = missingSince, trashedAt = trashedAt, trashFile = trashFile,
    )

    private class Run(val result: Reconciler.Result, val asked: List<String>) {
        val rows get() = result.entries
        val changed get() = result.changed
    }

    private fun run(
        entries: List<LibraryEntry>, files: List<FileStat>,
        tails: Map<String, String> = emptyMap(), taken: Set<String> = emptySet(),
    ): Run = runBlocking {
        val asked = ArrayList<String>()
        val r = Reconciler.reconcile(entries, files, { name ->
            asked += name
            files.firstOrNull { it.name == name }?.let { fp(it.size, tails[name] ?: "t-${it.size}", it.mtime) }
        }, taken, now)
        Run(r, asked)
    }

    @Test
    fun `unchanged file — no fingerprint read, nothing changed`() {
        val r = run(listOf(entry("a", "a.db", fp(10, "t-10", 5), counted)), listOf(file("a.db", 10, 5), file("a.db.journal", 32)))
        assertThat(r.changed).isFalse()
        assertThat(r.asked).isEmpty()
        assertThat(r.rows).hasSize(1)
        assertThat(r.rows[0].id).isEqualTo("a")
        assertThat(r.rows[0].statsJson).isEqualTo(counted)
    }

    @Test
    fun `changed by another program — new fingerprint, stats dropped`() {
        val r = run(listOf(entry("a", "a.db", fp(10, "t-10", 5), counted)), listOf(file("a.db", 12, 9)))
        assertThat(r.changed).isTrue()
        assertThat(r.rows[0].fingerprint).isEqualTo(fp(12, "t-12", 9))
        assertThat(r.rows[0].statsJson).isNull()
    }

    @Test
    fun `only the time changed (a copy) — stats stay, time follows`() {
        val r = run(listOf(entry("a", "a.db", fp(10, "t-10", 5), counted)), listOf(file("a.db", 10, 7)))
        assertThat(r.rows[0].statsJson).isEqualTo(counted)
        assertThat(r.rows[0].fingerprint!!.mtime).isEqualTo(7)
    }

    @Test
    fun `renamed outside the app — matched again by fingerprint`() {
        val r = run(listOf(entry("a", "old.db", fp(10, "t-10"), counted, title = "화월")), listOf(file("new.db", 10)))
        assertThat(r.rows).hasSize(1)
        with(r.rows[0]) {
            assertThat(id).isEqualTo("a")
            assertThat(file).isEqualTo("new.db")
            assertThat(title).isEqualTo("화월")
            assertThat(statsJson).isEqualTo(counted)
            assertThat(missingSince).isNull()
        }
    }

    @Test
    fun `same size but another tail — missing entry plus a new one`() {
        val r = run(listOf(entry("a", "old.db", fp(10, "zzz"))), listOf(file("new.db", 10)))
        assertThat(r.rows).hasSize(2)
        assertThat(r.rows.first { it.id == "a" }.missingSince).isEqualTo(now)
        val fresh = r.rows.first { it.id != "a" }
        assertThat(fresh.file).isEqualTo("new.db")
        assertThat(fresh.title).isEqualTo("new")
    }

    @Test
    fun `missing for more than 30 days — dropped`() {
        val old = now - (LibraryEntries.MISSING_DAYS + 1) * LibraryEntries.DAY_MS
        val r = run(listOf(entry("a", "gone.db", fp(1, "x"), missingSince = old)), emptyList())
        assertThat(r.rows).isEmpty()
        assertThat(r.changed).isTrue()
    }

    @Test
    fun `a new DB never saved (no fingerprint) is not missing`() {
        val r = run(listOf(entry("a", "새 DB.db")), emptyList())
        assertThat(r.rows[0].missingSince).isNull()
        assertThat(r.changed).isFalse()
    }

    @Test
    fun `new db files become auto-cover entries — not journals, hidden files or other extensions`() {
        val r = run(
            emptyList(),
            listOf(file("연구.db", 7), file("연구.db.journal", 32), file(".hidden.db", 1), file("notes.txt", 2)),
        )
        assertThat(r.rows).hasSize(1)
        with(r.rows[0]) {
            assertThat(file).isEqualTo("연구.db")
            assertThat(title).isEqualTo("연구")
            assertThat(cover.mode).isEqualTo(LibraryCover.AUTO)
            assertThat(fingerprint).isEqualTo(fp(7, "t-7", 1))
        }
    }

    @Test
    fun `engine backups beside their main file are not entries — old ones hide`() {
        val files = listOf(file("연구.db", 7), file("연구_bak1.db", 6), file("연구_bak2.db", 5), file("외톨이_bak1.db", 4))
        val r = run(listOf(entry("old", "연구_bak1.db", fp(6, "t-6"))), files)
        assertThat(r.rows.filter { it.missingSince == null }.map { it.file }.sorted())
            .containsExactly("연구.db", "외톨이_bak1.db").inOrder()
        assertThat(r.rows.first { it.id == "old" }.missingSince).isEqualTo(now)
    }

    @Test
    fun `follows a case-only rename`() {
        val r = run(listOf(entry("a", "a.db", fp(10, "t-10", 1))), listOf(file("A.db", 10)))
        assertThat(r.rows[0].file).isEqualTo("A.db")
        assertThat(r.changed).isTrue()
    }

    @Test
    fun `ids another source uses, and duplicates in one sidecar, get new ids`() {
        val r = run(
            listOf(entry("dup", "a.db", fp(1, "t-1")), entry("x", "b.db", fp(2, "t-2")), entry("x", "c.db")),
            listOf(file("a.db", 1), file("b.db", 2)),
            taken = setOf("dup"),
        )
        val ids = r.rows.map { it.id }
        assertThat(ids).doesNotContain("dup")
        assertThat(ids.toSet()).hasSize(3)
        assertThat(r.changed).isTrue()
    }

    @Test
    fun `trashed entries stay without their file`() {
        val r = run(listOf(entry("a", "a.db", trashedAt = 5, trashFile = "5-a.db")), emptyList())
        assertThat(r.rows[0].id).isEqualTo("a")
        assertThat(r.rows[0].trashedAt).isEqualTo(5)
        assertThat(r.rows[0].missingSince).isNull()
    }
}
