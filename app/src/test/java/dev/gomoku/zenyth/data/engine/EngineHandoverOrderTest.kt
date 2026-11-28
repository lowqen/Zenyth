package dev.gomoku.zenyth.data.engine

import android.app.Application
import com.google.common.truth.Truth.assertThat
import dev.gomoku.zenyth.core.model.AnalyzeParams
import dev.gomoku.zenyth.core.model.AppSettings
import dev.gomoku.zenyth.core.model.BeforeSearch
import dev.gomoku.zenyth.core.model.EngineEndpoint
import dev.gomoku.zenyth.core.model.EngineTarget
import dev.gomoku.zenyth.core.model.Move
import dev.gomoku.zenyth.core.model.Position
import dev.gomoku.zenyth.data.library.AppDirSource
import dev.gomoku.zenyth.data.library.AttachedDbStore
import dev.gomoku.zenyth.data.prefs.LocalEngineStore
import dev.gomoku.zenyth.domain.engine.DbQueryAll
import dev.gomoku.zenyth.feature.board.FakeSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.net.ServerSocket
import java.util.Collections
import kotlin.concurrent.thread

/**
 * L5-6 P1 against the real repository and a throwaway TCP «engine»:
 *
 *  - a move during an analysis stops the old search, then runs the board's
 *    database query, then sends the new board and search — the query no longer
 *    waits for the new search to end;
 *  - `sessionWanted` is true from connect to disconnect (what the foreground
 *    service lives by), and `analysisRunning` follows the analysis.
 *
 * The fake answers `YXSTOP` with a bare move, as Rapfi ends a search.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class EngineHandoverOrderTest {

    private val h8 = Move.fromLabel("H8")!!
    private val g7 = Move.fromLabel("G7")!!

    @Test
    fun `a hand-over stops the old search, queries the database, then sends the new board`() = runBlocking {
        val server = ServerSocket(0)
        val lines: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val fake = thread(name = "fake-rapfi", isDaemon = true) {
            runCatching {
                server.accept().use { client ->
                    val reader = client.getInputStream().bufferedReader()
                    val writer = client.getOutputStream().bufferedWriter()
                    while (true) {
                        val line = reader.readLine() ?: break
                        lines += line.trim()
                        if (line.trim().equals("YXSTOP", ignoreCase = true)) {
                            writer.write("7,7\n")
                            writer.flush()
                        }
                    }
                }
            }
        }

        val context = RuntimeEnvironment.getApplication()
        val installer = LocalEngineInstaller(context)
        val store = LocalEngineStore(context)
        val repo = EngineRepositoryImpl(
            connection = EngineConnection(writeDispatcher = Dispatchers.IO, ioDispatcher = Dispatchers.IO),
            settingsRepository = FakeSettings(AppSettings()),
            localEngine = installer,
            localEngineStore = store,
            thermal = ThermalMonitor(context),
            memoryGuard = MemoryGuard(context, store),
            attachedDb = AttachedDbStore(AppDirSource(File(context.filesDir, "library-test")), installer.engineDir),
            context = context,
            io = Dispatchers.IO,
        )

        suspend fun awaitCount(prefix: String, n: Int) {
            while (synchronized(lines) { lines.count { it.startsWith(prefix, ignoreCase = true) } } < n) delay(10)
        }

        try {
            withTimeout(15_000) {
                assertThat(repo.sessionWanted.value).isFalse()
                repo.connect(EngineTarget.Remote(EngineEndpoint("127.0.0.1", server.localPort)))
                assertThat(repo.sessionWanted.value).isTrue()

                val first = launch { repo.analyze(Position(moves = listOf(h8)), AnalyzeParams()).collect {} }
                awaitCount("YXNBEST", 1)
                repo.analysisRunning.first { it }

                val second = launch {
                    val hook = BeforeSearch { repo.send(DbQueryAll(listOf(h8, g7))) }
                    repo.analyze(Position(moves = listOf(h8, g7)), AnalyzeParams(beforeSearch = hook)).collect {}
                }
                awaitCount("YXNBEST", 2)

                val snapshot = synchronized(lines) { lines.toList() }
                val firstSearch = snapshot.indexOfFirst { it.startsWith("YXNBEST", ignoreCase = true) }
                val tail = snapshot.drop(firstSearch + 1)
                val stop = tail.indexOfFirst { it.equals("YXSTOP", ignoreCase = true) }
                val query = tail.indexOfFirst { it.startsWith("yxquerydatabaseall", ignoreCase = true) }
                val board = tail.indexOfFirst { it.startsWith("YXBOARD", ignoreCase = true) }
                val search = tail.indexOfFirst { it.startsWith("YXNBEST", ignoreCase = true) }
                assertThat(stop).isAtLeast(0)
                assertThat(query).isGreaterThan(stop)
                assertThat(board).isGreaterThan(query)
                assertThat(search).isGreaterThan(board)

                first.cancel()
                second.cancel()
                repo.analysisRunning.first { !it }
            }
        } finally {
            repo.disconnect()
            server.close()
            fake.join(1_000)
        }
        assertThat(repo.sessionWanted.value).isFalse()
    }
}
