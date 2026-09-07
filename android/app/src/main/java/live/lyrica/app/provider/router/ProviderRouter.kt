package live.lyrica.app.provider.router

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import live.lyrica.app.core.logging.LyricaLogger
import live.lyrica.app.core.model.LyricsDocument
import live.lyrica.app.core.model.TrackQuery
import live.lyrica.app.provider.LyricsProvider
import live.lyrica.app.provider.ProviderRegistry

/**
 * Orchestrates lyrics resolution across all registered providers.
 *
 * Strategy:
 *   1. Race ALL available providers concurrently using async{}
 *   2. Among all results that return before timeout, pick the best scored one
 *      (WORD sync > LINE sync, per [LyricsProvider.resultScore])
 *   3. Cache the winner (hits only — failed lookups are NOT cached)
 *
 * This means if LRCMux returns WORD sync and LRCLIB returns LINE sync,
 * LRCMux wins even if LRCLIB finishes first.
 */
class ProviderRouter(private val cache: LyricsCache) {
    private val TAG = "ProviderRouter"

    suspend fun resolve(query: TrackQuery): LyricsDocument? {
        // ── 1. Cache lookup ────────────────────────────────────────────────
        val cached = cache.get(query.cacheKey)
        if (cached != null) {
            LyricaLogger.d(TAG, "Cache hit for '${query.title}' (key=${query.cacheKey})")
            return cached
        }

        val available = ProviderRegistry.getAvailable()
        if (available.isEmpty()) {
            LyricaLogger.w(TAG, "No providers registered/available")
            return null
        }

        LyricaLogger.i(TAG, "Racing ${available.size} providers for '${query.artist} - ${query.title}'")

        // ── 2. Parallel race ───────────────────────────────────────────────
        val winner = raceProviders(available, query)

        // ── 3. Cache winner (hits only) ────────────────────────────────────
        if (winner != null) {
            cache.put(query.cacheKey, winner)
        } else {
            LyricaLogger.i(TAG, "All providers returned no synced lyrics for '${query.title}'")
        }

        return winner
    }

    private suspend fun raceProviders(
        providers: List<LyricsProvider>,
        query: TrackQuery
    ): LyricsDocument? = coroutineScope {
        val jobs = providers.map { provider ->
            async {
                val start = System.currentTimeMillis()
                try {
                    val doc = provider.search(query)
                    val elapsed = System.currentTimeMillis() - start
                    if (doc != null && doc.lines.isNotEmpty()) {
                        LyricaLogger.i(TAG, "✓ ${provider.displayName} → ${doc.lines.size} lines, precision=${doc.syncPrecision} in ${elapsed}ms")
                        doc
                    } else {
                        LyricaLogger.d(TAG, "✗ ${provider.displayName} → no synced lyrics in ${elapsed}ms")
                        null
                    }
                } catch (e: Exception) {
                    if (isActive) LyricaLogger.w(TAG, "✗ ${provider.displayName} threw: ${e.message}")
                    null
                }
            }
        }

        // Collect all results then pick best (this waits for ALL to complete)
        // This is intentional: we want the best result, not just the fastest.
        val results = jobs.mapNotNull { it.await() }

        // Score and rank: highest score wins (WORD > LINE)
        results.maxByOrNull { doc ->
            val provider = providers.find { it.id == doc.provider }
            provider?.resultScore(doc) ?: 0
        }
    }
}
