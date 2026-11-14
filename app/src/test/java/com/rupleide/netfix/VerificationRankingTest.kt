package com.rupleide.netfix

import com.rupleide.netfix.core.dpibypass.StrategyTestManager.RankedConfig
import com.rupleide.netfix.core.dpibypass.VerificationRanking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VerificationRankingTest {
    private fun result(strategy: String, hosts: Int, video: Boolean?, ms: Long = 100) =
        RankedConfig(strategy, hosts, 6, 0, 0, ms, video)

    @Test
    fun streamSuccessOutranksMoreReachableHosts() {
        val ranked = VerificationRanking.sorted(listOf(
            result("hosts-only", 6, false, 10),
            result("not-tested", 6, null, 20),
            result("video", 4, true, 900)
        ))
        assertEquals(listOf("video", "hosts-only", "not-tested"), ranked.map { it.strategy })
    }

    @Test
    fun hostAndLatencyRankingRemainsWhenNoVideoCanBeProbed() {
        val ranked = VerificationRanking.sorted(listOf(
            result("slower", 5, false, 500),
            result("faster", 5, null, 50),
            result("fewer", 4, null, 10)
        ))
        assertEquals(listOf("faster", "slower", "fewer"), ranked.map { it.strategy })
    }

    @Test
    fun universalChoiceMustNotReplaceWorkingVideoWithHostOnlySuccess() {
        val wifi = listOf(result("video", 4, true), result("hosts", 6, false))
        val mobile = listOf(result("video", 5, true), result("hosts", 6, false))
        assertEquals("video", VerificationRanking.universal(wifi, mobile)?.first?.strategy)
    }

    @Test
    fun separateChoicesRemainWhenNoVideoStrategyWorksOnBothNetworks() {
        val wifi = listOf(result("wifi-video", 5, true), result("shared", 6, false))
        val mobile = listOf(result("mobile-video", 5, true), result("shared", 6, false))
        assertNull(VerificationRanking.universal(wifi, mobile))
    }

    @Test
    fun olderResultsWithoutVideoDataStillSupportAUniversalChoice() {
        val wifi = listOf(result("shared", 6, null), result("other", 4, null))
        val mobile = listOf(result("shared", 5, null), result("other", 3, null))
        assertEquals("shared", VerificationRanking.universal(wifi, mobile)?.first?.strategy)
    }
}
