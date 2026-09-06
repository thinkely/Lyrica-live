package live.lyrica.app.core.providers

import live.lyrica.app.core.cache.LocalLyricsCache
import live.lyrica.app.core.logging.LyricaLogger
import live.lyrica.app.core.model.LyricsDocument
import live.lyrica.app.core.model.NormalizedTrack
import live.lyrica.app.security.SecureTokenStorage
import org.json.JSONObject

/**
 * Orchestrates lyrics resolution through Eligibility, Circuit Breaking, Rate Limiting,
 * Adaptive Ranking, Python Bridge invocation, and Fallback Chain.
 */
class ProviderRouter(
    private val cache: LocalLyricsCache,
    private val pythonBridge: PythonBridge,
    private val secureStorage: SecureTokenStorage,
    val circuitBreaker: ProviderCircuitBreaker = ProviderCircuitBreaker(),
    val rateLimiter: ProviderRateLimiter = ProviderRateLimiter(),
    val ranker: AdaptiveProviderRanker = AdaptiveProviderRanker()
) {
    private val TAG = "ProviderRouter"

    private val allConfiguredProviders = listOf(
        "lrclib",
        "lrcmux",
        "apple_music",
        "genius",
        "hosted_lyrica"
    )

    suspend fun resolveLyrics(track: NormalizedTrack): LyricsDocument? {
        if (!track.isValidForLyrics) {
            LyricaLogger.d(TAG, "Track '${track.rawArtist} - ${track.rawTitle}' is invalid for lyrics search")
            return null
        }

        // ── 1. Local Cache Lookup ──────────────────────────────────────────
        val cached = cache.get(track.stableKey)
        if (cached != null) {
            return cached
        }

        // ── 2. Filter Eligible Providers ──────────────────────────────────
        val eligible = getEligibleProviders()
        if (eligible.isEmpty()) {
            LyricaLogger.w(TAG, "No eligible lyrics providers available")
            return null
        }

        // ── 3. Apply Adaptive Ranking ─────────────────────────────────────
        val ranked = ranker.getRankedProviders(eligible)
        LyricaLogger.i(TAG, "Provider order for '${track.normalizedArtist} - ${track.normalizedTitle}': $ranked")

        val credentialsJson = buildCredentialsJson()

        // ── 4. Fallback Execution Loop ───────────────────────────────────
        for (provider in ranked) {
            // Check Circuit Breaker
            if (!circuitBreaker.canExecute(provider)) {
                LyricaLogger.d(TAG, "Skipping '$provider': Circuit Breaker is OPEN")
                continue
            }

            // Check Rate Limiter
            if (!rateLimiter.canMakeRequest(provider)) {
                LyricaLogger.d(TAG, "Skipping '$provider': Rate limited")
                continue
            }

            rateLimiter.recordRequest(provider)
            val startTime = System.currentTimeMillis()

            try {
                val doc = pythonBridge.fetchLyricsFromPython(
                    providerName = provider,
                    artist = track.normalizedArtist,
                    song = track.normalizedTitle,
                    album = track.rawAlbum,
                    durationMs = track.durationMs,
                    credentialsJson = credentialsJson
                )

                val elapsed = System.currentTimeMillis() - startTime

                if (doc != null && (doc.plainLyrics.isNotEmpty() || doc.lines.isNotEmpty())) {
                    circuitBreaker.recordSuccess(provider)
                    ranker.recordSuccess(provider, elapsed)

                    // Cache result
                    cache.put(track.stableKey, doc, matchConfidence = track.confidenceScore)
                    return doc
                } else {
                    // Record failure for 404 / no lyrics found
                    circuitBreaker.recordFailure(provider)
                    ranker.recordFailure(provider)
                    LyricaLogger.d(TAG, "Provider '$provider' returned no lyrics, falling back")
                }
            } catch (e: Exception) {
                val elapsed = System.currentTimeMillis() - startTime
                circuitBreaker.recordFailure(provider)
                ranker.recordFailure(provider)
                LyricaLogger.w(TAG, "Provider '$provider' failed with exception: ${e.message}")
            }
        }

        LyricaLogger.i(TAG, "All eligible providers exhausted without lyrics for '${track.normalizedArtist} - ${track.normalizedTitle}'")
        return null
    }

    private fun getEligibleProviders(): List<String> {
        val list = mutableListOf<String>()

        // LRCLIB is open / no auth required
        list.add("lrclib")
        // LRCMux is open / no auth required
        list.add("lrcmux")

        // Apple Music requires Developer Token
        val appleDevToken = secureStorage.getString("apple_developer_token")
        if (!appleDevToken.isNullOrBlank()) {
            list.add("apple_music")
        }

        // Genius optionally works if user token is provided
        val geniusToken = secureStorage.getString("genius_token")
        if (!geniusToken.isNullOrBlank()) {
            list.add("genius")
        }

        // Hosted Lyrica fallback is always available
        list.add("hosted_lyrica")

        return list
    }

    private fun buildCredentialsJson(): String {
        val json = JSONObject()
        secureStorage.getString("genius_token")?.let { json.put("genius_token", it) }
        secureStorage.getString("apple_developer_token")?.let { json.put("apple_developer_token", it) }
        secureStorage.getString("apple_user_token")?.let { json.put("apple_user_token", it) }
        secureStorage.getString("apple_storefront")?.let { json.put("apple_storefront", it) }
        secureStorage.getString("hosted_url")?.let { json.put("hosted_url", it) }
        return json.toString()
    }
}
