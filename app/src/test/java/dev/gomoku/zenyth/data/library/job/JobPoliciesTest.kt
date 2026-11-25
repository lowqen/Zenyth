package dev.gomoku.zenyth.data.library.job

import com.google.common.truth.Truth.assertThat
import dev.gomoku.zenyth.core.model.ThermalState
import dev.gomoku.zenyth.domain.library.yxdb.YxdbIndex
import org.junit.Test

/** The memory gate and when background work gives way (L5 design §2.2 · §2.3). */
class JobPoliciesTest {

    private val mb = 1L shl 20
    private val gb = 1L shl 30

    @Test
    fun theSmallestOfTheThreeLimitsWins() {
        // Galaxy A16 (L5-0): 5.8 GB, heap 256 MB, plenty free → the heap rule (153 MB).
        val a16 = MemoryGate.budget((5.8 * gb).toLong(), 256 * mb, 3 * gb, 200 * mb)
        assertThat(a16).isEqualTo((256 * mb * 0.6).toLong())
        // 2 GB phone: the web's deviceMemory/16 rule.
        assertThat(MemoryGate.budget(2 * gb, 256 * mb, gb, 100 * mb)).isEqualTo(128 * mb)
        // Little free (the engine holds its table): half of what is above the threshold.
        assertThat(MemoryGate.budget(8 * gb, 512 * mb, 300 * mb, 200 * mb)).isEqualTo(50 * mb)
        // Below the threshold: no index at all.
        assertThat(MemoryGate.budget(8 * gb, 512 * mb, 100 * mb, 200 * mb)).isEqualTo(0)
    }

    @Test
    fun theL50SampleFitsOnTheA16() {
        // 79 MB sample: 8,171,740 records → about 140 MB, under 153 MB (L5-0 table).
        val budget = MemoryGate.budget((5.8 * gb).toLong(), 256 * mb, 3 * gb, 200 * mb)
        assertThat(MemoryGate.fits(8_171_740, budget)).isTrue()
        assertThat(MemoryGate.fits(8_171_740, 100 * mb)).isFalse()
        assertThat(YxdbIndex.bytesEstimate(8_171_740)).isLessThan(budget)
    }

    @Test
    fun backgroundWorkYieldsToSearchesAndHeat() {
        assertThat(JobYield.of(engineSearching = false, thermalStatus = ThermalState.STATUS_NONE)).isNull()
        assertThat(JobYield.of(false, ThermalState.STATUS_MODERATE)).isNull()
        assertThat(JobYield.of(false, ThermalState.STATUS_SEVERE)).isEqualTo(JobYield.THERMAL)
        assertThat(JobYield.of(false, ThermalState.STATUS_SEVERE + 2)).isEqualTo(JobYield.THERMAL)
        assertThat(JobYield.of(true, ThermalState.STATUS_SEVERE)).isEqualTo(JobYield.ENGINE)
        for (y in JobYield.entries) assertThat(JobYield.ofCode(y.code)).isEqualTo(y)
        assertThat(JobYield.ofCode(0)).isNull()
    }
}
