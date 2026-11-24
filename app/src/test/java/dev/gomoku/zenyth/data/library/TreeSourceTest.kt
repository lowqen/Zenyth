package dev.gomoku.zenyth.data.library

import com.google.common.truth.Truth.assertThat
import dev.gomoku.zenyth.domain.library.LibraryEntries
import dev.gomoku.zenyth.domain.library.ManifestCodec
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

/**
 * Library L5-2 — the source over a document tree. The fake tree acts like a
 * SAF provider (no overwriting rename, ` (1)`, case-insensitive), so the
 * three-step replace and its recovery table are exercised; the real directory
 * checks the app-storage path (one atomic rename).
 */
class TreeSourceTest {

    @get:Rule val tmp = TemporaryFolder()

    private var now = 1_700_000_000_000L

    /** Verifier for the fake: a temp file is good when its text starts with "ok". */
    private val okVerifier = ReplaceVerifier { _, open -> open().use { it.readBytes().decodeToString().startsWith("ok") } }

    private fun saf(tree: FakeDocTree = FakeDocTree()) =
        TreeSource("saf-1", SourceKind.SAF, "연구DB", tree, verifier = okVerifier, clock = { now })

    private suspend fun TreeSource.writeText(name: String, text: String, dropJournal: Boolean = true) =
        writeAtomic(name, dropJournal) { s -> s.openOutput().use { it.write(text.toByteArray()) } }

    // ── atomic replace ─────────────────────────────────────────────────

    @Test
    fun `a new file appears whole, no temp left`() = runBlocking<Unit> {
        val tree = FakeDocTree()
        val src = saf(tree)
        val st = src.writeText("a.db", "ok-1")
        assertThat(tree.names()).containsExactly("a.db")
        assertThat(tree.text("a.db")).isEqualTo("ok-1")
        assertThat(st.name).isEqualTo("a.db")
        assertThat(st.size).isEqualTo(4)
        assertThat(st.mtime).isEqualTo(src.stat("a.db")!!.mtime)
    }

    @Test
    fun `replacing goes through old and tmp in three steps and drops the stale journal`() = runBlocking<Unit> {
        val tree = FakeDocTree()
        tree.put("a.db", "ok-old")
        tree.put("a.db.journal", "j")
        val src = saf(tree)
        tree.ops.clear()
        src.writeText("a.db", "ok-new")
        assertThat(tree.names()).containsExactly("a.db")
        assertThat(tree.text("a.db")).isEqualTo("ok-new")
        assertThat(tree.ops).containsAtLeast(
            "create a.db.zenyth-tmp", "rename a.db.zenyth-old", "rename a.db", "delete a.db.zenyth-old",
        ).inOrder()
    }

    @Test
    fun `dropJournal false keeps the journal`() = runBlocking<Unit> {
        val tree = FakeDocTree()
        tree.put("a.db", "ok-old")
        tree.put("a.db.journal", "j")
        saf(tree).writeText("a.db", "ok-new", dropJournal = false)
        assertThat(tree.names()).containsExactly("a.db", "a.db.journal")
    }

    @Test
    fun `a failing write leaves the file as it was`() = runBlocking<Unit> {
        val tree = FakeDocTree()
        tree.put("a.db", "ok-old")
        val src = saf(tree)
        assertThrows(IllegalStateException::class.java) {
            runBlocking { src.writeAtomic("a.db") { throw IllegalStateException("count mismatch") } }
        }
        assertThat(tree.names()).containsExactly("a.db")
        assertThat(tree.text("a.db")).isEqualTo("ok-old")
    }

    @Test
    fun `step 3 refused — the old file is put back at once`() = runBlocking<Unit> {
        val tree = FakeDocTree()
        tree.put("a.db", "ok-old")
        val src = saf(tree)
        var cut = true
        tree.failOn = { op, name -> op == "rename" && name == "a.db" && cut.also { cut = false } }
        assertThrows(IOException::class.java) { runBlocking { src.writeText("a.db", "ok-new") } }
        assertThat(tree.names()).containsExactly("a.db")
        assertThat(tree.text("a.db")).isEqualTo("ok-old")
    }

    @Test
    fun `a name being written is busy`() = runBlocking<Unit> {
        val tree = FakeDocTree()
        tree.put("a.db", "ok-old")
        val src = saf(tree)
        src.writeAtomic("a.db") {
            assertThrows(IllegalStateException::class.java) { runBlocking { src.rename("a.db", "b.db") } }
            assertThrows(IllegalStateException::class.java) { runBlocking { src.writeText("a.db", "ok-2") } }
            it.openOutput().use { o -> o.write("ok-1".toByteArray()) }
        }
        assertThat(tree.text("a.db")).isEqualTo("ok-1")
    }

    // ── recovery table (design §3.3) ───────────────────────────────────

    @Test
    fun `cut between 2 and 3 — a good tmp is committed`() = runBlocking<Unit> {
        val tree = FakeDocTree()
        tree.put("a.db.zenyth-old", "ok-old")
        tree.put("a.db.zenyth-tmp", "ok-new")
        saf(tree).recover()
        assertThat(tree.names()).containsExactly("a.db")
        assertThat(tree.text("a.db")).isEqualTo("ok-new")
    }

    @Test
    fun `cut between 2 and 3 — a bad tmp is dropped and old comes back`() = runBlocking<Unit> {
        val tree = FakeDocTree()
        tree.put("a.db.zenyth-old", "ok-old")
        tree.put("a.db.zenyth-tmp", "half")
        saf(tree).recover()
        assertThat(tree.names()).containsExactly("a.db")
        assertThat(tree.text("a.db")).isEqualTo("ok-old")
    }

    @Test
    fun `step 3 failed — old only goes back`() = runBlocking<Unit> {
        val tree = FakeDocTree()
        tree.put("a.db.zenyth-old", "ok-old")
        saf(tree).recover()
        assertThat(tree.names()).containsExactly("a.db")
        assertThat(tree.text("a.db")).isEqualTo("ok-old")
    }

    @Test
    fun `cut before 4 — old is deleted`() = runBlocking<Unit> {
        val tree = FakeDocTree()
        tree.put("a.db", "ok-new")
        tree.put("a.db.zenyth-old", "ok-old")
        saf(tree).recover()
        assertThat(tree.names()).containsExactly("a.db")
        assertThat(tree.text("a.db")).isEqualTo("ok-new")
    }

    @Test
    fun `cut during 1 — tmp only is deleted, with or without the real file`() = runBlocking<Unit> {
        val tree = FakeDocTree()
        tree.put("a.db", "ok-old")
        tree.put("a.db.zenyth-tmp", "half")
        tree.put("b.db.zenyth-tmp", "half")
        saf(tree).recover()
        assertThat(tree.names()).containsExactly("a.db")
        assertThat(tree.text("a.db")).isEqualTo("ok-old")
    }

    @Test
    fun `the sidecar recovers the same way`() = runBlocking<Unit> {
        val tree = FakeDocTree()
        val good = ManifestCodec.render(dev.gomoku.zenyth.domain.library.LibraryManifest())
        tree.put("zenyth-library.json.zenyth-old", good)
        tree.put("zenyth-library.json.zenyth-tmp", "{ half")
        TreeSource("s", SourceKind.SAF, "", tree).recover()   // default verifier: the sidecar must parse
        assertThat(tree.names()).containsExactly("zenyth-library.json")
        assertThat(tree.text("zenyth-library.json")).isEqualTo(good)
    }

    @Test
    fun `a case-only rename cut halfway goes back to its old name`() = runBlocking<Unit> {
        val tree = FakeDocTree()
        tree.put("a.db.zenyth-rename", "ok")
        saf(tree).recover()
        assertThat(tree.names()).containsExactly("a.db")
    }

    @Test
    fun `the default verifier knows a whole yixindb from a cut one`() {
        val db = javaClass.getResourceAsStream("/library/mixed.db")!!.readBytes()
        assertThat(ReplaceVerifier.Default.ok("x.db") { db.inputStream() }).isTrue()
        assertThat(ReplaceVerifier.Default.ok("x.db") { db.copyOf(db.size / 2).inputStream() }).isFalse()
        assertThat(ReplaceVerifier.Default.ok("x.db") { ByteArray(0).inputStream() }).isFalse()
    }

    // ── listing ────────────────────────────────────────────────────────

    @Test
    fun `list skips the sidecar, trash, hidden and in-flight files`() = runBlocking<Unit> {
        val tree = FakeDocTree()
        for (n in listOf(
            "a.db", "a.db.journal", "a_bak1.db", ".hidden.db", "zenyth-library.json", "zenyth-library.json.broken-5",
            "x.db.zenyth-tmp", "y.db.zenyth-old", "z.db.zenyth-rename",
        )) tree.put(n, "1")
        tree.dir(".zenyth-trash", create = true)
        assertThat(saf(tree).list().map { it.name }.sorted()).containsExactly("a.db", "a.db.journal", "a_bak1.db").inOrder()
    }

    @Test
    fun `fingerprint reads the tail only and matches the codec`() = runBlocking<Unit> {
        val tree = FakeDocTree()
        val bytes = ByteArray(10_000) { (it * 7).toByte() }
        tree.put("a.db", bytes, mtime = 42)
        val fp = saf(tree).fingerprint("a.db")!!
        val want = dev.gomoku.zenyth.domain.library.Fingerprint.of(10_000, 42, bytes, 10_000 - 4096, 4096)
        assertThat(fp).isEqualTo(want)
        assertThat(saf(tree).fingerprint("none.db")).isNull()
    }

    // ── rename ─────────────────────────────────────────────────────────

    @Test
    fun `rename takes the journal and engine backups along`() = runBlocking<Unit> {
        val tree = FakeDocTree()
        for (n in listOf("a.db", "a.db.journal", "a_bak1.db", "a_bak2.db", "ab.db")) tree.put(n, n)
        assertThat(saf(tree).rename("a.db", "화월.db")).isEqualTo("화월.db")
        assertThat(tree.names()).containsExactly("ab.db", "화월.db", "화월.db.journal", "화월_bak1.db", "화월_bak2.db")
        assertThat(tree.text("화월_bak2.db")).isEqualTo("a_bak2.db")
    }

    @Test
    fun `rename never overwrites — a taken name or companion name fails before anything moves`() = runBlocking<Unit> {
        val tree = FakeDocTree()
        for (n in listOf("a.db", "a.db.journal", "B.db", "c.db.journal")) tree.put(n, n)
        val src = saf(tree)
        assertThrows(IOException::class.java) { runBlocking { src.rename("a.db", "b.db") } }
        assertThrows(IOException::class.java) { runBlocking { src.rename("a.db", "c.db") } }
        assertThat(tree.names()).containsExactly("B.db", "a.db", "a.db.journal", "c.db.journal")
    }

    @Test
    fun `a case-only rename goes through zenyth-rename (the provider would answer (1))`() = runBlocking<Unit> {
        val tree = FakeDocTree()
        tree.put("a.db", "x")
        tree.put("a.db.journal", "j")
        saf(tree).rename("a.db", "A.db")
        assertThat(tree.names()).containsExactly("A.db", "A.db.journal")
    }

    @Test
    fun `a rename that fails halfway is undone`() = runBlocking<Unit> {
        val tree = FakeDocTree()
        for (n in listOf("a.db", "a.db.journal", "a_bak1.db")) tree.put(n, n)
        tree.failOn = { op, name -> op == "rename" && name == "b_bak1.db" }
        assertThrows(IOException::class.java) { runBlocking { saf(tree).rename("a.db", "b.db") } }
        assertThat(tree.names()).containsExactly("a.db", "a.db.journal", "a_bak1.db")
    }

    // ── trash ──────────────────────────────────────────────────────────

    @Test
    fun `trash, restore and purge move the companions with the file`() = runBlocking<Unit> {
        val tree = FakeDocTree()
        for (n in listOf("a.db", "a.db.journal", "a_bak1.db", "other.db")) tree.put(n, n)
        val src = saf(tree)
        val token = src.trash("a.db")
        assertThat(token).isEqualTo("$now-a.db")
        assertThat(tree.names()).containsExactly("other.db")
        val bin = tree.trashDir()!!
        assertThat(tree.names(bin)).containsExactly("$now-a.db", "$now-a.db.journal", "$now-a_bak1.db")

        assertThat(src.restore(token, "c.db")).isEqualTo("c.db")
        assertThat(tree.names()).containsExactly("c.db", "c.db.journal", "c_bak1.db", "other.db")
        assertThat(tree.text("c.db")).isEqualTo("a.db")
        assertThat(tree.names(bin)).isEmpty()

        assertThrows(IOException::class.java) { runBlocking { src.restore(src.trash("c.db"), "other.db") } }
        src.purge("$now-c.db")
        assertThat(tree.names(bin)).isEmpty()
        assertThat(tree.names()).containsExactly("other.db")
    }

    @Test
    fun `a provider without move copies into the trash`() = runBlocking<Unit> {
        val tree = FakeDocTree(canMove = false)
        tree.put("a.db", "bytes")
        tree.put("a.db.journal", "j")
        val src = saf(tree)
        val token = src.trash("a.db")
        assertThat(tree.names()).isEmpty()
        assertThat(tree.text(token, tree.trashDir())).isEqualTo("bytes")
        src.restore(token, "a.db")
        assertThat(tree.names()).containsExactly("a.db", "a.db.journal")
    }

    @Test
    fun `sweeping drops trash older than the cut by its ms prefix`() = runBlocking<Unit> {
        val tree = FakeDocTree()
        val bin = tree.dir(".zenyth-trash", create = true)
        for (n in listOf("100-x.db", "100-x.db.journal", "5000-y.db", "junk.db")) tree.put(n, "1", bin)
        assertThat(saf(tree).sweepTrash(before = 1000)).isEqualTo(2)
        assertThat(tree.names(bin)).containsExactly("5000-y.db", "junk.db")
        assertThat(saf(FakeDocTree()).sweepTrash(1000)).isEqualTo(0)
    }

    // ── sidecar ────────────────────────────────────────────────────────

    @Test
    fun `a broken sidecar is kept beside it before the first overwrite`() = runBlocking<Unit> {
        val tree = FakeDocTree()
        tree.put("zenyth-library.json", "{ hand edited")
        val src = saf(tree)
        assertThat(src.readManifest().entries).isEmpty()
        val e = LibraryEntries.newEntry("A", "a.db", now = 1)
        src.writeManifest(dev.gomoku.zenyth.domain.library.LibraryManifest(listOf(e)))
        assertThat(tree.text("zenyth-library.json.broken-$now")).isEqualTo("{ hand edited")
        assertThat(src.readManifest().entries.map { it.id }).containsExactly(e.id)
        // only once
        src.writeManifest(src.readManifest())
        assertThat(tree.names().count { it.contains(".broken-") }).isEqualTo(1)
    }

    @Test
    fun `a web sidecar read and written back through the source is unchanged`() = runBlocking<Unit> {
        val web = javaClass.getResourceAsStream("/library/web-sidecar.json")!!.readBytes().decodeToString()
        val tree = FakeDocTree()
        tree.put("zenyth-library.json", web)
        val src = saf(tree)
        src.writeManifest(src.readManifest())
        assertThat(tree.text("zenyth-library.json")).isEqualTo(web)
    }

    // ── app storage: a real directory, one atomic rename ──────────────

    @Test
    fun `app storage replaces with one rename and keeps companions together`() = runBlocking<Unit> {
        val dir = tmp.newFolder("library")
        val src = AppDirSource(dir)
        src.writeText("u.db", "v1")
        File(dir, "u.db.journal").writeText("j")
        File(dir, "u_bak1.db").writeText("b")
        src.writeText("u.db", "v2")
        assertThat(dir.list()!!.sorted()).containsExactly("u.db", "u_bak1.db").inOrder()
        assertThat(File(dir, "u.db").readText()).isEqualTo("v2")
        assertThat(src.fileOf("u.db").absolutePath).isEqualTo(File(dir, "u.db").absolutePath)

        val token = src.trash("u.db")
        assertThat(dir.list()!!.filter { !it.startsWith(".") }).isEmpty()
        assertThat(File(dir, ".zenyth-trash").list()!!.sorted()).containsExactly(token, token.removeSuffix(".db") + "_bak1.db").inOrder()
        src.restore(token, "u.db")
        assertThat(src.list().map { it.name }.sorted()).containsExactly("u.db", "u_bak1.db").inOrder()

        src.rename("u.db", "w.db")
        assertThat(src.list().map { it.name }.sorted()).containsExactly("w.db", "w_bak1.db").inOrder()
    }

    @Test
    fun `app storage recovers a cut replace too`() = runBlocking<Unit> {
        val dir = tmp.newFolder("library")
        File(dir, "u.db.zenyth-tmp").writeText("half")
        File(dir, "staging").mkdirs()
        val src = AppDirSource(dir)
        src.recover()
        assertThat(dir.list()!!.sorted()).containsExactly("staging")
        assertThat(src.list()).isEmpty()
    }
}
