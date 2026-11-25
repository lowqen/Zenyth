package dev.gomoku.zenyth.feature.settings

import com.google.common.truth.Truth.assertThat
import dev.gomoku.zenyth.core.model.EngineEndpoint
import org.junit.Test

/** The server address moved to Settings ▸ 엔진 (L5-5); its warning came with it. */
class SettingsEndpointTest {

    private val server = SettingsUiState(serverEnabled = true, localMode = false)

    private fun at(host: String, local: Boolean = false) =
        server.copy(endpoint = EngineEndpoint(host = host), localMode = local)

    @Test
    fun `only a public address on the chosen server warns`() {
        assertThat(at("100.64.0.9").plaintextWarning).isFalse()
        assertThat(at("192.168.0.4").plaintextWarning).isFalse()
        assertThat(at("").plaintextWarning).isFalse()
        assertThat(at("example.com").plaintextWarning).isTrue()
        assertThat(at("8.8.8.8").plaintextWarning).isTrue()
        // On-device chosen: the address is not used, so it does not warn.
        assertThat(at("8.8.8.8", local = true).plaintextWarning).isFalse()
        // The server not offered at all: nothing is chosen but the phone.
        assertThat(at("8.8.8.8").copy(serverEnabled = false).plaintextWarning).isFalse()
    }
}
