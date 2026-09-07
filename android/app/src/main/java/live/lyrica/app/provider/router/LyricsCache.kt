package live.lyrica.app.provider.router

import live.lyrica.app.core.logging.LyricaLogger
import live.lyrica.app.core.model.LyricsDocument
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory LRU-style lyrics cache.
 * Only successful (synced) lookups are cached — no failed-lookup entries.
 *
 * Cache entries expire after [TTL_MS] (default 7 days).
 * Maximum [MAX_ENTRIES] entries; oldest evicted when full.
 */
class LyricsCache(
    private val maxEntries: Int = 200,
    private val ttlMs: Long = 7L * 24 * 60 * 60 * 1000   // 7 days
) {
    private val TAG = "LyricsCache"

    private data class Entry(
        val doc: LyricsDocument,
        val expiresAt: Long,
        val insertedAt: Long = System.currentTimeMillis()
    )

    private val store = ConcurrentHashMap<String, Entry>()

    fun get(key: String): LyricsDocument? {
        val entry = store[key] ?: return null
        if (System.currentTimeMillis() > entry.expiresAt) {
            store.remove(key)
            return null
        }
        return entry.doc
    }

    fun put(key: String, doc: LyricsDocument) {
        // Evict oldest if at capacity
        if (store.size >= maxEntries) {
            val oldest = store.entries.minByOrNull { it.value.insertedAt }
            oldest?.let {
                store.remove(it.key)
                LyricaLogger.d(TAG, "Evicted oldest cache entry: ${it.key}")
            }
        }
        store[key] = Entry(doc = doc, expiresAt = System.currentTimeMillis() + ttlMs)
        LyricaLogger.d(TAG, "Cached '${doc.artist} - ${doc.title}' (provider=${doc.provider})")
    }

    fun remove(key: String) {
        store.remove(key)
    }

    fun clear() = store.clear()

    val size: Int get() = store.size
}
