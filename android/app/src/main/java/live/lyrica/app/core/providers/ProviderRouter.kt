package live.lyrica.app.core.providers

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import live.lyrica.app.core.cache.LocalLyricsCache
import live.lyrica.app.core.logging.LyricaLogger
import live.lyrica.app.core.model.LyricsDocument
import live.lyrica.app.core.model.NormalizedTrack
import live.lyrica.app.security.SecureTokenStorage
import org.json.JSONObject

/**
 * Orchestrates lyrics resolution through Eligibility, Circuit Breaking, Rate Limiting,
 * Adaptive Ranking, Python Bridge invocation, and Fallback Chain.
 *
 * Strategy:
 *  - LRCLIB and LRCMux are always raced in parallel; the first to return a synced result wins.
 *  - Only documents with timed lines (doc.lines.isNotEmpty()) are accepted — plain-text-only
 *    results are treated as misses so we don't waste the notification slot on unsynced text.
 *  - Optional providers (Apple Music, Genius) are tried sequentially after the parallel race
 *    if it produces no result.
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

    /** Providers that are raced in parallel on every lookup (no auth required). */
    private val parallelProviders = listOf("lrclib", "lrcmux")

    /** Providers tried sequentially only after the parallel race fails (may require auth). */
    private val sequentialFallbackProviders = listOf("apple_music", "genius", "hosted_lyrica")

    suspend fun resolveLyrics(track: NormalizedTrack, wordLevel: Boolean = true): LyricsDocument? {
        if (!track.isValidForLyrics) {
            LyricaLogger.d(TAG, "Track '${track.rawArtist} - ${track.rawTitle}' is invalid for lyrics search")
            return null
        }

        // ── 1. Local Cache Lookup ──────────────────────────────────────────
        val cached = cache.get(track.stableKey)
        if (cached != null) {
            LyricaLogger.d(TAG, "Cache hit for '${track.normalizedTitle}'")
            return cached
        }

        val credentialsJson = buildCredentialsJson()

        // ── 2. Parallel Race: LRCLIB vs LRCMux ────────────────────────────
        LyricaLogger.i(TAG, "Starting parallel race [${parallelProviders.joinToString(", ")}] for '${track.normalizedArtist} - ${track.normalizedTitle}'")

        val parallelResult = raceProviders(
            providers = parallelProviders.filter { isProviderEligible(it) },
            track = track,
            wordLevel = wordLevel,
            credentialsJson = credentialsJson
        )

        if (parallelResult != null) {
            cache.put(track.stableKey, parallelResult, matchConfidence = track.confidenceScore)
            return parallelResult
        }

        LyricaLogger.i(TAG, "Parallel race produced no synced result — trying sequential fallbacks")

        // ── 3. Sequential Fallbacks ────────────────────────────────────────
        for (provider in sequentialFallbackProviders) {
            if (!isProviderEligible(provider)) continue

            val doc = fetchFromProvider(
                provider = provider,
                track = track,
                wordLevel = wordLevel,
                credentialsJson = credentialsJson
            )
            if (doc != null) {
                cache.put(track.stableKey, doc, matchConfidence = track.confidenceScore)
                return doc
            }
        }

        LyricaLogger.i(TAG, "All providers exhausted — no synced lyrics for '${track.normalizedArtist} - ${track.normalizedTitle}'")
        return null
    }

    /**
     * Races [providers] concurrently and returns the first doc that has timed lines.
     * All jobs are cancelled as soon as a winner is found.
     */
    private suspend fun raceProviders(
        providers: List<String>,
        track: NormalizedTrack,
        wordLevel: Boolean,
        credentialsJson: String
    ): LyricsDocument? {
        if (providers.isEmpty()) return null

        // Use coroutineScope so cancellation propagates correctly
        return coroutineScope {
            val jobs = providers.map { provider ->
                async {
                    val startTime = System.currentTimeMillis()
                    val doc = try {
                        if (!rateLimiter.canMakeRequest(provider)) {
                            LyricaLogger.d(TAG, "Rate-limited: skipping '$provider'")
                            null
                        } else {
                            rateLimiter.recordRequest(provider)
                            pythonBridge.fetchLyricsFromPython(
                                providerName = provider,
                                artist = track.normalizedArtist,
                                song = track.normalizedTitle,
                                album = track.rawAlbum,
                                durationMs = track.durationMs,
                                credentialsJson = credentialsJson,
                                wordLevel = wordLevel
                            )
                        }
                    } catch (e: Exception) {
                        LyricaLogger.w(TAG, "Provider '$provider' threw: ${e.message}")
                        null
                    }
                    val elapsed = System.currentTimeMillis() - startTime
                    if (doc != null && doc.lines.isNotEmpty()) {
                        circuitBreaker.recordSuccess(provider)
                        ranker.recordSuccess(provider, elapsed)
                        LyricaLogger.i(TAG, "Provider '$provider' won race in ${elapsed}ms (lines=${doc.lines.size}, precision=${doc.syncPrecision})")
                        doc
                    } else {
                        circuitBreaker.recordFailure(provider)
                        ranker.recordFailure(provider)
                        LyricaLogger.d(TAG, "Provider '$provider' returned no synced lyrics in ${elapsed}ms")
                        null
                    }
                }
            }

            // Wait for all to complete; return first non-null result
            var winner: LyricsDocument? = null
            for (job in jobs) {
                val result = job.await()
                if (winner == null && result != null) {
                    winner = result
                }
            }
            winner
        }
    }

    /** Single provider fetch with circuit-breaker and rate-limiter guards. */
    private suspend fun fetchFromProvider(
        provider: String,
        track: NormalizedTrack,
        wordLevel: Boolean,
        credentialsJson: String
    ): LyricsDocument? {
        if (!circuitBreaker.canExecute(provider)) {
            LyricaLogger.d(TAG, "Skipping '$provider': Circuit Breaker is OPEN")
            return null
        }
        if (!rateLimiter.canMakeRequest(provider)) {
            LyricaLogger.d(TAG, "Skipping '$provider': Rate limited")
            return null
        }

        rateLimiter.recordRequest(provider)
        val startTime = System.currentTimeMillis()

        return try {
            val doc = pythonBridge.fetchLyricsFromPython(
                providerName = provider,
                artist = track.normalizedArtist,
                song = track.normalizedTitle,
                album = track.rawAlbum,
                durationMs = track.durationMs,
                credentialsJson = credentialsJson,
                wordLevel = wordLevel
            )
            val elapsed = System.currentTimeMillis() - startTime

            // Only accept synced results — plain-text-only docs are treated as misses
            if (doc != null && doc.lines.isNotEmpty()) {
                circuitBreaker.recordSuccess(provider)
                ranker.recordSuccess(provider, elapsed)
                LyricaLogger.i(TAG, "Provider '$provider' returned synced lyrics in ${elapsed}ms")
                doc
            } else {
                circuitBreaker.recordFailure(provider)
                ranker.recordFailure(provider)
                LyricaLogger.d(TAG, "Provider '$provider' returned no synced lyrics in ${elapsed}ms — skipping")
                null
            }
        } catch (e: Exception) {
            circuitBreaker.recordFailure(provider)
            ranker.recordFailure(provider)
            LyricaLogger.w(TAG, "Provider '$provider' threw exception: ${e.message}")
            null
        }
    }

    private fun isProviderEligible(provider: String): Boolean {
        if (!circuitBreaker.canExecute(provider)) return false
        return when (provider) {
            "lrclib", "lrcmux", "hosted_lyrica" -> true
            "apple_music" -> !secureStorage.getString("apple_developer_token").isNullOrBlank()
            "genius" -> !secureStorage.getString("genius_token").isNullOrBlank()
            else -> false
        }
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
