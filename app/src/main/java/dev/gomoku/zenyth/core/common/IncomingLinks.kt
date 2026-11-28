package dev.gomoku.zenyth.core.common

import dev.gomoku.zenyth.core.joseki.LessonTarget
import dev.gomoku.zenyth.core.model.Move
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Zenyth Web links the app was opened with, waiting for the screen that takes
 * them. The activity receives the intent; the screen, whenever it next exists,
 * consumes it.
 *
 *  - a position (`…/board?p=h8i9j10`) — the board;
 *  - a curriculum lesson or course (`…/joseki?lesson=<id>`) — the shell moves
 *    to the joseki tab and the joseki screen opens it;
 *  - a position handed over inside the app ([BoardHandoff] — the library's
 *    «이 DB 열기»), which the board opens the way it opens a link.
 */
@Singleton
class IncomingLinks @Inject constructor() {
    private val _pending = MutableStateFlow<String?>(null)
    val pending: StateFlow<String?> = _pending.asStateFlow()

    private val _lesson = MutableStateFlow<LessonTarget?>(null)
    val lesson: StateFlow<LessonTarget?> = _lesson.asStateFlow()

    fun offer(text: String) {
        _pending.value = text
    }

    fun consume(): String? = _pending.value.also { _pending.value = null }

    fun offerLesson(target: LessonTarget) {
        _lesson.value = target
    }

    fun consumeLesson(): LessonTarget? = _lesson.value.also { _lesson.value = null }

    private val _position = MutableStateFlow<BoardHandoff?>(null)
    val position: StateFlow<BoardHandoff?> = _position.asStateFlow()

    fun offerPosition(handoff: BoardHandoff) {
        _position.value = handoff
    }

    fun consumePosition(): BoardHandoff? = _position.value.also { _position.value = null }
}

/**
 * A line for the board from another screen. [title] heads the card that keeps
 * the board it replaced («두던 판으로 돌아가기 · 버리기»).
 */
data class BoardHandoff(val moves: List<Move>, val title: String)
