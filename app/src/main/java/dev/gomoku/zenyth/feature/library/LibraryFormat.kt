package dev.gomoku.zenyth.feature.library

import dev.gomoku.zenyth.core.i18n.AppLanguage
import dev.gomoku.zenyth.core.i18n.appLanguage
import dev.gomoku.zenyth.core.i18n.tr
import dev.gomoku.zenyth.domain.library.yxdb.DbStats
import java.text.DateFormat
import java.text.NumberFormat
import java.util.Date
import java.util.Locale
import kotlin.math.floor

/** The library's numbers and labels (web `features/library/format.ts`). */
object LibraryFormat {

    /** Position counts — Korean and Chinese «81만» / «81万», English «810K». */
    fun count(n: Long): String = when (appLanguage()) {
        AppLanguage.KOREAN -> when {
            n >= 100_000_000 -> "${trim(n / 1e8)}억"
            n >= 10_000 -> "${trim(n / 1e4)}만"
            else -> NumberFormat.getIntegerInstance(Locale.KOREA).format(n)
        }
        AppLanguage.CHINESE -> when {
            n >= 100_000_000 -> "${trim(n / 1e8)}亿"
            n >= 10_000 -> "${trim(n / 1e4)}万"
            else -> NumberFormat.getIntegerInstance(Locale.CHINA).format(n)
        }
        else -> when {
            n >= 1_000_000 -> "${trim(n / 1e6)}M"
            n >= 10_000 -> "${trim(n / 1e3)}K"
            else -> NumberFormat.getIntegerInstance(Locale.US).format(n)
        }
    }

    /** One decimal under 10, whole numbers above, never rounded up. */
    private fun trim(v: Double): String =
        if (v < 10) (floor(v * 10) / 10).let { if (it == floor(it)) it.toLong().toString() else it.toString() }
        else floor(v).toLong().toString()

    fun bytes(n: Long): String = when {
        n >= 1024L * 1024 * 1024 -> String.format(Locale.US, "%.1fGB", n / 1024.0 / 1024 / 1024)
        n >= 1024L * 1024 -> if (n >= 100L * 1024 * 1024) String.format(Locale.US, "%.0fMB", n / 1024.0 / 1024) else String.format(Locale.US, "%.1fMB", n / 1024.0 / 1024)
        else -> "${maxOf(1L, Math.round(n / 1024.0))}KB"
    }

    /** «12만 국면». */
    fun positions(n: Long): String = tr("${count(n)} 국면", "${count(n)} positions", "${count(n)} 局面")

    /** A stats key (`renju-15`, `freestyle-20` …) → «렌주 15». */
    fun rule(key: String): String {
        val m = Regex("^([a-z]+\\d*)-(\\d+(?:x\\d+)?)$").find(key) ?: return key
        val name = when (m.groupValues[1]) {
            "renju" -> tr("렌주", "Renju", "连珠")
            "standard" -> tr("표준", "Standard", "标准")
            "freestyle" -> tr("자유", "Freestyle", "自由")
            else -> m.groupValues[1]
        }
        return "$name ${m.groupValues[2]}"
    }

    /** The most frequent rule · board («외 n» when mixed). */
    fun mainRule(rules: Map<String, Long>?): String? {
        if (rules.isNullOrEmpty()) return null
        val first = rule(rules.entries.maxBy { it.value }.key)
        val others = rules.size - 1
        return if (others > 0) tr("$first 외 $others", "$first +$others", "$first 等 ${others + 1} 种") else first
    }

    fun date(ms: Long): String = DateFormat.getDateInstance(DateFormat.MEDIUM, localeOf()).format(Date(ms))

    private fun localeOf(): Locale = when (appLanguage()) {
        AppLanguage.KOREAN -> Locale.KOREA
        AppLanguage.CHINESE -> Locale.CHINA
        else -> Locale.US
    }

    /** A card's second line — positions and rule once counted, else the size, else «비어 있음». */
    fun subtitle(stats: DbStats?, size: Long): String {
        if (stats != null) return listOfNotNull(positions(stats.records), mainRule(stats.rules)).joinToString(" · ")
        return if (size > 0) bytes(size) else tr("비어 있음", "Empty", "空")
    }
}
