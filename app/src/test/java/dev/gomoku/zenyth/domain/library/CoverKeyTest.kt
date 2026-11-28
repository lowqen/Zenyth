package dev.gomoku.zenyth.domain.library

import com.google.common.truth.Truth.assertThat
import dev.gomoku.zenyth.core.model.Move
import dev.gomoku.zenyth.domain.library.yxdb.DbLabels
import dev.gomoku.zenyth.domain.library.yxdb.DbRoot
import dev.gomoku.zenyth.domain.library.yxdb.DbStats
import dev.gomoku.zenyth.domain.library.yxdb.Yxdb
import dev.gomoku.zenyth.domain.library.yxdb.YxdbKey
import org.junit.Test

/** Web `features/library/cover.test.ts`, ported: the cover key, roots → moves, a DB's start. */
class CoverKeyTest {

    private fun stats(openings: List<Long>? = null, roots: List<DbRoot>? = null) = DbStats(
        records = 10,
        rules = mapOf("renju-15" to 10L),
        labels = DbLabels(),
        commented = 0,
        openings = openings,
        roots = roots,
    )

    private val ops = MutableList(27) { 0L }.apply {
        this[4] = 50
        this[20] = 10
        this[26] = 900
    }

    private fun hex(size: Int, moves: List<Move>) = YxdbKey.toHex(YxdbKey.keyOfMoves(Yxdb.RULE_RENJU, size, moves))

    @Test
    fun `auto is the most frequent opening, the unclassified slot not counted`() {
        assertThat(Covers.dominantOpening(stats(ops))).isEqualTo(4)
        assertThat(Covers.keyOf(LibraryCover(LibraryCover.AUTO, seed = 3), stats(ops))).isEqualTo(CoverKey(4))
    }

    @Test
    fun `nothing classified or no stats - auto is an eclipse, the seed picks which`() {
        assertThat(Covers.keyOf(LibraryCover(LibraryCover.AUTO, seed = 2), stats(List(27) { 0L }))).isEqualTo(CoverKey.E0)
        assertThat(Covers.keyOf(LibraryCover(LibraryCover.AUTO, seed = 3), null)).isEqualTo(CoverKey.E1)
    }

    @Test
    fun `a chosen opening is itself, out of range an eclipse`() {
        assertThat(Covers.keyOf(LibraryCover(LibraryCover.OPENING, opening = 17, seed = 0), stats(ops))).isEqualTo(CoverKey(17))
        assertThat(Covers.keyOf(LibraryCover(LibraryCover.OPENING, opening = 26, seed = 1), null)).isEqualTo(CoverKey.E1)
        assertThat(Covers.keyOf(LibraryCover(LibraryCover.ECLIPSE, seed = 0), stats(ops))).isEqualTo(CoverKey.E0)
    }

    @Test
    fun `a root key comes back as alternating moves of the same position`() {
        val moves = listOf(Move(7, 7), Move(8, 6), Move(9, 9), Move(6, 6), Move(5, 9))
        val h = hex(15, moves)
        val out = LibraryPositions.movesOfKey(h, 15)!!
        assertThat(out).hasSize(5)
        assertThat(hex(15, out)).isEqualTo(h)
    }

    @Test
    fun `the empty board is no moves - another size or a bad key is null`() {
        assertThat(LibraryPositions.movesOfKey(hex(15, emptyList()), 15)).isEmpty()
        assertThat(LibraryPositions.movesOfKey(hex(20, listOf(Move(1, 1))), 15)).isNull()
        assertThat(LibraryPositions.movesOfKey("zz", 15)).isNull()
    }

    @Test
    fun `a DB starts at the largest root that reads on this board`() {
        val big = listOf(Move(7, 7), Move(8, 8), Move(9, 7))
        val roots = listOf(
            DbRoot(hex(20, listOf(Move(1, 1))), 900),
            DbRoot(hex(15, big), 500),
            DbRoot(hex(15, emptyList()), 10),
        )
        val out = LibraryPositions.startMovesOf(stats(roots = roots), 15)!!
        assertThat(hex(15, out)).isEqualTo(roots[1].key)
        assertThat(LibraryPositions.startMovesOf(stats(), 15)).isNull()
        assertThat(LibraryPositions.startMovesOf(null, 15)).isNull()
    }

    @Test
    fun `a root's start wins when the index found one`() {
        val deep = listOf(Move(7, 7), Move(7, 6), Move(7, 5), Move(9, 9), Move(5, 7))
        val roots = listOf(DbRoot(hex(15, emptyList()), 900, start = hex(15, deep)))
        val out = LibraryPositions.startMovesOf(stats(roots = roots), 15)!!
        assertThat(out).hasSize(5)
        assertThat(hex(15, out)).isEqualTo(roots[0].start)
    }

    @Test
    fun `the start mode picks the branch, the root, or an empty board`() {
        val deep = listOf(Move(7, 7), Move(7, 6), Move(7, 5))
        val roots = listOf(DbRoot(hex(15, emptyList()), 900, start = hex(15, deep)))
        val s = stats(roots = roots)
        assertThat(LibraryPositions.startMovesOf(s, 15, StartMode.BRANCH)).hasSize(3)
        assertThat(LibraryPositions.startMovesOf(s, 15, StartMode.ROOT)).isEmpty()
        assertThat(LibraryPositions.startMovesOf(s, 15, StartMode.EMPTY)).isEmpty()
        assertThat(LibraryPositions.startMovesOf(null, 15, StartMode.EMPTY)).isEmpty() // no stats needed
        assertThat(LibraryPositions.startMovesOf(null, 15, StartMode.BRANCH)).isNull()
        assertThat(StartMode.of("root")).isEqualTo(StartMode.ROOT)
        assertThat(StartMode.of("??")).isEqualTo(StartMode.BRANCH)
    }

    @Test
    fun `the main rule is the one with the most positions`() {
        val s = stats().copy(rules = mapOf("freestyle-15" to 3L, "renju-15" to 9L, "standard-20" to 1L))
        assertThat(LibraryPositions.mainRuleOf(s)).isEqualTo(DbRule(2, 15))
        assertThat(LibraryPositions.mainRuleOf(s.copy(rules = mapOf("standard-20" to 1L)))).isEqualTo(DbRule(1, 20))
        assertThat(LibraryPositions.mainRuleOf(s.copy(rules = mapOf("rule7-15" to 1L)))).isNull()
        assertThat(LibraryPositions.mainRuleOf(s.copy(rules = mapOf("renju-15x20" to 1L)))).isNull()
        assertThat(LibraryPositions.mainRuleOf(null)).isNull()
    }
}
