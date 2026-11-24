package dev.gomoku.zenyth.domain.library

import java.text.Normalizer

/**
 * File-name rules shared with Zenyth-web (`source.ts`, `fileName.ts`) and the
 * desktop (`library.rs`) — a folder must read the same in all three apps.
 */
object LibraryFileNames {
    private const val EXT = ".db"
    private const val MAX_BYTES = 255
    private val RESERVED = Regex("^(con|prn|aux|nul|com[1-9]|lpt[1-9])(\\..*)?$", RegexOption.IGNORE_CASE)
    private val FORBIDDEN = Regex("[<>:\"/\\\\|?*\\u0000-\\u001f]")
    private val TRAILING_DOTS = Regex("[. ]+$")
    private val COMPANION = Regex("^\\.(journal|bak\\d*|backup\\d*|\\d+)$", RegexOption.IGNORE_CASE)
    private val BACKUP_TAIL = Regex("^_bak\\d+\\.db$", RegexOption.IGNORE_CASE)
    private val BACKUP_NAME = Regex("^(.*)_bak\\d+\\.db$", RegexOption.IGNORE_CASE)
    private val DB = Regex("\\.db$", RegexOption.IGNORE_CASE)

    /** Suffixes of in-flight files — never items, never listed. */
    const val TMP = ".zenyth-tmp"
    const val OLD = ".zenyth-old"
    const val RENAME = ".zenyth-rename"

    fun nfc(s: String): String = Normalizer.normalize(s, Normalizer.Form.NFC)

    /** Name key for matching: NFC + lowercase (web `normalize("NFC").toLowerCase()`). */
    fun key(name: String): String = nfc(name).lowercase()

    /** Can this folder file be an item — `.db`, not hidden, not a lock/temp file. */
    fun isDbFileName(name: String): Boolean =
        DB.containsMatchIn(name) && !name.startsWith(".") && !name.startsWith("~")

    /** In-flight names of an atomic replace or a case-only rename. */
    fun isInFlight(name: String): Boolean = name.endsWith(TMP) || name.endsWith(OLD) || name.endsWith(RENAME)

    /**
     * The engine's files beside [base] — journal (DB6), save temp (DB1) and
     * numbered backups. Returns the tail (`.journal`, `_bak1.db`) or null.
     */
    fun companionSuffix(base: String, name: String): String? {
        if (name.length > base.length && name.substring(0, base.length).lowercase() == base.lowercase()) {
            val rest = name.substring(base.length)
            if (COMPANION.matches(rest)) return rest
        }
        return backupTail(base, name)
    }

    /** DB1 `makeBackupFilePath`: `<stem>_bak<N><ext>` — `연구.db` → `연구_bak1.db`. */
    private fun backupTail(base: String, name: String): String? {
        if (!DB.containsMatchIn(base)) return null
        val stem = base.substring(0, base.length - 3)
        if (name.length <= stem.length || name.substring(0, stem.length).lowercase() != stem.lowercase()) return null
        val rest = name.substring(stem.length)
        return if (BACKUP_TAIL.matches(rest)) rest else null
    }

    /** Puts a companion tail on a main name — `.journal` after it, `_bak1.db` on its stem. */
    fun withTail(base: String, tail: String): String =
        if (tail.startsWith("_")) DB.replace(base, "") + tail else base + tail

    /**
     * Is this `.db` an engine backup — only when its main file (`<stem>.db`)
     * is in the same folder. [keys] = folder names through [key].
     */
    fun isEngineBackup(name: String, keys: Set<String>): Boolean {
        val m = BACKUP_NAME.find(name) ?: return false
        return key(m.groupValues[1] + ".db") in keys
    }

    /**
     * Title → folder file name (web `fileNameFor`): forbidden and control
     * characters → `_`, no trailing dots · spaces, reserved Windows names get
     * `_`, NFC, at most 255 UTF-8 bytes without splitting a character, `.db`,
     * and ` (2)`, ` (3)` … past [taken] (case-insensitive).
     */
    fun fileNameFor(title: String, taken: Iterable<String> = emptyList()): String {
        val used = taken.mapTo(HashSet()) { key(it) }
        val stem = stemOf(title)
        var i = 1
        while (true) {
            val suffix = if (i == 1) "" else " ($i)"
            val room = MAX_BYTES - utf8Len(suffix + EXT)
            val base = TRAILING_DOTS.replace(clipBytes(stem, room), "").ifEmpty { "db" }
            val name = base + suffix + EXT
            if (name.lowercase() !in used) return name
            i++
        }
    }

    private fun stemOf(title: String): String {
        var s = nfc(title)
        if (s.lowercase().endsWith(EXT)) s = s.substring(0, s.length - EXT.length)
        s = FORBIDDEN.replace(s, "_")
        s = jsTrim(TRAILING_DOTS.replace(s, ""))
        if (s.isEmpty()) s = "db"
        if (RESERVED.matches(s)) s += "_"
        return s
    }

    /** UTF-8 length as `TextEncoder` counts it (a lone surrogate is U+FFFD, 3 bytes). */
    private fun cpLen(cp: Int): Int = when {
        cp < 0x80 -> 1
        cp < 0x800 -> 2
        cp < 0x10000 -> 3
        else -> 4
    }

    private fun utf8Len(s: String): Int {
        var n = 0
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            n += cpLen(cp)
            i += Character.charCount(cp)
        }
        return n
    }

    private fun clipBytes(s: String, max: Int): String {
        var n = 0
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            val len = cpLen(cp)
            if (n + len > max) break
            n += len
            i += Character.charCount(cp)
        }
        return s.substring(0, i)
    }

    /** JS `String.prototype.trim` — its white space set, not Java's. */
    private fun jsTrim(s: String): String {
        var a = 0
        var b = s.length
        while (a < b && isJsSpace(s[a])) a++
        while (b > a && isJsSpace(s[b - 1])) b--
        return s.substring(a, b)
    }

    private fun isJsSpace(c: Char): Boolean = when (c) {
        '\t', '\n', '\u000b', '\u000c', '\r', ' ', ' ', ' ', ' ', ' ',
        ' ', ' ', '　', '﻿' -> true
        else -> c in ' '..' '
    }
}
