package dev.gomoku.zenyth.domain.repository

import kotlinx.coroutines.flow.Flow

/**
 * App-local database preferences (not part of the desktop settings files, whose
 * 67 lines are pinned by the codec). Behind an interface so the repository's
 * guard logic can be unit-tested without Android's DataStore.
 */
interface DbPreferences {
    /** Bulk delete / split allowed — «고급 사용자 모드» (L5-5; it was a switch of its own). */
    val destructiveUnlocked: Flow<Boolean>

    /** Last engine-side path used by a file operation. */
    val lastPath: Flow<String>

    suspend fun setLastPath(path: String)
}
