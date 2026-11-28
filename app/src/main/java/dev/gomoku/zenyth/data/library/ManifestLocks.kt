package dev.gomoku.zenyth.data.library

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * One sidecar writer per source at a time (design §4: «사이드카 쓰기는 소스마다
 * 한 줄»). The library screen's repository, the attach controller's write-back
 * and the boot path's «내 DB» all read, change and write the same
 * `zenyth-library.json`; without this a write-back finishing during a rename
 * would put the old title back.
 *
 * Not reentrant: a block never calls something that takes the same lock.
 */
object ManifestLocks {
    private val locks = ConcurrentHashMap<String, Mutex>()

    fun of(sourceId: String): Mutex = locks.getOrPut(sourceId) { Mutex() }

    suspend fun <T> with(sourceId: String, block: suspend () -> T): T = of(sourceId).withLock { block() }
}
