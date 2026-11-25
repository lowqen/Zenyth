package dev.gomoku.zenyth.data.library

import android.app.Application
import com.google.common.truth.Truth.assertThat
import dev.gomoku.zenyth.core.model.ConnectionState
import dev.gomoku.zenyth.core.model.DbFileState
import dev.gomoku.zenyth.core.model.DbOpResult
import dev.gomoku.zenyth.data.engine.EngineKeepAlive
import dev.gomoku.zenyth.domain.engine.CoordMapper
import dev.gomoku.zenyth.domain.engine.DbCompact
import dev.gomoku.zenyth.domain.engine.DbSetFile
import dev.gomoku.zenyth.domain.engine.EngineCommand
import dev.gomoku.zenyth.domain.engine.EngineResponse
import dev.gomoku.zenyth.domain.library.Fingerprint
import dev.gomoku.zenyth.domain.library.LibraryEntries
import dev.gomoku.zenyth.domain.library.LibraryEntry
import dev.gomoku.zenyth.domain.library.LibraryManifest
import dev.gomoku.zenyth.domain.repository.DatabaseRepository
import dev.gomoku.zenyth.domain.repository.EngineRepository
import dev.gomoku.zenyth.feature.board.FakeDatabase
import dev.gomoku.zenyth.feature.board.FakeEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Library L5-4 — switching the engine's database (design §5) and the SAF
 * staging round trip (§3.3 · §9), over a scripted engine: it answers
 * `YXCOMPACTDATABASE` with a save's end (folding the staging copy as the real
 * one would) and `ABOUT` with its name. Robolectric only for `android.util.Log`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class LibraryAttachControllerTest {

    @get:Rule val tmp = TemporaryFolder()

    private val now = 1_800_000_000_000L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After fun tearDown() = scope.cancel()

    // ── the scripted engine ──────────────────────────────────────────

    private class Engine(val fake: FakeEngine = FakeEngine()) : EngineRepository by fake {
        val sent = java.util.Collections.synchronizedList(ArrayList<String>())
        var holds = true
        var idles = 0
        /** What a compact does to the files, and whether it fails. */
        var onCompact: () -> Unit = {}
        var compactFails = false

        override val state get() = fake.stateFlow
        override val responses get() = fake.responseFlow
        override val isLocal get() = true
        override val holdsDatabase get() = holds

        override suspend fun awaitIdle() {
            idles++
        }

        override suspend fun send(command: EngineCommand) {
            sent += command.serialize(CoordMapper())
            when {
                command == DbCompact -> {
                    if (compactFails) {
                        emit(EngineResponse.Error("Failed to save database: disk full", "ERROR"))
                    } else {
                        onCompact()
                        emit(EngineResponse.DbFileEvent(saving = true, started = true, file = "x", raw = ""))
                        emit(EngineResponse.DbFileEvent(saving = true, started = false, file = "", raw = ""))
                    }
                }
                command is DbSetFile -> holds = true
                command is EngineCommand.Info && command.key == "usedatabase" -> holds = command.value == "1"
                command == EngineCommand.About -> emit(EngineResponse.About(mapOf("name" to "rapfi"), "name=\"rapfi\""))
            }
        }

        fun emit(r: EngineResponse) {
            check(fake.responseFlow.tryEmit(r))
        }
    }

    private class Db(private val engine: Engine) : DatabaseRepository by FakeDatabase() {
        val file = MutableStateFlow(DbFileState(loaded = true))
        override val fileState: StateFlow<DbFileState> get() = file
        override suspend fun compact(): DbOpResult {
            engine.send(DbCompact)
            return DbOpResult.Sent
        }
    }

    // ── rig ──────────────────────────────────────────────────────────

    private val okVerifier = ReplaceVerifier { _, _ -> true }

    private inner class Rig {
        val engineDir = tmp.newFolder("engine")
        val app = AppDirSource(File(tmp.root, "library")).also { it.dir.mkdirs() }
        val tree = FakeDocTree()
        val saf = TreeSource("saf-1", SourceKind.SAF, "연구", tree, verifier = okVerifier, clock = { now })
        val store = AttachedDbStore(app, engineDir) { now }
        val engine = Engine()
        val db = Db(engine)
        val keepAlive = EngineKeepAlive()
        var copies = 0
        var heldDuringCopy = false
        val copier = LibraryCopier { size, from, to ->
            copies++
            if (keepAlive.holds.value.isNotEmpty()) heldDuringCopy = true
            StreamCopier.copy(size, from, to)
        }
        val controller = LibraryAttachController(
            engine, db, store,
            { id -> listOf(app, saf).firstOrNull { it.id == id } },
            copier, keepAlive, scope, { now }, stoppedGraceMs = 0,
        )

        suspend fun started(): Rig {
            controller.start()
            withTimeout(5_000) { engine.fake.responseFlow.subscriptionCount.first { it >= 1 } }
            return this
        }

        fun appEntry(title: String): LibraryEntry {
            val e = LibraryEntries.newEntry(title, "", now = now)
            return e.copy(file = AppDirSource.fileNameOf(e.id))
        }

        suspend fun safEntry(name: String, bytes: ByteArray): LibraryEntry {
            tree.put(name, bytes)
            val e = LibraryEntries.newEntry("연구", name, now = now)
                .copy(fingerprint = saf.fingerprint(name))
                .copy(statsJson = JsonObject(mapOf("records" to JsonPrimitive(3))))
            saf.writeManifest(LibraryManifest(entries = listOf(e)))
            return e
        }

        fun attachApp(e: LibraryEntry) = store.setAttached(AttachedDb(e.id, app.id, app.fileOf(e.file).absolutePath))

        fun stagingOf(e: LibraryEntry) = store.stagingFile(store.markForEntry(e.id)!!.key)

        /** The engine saved into the copy: new bytes plus a journal. */
        fun engineSaved(e: LibraryEntry, main: ByteArray, journal: ByteArray? = byteArrayOf(9, 9)) {
            val f = stagingOf(e)
            f.writeBytes(main)
            journal?.let { File(f.path + ".journal").writeBytes(it) }
            engine.emit(EngineResponse.Message("Saved database file using 3 ms.", "MESSAGE Saved database file using 3 ms."))
        }

        /** The real compact folds the journal into the main file. */
        fun foldsInto(e: LibraryEntry, folded: ByteArray) {
            engine.onCompact = {
                stagingOf(e).writeBytes(folded)
                File(stagingOf(e).path + ".journal").delete()
            }
        }
    }

    private fun bytes(n: Int, seed: Int = 1) = ByteArray(n) { (it * 31 + seed).toByte() }

    /** Waits for [check] (5 s), naming [what] when it never comes. */
    private suspend fun eventually(what: String, check: () -> Boolean) {
        val ok = kotlinx.coroutines.withTimeoutOrNull(5_000) {
            while (!check()) delay(10)
            true
        }
        if (ok != true) throw AssertionError("never: $what")
    }

    // ── switching ────────────────────────────────────────────────────

    @Test
    fun `an app entry switch folds, detaches, loads and confirms — in that order`() = runBlocking<Unit> {
        val r = Rig().started()
        val a = r.appEntry("A")
        val b = r.appEntry("B")
        r.attachApp(a)

        val out = r.controller.switchTo(r.app, b)

        val pathB = r.app.fileOf(b.file).absolutePath
        assertThat(out).isEqualTo(AttachOutcome.Attached(pathB, live = true))
        assertThat(r.engine.sent).containsExactly(
            "yxcompactdatabase", "INFO usedatabase 0", "yxsetdatabase\n$pathB", "ABOUT",
        ).inOrder()
        assertThat(r.engine.idles).isEqualTo(1)
        assertThat(r.store.attached()).isEqualTo(AttachedDb(b.id, "app", pathB))
        assertThat(r.store.enginePath()).isEqualTo(pathB)
        // and again: nothing to do
        assertThat(r.controller.switchTo(r.app, b)).isEqualTo(AttachOutcome.Already)
    }

    @Test
    fun `a failed save switches nothing`() = runBlocking<Unit> {
        val r = Rig().started()
        val a = r.appEntry("A")
        val b = r.appEntry("B")
        r.attachApp(a)
        r.engine.compactFails = true

        val out = r.controller.switchTo(r.app, b)

        assertThat(out).isInstanceOf(AttachOutcome.Failed::class.java)
        assertThat((out as AttachOutcome.Failed).reason).contains("disk full")
        assertThat(r.engine.sent).containsExactly("yxcompactdatabase")
        assertThat(r.store.attached()!!.entryId).isEqualTo(a.id)
    }

    @Test
    fun `with no database held the switch is only recorded`() = runBlocking<Unit> {
        val r = Rig().started()
        val a = r.appEntry("A")
        val b = r.appEntry("B")
        r.attachApp(a)
        r.engine.holds = false

        val out = r.controller.switchTo(r.app, b)

        assertThat(out).isEqualTo(AttachOutcome.Attached(r.app.fileOf(b.file).absolutePath, live = false))
        assertThat(r.engine.sent).isEmpty()
        assertThat(r.store.attached()!!.entryId).isEqualTo(b.id)
    }

    @Test
    fun `a read-only or unloaded database is not folded before the switch`() = runBlocking<Unit> {
        val r = Rig().started()
        val a = r.appEntry("A")
        val b = r.appEntry("B")
        r.attachApp(a)
        r.db.file.value = DbFileState(loaded = false)

        r.controller.switchTo(r.app, b)

        assertThat(r.engine.sent.first()).isEqualTo("INFO usedatabase 0")
    }

    // ── staging ──────────────────────────────────────────────────────

    @Test
    fun `a SAF entry is attached through its staging copy, which a later attach reuses`() = runBlocking<Unit> {
        val r = Rig().started()
        val a = r.appEntry("A")
        r.attachApp(a)
        val content = bytes(9000)
        val e = r.safEntry("연구.db", content)

        val out = r.controller.switchTo(r.saf, e) as AttachOutcome.Attached

        val staging = r.stagingOf(e)
        assertThat(out.path).isEqualTo(staging.absolutePath)
        assertThat(staging.parentFile!!.name).isEqualTo("staging")
        assertThat(staging.readBytes()).isEqualTo(content)
        val m = r.store.markForEntry(e.id)!!
        assertThat(m.name).isEqualTo("연구.db")
        assertThat(m.dirty).isFalse()
        assertThat(m.base!!.toFingerprint().sameFile(r.saf.fingerprint("연구.db")!!)).isTrue()
        assertThat(r.copies).isEqualTo(1)

        // away and back: the copy is still the folder's file — no second copy
        r.controller.switchTo(r.app, a)
        r.controller.switchTo(r.saf, e)
        assertThat(r.copies).isEqualTo(1)

        // the PC changed the file while it was not attached: copied again
        r.controller.switchTo(r.app, a)
        r.tree.overwrite("연구.db", bytes(9100, seed = 2))
        r.controller.switchTo(r.saf, e)
        assertThat(r.copies).isEqualTo(2)
        assertThat(r.stagingOf(e).readBytes()).isEqualTo(bytes(9100, seed = 2))
    }

    @Test
    fun `the folder's journal travels into the staging copy`() = runBlocking<Unit> {
        val r = Rig().started()
        r.attachApp(r.appEntry("A"))
        val e = r.safEntry("연구.db", bytes(5000))
        r.tree.put("연구.db.journal", byteArrayOf(1, 2, 3))

        r.controller.switchTo(r.saf, e)

        assertThat(File(r.stagingOf(e).path + ".journal").readBytes()).isEqualTo(byteArrayOf(1, 2, 3))
        assertThat(r.store.markForEntry(e.id)!!.baseJournal).isNotNull()
    }

    // ── write-back ───────────────────────────────────────────────────

    @Test
    fun `a save that changed nothing leaves the copy clean — no fold, no copy home`() = runBlocking<Unit> {
        val r = Rig().started()
        val a = r.appEntry("A")
        r.attachApp(a)
        val e = r.safEntry("연구.db", bytes(5000))
        r.controller.switchTo(r.saf, e)
        r.engine.sent.clear()
        val writesBefore = r.tree.ops.count { it.startsWith("create") }

        r.engine.emit(EngineResponse.DbFileEvent(saving = true, started = false, file = "", raw = ""))
        delay(100)
        assertThat(r.store.markForEntry(e.id)!!.dirty).isFalse()
        assertThat(r.controller.writeBackAll()).isEmpty()
        assertThat(r.engine.sent).isEmpty()
        assertThat(r.tree.ops.count { it.startsWith("create") }).isEqualTo(writesBefore)
    }

    @Test
    fun `a dirty copy goes home folded when the entry is switched away`() = runBlocking<Unit> {
        val r = Rig().started()
        val a = r.appEntry("A")
        r.attachApp(a)
        val e = r.safEntry("연구.db", bytes(5000))
        r.controller.switchTo(r.saf, e)

        r.engineSaved(e, bytes(6000, seed = 3))
        eventually("dirty") { r.store.markForEntry(e.id)!!.dirty }
        val folded = bytes(6100, seed = 4)
        r.foldsInto(e, folded)

        assertThat(r.controller.switchTo(r.app, a)).isInstanceOf(AttachOutcome.Attached::class.java)
        eventually("home") { r.store.markForEntry(e.id)?.dirty == false }

        assertThat(r.tree.bytes("연구.db")).isEqualTo(folded)
        assertThat(r.tree.names()).doesNotContain("연구.db.journal")
        val back = r.saf.readManifest().entries.single { it.id == e.id }
        assertThat(back.fingerprint!!.sameFile(r.saf.fingerprint("연구.db")!!)).isTrue()
        assertThat(back.statsJson).isNull() // other bytes, other stats
        assertThat(r.store.markForEntry(e.id)!!.base!!.toFingerprint().sameFile(r.saf.fingerprint("연구.db")!!)).isTrue()
    }

    @Test
    fun `the PC changed the file meanwhile — the device's copy goes beside it and takes over`() = runBlocking<Unit> {
        val r = Rig().started()
        r.attachApp(r.appEntry("A"))
        val e = r.safEntry("연구.db", bytes(5000))
        r.controller.switchTo(r.saf, e)
        r.engineSaved(e, bytes(6000, seed = 3), journal = null)
        eventually("dirty") { r.store.markForEntry(e.id)!!.dirty }
        val pc = bytes(7000, seed = 5)
        r.tree.overwrite("연구.db", pc)

        val out = r.controller.writeBackAll().single()

        assertThat(out).isInstanceOf(WriteBackOutcome.Conflict::class.java)
        out as WriteBackOutcome.Conflict
        // tr() follows the runtime locale (Robolectric: English) — the rule, not the words
        val copyTitle = LibraryAttachController.conflictTitle("연구")
        assertThat(out.name).isEqualTo("$copyTitle.db")
        assertThat(r.tree.bytes("연구.db")).isEqualTo(pc) // nobody loses
        assertThat(r.tree.bytes("$copyTitle.db")).isEqualTo(bytes(6000, seed = 3))
        val entries = r.saf.readManifest().entries
        val copy = entries.single { it.id == out.entryId }
        assertThat(copy.title).isEqualTo(copyTitle)
        assertThat(copy.file).isEqualTo("$copyTitle.db")
        assertThat(entries.single { it.id == e.id }.fingerprint).isEqualTo(e.fingerprint)
        // the engine's copy is now the new entry's
        assertThat(r.store.attached()!!.entryId).isEqualTo(out.entryId)
        val m = r.store.markForEntry(out.entryId)!!
        assertThat(m.name).isEqualTo("$copyTitle.db")
        assertThat(m.dirty).isFalse()
        assertThat(r.store.markForEntry(e.id)).isNull()
    }

    @Test
    fun `leaving the app writes back under the engine service's hold`() = runBlocking<Unit> {
        val r = Rig().started()
        r.attachApp(r.appEntry("A"))
        val e = r.safEntry("연구.db", bytes(5000))
        r.controller.switchTo(r.saf, e)
        r.engineSaved(e, bytes(6000, seed = 3), journal = null)
        eventually("dirty") { r.store.markForEntry(e.id)!!.dirty }
        r.heldDuringCopy = false

        r.controller.onBackground()

        assertThat(r.heldDuringCopy).isTrue()
        assertThat(r.keepAlive.holds.value).isEmpty()
        assertThat(r.engine.sent).contains("yxcompactdatabase")
        assertThat(r.tree.bytes("연구.db")).isEqualTo(bytes(6000, seed = 3))
    }

    @Test
    fun `with the engine already down nothing starts from the background`() = runBlocking<Unit> {
        val r = Rig().started()
        r.attachApp(r.appEntry("A"))
        val e = r.safEntry("연구.db", bytes(5000))
        r.controller.switchTo(r.saf, e)
        r.store.updateMark(r.store.markForEntry(e.id)!!.key) { it.copy(dirty = true) }
        r.engine.fake.stateFlow.value = ConnectionState.Disconnected
        // the stop trigger runs first (the copy is still the folder's: clean)
        eventually("stop trigger") { r.store.markForEntry(e.id)?.dirty == false }
        r.copies = 0
        r.store.updateMark(r.store.markForEntry(e.id)!!.key) { it.copy(dirty = true) }

        r.controller.onBackground()

        assertThat(r.copies).isEqualTo(0)
        assertThat(r.store.markForEntry(e.id)!!.dirty).isTrue()
    }

    @Test
    fun `a copy left dirty by a killed run is sent home at the next start`() = runBlocking<Unit> {
        // the first run's controller never started: only its files count
        val first = Rig()
        first.attachApp(first.appEntry("A"))
        val e = first.safEntry("연구.db", bytes(5000))
        first.engine.holds = false
        first.controller.switchTo(first.saf, e)
        // killed after a save that never went home, engine gone
        first.stagingOf(e).writeBytes(bytes(6000, seed = 3))
        first.store.updateMark(first.store.markForEntry(e.id)!!.key) { it.copy(dirty = true) }
        first.store.setAttached(AttachedDb("other", "app", "/x"))
        first.engine.fake.stateFlow.value = ConnectionState.Disconnected

        // next start: a new controller over the same files
        val next = LibraryAttachController(
            first.engine, first.db, first.store,
            { id -> listOf(first.app, first.saf).firstOrNull { it.id == id } },
            StreamCopier, first.keepAlive, scope, { now }, stoppedGraceMs = 0,
        )
        next.start()
        eventually("home") { first.store.markForEntry(e.id)?.dirty == false }
        assertThat(first.tree.bytes("연구.db")).isEqualTo(bytes(6000, seed = 3))
    }

    // ── boot ─────────────────────────────────────────────────────────

    @Test
    fun `boot moves the old rapfi_db in and bakes its new path`() = runBlocking<Unit> {
        val r = Rig()
        File(r.engineDir, "rapfi.db").writeBytes(bytes(5000))

        val path = r.store.bootPath("내 DB")!!

        val a = r.store.attached()!!
        assertThat(a.sourceId).isEqualTo("app")
        assertThat(path).isEqualTo(r.app.fileOf("${a.entryId}.db").absolutePath)
        assertThat(File(path).readBytes()).isEqualTo(bytes(5000))
        assertThat(File(r.engineDir, "rapfi.db").exists()).isFalse()
        // the next start: same path, nothing moves
        assertThat(r.store.bootPath("내 DB")).isEqualTo(path)
    }

    @Test
    fun `a fresh install gets an empty «내 DB» to attach`() = runBlocking<Unit> {
        val r = Rig()

        val path = r.store.bootPath("내 DB")!!

        val e = r.app.readManifest().entries.single()
        assertThat(e.title).isEqualTo("내 DB")
        assertThat(e.fingerprint).isNull()
        assertThat(path).isEqualTo(r.app.fileOf(e.file).absolutePath)
        assertThat(File(path).exists()).isFalse() // the engine makes it on its first save
        assertThat(r.store.bootPath("내 DB")).isEqualTo(path)
    }

    @Test
    fun `a SAF attachment whose staging copy is gone is dropped, never booted empty`() = runBlocking<Unit> {
        val r = Rig().started()
        r.attachApp(r.appEntry("A"))
        File(r.app.dir, ".migrated").writeText("")
        val e = r.safEntry("연구.db", bytes(5000))
        r.controller.switchTo(r.saf, e)
        r.stagingOf(e).delete()

        val path = r.store.bootPath("내 DB")!!

        assertThat(r.store.attached()!!.sourceId).isEqualTo("app")
        assertThat(path).isNotEqualTo(r.stagingOf(e).absolutePath)
    }

    @Test
    fun `boot sweeps copies cut short`() = runBlocking<Unit> {
        val r = Rig()
        r.store.stagingDir.mkdirs()
        val cut = File(r.store.stagingDir, "k.db.zenyth-tmp").apply { writeText("half") }

        r.store.bootPath("내 DB")

        assertThat(cut.exists()).isFalse()
    }

    @Test
    fun `fingerprints line up with the tail rule`() {
        val f = File(tmp.root, "x.db").apply { writeBytes(bytes(5000)) }
        val store = AttachedDbStore(AppDirSource(File(tmp.root, "library")), tmp.root)
        assertThat(store.fingerprintOf(f)!!.sameFile(Fingerprint.of(5000, 0, bytes(5000), 5000 - 4096, 4096))).isTrue()
    }
}
