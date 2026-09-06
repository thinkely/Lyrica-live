package live.lyrica.app

import live.lyrica.app.core.providers.AdaptiveProviderRanker
import live.lyrica.app.core.providers.ProviderRateLimiter
import org.junit.Assert.*
import org.junit.Test

class ProviderRateLimiterAndRankerTest {

    @Test
    fun testRateLimiterInterval() {
        val limiter = ProviderRateLimiter()
        val provider = "lrclib"

        assertTrue(limiter.canMakeRequest(provider))
        limiter.recordRequest(provider)
        // Immediately after -> should be rate limited
        assertFalse(limiter.canMakeRequest(provider))
    }

    @Test
    fun testAdaptiveRankingOrder() {
        val ranker = AdaptiveProviderRanker()
        val providers = listOf("hosted_lyrica", "lrclib", "lrcmux")

        // Initial ranking order
        val initialRank = ranker.getRankedProviders(providers)
        assertEquals("lrcmux", initialRank[0])
        assertEquals("lrclib", initialRank[1])
        assertEquals("hosted_lyrica", initialRank[2])

        // Record fast success on lrclib
        ranker.recordSuccess("lrclib", latencyMs = 50L)
        // Record failures on lrcmux
        ranker.recordFailure("lrcmux")
        ranker.recordFailure("lrcmux")

        val newRank = ranker.getRankedProviders(providers)
        assertEquals("lrclib", newRank[0])
    }
}
