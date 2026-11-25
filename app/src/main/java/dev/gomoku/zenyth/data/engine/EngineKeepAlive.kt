package dev.gomoku.zenyth.data.engine

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/**
 * "Do not go down yet" for [EngineService] (library L5-4 §9 safe background
 * write-back). Android 12+ will not let a backgrounded app start a foreground
 * service, but the engine's is already running whenever a SAF copy is dirty —
 * so the write-back holds it up (notification «DB 저장 중») instead of
 * starting anything new. The process keeps its foreground-service priority,
 * and so does the `:library` process it binds for the copy.
 */
@Singleton
class EngineKeepAlive @Inject constructor() {
    private val _holds = MutableStateFlow<Set<String>>(emptySet())
    val holds: StateFlow<Set<String>> = _holds.asStateFlow()

    fun acquire(reason: String) = _holds.update { it + reason }
    fun release(reason: String) = _holds.update { it - reason }

    companion object {
        const val DB_WRITEBACK = "db-writeback"
    }
}
