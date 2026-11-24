package dev.gomoku.zenyth.domain.library

import dev.gomoku.zenyth.domain.library.yxdb.DbStats
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import java.util.UUID
import kotlin.random.Random

/**
 * One library item — the same fields as Zenyth-web `data/library.ts`
 * `LibraryEntry`, stored in the source's `zenyth-library.json` sidecar
 * (library L5 design §4). A folder written by the web · desktop app reads back
 * here unchanged and the other way round.
 *
 * Fields this app does not know are never dropped: [raw] keeps the object as
 * read, and [ManifestCodec.render] writes the known fields over it in place
 * (a later web version may add fields; an Android round trip leaves them).
 */
data class LibraryEntry(
    /** UUID v4 — stays when the file is renamed. */
    val id: String,
    /** Name relative to the source. App storage uses `<uuid>.db`. */
    val file: String,
    val title: String,
    val cover: LibraryCover,
    /** Identity of the main file; absent while a new DB has no file yet. */
    val fingerprint: Fingerprint? = null,
    /** Stats counted from [fingerprint] — as JSON, so fields this app does not know survive. */
    val statsJson: JsonObject? = null,
    val createdAt: Long,
    val updatedAt: Long,
    val lastOpenedAt: Long? = null,
    val trashedAt: Long? = null,
    /** Name inside the source's trash (`<ms>-<file>`). */
    val trashFile: String? = null,
    /** When the file disappeared from the folder (renamed or deleted elsewhere). */
    val missingSince: Long? = null,
    /** File name still to apply — the provider refused, the title already changed. */
    val fileRenamePending: String? = null,
    /** The object as read (null for entries made here). */
    val raw: JsonObject? = null,
) {
    /** Typed stats, or null when absent or of a shape this app cannot read. */
    val stats: DbStats?
        get() = statsJson?.let { runCatching { ManifestCodec.json.decodeFromJsonElement(DbStats.serializer(), it) }.getOrNull() }

    fun withStats(s: DbStats?): LibraryEntry =
        copy(statsJson = s?.let { ManifestCodec.json.encodeToJsonElement(DbStats.serializer(), it) as JsonObject })

    /** Shown in the list — not in the trash and not lost. */
    val live: Boolean get() = trashedAt == null && missingSince == null
}

data class LibraryCover(
    /** `auto` · `eclipse` · `opening`. */
    val mode: String,
    /** 26-opening number (0..25) when [mode] is `opening`. */
    val opening: Int? = null,
    val seed: Int,
    val raw: JsonObject? = null,
) {
    companion object {
        const val AUTO = "auto"
        const val ECLIPSE = "eclipse"
        const val OPENING = "opening"
    }
}

/** The sidecar — `{ "version": 1, "app": "zenyth", "entries": [...] }`. */
data class LibraryManifest(
    val entries: List<LibraryEntry> = emptyList(),
    /** The top-level object as read (unknown fields survive). */
    val raw: JsonObject? = null,
)

object LibraryEntries {
    const val MANIFEST_FILE = "zenyth-library.json"
    const val TRASH_DIR = ".zenyth-trash"
    const val TRASH_DAYS = 30
    const val MISSING_DAYS = 30
    const val DAY_MS = 86_400_000L

    fun newId(): String = UUID.randomUUID().toString()

    fun randomSeed(): Int = Random.nextInt(0x7fffffff)

    /**
     * A new entry (web `newEntry`). Cover `eclipse` for an empty new DB,
     * `auto` for an imported or newly found file (2026-11-23 decision).
     */
    fun newEntry(title: String, file: String, mode: String = LibraryCover.ECLIPSE, now: Long = System.currentTimeMillis()) =
        LibraryEntry(
            id = newId(), file = file, title = title,
            cover = LibraryCover(mode = mode, seed = randomSeed()),
            createdAt = now, updatedAt = now,
        )
}

/**
 * Reads and writes the sidecar like web `parseManifest` · `renderManifest`:
 * entries missing a required field are dropped, broken JSON is an empty list
 * (the caller keeps a `.broken-<ms>` copy before overwriting), and the text is
 * `JSON.stringify(…, null, 1)` + newline with entries by `createdAt` then `id`.
 */
object ManifestCodec {
    /** `labels` always carries W · L · D · other as the web writes them; absent optionals stay absent. */
    internal val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

    class Parsed(val manifest: LibraryManifest, /** The text was there but not a sidecar. */ val broken: Boolean)

    fun parse(text: String?): Parsed {
        if (text.isNullOrEmpty()) return Parsed(LibraryManifest(), false)
        val root = try {
            json.parseToJsonElement(text)
        } catch (_: Exception) {
            return Parsed(LibraryManifest(), true)
        }
        val obj = root as? JsonObject ?: return Parsed(LibraryManifest(), true)
        val list = obj["entries"] as? JsonArray ?: return Parsed(LibraryManifest(raw = obj), true)
        return Parsed(LibraryManifest(list.mapNotNull { entryOf(it) }, obj), false)
    }

    fun render(m: LibraryManifest): String {
        val entries = m.entries.sortedWith { a, b ->
            when {
                a.createdAt != b.createdAt -> a.createdAt.compareTo(b.createdAt)
                else -> a.id.compareTo(b.id)
            }
        }
        val top = merge(
            m.raw,
            listOf(
                "version" to JsonPrimitive(1),
                "app" to JsonPrimitive("zenyth"),
                "entries" to JsonArray(entries.map { objectOf(it) }),
            ),
        )
        return JsStringify.write(top) + "\n"
    }

    // ── entries ────────────────────────────────────────────────────────

    private fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonElement?.num(): Long? {
        val p = this as? JsonPrimitive ?: return null
        if (p.isString) return null
        return p.longOrNull ?: p.doubleOrNull?.takeIf { !it.isNaN() }?.toLong()
    }

    private fun entryOf(e: JsonElement): LibraryEntry? {
        val o = e as? JsonObject ?: return null
        val id = o["id"].str() ?: return null
        val file = o["file"].str() ?: return null
        val title = o["title"].str() ?: return null
        val coverObj = o["cover"] as? JsonObject ?: return null
        val createdAt = o["createdAt"].num() ?: return null
        val updatedAt = o["updatedAt"].num() ?: return null
        val cover = LibraryCover(
            mode = coverObj["mode"].str() ?: LibraryCover.AUTO,
            opening = coverObj["opening"].num()?.toInt(),
            seed = coverObj["seed"].num()?.toInt() ?: 0,
            raw = coverObj,
        )
        val fp = (o["fingerprint"] as? JsonObject)?.let { f ->
            val size = f["size"].num()
            val tail = f["tail"].str()
            if (size != null && tail != null) Fingerprint(size, f["mtime"].num() ?: 0L, tail) else null
        }
        return LibraryEntry(
            id = id, file = file, title = title, cover = cover, fingerprint = fp,
            statsJson = o["stats"] as? JsonObject,
            createdAt = createdAt, updatedAt = updatedAt,
            lastOpenedAt = o["lastOpenedAt"].num(),
            trashedAt = o["trashedAt"].num(),
            trashFile = o["trashFile"].str(),
            missingSince = o["missingSince"].num(),
            fileRenamePending = o["fileRenamePending"].str(),
            raw = o,
        )
    }

    private fun objectOf(e: LibraryEntry): JsonObject {
        val c = e.cover
        val cover = merge(
            c.raw,
            listOf(
                "mode" to JsonPrimitive(c.mode),
                "opening" to c.opening?.let { JsonPrimitive(it) },
                "seed" to JsonPrimitive(c.seed),
            ),
        )
        val fp = e.fingerprint?.let { f ->
            merge(
                e.raw?.get("fingerprint") as? JsonObject,
                listOf("size" to JsonPrimitive(f.size), "mtime" to JsonPrimitive(f.mtime), "tail" to JsonPrimitive(f.tail)),
            )
        }
        return merge(
            e.raw,
            listOf(
                "id" to JsonPrimitive(e.id),
                "file" to JsonPrimitive(e.file),
                "title" to JsonPrimitive(e.title),
                "cover" to cover,
                "fingerprint" to fp,
                "stats" to e.statsJson,
                "createdAt" to JsonPrimitive(e.createdAt),
                "updatedAt" to JsonPrimitive(e.updatedAt),
                "lastOpenedAt" to e.lastOpenedAt?.let { JsonPrimitive(it) },
                "trashedAt" to e.trashedAt?.let { JsonPrimitive(it) },
                "trashFile" to e.trashFile?.let { JsonPrimitive(it) },
                "missingSince" to e.missingSince?.let { JsonPrimitive(it) },
                "fileRenamePending" to e.fileRenamePending?.let { JsonPrimitive(it) },
            ),
        )
    }

    /**
     * [known] written over [raw] in its own key order: a known field keeps its
     * place, a null known field is removed, unknown fields stay, new known
     * fields go at the end in [known] order. A known value equal to the raw one
     * keeps the raw element (its number spelling survives).
     */
    private fun merge(raw: JsonObject?, known: List<Pair<String, JsonElement?>>): JsonObject {
        val values = known.toMap()
        val out = LinkedHashMap<String, JsonElement>()
        raw?.forEach { (k, v) ->
            if (k in values) {
                val nv = values[k] ?: return@forEach
                out[k] = if (sameValue(v, nv)) v else nv
            } else {
                out[k] = v
            }
        }
        for ((k, v) in known) if (v != null && k !in out) out[k] = v
        return JsonObject(out)
    }

    private fun sameValue(a: JsonElement, b: JsonElement): Boolean {
        if (a is JsonPrimitive && b is JsonPrimitive && !a.isString && !b.isString) {
            val x = a.doubleOrNull
            val y = b.doubleOrNull
            return x != null && x == y
        }
        return a == b
    }
}

/** `JSON.stringify(value, null, 1)` — byte for byte, so a web sidecar round-trips unchanged. */
internal object JsStringify {
    fun write(e: JsonElement): String = StringBuilder().also { write(it, e, 0) }.toString()

    private fun write(sb: StringBuilder, e: JsonElement, depth: Int) {
        when (e) {
            is JsonNull -> sb.append("null")
            is JsonPrimitive -> if (e.isString) quote(sb, e.content) else sb.append(e.content)
            is JsonArray -> {
                if (e.isEmpty()) {
                    sb.append("[]"); return
                }
                sb.append('[')
                e.forEachIndexed { i, v ->
                    if (i > 0) sb.append(',')
                    newline(sb, depth + 1)
                    write(sb, v, depth + 1)
                }
                newline(sb, depth)
                sb.append(']')
            }
            is JsonObject -> {
                if (e.isEmpty()) {
                    sb.append("{}"); return
                }
                sb.append('{')
                var first = true
                for ((k, v) in e) {
                    if (!first) sb.append(',')
                    first = false
                    newline(sb, depth + 1)
                    quote(sb, k)
                    sb.append(": ")
                    write(sb, v, depth + 1)
                }
                newline(sb, depth)
                sb.append('}')
            }
        }
    }

    private fun newline(sb: StringBuilder, depth: Int) {
        sb.append('\n')
        repeat(depth) { sb.append(' ') }
    }

    private fun quote(sb: StringBuilder, s: String) {
        sb.append('"')
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c == '\b' -> sb.append("\\b")
                c == '\u000c' -> sb.append("\\f")
                c == '\n' -> sb.append("\\n")
                c == '\r' -> sb.append("\\r")
                c == '\t' -> sb.append("\\t")
                c < ' ' -> hex(sb, c)
                Character.isHighSurrogate(c) && i + 1 < s.length && Character.isLowSurrogate(s[i + 1]) -> {
                    sb.append(c).append(s[i + 1]); i++
                }
                Character.isSurrogate(c) -> hex(sb, c)   // lone surrogate (well-formed stringify)
                else -> sb.append(c)
            }
            i++
        }
        sb.append('"')
    }

    private fun hex(sb: StringBuilder, c: Char) {
        sb.append("\\u").append(Integer.toHexString(c.code).padStart(4, '0'))
    }
}
