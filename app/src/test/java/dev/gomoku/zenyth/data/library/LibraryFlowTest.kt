package dev.gomoku.zenyth.data.library

import com.google.common.truth.Truth.assertThat
import dev.gomoku.zenyth.domain.library.Fingerprint
import dev.gomoku.zenyth.domain.library.LibraryCover
import dev.gomoku.zenyth.domain.library.LibraryEntries
import dev.gomoku.zenyth.domain.library.LibraryManifest
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Library L5-2 — the old `rapfi.db` moving in, reconciling a source, copying between sources. */
class LibraryFlowTest {

    @get:Rule val tmp = TemporaryFolder()

    private val now = 1_800_000_000_000L
    private val okVerifier = ReplaceVerifier { _, _ -> true }

    private fun saf(tree: FakeDocTree) = TreeSource("saf-1", SourceKind.SAF, "연구DB", tree, verifier = okVerifier, clock = { now })

    // ── migration (design §3.2) ────────────────────────────────────────

    private fun dirs(): Pair<File, AppDirSource> {
        val engine = tmp.newFolder("engine")
        val lib = File(tmp.root, "library")
        return engine to AppDirSource(lib)
    }

    @Test
    fun `the old rapfi_db with its journal and backups becomes «내 DB»`() = runBlocking<Unit> {
        val (engine, app) = dirs()
        val main = ByteArray(5000) { it.toByte() }
        File(engine, "rapfi.db").writeBytes(main)
        File(engine, "rapfi.db.journal").writeText("j")
        File(engine, "rapfi_bak1.db").writeText("b1")
        File(engine, "rapfi_bak2.db").writeText("b2")
        File(engine, "config.toml").writeText("url = \"rapfi.db\"")
        File(engine, "rapfi.db").setLastModified(1_600_000_000_000)

        val out = LegacyDbMigration(engine, app, clock = { now }).run("내 DB")
        assertThat(out).isInstanceOf(LegacyDbMigration.Outcome.Migrated::class.java)
        val e = (out as LegacyDbMigration.Outcome.Migrated).entry
        val id = e.id
        assertThat(engine.list()!!.toList()).containsExactly("config.toml")
        assertThat(app.list().map { it.name }.sorted())
            .containsExactly("$id.db", "$id.db.journal", "${id}_bak1.db", "${id}_bak2.db").inOrder()
        assertThat(File(app.dir, "$id.db").readBytes()).isEqualTo(main)
        assertThat(e.file).isEqualTo("$id.db")
        assertThat(e.title).isEqualTo("내 DB")
        assertThat(e.cover.mode).isEqualTo(LibraryCover.ECLIPSE)
        assertThat(e.lastOpenedAt).isEqualTo(now)
        assertThat(e.fingerprint!!.sameFile(Fingerprint.of(5000, 0, main, 5000 - 4096, 4096))).isTrue()
        assertThat(app.readManifest().entries.map { it.id }).containsExactly(id)
        assertThat(File(app.dir, ".migrated").exists()).isTrue()
        assertThat(File(app.dir, ".migrating").exists()).isFalse()

        // once only
        assertThat(LegacyDbMigration(engine, app).run()).isEqualTo(LegacyDbMigration.Outcome.Done)
    }

    @Test
    fun `no old database — a lone journal is dropped and nothing else moves`() = runBlocking<Unit> {
        val (engine, app) = dirs()
        File(engine, "rapfi.db.journal").writeText("j")
        assertThat(LegacyDbMigration(engine, app).run()).isEqualTo(LegacyDbMigration.Outcome.NoLegacy)
        assertThat(engine.list()!!.toList()).isEmpty()
        assertThat(File(app.dir, ".migrated").exists()).isTrue()
    }

    @Test
    fun `a run killed halfway is finished on the next start`() = runBlocking<Unit> {
        val (engine, app) = dirs()
        app.dir.mkdirs()
        val id = "11111111-2222-4333-8444-555555555555"
        File(app.dir, ".migrating").writeText(id)
        File(app.dir, "$id.db.journal").writeText("j")          // moved before the kill
        File(engine, "rapfi.db").writeText("main")              // not yet
        val out = LegacyDbMigration(engine, app, clock = { now }).run()
        assertThat((out as LegacyDbMigration.Outcome.Migrated).entry.id).isEqualTo(id)
        assertThat(app.list().map { it.name }.sorted()).containsExactly("$id.db", "$id.db.journal").inOrder()
        assertThat(File(app.dir, ".migrating").exists()).isFalse()
    }

    @Test
    fun `a rename that fails puts everything back and tries again next time`() = runBlocking<Unit> {
        val (engine, app) = dirs()
        File(engine, "rapfi.db").writeText("main")
        File(engine, "rapfi.db.journal").writeText("j")
        val id = "11111111-2222-4333-8444-555555555555"
        app.dir.mkdirs()
        File(app.dir, "$id.db").mkdirs()   // the main file's target is taken: the journal moves, then the main fails
        val out = LegacyDbMigration(engine, app, newId = { id }).run()
        assertThat(out).isInstanceOf(LegacyDbMigration.Outcome.Failed::class.java)
        assertThat(engine.list()!!.sorted()).containsExactly("rapfi.db", "rapfi.db.journal").inOrder()
        assertThat(File(app.dir, "$id.db.journal").exists()).isFalse()
        assertThat(File(app.dir, ".migrating").exists()).isFalse()
        assertThat(File(app.dir, ".migrated").exists()).isFalse()

        File(app.dir, "$id.db").delete()
        assertThat(LegacyDbMigration(engine, app, newId = { id }).run()).isInstanceOf(LegacyDbMigration.Outcome.Migrated::class.java)
    }

    // ── reconcile a source (design §4) ─────────────────────────────────

    @Test
    fun `first sync writes the sidecar, a quiet second sync writes nothing`() = runBlocking<Unit> {
        val tree = FakeDocTree()
        tree.put("화월.db", "x".repeat(10))
        tree.put("화월.db.journal", "j")
        val src = saf(tree)
        val first = LibrarySync.sync(src, now = now)
        assertThat(first.map { it.file }).containsExactly("화월.db")
        assertThat(first[0].title).isEqualTo("화월")
        assertThat(src.readManifest().entries.map { it.id }).containsExactly(first[0].id)

        tree.ops.clear()
        val second = LibrarySync.sync(src, now = now)
        assertThat(second.map { it.id }).containsExactly(first[0].id)
        assertThat(tree.ops.none { it.startsWith("create zenyth-library.json") }).isTrue()
    }

    @Test
    fun `sync finishes a cut replace first, and drops trash past 30 days`() = runBlocking<Unit> {
        val tree = FakeDocTree()
        val bin = tree.dir(".zenyth-trash", create = true)
        val old = now - 31 * LibraryEntries.DAY_MS
        tree.put("$old-gone.db", "1", bin)
        tree.put("${now - 1}-recent.db", "1", bin)
        tree.put("a.db.zenyth-old", "old")
        tree.put("a.db.zenyth-tmp", "new")
        val src = saf(tree)
        val gone = LibraryEntries.newEntry("gone", "gone.db", now = 1).copy(trashedAt = old, trashFile = "$old-gone.db")
        val recent = LibraryEntries.newEntry("recent", "recent.db", now = 2).copy(trashedAt = now - 1, trashFile = "${now - 1}-recent.db")
        src.writeManifest(LibraryManifest(listOf(gone, recent)))

        val rows = LibrarySync.sync(src, now = now)
        assertThat(tree.text("a.db")).isEqualTo("new")
        assertThat(rows.map { it.title }.sorted()).containsExactly("a", "recent").inOrder()
        assertThat(tree.names(bin)).containsExactly("${now - 1}-recent.db")
        assertThat(src.readManifest().entries.map { it.title }.sorted()).containsExactly("a", "recent").inOrder()
    }

    @Test
    fun `ids taken by another source are renewed`() = runBlocking<Unit> {
        val tree = FakeDocTree()
        tree.put("a.db", "1")
        val src = saf(tree)
        val e = LibraryEntries.newEntry("A", "a.db", now = 1)
        src.writeManifest(LibraryManifest(listOf(e)))
        val rows = LibrarySync.sync(src, takenIds = setOf(e.id), now = now)
        assertThat(rows.single().id).isNotEqualTo(e.id)
        assertThat(rows.single().title).isEqualTo("A")
    }

    // ── copy · move between sources (design §9-8) ──────────────────────

    private val stats = JsonObject(mapOf("records" to JsonPrimitive(3)))

    @Test
    fun `copy from app storage to a folder names the file from the title`() = runBlocking<Unit> {
        val app = AppDirSource(tmp.newFolder("library"))
        val bytes = ByteArray(6000) { (it % 251).toByte() }
        File(app.dir, "u.db").writeBytes(bytes)
        File(app.dir, "u.db.journal").writeText("j")
        val e = LibraryEntries.newEntry("화월 연구", "u.db", now = 1).copy(id = "u", statsJson = stats)
        val tree = FakeDocTree()
        tree.put("화월 연구.db", "someone else's")
        val folder = saf(tree)

        val r = LibraryTransfer.transfer(e, app, folder, emptyList(), move = false, now = now)
        assertThat(r.sourceEntry).isNull()
        assertThat(r.entry.id).isNotEqualTo("u")
        assertThat(r.entry.file).isEqualTo("화월 연구 (2).db")
        assertThat(r.entry.title).isEqualTo("화월 연구")
        assertThat(r.entry.statsJson).isEqualTo(stats)
        assertThat(r.entry.createdAt).isEqualTo(now)
        assertThat(tree.names()).containsExactly("화월 연구.db", "화월 연구 (2).db", "화월 연구 (2).db.journal")
        assertThat(r.entry.fingerprint!!.sameFile(app.fingerprint("u.db")!!)).isTrue()
        assertThat(r.entry.fingerprint!!.mtime).isEqualTo(folder.stat("화월 연구 (2).db")!!.mtime)
        assertThat(File(app.dir, "u.db").exists()).isTrue()
    }

    @Test
    fun `move from a folder into app storage keeps the id and trashes the source file`() = runBlocking<Unit> {
        val tree = FakeDocTree()
        tree.put("연구.db", "x".repeat(100))
        tree.put("연구_bak1.db", "b")
        val folder = saf(tree)
        val app = AppDirSource(tmp.newFolder("library"))
        val e = LibraryEntries.newEntry("연구", "연구.db", now = 7).copy(lastOpenedAt = 9)

        val r = LibraryTransfer.transfer(e, folder, app, emptyList(), move = true, now = now)
        assertThat(r.entry.id).isEqualTo(e.id)
        assertThat(r.entry.file).isEqualTo("${e.id}.db")
        assertThat(r.entry.createdAt).isEqualTo(7)
        assertThat(r.entry.lastOpenedAt).isEqualTo(9)
        assertThat(File(app.dir, "${e.id}.db").readText()).isEqualTo("x".repeat(100))
        assertThat(tree.names()).isEmpty()
        val back = r.sourceEntry!!
        assertThat(back.id).isNotEqualTo(e.id)
        assertThat(back.trashedAt).isEqualTo(now)
        assertThat(tree.names(tree.trashDir())).containsExactly("${back.trashFile}", "$now-연구_bak1.db")
    }
}
