package dev.gomoku.zenyth.domain.library.yxdb

import dev.gomoku.zenyth.core.model.Move

/**
 * yixindb keys — symmetric Zobrist hashes and parent positions (Zenyth-web
 * `yxdbkey.ts`). The library index finds positions without holding key bytes.
 *
 * - Transform numbers follow the engine's `applyTransform` (pos.h): IDENTITY,
 *   ROTATE_90, 180, 270, FLIP_X, FLIP_Y, FLIP_XY, FLIP_YX.
 * - Symmetric hash: the minimum of the 8 transforms' Zobrist values (two
 *   signed 32-bit halves, compared signed) — the same in any frame. Adding or
 *   removing a stone is an XOR, so a parent candidate's hash needs no new key.
 * - The table comes from splitmix64 seeded "ZENYTH" — the web's table exactly.
 *   It is deliberately not `domain/rif/Zobrist` (another seed and cell order):
 *   the library's stats must come out the same on every device.
 *
 * Cell = `(x shl 5) or y` (boards up to 32), which sorts like the (x, y) bytes.
 *
 * - Rule · board salt (L5-0, 2026-11-24): a hash starts from
 *   [ruleSalt] instead of 0, so a mixed-rule file does not merge equal stones
 *   of different rules into one position. Stats carry [YxdbIndex.INDEX_VERSION].
 */
object YxdbKey {
    const val MAX_BOARD = 32
    const val CELLS = MAX_BOARD * MAX_BOARD

    /** Zobrist halves, index `colour * 1024 + cell`. */
    internal val ZH = IntArray(2 * CELLS)
    internal val ZL = IntArray(2 * CELLS)

    init {
        var s = 0x5a454e595448L // "ZENYTH"
        for (i in ZH.indices) {
            s += -0x61c8864680b583ebL            // 0x9e3779b97f4a7c15
            var z = s
            z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L // 0xbf58476d1ce4e5b9
            z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L // 0x94d049bb133111eb
            z = z xor (z ushr 31)
            ZH[i] = (z ushr 32).toInt()
            ZL[i] = z.toInt()
        }
    }

    /** Moves (x, y) by transform [t]; s = board size - 1. Returns `(X shl 5) or Y`. */
    fun transformCell(t: Int, x: Int, y: Int, s: Int): Int = when (t) {
        1 -> (y shl 5) or (s - x)
        2 -> ((s - x) shl 5) or (s - y)
        3 -> ((s - y) shl 5) or x
        4 -> (x shl 5) or (s - y)
        5 -> ((s - x) shl 5) or y
        6 -> (y shl 5) or x
        7 -> ((s - y) shl 5) or (s - x)
        else -> (x shl 5) or y
    }

    private val rowsCache = arrayOfNulls<IntArray>(MAX_BOARD + 1)

    /**
     * Hash rows for board size w: `R[(colour * 1024 + cell) * 16 + 2t (+1)]` =
     * the high (low) Zobrist half of the cell moved by transform t. Adding a
     * stone is 16 consecutive XORs. 128 KB per board size.
     */
    @Synchronized
    internal fun rows(w: Int): IntArray {
        rowsCache[w]?.let { return it }
        val r = IntArray(2 * CELLS * 16)
        val s = w - 1
        for (c in 0..1) for (x in 0 until w) for (y in 0 until w) {
            val o = (c * CELLS + ((x shl 5) or y)) * 16
            for (t in 0 until 8) {
                val q = c * CELLS + transformCell(t, x, y, s)
                r[o + 2 * t] = ZH[q]
                r[o + 2 * t + 1] = ZL[q]
            }
        }
        rowsCache[w] = r
        return r
    }

    fun toHex(b: ByteArray, off: Int = 0, len: Int = b.size - off): String {
        val sb = StringBuilder(len * 2)
        for (i in off until off + len) {
            val v = b[i].toInt() and 0xff
            sb.append(HEX[v ushr 4]).append(HEX[v and 15])
        }
        return sb.toString()
    }

    fun fromHex(hex: String): ByteArray {
        require(hex.length % 2 == 0) { "not a key string: $hex" }
        return ByteArray(hex.length / 2) { i ->
            val hi = Character.digit(hex[2 * i], 16)
            val lo = Character.digit(hex[2 * i + 1], 16)
            require(hi >= 0 && lo >= 0) { "not a key string: $hex" }
            ((hi shl 4) or lo).toByte()
        }
    }

    private val HEX = "0123456789abcdef".toCharArray()

    private val saltCache = HashMap<Int, Long>()

    /**
     * The hash start for (rule, board size) — one splitmix64 step from
     * `"RULE" shl 16 or rule shl 8 or w` (web `ruleSalt`). The same value in all
     * 8 transforms, so canonical and parent hashes still agree, while equal
     * stones under renju · standard · freestyle (or another board) stay apart.
     */
    @Synchronized
    fun ruleSalt(rule: Int, w: Int): Long {
        val k = ((rule and 0xff) shl 8) or (w and 0xff)
        return saltCache.getOrPut(k) {
            var z = (0x52554c45L shl 16) or k.toLong()
            z += -0x61c8864680b583ebL                         // 0x9e3779b97f4a7c15
            z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L    // 0xbf58476d1ce4e5b9
            z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L    // 0x94d049bb133111eb
            z xor (z ushr 31)
        }
    }

    /** Key bytes → stones (a pass slot ends its section). Null when malformed. */
    fun decode(b: ByteArray): DecodedKey? {
        if (b.size < 3 || (b.size - 3) and 1 != 0) return null
        val w = b[1].toInt() and 0xff
        val h = b[2].toInt() and 0xff
        val slots = (b.size - 3) shr 1
        val nb = (slots + 1) shr 1
        val black = ArrayList<Move>()
        val white = ArrayList<Move>()
        var pass = false
        for (c in 0..1) {
            val begin = if (c == 0) 0 else nb
            val cnt = if (c == 0) nb else slots - nb
            for (i in 0 until cnt) {
                val x = b[3 + 2 * (begin + i)].toInt() and 0xff
                val y = b[4 + 2 * (begin + i)].toInt() and 0xff
                if (x == 0xff && y == 0xff) {
                    pass = true
                    break
                }
                if (x >= w || y >= h) return null
                (if (c == 0) black else white).add(Move(x, y))
            }
        }
        return DecodedKey(b[0].toInt() and 0xff, w, h, black, white, pass)
    }

    /** The engine's canonical key (`toSmallestDBKey`): smallest (sorted black, sorted white) over 8 transforms. */
    fun canonicalKey(rule: Int, w: Int, black: List<Move>, white: List<Move>): ByteArray {
        val s = w - 1
        var best: IntArray? = null
        for (t in 0 until 8) {
            val tb = black.map { transformCell(t, it.x, it.y, s) }.sorted()
            val tw = white.map { transformCell(t, it.x, it.y, s) }.sorted()
            val all = (tb + tw).toIntArray()
            val cur = best
            if (cur == null || lessSeq(all, cur)) best = all
        }
        val cells = best!!
        val key = ByteArray(3 + 2 * cells.size)
        key[0] = rule.toByte(); key[1] = w.toByte(); key[2] = w.toByte()
        cells.forEachIndexed { i, c ->
            key[3 + 2 * i] = (c shr 5).toByte()
            key[4 + 2 * i] = (c and 31).toByte()
        }
        return key
    }

    fun keyOfMoves(rule: Int, w: Int, moves: List<Move>): ByteArray =
        canonicalKey(rule, w, moves.filterIndexed { i, _ -> i % 2 == 0 }, moves.filterIndexed { i, _ -> i % 2 == 1 })

    private fun lessSeq(a: IntArray, b: IntArray): Boolean {
        for (i in a.indices) if (a[i] != b[i]) return a[i] < b[i]
        return false
    }
}

data class DecodedKey(
    val rule: Int,
    val w: Int,
    val h: Int,
    val black: List<Move>,
    val white: List<Move>,
    /** A pass slot was present (side to move is the opposite of the stone count). */
    val pass: Boolean,
)

/**
 * Reads one key and holds its 8 transform hashes; yields the canonical hash
 * and parent candidates' hashes. Reused without allocation. Results land in
 * [hi] · [lo] (signed, compared signed).
 */
class KeyHasher {
    /** 8 transform hashes — `vec[2t]` high, `vec[2t + 1]` low. */
    val vec = IntArray(16)
    /** Cells `(x shl 5) or y` — [nb] black, then white. */
    val cells = IntArray(YxdbKey.CELLS)
    var n = 0
        private set
    var nb = 0
        private set
    var rule = 0
        private set
    var w = 0
        private set
    var hi = 0
        private set
    var lo = 0
        private set
    private var rows: IntArray = YxdbKey.rows(15)

    /**
     * Prefix hashes — `pre[i * 16 ..]` = the first i stones' 8 hashes. Files are
     * in key order, so neighbours share most leading stones (sample: 24 of 37);
     * only the part after the common prefix is recomputed.
     */
    private val pre = IntArray((YxdbKey.CELLS + 1) * 16)
    private var preValid = 0

    /**
     * Loads a key. True when hashable (square, 1..32 lines, no pass slot,
     * coordinates on the board). Call [hash] separately.
     *
     * [known]: leading slots byte-equal to the previous key, which loaded
     * successfully with the same rule and board (`KeyTrail.common`) — they are
     * already in [cells] and on the board, so they are not read again.
     */
    fun load(b: ByteArray, off: Int, len: Int, known: Int = 0): Boolean {
        if (len < 3 || (len - 3) and 1 != 0) return false
        val bw = b[off + 1].toInt() and 0xff
        if (bw == 0 || bw > YxdbKey.MAX_BOARD || (b[off + 2].toInt() and 0xff) != bw) return false
        val count = (len - 3) shr 1
        val c = cells
        // Another rule or board starts from another salt.
        var lcp = if (bw == w && (b[off].toInt() and 0xff) == rule) preValid else 0
        val from = minOf(known, count)
        var p = off + 3 + 2 * from
        for (i in from until count) {
            val x = b[p].toInt() and 0xff
            val y = b[p + 1].toInt() and 0xff
            if (x >= bw || y >= bw) {
                preValid = 0 // cells were partly overwritten
                return false // pass slots (0xFF) land here too
            }
            val cell = (x shl 5) or y
            if (i < lcp && c[i] != cell) lcp = i
            c[i] = cell
            p += 2
        }
        val newNb = (count + 1) shr 1
        if (lcp > count) lcp = count
        // Colour is "i < nb": when nb changes only the all-black prefix still holds.
        if (newNb != nb) lcp = minOf(lcp, newNb, nb)
        preValid = lcp
        if (bw != w) {
            rows = YxdbKey.rows(bw)
            w = bw
        }
        rule = b[off].toInt() and 0xff
        n = count
        nb = newNb
        return true
    }

    /** Fills [vec] with the loaded key's 8 transform hashes. */
    fun hash() {
        val r = rows
        val c = cells
        val pr = pre
        val count = n
        val black = nb
        var i = preValid
        if (i == 0) {
            val salt = YxdbKey.ruleSalt(rule, w)
            val sh = (salt ushr 32).toInt()
            val sl = salt.toInt()
            for (t in 0 until 16 step 2) { pr[t] = sh; pr[t + 1] = sl }
        }
        var o = i * 16
        while (i < count) {
            val z = ((if (i < black) 0 else YxdbKey.CELLS) + c[i]) * 16
            val d = o + 16
            for (k in 0 until 16) pr[d + k] = pr[o + k] xor r[z + k]
            o = d
            i++
        }
        preValid = count
        System.arraycopy(pr, count * 16, vec, 0, 16)
    }

    /** This position's canonical hash (after [hash]). */
    fun canon() {
        val v = vec
        var bh = v[0]
        var bl = v[1]
        for (t in 1 until 8) {
            val h = v[2 * t]
            val l = v[2 * t + 1]
            if (h < bh || (h == bh && l < bl)) {
                bh = h
                bl = l
            }
        }
        hi = bh
        lo = bl
    }

    /** Range [from, to) of the side that moved last — empty when no stones. */
    val lastFrom: Int get() = if (n % 2 == 1) 0 else nb
    val lastTo: Int get() = if (n == 0) 0 else if (n % 2 == 1) nb else n

    /** Canonical hash of the position without stone k (a parent candidate), after [hash]. */
    fun without(k: Int) {
        val v = vec
        val r = rows
        val z = ((if (k < nb) 0 else YxdbKey.CELLS) + cells[k]) * 16
        var bh = v[0] xor r[z]
        var bl = v[1] xor r[z + 1]
        var t = 2
        while (t < 16) {
            val h = v[t] xor r[z + t]
            val l = v[t + 1] xor r[z + t + 1]
            if (h < bh || (h == bh && l < bl)) {
                bh = h
                bl = l
            }
            t += 2
        }
        hi = bh
        lo = bl
    }
}
