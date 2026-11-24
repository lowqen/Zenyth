package dev.gomoku.zenyth.domain.library

import com.google.common.truth.Truth.assertThat
import dev.gomoku.zenyth.domain.library.yxdb.DbLabels
import dev.gomoku.zenyth.domain.library.yxdb.DbStats
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

/**
 * The sidecar codec (library L5 design §4 · §8): `web-sidecar.json` was
 * written by `JSON.stringify(…, null, 1)` (node) with fields this app does not
 * know at every level. Reading and writing it back must give the same bytes,
 * and changing one field must change only that field.
 */
class ManifestCodecTest {

    private val web = javaClass.getResourceAsStream("/library/web-sidecar.json")!!.readBytes().decodeToString()

    @Test
    fun `a web sidecar round-trips byte for byte, unknown fields included`() {
        val p = ManifestCodec.parse(web)
        assertThat(p.broken).isFalse()
        assertThat(p.manifest.entries).hasSize(3)
        assertThat(ManifestCodec.render(p.manifest)).isEqualTo(web)
    }

    @Test
    fun `known fields are read`() {
        val e = ManifestCodec.parse(web).manifest.entries
        with(e[0]) {
            assertThat(file).isEqualTo("화월 연구.db")
            assertThat(title).isEqualTo("화월 \"연구\"\n둘째 줄 😀")
            assertThat(cover).isEqualTo(cover.copy(mode = "opening", opening = 3, seed = 17))
            assertThat(fingerprint).isEqualTo(Fingerprint(1234567, 1800000000000, "0123456789abcdef"))
            assertThat(stats!!.records).isEqualTo(485)
            assertThat(stats!!.labels).isEqualTo(DbLabels(3, 2, 1, 479))
            assertThat(stats!!.indexVersion).isEqualTo(2)
            assertThat(lastOpenedAt).isEqualTo(1800000000002)
        }
        with(e[2]) {
            assertThat(missingSince).isEqualTo(1760000000000)
            assertThat(trashedAt).isEqualTo(1770000000000)
            assertThat(trashFile).isEqualTo("1770000000000-gone.db")
            assertThat(fileRenamePending).isEqualTo("가 나.db")
            assertThat(cover.seed).isEqualTo(2147483646)
        }
    }

    @Test
    fun `changing a known field keeps everything else in place`() {
        val m = ManifestCodec.parse(web).manifest
        val changed = m.copy(entries = m.entries.mapIndexed { i, e -> if (i == 0) e.copy(title = "새 이름", lastOpenedAt = null) else e })
        val out = Json.parseToJsonElement(ManifestCodec.render(changed)).jsonObject
        val before = Json.parseToJsonElement(web).jsonObject
        val e0 = (out["entries"] as kotlinx.serialization.json.JsonArray)[0].jsonObject
        val b0 = (before["entries"] as kotlinx.serialization.json.JsonArray)[0].jsonObject
        assertThat(e0["title"]!!.jsonPrimitive.content).isEqualTo("새 이름")
        assertThat(e0.containsKey("lastOpenedAt")).isFalse()
        assertThat(e0.keys.toList()).isEqualTo(b0.keys.filter { it != "lastOpenedAt" })
        for (k in listOf("pinned", "tags", "cover", "stats", "fingerprint")) assertThat(e0[k]).isEqualTo(b0[k])
        assertThat(out["future"]).isEqualTo(before["future"])
    }

    @Test
    fun `new stats replace the old ones whole, with labels always spelled out`() {
        val m = ManifestCodec.parse(web).manifest
        val s = DbStats(records = 9, rules = mapOf("renju-15" to 9L), labels = DbLabels(), commented = 0, indexVersion = 2)
        val e = m.entries[0].withStats(s)
        val obj = e.statsJson!!
        assertThat(obj.containsKey("futureStat")).isFalse()
        assertThat(obj["labels"]!!.jsonObject.keys).containsExactly("W", "L", "D", "other").inOrder()
        assertThat(obj.containsKey("openings")).isFalse()
        assertThat(obj.containsKey("roots")).isFalse()
        assertThat(e.stats).isEqualTo(s)
    }

    @Test
    fun `entries missing a required field are dropped, like parseManifest`() {
        val text = """{"version":1,"entries":[
            {"id":"a","file":"a.db","title":"A","cover":{"mode":"auto","seed":1},"createdAt":1,"updatedAt":1},
            {"id":"b","file":"b.db","cover":{"mode":"auto","seed":1},"createdAt":1,"updatedAt":1},
            {"id":"c","file":"c.db","title":"C","cover":null,"createdAt":1,"updatedAt":1},
            {"id":"d","file":"d.db","title":"D","cover":{},"createdAt":"1","updatedAt":1},
            {"id":5,"file":"e.db","title":"E","cover":{},"createdAt":1,"updatedAt":1},
            "junk"
        ]}"""
        val p = ManifestCodec.parse(text)
        assertThat(p.broken).isFalse()
        assertThat(p.manifest.entries.map { it.id }).containsExactly("a")
    }

    @Test
    fun `broken text is an empty list and says so`() {
        assertThat(ManifestCodec.parse("{ not json").broken).isTrue()
        assertThat(ManifestCodec.parse("[1,2]").broken).isTrue()
        assertThat(ManifestCodec.parse("{\"entries\":3}").broken).isTrue()
        assertThat(ManifestCodec.parse("{ not json").manifest.entries).isEmpty()
        assertThat(ManifestCodec.parse(null).broken).isFalse()
        assertThat(ManifestCodec.parse("").broken).isFalse()
    }

    @Test
    fun `entries are written by createdAt then id, one-space indent and a final newline`() {
        val a = LibraryEntries.newEntry("A", "a.db", now = 5).copy(id = "b")
        val b = LibraryEntries.newEntry("B", "b.db", now = 5).copy(id = "a")
        val c = LibraryEntries.newEntry("C", "c.db", now = 1).copy(id = "z")
        val text = ManifestCodec.render(LibraryManifest(listOf(a, b, c)))
        assertThat(text).startsWith("{\n \"version\": 1,\n \"app\": \"zenyth\",\n \"entries\": [\n  {\n   \"id\": \"z\",")
        assertThat(text).endsWith("}\n")
        val ids = (Json.parseToJsonElement(text).jsonObject["entries"] as kotlinx.serialization.json.JsonArray)
            .map { (it as JsonObject)["id"]!!.jsonPrimitive.content }
        assertThat(ids).containsExactly("z", "a", "b").inOrder()
        assertThat(ManifestCodec.render(LibraryManifest())).isEqualTo("{\n \"version\": 1,\n \"app\": \"zenyth\",\n \"entries\": []\n}\n")
    }
}
