package live.lyrica.app.provider

import live.lyrica.app.core.model.LyricsDocument
import live.lyrica.app.core.model.TrackQuery

/**
 * Plugin interface that every lyrics provider must implement.
 *
 * Community providers simply implement this interface and call
 * [ProviderRegistry.register] to make themselves available to the router.
 *
 * Provider lifecycle:
 *  1. [isAvailable] is checked first. If false, provider is skipped entirely.
 *  2. [search] is called. It may try multiple internal strategies (e.g. GET then SEARCH).
 *  3. The router picks the best result from all parallel winners via [resultScore].
 */
interface LyricsProvider {

    /** Stable unique identifier (snake_case). Used as cache namespace and in logs. */
    val id: String

    /** Human-readable name shown in the UI (e.g. "LRCLIB", "LRCMux"). */
    val displayName: String

    /**
     * Priority for fallback ordering (lower = tried first).
     * Parallel providers are still raced regardless of priority.
     */
    val priority: Int

    /**
     * Whether this provider can currently respond to requests.
     * Return false if required auth is missing or service is known to be down.
     */
    fun isAvailable(): Boolean = true

    /**
     * Fetch synced lyrics for [query].
     * Return null if no synced lyrics were found (do NOT return plain-text-only results).
     * This method is expected to handle its own retry / fallback internally.
     *
     * @throws Exception on network errors (router will handle these)
     */
    suspend fun search(query: TrackQuery): LyricsDocument?

    /**
     * Score a result for ranking when multiple providers succeed in parallel.
     * Higher score = preferred. Default weights: word sync > line sync.
     */
    fun resultScore(doc: LyricsDocument): Int {
        return when (doc.syncPrecision) {
            live.lyrica.app.core.model.SyncPrecision.WORD -> 100
            live.lyrica.app.core.model.SyncPrecision.LINE -> 50
            live.lyrica.app.core.model.SyncPrecision.NONE -> 0
        }
    }
}
