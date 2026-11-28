package dev.gomoku.zenyth.data.engine

import android.app.Application
import android.os.Looper
import com.google.common.truth.Truth.assertThat
import dev.gomoku.zenyth.core.model.ConnectionState
import dev.gomoku.zenyth.domain.repository.EngineRepository
import dev.gomoku.zenyth.feature.board.FakeEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config

/**
 * L5-6 P1 6d: the real [EngineService] stays in the foreground while a connect
 * is still before `Connecting` — the window a slow `bootPath` (library
 * attachment, legacy DB move) used to open. The service then saw
 * `Disconnected`, stopped itself 5 ms after starting, and the engine ran on in a
 * cached app until the system killed it for CPU (353 s in 5 min).
 *
 * Hilt is not in the test classpath, so the two injected fields are set by hand
 * and the generated `inject()` is told it already ran.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class EngineServiceForegroundTest {

    private val state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    private val wanted = MutableStateFlow(false)
    private val repo: EngineRepository = object : EngineRepository by FakeEngine() {
        override val state: StateFlow<ConnectionState> get() = this@EngineServiceForegroundTest.state
        override val sessionWanted: StateFlow<Boolean> get() = wanted
    }
    private val keepAlive = EngineKeepAlive()
    private var controller: ServiceController<EngineService>? = null

    @After
    fun tearDown() {
        controller?.destroy()
    }

    private fun start(): EngineService {
        val c = Robolectric.buildService(EngineService::class.java)
        controller = c
        val service = c.get()
        service.repository = repo
        service.keepAlive = keepAlive
        skipHiltInject(service)
        c.create().startCommand(0, 1)
        idle()
        return service
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    @Test
    fun `a connect still before Connecting keeps the service in the foreground`() {
        // connect(): wantsConnection = true, then the service starts; the link is
        // still Disconnected while the local engine's database is being decided.
        wanted.value = true
        val service = start()
        val shadow = shadowOf(service)
        assertThat(shadow.lastForegroundNotification).isNotNull()
        assertThat(shadow.isStoppedBySelf).isFalse()

        // A slow bootPath: many turns of the main loop, still Disconnected.
        repeat(20) {
            Thread.sleep(5)
            idle()
        }
        assertThat(shadow.isStoppedBySelf).isFalse()
        assertThat(shadow.isForegroundStopped).isFalse()

        state.value = ConnectionState.Connecting
        idle()
        state.value = ConnectionState.Ready
        idle()
        assertThat(shadow.isStoppedBySelf).isFalse()

        // Thinking outlives a hang-up until the search settles…
        state.value = ConnectionState.Thinking
        idle()
        wanted.value = false
        idle()
        assertThat(shadow.isStoppedBySelf).isFalse()

        // …and then the service goes.
        state.value = ConnectionState.Disconnected
        idle()
        assertThat(shadow.isStoppedBySelf).isTrue()
    }

    @Test
    fun `nobody wanting a session lets a Disconnected service go`() {
        val service = start()
        assertThat(shadowOf(service).isStoppedBySelf).isTrue()
    }

    @Test
    fun `a library write-back holds the service past the session`() {
        keepAlive.acquire(EngineKeepAlive.DB_WRITEBACK)
        val service = start()
        assertThat(shadowOf(service).isStoppedBySelf).isFalse()
        keepAlive.release(EngineKeepAlive.DB_WRITEBACK)
        idle()
        assertThat(shadowOf(service).isStoppedBySelf).isTrue()
    }

    /** Hilt's generated base injects in `onCreate` unless `injected` is already set. */
    private fun skipHiltInject(service: Any) {
        var c: Class<*>? = service.javaClass
        while (c != null && !c.simpleName.startsWith("Hilt_")) c = c.superclass
        val field = checkNotNull(c) { "EngineService has no Hilt_ base class" }.getDeclaredField("injected")
        field.isAccessible = true
        field.setBoolean(service, true)
    }
}
