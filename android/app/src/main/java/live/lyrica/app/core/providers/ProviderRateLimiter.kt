package live.lyrica.app.core.providers

import live.lyrica.app.core.logging.LyricaLogger
import java.util.concurrent.ConcurrentHashMap

/**
 * Provider-specific rate limiter with minimum intervals and retry backoff.
 */
class ProviderRateLimiter {
    private val TAG = "RateLimiter"

    private val providerMinIntervals = mapOf(
        "lrclib" to 500L,        // 500ms min interval
        "lrcmux" to 800L,        // 800ms min interval
        "genius" to 1000L,       // 1s min interval
        "apple_music" to 500L,   // 500ms min interval
        "hosted_lyrica" to 500L  // 500ms min interval
    )

    private val lastRequestTimestamps = ConcurrentHashMap<String, Long>()

    fun canMakeRequest(providerName: String): Boolean {
        val key = providerName.lowercase()
        val minInterval = providerMinIntervals[key] ?: 500L
        val lastTime = lastRequestTimestamps[key] ?: 0L
        val now = System.currentTimeMillis()

        val elapsed = now - lastTime
        if (elapsed < minInterval) {
            LyricaLogger.d(TAG, "Rate limiting '$providerName': ${minInterval - elapsed}ms remaining")
            return false
        }
        return true
    }

    fun recordRequest(providerName: String) {
        lastRequestTimestamps[providerName.lowercase()] = System.currentTimeMillis()
    }
}
