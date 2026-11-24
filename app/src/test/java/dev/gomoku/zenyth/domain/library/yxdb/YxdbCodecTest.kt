package dev.gomoku.zenyth.domain.library.yxdb

import com.google.common.truth.Truth.assertThat
import dev.gomoku.zenyth.core.model.Move
import dev.gomoku.zenyth.core.model.Opening26
import dev.gomoku.zenyth.domain.library.Fingerprint
import dev.gomoku.zenyth.domain.library.Fnv1a64
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.random.Random

/**
 * Library L5 codec — the vectors and round trips the web port pins
 * (Zenyth-web `yxdbfile.test.ts`, `data/library.test.ts`, `yxdbindex.test.ts`).
 */
class YxdbCodecTest {

    @Test
    fun xxh32Vectors() {
        assertThat(Xxh32.of(ByteArray(0))).isEqualTo(0x02cc5d05)
        assertThat(Xxh32.of("abc".toByteArray())).isEqualTo(0x32d153ff)
        // Streaming in odd pieces gives the same digest as one shot.
        val data = Random(7).nextBytes(1000)
        val h = Xxh32()
        var p = 0
        for (n in listOf(1, 3, 15, 16, 17, 100, 848)) { h.update(data, p, n); p += n }
        assertThat(h.digest()).isEqualTo(Xxh32.of(data))
    }

    @Test
    fun fnvVectorsAndFingerprint() {
        assertThat(Fingerprint.hex16(Fnv1a64.of(ByteArray(0)))).isEqualTo("cbf29ce484222325")
        assertThat(Fingerprint.hex16(Fnv1a64.of("a".toByteArray()))).isEqualTo("af63dc4c8601ec8c")
        assertThat(Fingerprint.hex16(Fnv1a64.of("foobar".toByteArray()))).isEqualTo("85944171f73967e8")
        val a = Fingerprint.of(6, 1, "foobar".toByteArray())
        assertThat(a.sameFile(a.copy(mtime = 99))).isTrue()
        assertThat(a.sameFile(a.copy(size = 7))).isFalse()
    }

    @Test
    fun lz4FrameRoundTrip() {
        val rnd = Random(11)
        // Mixed: incompressible noise, long runs, and repeats at many offsets.
        val src = ByteArrayOutputStream().apply {
            write(rnd.nextBytes(70_000))
            write(ByteArray(300_000) { 7 })
            val word = rnd.nextBytes(37)
            repeat(20_000) { write(word, 0, 1 + it % 37) }
        }.toByteArray()
        val frame = ByteArrayOutputStream()
        Lz4FrameEncoder({ b, o, l -> frame.write(b, o, l) }, blockKB = 64).apply { write(src); finish() }
        val packed = frame.toByteArray()
        assertThat(packed.size).isLessThan(src.size)

        val out = ByteArrayOutputStream()
        val dec = Lz4FrameDecoder({ b, o, l -> out.write(b, o, l) }, verify = true)
        // Push in uneven chunks to cross every unit boundary.
        var p = 0
        var step = 1
        while (p < packed.size) {
            val n = minOf(step, packed.size - p)
            dec.push(packed, p, n)
            p += n
            step = step * 3 % 7919 + 1
        }
        dec.end()
        assertThat(out.toByteArray()).isEqualTo(src)
    }

    @Test
    fun lz4DetectsCorruption() {
        val frame = ByteArrayOutputStream()
        Lz4FrameEncoder({ b, o, l -> frame.write(b, o, l) }).apply { write(ByteArray(5000) { (it % 13).toByte() }); finish() }
        val bad = frame.toByteArray().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
        val dec = Lz4FrameDecoder({ _, _, _ -> }, verify = true)
        assertThrows(Lz4Exception::class.java) { dec.push(bad) }
        // A cut frame is reported at the end.
        val cut = frame.toByteArray().copyOf(frame.size() - 3)
        val dec2 = Lz4FrameDecoder({ _, _, _ -> })
        dec2.push(cut)
        assertThrows(Lz4Exception::class.java) { dec2.end() }
    }

    // ── a small renju DB: two opening branches under the empty board ──

    private fun key(moves: List<Move>) = YxdbKey.keyOfMoves(Yxdb.RULE_RENJU, 15, moves)

    private fun value(label: Char, text: String = ""): ByteArray {
        val t = text.toByteArray()
        return ByteArray(5 + t.size).also { it[0] = label.code.toByte(); System.arraycopy(t, 0, it, 5, t.size) }
    }

    private fun sampleDb(compress: Boolean): ByteArray {
        val d5 = Opening26.representative(4) + Move(3, 3)
        val i1 = Opening26.representative(13) + Move(11, 2)
        val keys = (listOf(emptyList<Move>()) + (1..4).map { d5.take(it) } + (2..4).map { i1.take(it) })
            .map { key(it) }
            .distinctBy { it.toList() }
            .sortedWith { a, b -> Yxdb.compareKeys(a, 0, a.size, b, 0, b.size) }
        return buildDb(keys, compress)
    }

    private fun buildDb(keys: List<ByteArray>, compress: Boolean): ByteArray {
        val (mk, mv) = Yxdb.metaRecord()
        val out = ByteArrayOutputStream()
        val w = YxdbWriter(keys.size + 1L, { b, o, l -> out.write(b, o, l) }, compress = compress, blockKB = 64)
        w.recordKV(mk, 0, mk.size, mv, 0, mv.size)
        keys.forEachIndexed { i, k ->
            val v = when (i % 4) { 0 -> value('w', "note $i"); 1 -> value('l'); 2 -> value('d'); else -> ByteArray(0) }
            w.recordKV(k, 0, k.size, v, 0, v.size)
        }
        w.finish()
        return out.toByteArray()
    }

    @Test
    fun scanCountsOpeningsAndRoots() {
        for (compress in listOf(true, false)) {
            val db = sampleDb(compress)
            val r = YxdbIndex.scan({ ByteArrayInputStream(db) }, db.size.toLong())
            assertThat(r.compressed).isEqualTo(compress)
            assertThat(r.sorted).isTrue()
            assertThat(r.countOrdered).isTrue()
            assertThat(r.stats.records).isEqualTo(8)
            assertThat(r.stats.rules).containsExactly("renju-15", 8L)
            assertThat(r.stats.labels).isEqualTo(DbLabels(W = 2, L = 2, D = 2, other = 0))
            assertThat(r.stats.commented).isEqualTo(2)
            val o = r.stats.openings!!
            assertThat(o).hasSize(27)
            assertThat(o[4]).isEqualTo(2)   // D5: its 3- and 4-stone positions
            assertThat(o[13]).isEqualTo(2)  // I1
            assertThat(o[26]).isEqualTo(4)  // empty, 1 stone, two 2-stone positions
            assertThat(r.stats.roots!!.map { it.subtree }).containsExactly(8L)
            assertThat(r.stats.roots.single().key).isEqualTo("020f0f") // the empty board
        }
    }

    @Test
    fun indexIsSkippedOverBudgetAndStatsStay() {
        val db = sampleDb(true)
        val r = YxdbIndex.scan({ ByteArrayInputStream(db) }, db.size.toLong(), maxIndexBytes = 1)
        assertThat(r.indexSkipped).isEqualTo(IndexSkipped.MEMORY)
        assertThat(r.stats.records).isEqualTo(8)
        assertThat(r.stats.openings).isNull()
        assertThat(r.stats.roots).isNull()
    }

    @Test
    fun cancelStopsTheRead() {
        val db = sampleDb(true)
        assertThrows(YxdbCancelled::class.java) {
            YxdbIndex.scan({ ByteArrayInputStream(db) }, db.size.toLong(), isCancelled = { true })
        }
    }

    @Test
    fun cutFileIsReported() {
        val db = sampleDb(false)
        val cut = db.copyOf(db.size - 2)
        assertThrows(YxdbFormatException::class.java) {
            Yxdb.read(ByteArrayInputStream(cut), cut.size.toLong(), { _, _, _, _, _, _ -> true })
        }
    }

    @Test
    fun parentHashMatchesTheParentsOwnHash() {
        val moves = Opening26.representative(7) + Move(2, 12) + Move(12, 12)
        val h = KeyHasher()
        val child = key(moves)
        val parent = key(moves.dropLast(1))
        check(h.load(parent, 0, parent.size)); h.hash(); h.canon()
        val (ph, pl) = h.hi to h.lo
        check(h.load(child, 0, child.size)); h.hash()
        val found = (h.lastFrom until h.lastTo).any { k -> h.without(k); h.hi == ph && h.lo == pl }
        assertThat(found).isTrue()
    }

    @Test
    fun ruleSaltKeepsRulesApart() {
        // The web's yxdbfile.test.ts pins the same vector.
        assertThat(Fingerprint.hex16(YxdbKey.ruleSalt(Yxdb.RULE_RENJU, 15))).isEqualTo("f8e1fdffa82a22c5")
        val moves = listOf(Move(7, 7), Move(8, 6), Move(9, 9))
        val h = KeyHasher()
        val seen = listOf(2 to 15, 1 to 15, 0 to 15, 0 to 20, 2 to 15).map { (rule, w) ->
            val k = YxdbKey.keyOfMoves(rule, w, moves)
            check(h.load(k, 0, k.size)); h.hash(); h.canon()
            h.hi to h.lo
        }
        assertThat(seen.toSet()).hasSize(4)
        assertThat(seen[4]).isEqualTo(seen[0]) // same after prefix reuse
    }

    @Test
    fun oldStatsAreCurrentOnlyForOneRule() {
        val one = DbStats(3, mapOf("renju-15" to 3L), DbLabels(0, 0, 0, 0), 0)
        assertThat(one.current).isTrue()
        val mixed = one.copy(rules = mapOf("renju-15" to 2L, "standard-15" to 1L))
        assertThat(mixed.current).isFalse()
        assertThat(mixed.copy(indexVersion = YxdbIndex.INDEX_VERSION).current).isTrue()
    }

    /** Random keys: shared prefixes, other lengths · rules · boards, pass slots, short keys. */
    private fun randomKeys(rnd: Random, n: Int): List<ByteArray> {
        val out = ArrayList<ByteArray>()
        var last = key(Opening26.representative(0))
        repeat(n) {
            val k = when (rnd.nextInt(8)) {
                0 -> byteArrayOf(rnd.nextInt(3).toByte(), 15, 15) + rnd.nextBytes(2 * rnd.nextInt(6)).map { (it.toInt() and 15).toByte() }
                1 -> last.copyOf(last.size + 2).also { it[it.size - 2] = rnd.nextInt(15).toByte(); it[it.size - 1] = rnd.nextInt(15).toByte() }
                2 -> if (last.size > 5) last.copyOf(last.size - 2) else last.copyOf()
                3 -> last.copyOf().also { if (it.size > 3) it[it.size - 1] = (-1).toByte(); if (it.size > 4) it[it.size - 2] = (-1).toByte() }
                4 -> byteArrayOf(rnd.nextInt(3).toByte())
                5 -> last.copyOf().also { it[0] = rnd.nextInt(3).toByte() }
                else -> last.copyOf().also { if (it.size > 3) it[3 + rnd.nextInt(it.size - 3)] = rnd.nextInt(15).toByte() }
            }
            out.add(k)
            last = k
        }
        return out
    }

    @Test
    fun keyTrailAgreesWithCompareKeys() {
        val keys = randomKeys(Random(3), 5000)
        val t = KeyTrail()
        val sign = { v: Int -> v.coerceIn(-1, 1) }
        keys.forEachIndexed { i, k ->
            val c = t.next(k, 0, k.size)
            if (i == 0) return@forEachIndexed
            val p = keys[i - 1]
            assertThat(sign(c)).isEqualTo(sign(Yxdb.compareKeys(p, 0, p.size, k, 0, k.size)))
            var m = 0
            while (m < minOf(p.size, k.size) && p[m] == k[m]) m++
            assertThat(t.common).isEqualTo(m)
        }
    }

    @Test
    fun parserCutsRecordsAcrossAnyChunking() {
        val db = sampleDb(compress = false)
        fun records(step: (Int) -> Int): List<String> {
            val out = ArrayList<String>()
            val p = YxdbRecordParser({ b, ko, kl, vo, vl, _ -> out.add(YxdbKey.toHex(b, ko, kl) + ":" + YxdbKey.toHex(b, vo, vl)); true })
            var pos = 0
            var i = 0
            while (pos < db.size) {
                val n = minOf(step(i++), db.size - pos)
                p.push(db, pos, n)
                pos += n
            }
            p.end()
            return out
        }
        val whole = records { db.size }
        assertThat(whole).hasSize(9)
        for (s in listOf(1, 2, 3, 5, 7, 13)) assertThat(records { s }).isEqualTo(whole)
        assertThat(records { it % 11 + 1 }).isEqualTo(whole)
    }

    @Test
    fun keyHexRoundTripAndDecode() {
        val k = key(Opening26.representative(0))
        val back = YxdbKey.fromHex(YxdbKey.toHex(k))
        assertThat(back).isEqualTo(k)
        val d = YxdbKey.decode(back)!!
        assertThat(d.black).hasSize(2)
        assertThat(d.white).hasSize(1)
        assertThat(d.pass).isFalse()
    }
}
