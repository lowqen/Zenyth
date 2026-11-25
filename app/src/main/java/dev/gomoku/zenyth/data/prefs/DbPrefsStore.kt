package dev.gomoku.zenyth.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.gomoku.zenyth.domain.repository.DbPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dbDataStore by preferencesDataStore(name = "database")

/**
 * App-local database preferences. Deliberately **not** part of [dev.gomoku
 * .zenyth.core.model.AppSettings]: that model is pinned line-for-line to the
 * desktop's `settings.txt`/`settings_dev.txt` (47 + 20), and adding a field
 * there would break the codec's round-trip.
 *
 * Holds the last server-side path, so the file dialogs do not start empty
 * every time. The destructive-operation unlock is expert mode now (L5-5,
 * design §9-2): the old `destructive_unlocked` key is left unread.
 */
@Singleton
class DbPrefsStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val ui: UiPrefs,
) : DbPreferences {
    private val lastPathKey = stringPreferencesKey("last_path")

    override val destructiveUnlocked: Flow<Boolean> get() = ui.proMode.state

    override val lastPath: Flow<String> =
        context.dbDataStore.data.map { it[lastPathKey] ?: DEFAULT_PATH }

    override suspend fun setLastPath(path: String) {
        context.dbDataStore.edit { it[lastPathKey] = path }
    }

    private companion object {
        /** The engine's own database, as configured on the server. */
        const val DEFAULT_PATH = "rapfi.db"
    }
}
