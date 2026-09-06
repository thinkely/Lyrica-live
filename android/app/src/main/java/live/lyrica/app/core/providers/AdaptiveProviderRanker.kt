package live.lyrica.app.core.providers

import live.lyrica.app.core.logging.LyricaLogger
import live.lyrica.app.core.model.ProviderStats
import java.util.concurrent.ConcurrentHashMap

/**
 * Adaptive provider ranking based on rolling EWMA latency, success rate, and sync quality.
 */
class AdaptiveProviderRanker {
    private val TAG = "ProviderRanker"
    private val statsMap = ConcurrentHashMap<String, ProviderStats>()

    fun getRankedProviders(eligibleProviders: List<String>): List<String> {
        return eligibleProviders.sortedByDescending { provider ->
            calculateScore(provider)
        }
    }

    private fun calculateScore(providerName: String): Double {
        val stats = statsMap.computeIfAbsent(providerName.lowercase()) { ProviderStats(providerName) }

        // Provider baseline priority weights
        val basePriority = when (providerName.lowercase()) {
            "apple_music" -> 1.0   // Syllable/word sync highest fidelity
            "lrcmux" -> 0.95       // Word/line sync
            "lrclib" -> 0.9        // Excellent general purpose line sync
            "genius" -> 0.7        // Plain lyrics
            "spotify_web" -> 0.65  // Experimental
            "hosted_lyrica" -> 0.5 // Fallback
            else -> 0.4
        }

        val successComponent = stats.successRate * 0.4
        // Normalize EWMA latency: 0ms -> 0.2, 2000ms+ -> 0.0
        val latencyComponent = maxOf(0.0, (2000.0 - minOf(stats.ewmaLatencyMs, 2000.0)) / 2000.0) * 0.2
        val score = (basePriority * 0.4) + successComponent + latencyComponent

        LyricaLogger.d(TAG, "Provider '$providerName' score: %.3f (successRate=%.2f, ewmaLatency=%.0fms)".format(score, stats.successRate, stats.ewmaLatencyMs))
        return score
    }

    fun recordSuccess(providerName: String, latencyMs: Long) {
        val stats = statsMap.computeIfAbsent(providerName.lowercase()) { ProviderStats(providerName) }
        stats.recordSuccess(latencyMs)
    }

    fun recordFailure(providerName: String) {
        val stats = statsMap.computeIfAbsent(providerName.lowercase()) { ProviderStats(providerName) }
        stats.recordFailure()
    }

    fun getStats(providerName: String): ProviderStats {
        return statsMap.computeIfAbsent(providerName.lowercase()) { ProviderStats(providerName) }
    }
}
