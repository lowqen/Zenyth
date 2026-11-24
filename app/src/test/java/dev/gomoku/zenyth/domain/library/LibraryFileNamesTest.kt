package dev.gomoku.zenyth.domain.library

import com.google.common.truth.Truth.assertThat
import dev.gomoku.zenyth.domain.library.LibraryFileNames.companionSuffix
import dev.gomoku.zenyth.domain.library.LibraryFileNames.fileNameFor
import dev.gomoku.zenyth.domain.library.LibraryFileNames.isDbFileName
import dev.gomoku.zenyth.domain.library.LibraryFileNames.isEngineBackup
import dev.gomoku.zenyth.domain.library.LibraryFileNames.withTail
import org.junit.Test
import java.text.Normalizer

/** Zenyth-web `fileName.test.ts` + the companion rules of `source.ts` (library L5-2). */
class LibraryFileNamesTest {

    private fun utf8(s: String) = s.toByteArray(Charsets.UTF_8).size

    @Test
    fun `forbidden and control characters become underscores`() {
        assertThat(fileNameFor("a<b>c:d\"e/f\\g|h?i*j")).isEqualTo("a_b_c_d_e_f_g_h_i_j.db")
        assertThat(fileNameFor("tab\there")).isEqualTo("tab_here.db")
    }

    @Test
    fun `trailing dots and spaces go, one db extension`() {
        assertThat(fileNameFor("연구 . ")).isEqualTo("연구.db")
        assertThat(fileNameFor("화월.db")).isEqualTo("화월.db")
        assertThat(fileNameFor("화월.DB")).isEqualTo("화월.db")
    }

    @Test
    fun `reserved names get an underscore even with an extension`() {
        assertThat(fileNameFor("CON")).isEqualTo("CON_.db")
        assertThat(fileNameFor("com1")).isEqualTo("com1_.db")
        assertThat(fileNameFor("lpt9.txt")).isEqualTo("lpt9.txt_.db")
        assertThat(fileNameFor("CONSOLE")).isEqualTo("CONSOLE.db")
    }

    @Test
    fun `empty title is db`() {
        assertThat(fileNameFor("   ")).isEqualTo("db.db")
        assertThat(fileNameFor("...")).isEqualTo("db.db")
        // JS trim also takes the no-break space Java's trim keeps
        assertThat(fileNameFor(" ")).isEqualTo("db.db")
    }

    @Test
    fun `NFC like the web (macOS decomposed hangul)`() {
        val nfd = Normalizer.normalize("화월", Normalizer.Form.NFD)
        assertThat(fileNameFor(nfd)).isEqualTo("화월.db")
    }

    @Test
    fun `at most 255 UTF-8 bytes without splitting a character`() {
        val name = fileNameFor("가".repeat(200))
        assertThat(utf8(name)).isAtMost(255)
        assertThat(name).isEqualTo("가".repeat(84) + ".db")
        val emoji = fileNameFor("😀".repeat(100))
        assertThat(utf8(emoji)).isAtMost(255)
        assertThat(emoji).doesNotContain("�")
        assertThat(emoji.dropLast(3).codePoints().allMatch { it == 0x1F600 }).isTrue()
    }

    @Test
    fun `taken names get (2), (3) — case-insensitive`() {
        assertThat(fileNameFor("연구", listOf("연구.db"))).isEqualTo("연구 (2).db")
        assertThat(fileNameFor("연구", listOf("연구.db", "연구 (2).db"))).isEqualTo("연구 (3).db")
        assertThat(fileNameFor("Study", listOf("study.DB"))).isEqualTo("Study (2).db")
    }

    @Test
    fun `db file names — not hidden, not lock files`() {
        assertThat(isDbFileName("a.db")).isTrue()
        assertThat(isDbFileName("A.DB")).isTrue()
        assertThat(isDbFileName(".a.db")).isFalse()
        assertThat(isDbFileName("~a.db")).isFalse()
        assertThat(isDbFileName("a.db.journal")).isFalse()
        assertThat(isDbFileName("a.db.zenyth-tmp")).isFalse()
        assertThat(isDbFileName("a.db.zenyth-old")).isFalse()
    }

    @Test
    fun `companions — journal, DB1 temp, numbered backups on the stem`() {
        assertThat(companionSuffix("연구.db", "연구.db.journal")).isEqualTo(".journal")
        assertThat(companionSuffix("연구.db", "연구.DB.JOURNAL")).isEqualTo(".JOURNAL")
        assertThat(companionSuffix("연구.db", "연구.db.bak")).isEqualTo(".bak")
        assertThat(companionSuffix("연구.db", "연구.db.backup2")).isEqualTo(".backup2")
        assertThat(companionSuffix("연구.db", "연구.db.123")).isEqualTo(".123")
        assertThat(companionSuffix("연구.db", "연구_bak1.db")).isEqualTo("_bak1.db")
        assertThat(companionSuffix("연구.db", "연구_BAK12.DB")).isEqualTo("_BAK12.DB")
        assertThat(companionSuffix("연구.db", "연구.db")).isNull()
        assertThat(companionSuffix("연구.db", "연구2.db")).isNull()
        assertThat(companionSuffix("연구.db", "연구.db.zenyth-tmp")).isNull()
        assertThat(companionSuffix("연구.db", "연구_bak.db")).isNull()
        assertThat(companionSuffix("1700-연구.db", "1700-연구_bak1.db")).isEqualTo("_bak1.db")
    }

    @Test
    fun `withTail puts backups on the stem and the rest after`() {
        assertThat(withTail("b.db", ".journal")).isEqualTo("b.db.journal")
        assertThat(withTail("b.db", "_bak1.db")).isEqualTo("b_bak1.db")
        assertThat(withTail("1700-b.db", "_bak2.db")).isEqualTo("1700-b_bak2.db")
    }

    @Test
    fun `an engine backup is a companion only beside its main file`() {
        val keys = setOf("연구.db", "연구_bak1.db", "외톨이_bak1.db").map(LibraryFileNames::key).toSet()
        assertThat(isEngineBackup("연구_bak1.db", keys)).isTrue()
        assertThat(isEngineBackup("연구_BAK1.DB", keys)).isTrue()
        assertThat(isEngineBackup("외톨이_bak1.db", keys)).isFalse()
        assertThat(isEngineBackup("연구.db", keys)).isFalse()
    }
}
