package dev.gomoku.zenyth.feature.connection

import com.google.common.truth.Truth.assertThat
import dev.gomoku.zenyth.core.model.ConnectionState
import dev.gomoku.zenyth.core.model.EngineEndpoint
import dev.gomoku.zenyth.core.model.LinkHealth
import org.junit.Test

class ConnectionUiStateTest {

    private val server = ConnectionUiState(serverEnabled = true, localMode = false, endpoint = EngineEndpoint(host = ""))

    @Test
    fun `the on-device engine starts without an address`() {
        assertThat(ConnectionUiState().canStart).isTrue()
        assertThat(ConnectionUiState(serverEnabled = true, localMode = true, endpoint = EngineEndpoint(host = "")).canStart).isTrue()
        // Server switched off in settings: the saved address is not even read.
        assertThat(ConnectionUiState(serverEnabled = false, localMode = false, endpoint = EngineEndpoint(host = "")).canStart).isTrue()
    }

    @Test
    fun `the server engine needs an address from settings`() {
        assertThat(server.needsAddress).isTrue()
        assertThat(server.canStart).isFalse()
        assertThat(server.copy(endpoint = EngineEndpoint(host = "  ")).canStart).isFalse()
        assertThat(server.copy(endpoint = EngineEndpoint(host = "100.64.0.9")).canStart).isTrue()
    }

    @Test
    fun `start and hash clear follow the engine state`() {
        val ready = ConnectionUiState(state = ConnectionState.Ready)
        assertThat(ready.canConnect).isFalse()
        assertThat(ready.canClearHash).isTrue()
        // Mid-search it would empty the table being read.
        assertThat(ready.copy(state = ConnectionState.Thinking).canClearHash).isFalse()
        assertThat(ConnectionUiState().canClearHash).isFalse()
        assertThat(ConnectionUiState(state = ConnectionState.Error("x")).canConnect).isTrue()
        assertThat(ConnectionUiState(health = LinkHealth(reconnecting = true)).canConnect).isFalse()
    }
}
