package com.rupleide.netfix.core.dpibypass

/** Ranking for the separately verified Wi-Fi and mobile strategies. */
internal object VerificationRanking {
    private val comparator = compareByDescending<StrategyTestManager.RankedConfig> { it.videoLoaded == true }
        .thenByDescending { it.totalOk }
        .thenBy { if (it.averageMs < 0) Long.MAX_VALUE else it.averageMs }

    fun sorted(results: List<StrategyTestManager.RankedConfig>): List<StrategyTestManager.RankedConfig> =
        results.sortedWith(comparator)

    fun universal(
        wifi: List<StrategyTestManager.RankedConfig>,
        mobile: List<StrategyTestManager.RankedConfig>
    ): Pair<StrategyTestManager.RankedConfig, StrategyTestManager.RankedConfig>? {
        if (wifi.isEmpty() || mobile.isEmpty()) return null

        // Never replace a verified video-capable per-network choice with a
        // host-only universal choice. If no common choice remains, keep the two
        // separate strategies instead.
        val wifiEligible = if (wifi.any { it.videoLoaded == true }) wifi.filter { it.videoLoaded == true } else wifi
        val mobileEligible = if (mobile.any { it.videoLoaded == true }) mobile.filter { it.videoLoaded == true } else mobile
        val wifiBest = wifiEligible.maxOf { it.totalOk }
        val mobileBest = mobileEligible.maxOf { it.totalOk }
        val mobileByKey = mobileEligible.associateBy { StrategyTester.normalizeStrategy(it.strategy) }
        return wifiEligible
            .mapNotNull { w -> mobileByKey[StrategyTester.normalizeStrategy(w.strategy)]?.let { w to it } }
            .filter { (w, m) -> minOf(w.totalOk, m.totalOk) > 0 && w.totalOk >= wifiBest - 1 && m.totalOk >= mobileBest - 1 }
            .maxWithOrNull(
                compareBy<Pair<StrategyTestManager.RankedConfig, StrategyTestManager.RankedConfig>> { (w, m) -> minOf(w.totalOk, m.totalOk) }
                    .thenBy { (w, m) -> w.totalOk + m.totalOk }
                    .thenBy { (w, m) ->
                        -maxOf(
                            if (w.averageMs < 0) Long.MAX_VALUE else w.averageMs,
                            if (m.averageMs < 0) Long.MAX_VALUE else m.averageMs
                        )
                    }
            )
    }
}
