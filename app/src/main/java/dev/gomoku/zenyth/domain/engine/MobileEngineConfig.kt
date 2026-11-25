package dev.gomoku.zenyth.domain.engine

import dev.gomoku.zenyth.core.i18n.tr
import dev.gomoku.zenyth.core.model.LocalEngineProfile

/**
 * Turns the shipped `config.toml` — a **verbatim copy of the server's** — into
 * the one the on-device engine reads.
 *
 * Shipping the server's file and patching two lines (rather than writing a fresh
 * config) is deliberate: every search parameter, database rule and candidate
 * range stays identical, so a position analysed on the phone is analysed the way
 * the PC would. Only the two numbers that would kill a phone are rewritten.
 *
 * Why they would kill it: `default_tt_size_kb` is applied **when the config is
 * committed**, i.e. before the app has said a word — the shipped value is
 * 8388608 KiB (8 GiB). And `default_thread_num = 0` means "every core", which on
 * a phone means the little cores too.
 *
 * Pure on purpose: this is the one piece of the local-engine path that can be
 * tested without a device, and it is the piece that has to be right.
 */
object MobileEngineConfig {

    /** `default_tt_size_kb` — the transposition table, sized at config load. */
    private const val TT_KEY = "default_tt_size_kb"

    /** `default_thread_num` — 0 would mean every core including the little ones. */
    private const val THREADS_KEY = "default_thread_num"

    /** `[database] url` — the file `INFO usedatabase 1` loads. */
    private const val URL_KEY = "url"

    /**
     * @param databaseUrl the library entry to attach at boot (L5-4) — an
     *   absolute path under `filesDir`, ASCII by construction (`<uuid>.db`, a
     *   staging copy). Null keeps the template's own `rapfi.db`.
     */
    fun render(template: String, profile: LocalEngineProfile, databaseUrl: String? = null): String {
        var out = replaceScalar(template, TT_KEY, profile.ttSizeKb.toString())
        out = replaceScalar(out, THREADS_KEY, profile.threads.toString())
        if (databaseUrl != null) out = replaceDatabaseUrl(out, databaseUrl)
        requireCoordModeNone(out)
        return out
    }

    /**
     * Only the `url` inside `[database]` — another table may grow a key of the
     * same name. Written as a TOML basic string.
     */
    private fun replaceDatabaseUrl(text: String, url: String): String {
        require(url.none { it < ' ' }) { "database url has a control character" }
        val start = Regex("""^[ \t]*\[database][ \t]*$""", RegexOption.MULTILINE).find(text)
        require(start != null) {
            tr("config.toml 템플릿에 [database] 표가 없습니다", "config.toml template has no [database] table", "config.toml 模板中没有 [database] 表")
        }
        val bodyStart = start.range.last + 1
        val next = Regex("""^[ \t]*\[""", RegexOption.MULTILINE).find(text, bodyStart)?.range?.first ?: text.length
        val body = text.substring(bodyStart, next)
        val quoted = "\"" + url.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        return text.substring(0, bodyStart) + replaceScalar(body, URL_KEY, quoted) + text.substring(next)
    }

    /**
     * Replace `key = <anything>` on its own line, keeping the rest of the file —
     * comments included — byte for byte.
     *
     * Fails loudly when the key is absent. A silently unpatched template is the
     * bad case: the engine would start, allocate 8 GiB and be killed, and the
     * only symptom the user sees is "the local engine won't connect".
     */
    private fun replaceScalar(text: String, key: String, value: String): String {
        val line = Regex("^[ \\t]*$key[ \\t]*=.*$", RegexOption.MULTILINE)
        require(line.containsMatchIn(text)) {
            tr("config.toml 템플릿에 `$key` 항목이 없습니다 — 서버 config 를 새로 복사했다면 이 코드를 함께 고칠 것", "config.toml template has no `$key` entry — if the server config was copied anew, fix this code with it", "config.toml 模板中没有 `$key` 项，如果重新复制了服务器配置，请一并修改这段代码")
        }
        return line.replace(text) { "$key = $value" }
    }

    /**
     * The app reads engine coordinates as `y,x` with no flip, which is only true
     * while the engine converts nothing. If a future copy of the server config
     * ever carries `flipY_X`, every move and every PV would land transposed on
     * the board — so refuse to write that config at all.
     */
    private fun requireCoordModeNone(text: String) {
        val ok = Regex("^[ \\t]*coord_conversion_mode[ \\t]*=[ \\t]*\"none\"", RegexOption.MULTILINE)
        require(ok.containsMatchIn(text)) {
            tr("config.toml 의 coord_conversion_mode 가 \"none\" 이 아닙니다 — 앱의 y,x 좌표 해석이 깨집니다", "coord_conversion_mode in config.toml is not \"none\" — the app's y,x coordinates would break", "config.toml 中的 coord_conversion_mode 不是 \"none\"，应用的 y,x 坐标解析会出错")
        }
    }
}
