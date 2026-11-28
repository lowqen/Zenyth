package dev.gomoku.zenyth.domain.library.yxdb

import com.google.common.truth.Truth.assertThat
import dev.gomoku.zenyth.core.model.Move
import dev.gomoku.zenyth.core.model.Opening26
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Test
import java.io.ByteArrayInputStream

/**
 * Cross-implementation goldens (library L5 design §8). The files under
 * `src/test/resources/library/` were written by the web codec
 * (Zenyth-web `bench/dbio/l5-golden.ts`): synthetic yixindb files and what
 * the web's `scanYxdb` made of them. Stats travel in the folder sidecar, so
 * each app trusts the other's numbers only if both count the same way.
 */
class LibraryGoldenTest {

    private fun resource(name: String): ByteArray =
        checkNotNull(javaClass.classLoader!!.getResourceAsStream("library/$name")) { "missing library/$name" }
            .use { it.readBytes() }

    private val json = Json { ignoreUnknownKeys = true }

    private fun check(name: String) {
        val db = resource("$name.db")
        val golden = json.parseToJsonElement(resource("$name.json").decodeToString()).jsonObject
        val r = YxdbIndex.scan({ ByteArrayInputStream(db) }, db.size.toLong())

        val want = json.decodeFromJsonElement(DbStats.serializer(), golden.getValue("stats"))
        assertThat(r.stats).isEqualTo(want)
        // The sidecar text: same keys, same order of rules.
        assertThat(r.stats.rules.keys.toList()).isEqualTo(want.rules.keys.toList())
        assertThat(r.count).isEqualTo(golden.long("count"))
        assertThat(r.compressed).isEqualTo(golden.bool("compressed"))
        assertThat(r.sorted).isEqualTo(golden.bool("sorted"))
        assertThat(r.duplicates).isEqualTo(golden.long("duplicates"))
        assertThat(r.countOrdered).isEqualTo(golden.bool("countOrdered"))
        assertThat(r.special).isEqualTo(golden.long("special"))
        val skipped = golden["openingsSkipped"]?.jsonPrimitive?.contentOrNull
        assertThat(r.openingsUnordered).isEqualTo(skipped == "unordered")

        // Root keys dropped in pass 2 (past the cap) and read again at the end: the same roots.
        // The hash table is gone by then, so they are matched by record number — this used to
        // read the freed bloom filter and fail the whole count (web: silently empty keys).
        val d = YxdbIndex.scan({ ByteArrayInputStream(db) }, db.size.toLong(), rootKeyCap = 0)
        assertThat(d.stats).isEqualTo(want)
        val few = YxdbIndex.scan({ ByteArrayInputStream(db) }, db.size.toLong(), rootsTop = 3)
        val fewDropped = YxdbIndex.scan({ ByteArrayInputStream(db) }, db.size.toLong(), rootsTop = 3, rootKeyCap = 0)
        assertThat(fewDropped.stats.roots).isEqualTo(few.stats.roots)
        assertThat(fewDropped.stats.roots!!.all { it.key.isNotEmpty() }).isTrue()

        // Stats alone (L5-1 fast path: shared slots unread) — the same numbers without openings · roots.
        val s = YxdbIndex.scan({ ByteArrayInputStream(db) }, db.size.toLong(), index = false)
        assertThat(s.stats).isEqualTo(want.copy(openings = null, roots = null))
        assertThat(s.sorted).isEqualTo(r.sorted)
        assertThat(s.duplicates).isEqualTo(r.duplicates)
        assertThat(s.countOrdered).isEqualTo(r.countOrdered)
        assertThat(s.special).isEqualTo(r.special)
    }

    private fun JsonObject.long(k: String) = getValue(k).jsonPrimitive.long
    private fun JsonObject.bool(k: String) = getValue(k).jsonPrimitive.boolean

    @Test fun mixedRulesAndOpenings() = check("mixed")

    @Test fun unorderedFileSkipsOpenings() = check("unordered")

    @Test fun orphansBecomeRootsAndUnclassified() = check("orphans")

    /**
     * Index version 3 (L5-6): a symmetric non-standard parent casts no vote (the
     * Phone Copy collapse), a mixed merge spreads to its children, and the
     * empty-board root carries its start.
     */
    @Test fun nonStandardParentsDoNotCollapseOpenings() = check("collapse")

    @Test
    fun threeStoneClassificationMatchesTheWeb() {
        val chars = "0123456789abcdefghijklmnopq"
        val lines = resource("classify3.txt").decodeToString().trimEnd().lines()
        assertThat(lines).hasSize(225)
        var mismatches = 0
        for (c in 0 until 225) for (d in 0 until 225) {
            val want = lines[c][d]
            if (want == '.') continue
            val got = Opening26.classify(listOf(Move(7, 7), Move(c % 15, c / 15), Move(d % 15, d / 15)))
            if (chars[got] != want) mismatches++
        }
        assertThat(mismatches).isEqualTo(0)
    }
}
