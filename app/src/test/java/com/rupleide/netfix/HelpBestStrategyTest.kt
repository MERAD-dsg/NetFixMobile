package com.rupleide.netfix

import com.rupleide.netfix.core.dpibypass.StrategyTestManager.RankedConfig
import com.rupleide.netfix.core.help.HelpActions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HelpBestStrategyTest {

    private val current = RankedConfig("-o1 -a1", 0, 6, 2, 7, 900)
    private val youtubeStar = RankedConfig("-d1 -a1", 6, 6, 1, 7, 800)
    private val discordStar = RankedConfig("-q1 -a1", 4, 6, 7, 7, 1200)

    @Test
    fun youtubePicksBestYoutubeScore() {
        assertEquals(youtubeStar, HelpActions.pickBest(listOf(current, youtubeStar, discordStar), current.strategy, forDiscord = false))
    }

    @Test
    fun discordPicksBestDiscordScore() {
        assertEquals(discordStar, HelpActions.pickBest(listOf(current, youtubeStar, discordStar), current.strategy, forDiscord = true))
    }

    @Test
    fun nothingWhenCurrentIsAlreadyBest() {
        val best = RankedConfig("-o1 -a1", 6, 6, 7, 7, 500)
        assertNull(HelpActions.pickBest(listOf(best, youtubeStar, discordStar), best.strategy, forDiscord = true))
        assertNull(HelpActions.pickBest(listOf(best, youtubeStar), best.strategy, forDiscord = false))
    }

    @Test
    fun equalStrategyWhenCurrentFailsLive() {
        val best = RankedConfig("-o1 -a1", 6, 6, 7, 7, 700)
        val twin = RankedConfig("-r1 -a1", 6, 6, 7, 7, 650)
        val weaker = RankedConfig("-s1 -a1", 5, 6, 7, 7, 400)
        assertNull(HelpActions.pickBest(listOf(best, twin, weaker), best.strategy, forDiscord = false))
        assertEquals(twin, HelpActions.pickBest(listOf(best, twin, weaker), best.strategy, forDiscord = false, allowEqual = true))
        assertNull(HelpActions.pickBest(listOf(best, weaker), best.strategy, forDiscord = false, allowEqual = true))
    }

    @Test
    fun nothingWhenOthersDoNotWorkAtAll() {
        val dead = RankedConfig("-d7 -a1", 0, 6, 0, 7, -1)
        assertNull(HelpActions.pickBest(listOf(current, dead), current.strategy, forDiscord = false))
    }

    @Test
    fun youtubeHelpPrefersVerifiedVideoOverHostCounts() {
        val hostOnly = RankedConfig("-r1", 6, 6, 7, 7, 50, false)
        val video = RankedConfig("-s1", 4, 6, 2, 7, 900, true)
        assertEquals(video, HelpActions.pickBest(listOf(current, hostOnly, video), current.strategy, forDiscord = false))
        assertEquals(video, HelpActions.pickBest(listOf(hostOnly, video), hostOnly.strategy, forDiscord = false))
        assertNull(HelpActions.pickBest(listOf(video, hostOnly), video.strategy, forDiscord = false, allowEqual = true))
    }

    @Test
    fun discordHelpStillPrioritizesDiscordHosts() {
        val video = RankedConfig("-s1", 4, 6, 2, 7, 900, true)
        assertEquals(discordStar, HelpActions.pickBest(listOf(current, video, discordStar), current.strategy, forDiscord = true))
    }
}
