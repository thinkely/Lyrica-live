package live.lyrica.app.core.cache

import android.content.Context
import live.lyrica.app.core.logging.LyricaLogger
import live.lyrica.app.core.model.CacheEntry
import live.lyrica.app.core.model.LyricsDocument
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Robust local lyrics cache with in-memory L1 and persistent file-backed L2.
 */
class LocalLyricsCache(context: Context? = null) {
    private val TAG = "LyricsCache"
    private val memoryCache = ConcurrentHashMap<String, CacheEntry>()
    private val cacheDir: File? = context?.let { File(it.filesDir, "lyrics_cache").apply { mkdirs() } }

    private val DEFAULT_TTL_MS = 14 * 24 * 3600 * 1000L // 14 days

    fun get(trackKey: String): LyricsDocument? {
        // L1 Memory check
        val memEntry = memoryCache[trackKey]
        if (memEntry != null) {
            if (!memEntry.isExpired) {
                LyricaLogger.d(TAG, "Cache L1 HIT for trackKey=$trackKey (provider=${memEntry.provider})")
                return memEntry.lyricsDocument
            } else {
                LyricaLogger.d(TAG, "Cache L1 expired for trackKey=$trackKey")
                memoryCache.remove(trackKey)
            }
        }

        // L2 Disk check
        val file = getCacheFile(trackKey) ?: return null
        if (!file.exists()) {
            LyricaLogger.d(TAG, "Cache MISS for trackKey=$trackKey")
            return null
        }

        return try {
            val jsonStr = file.readText(Charsets.UTF_8)
            val json = JSONObject(jsonStr)
            val entry = CacheEntry.fromJson(json)

            if (entry == null) {
                LyricaLogger.w(TAG, "Corrupt or invalid schema in cached file for key=$trackKey, deleting")
                file.delete()
                null
            } else if (entry.isExpired) {
                LyricaLogger.d(TAG, "Cache L2 expired for trackKey=$trackKey, deleting")
                file.delete()
                null
            } else {
                memoryCache[trackKey] = entry
                LyricaLogger.i(TAG, "Cache L2 HIT for trackKey=$trackKey (provider=${entry.provider})")
                entry.lyricsDocument
            }
        } catch (e: Exception) {
            LyricaLogger.w(TAG, "Error reading cache for key=$trackKey: ${e.message}")
            file.delete()
            null
        }
    }

    fun put(
        trackKey: String,
        lyricsDocument: LyricsDocument,
        ttlMs: Long = DEFAULT_TTL_MS,
        matchConfidence: Double = 1.0
    ) {
        val now = System.currentTimeMillis()
        val entry = CacheEntry(
            schemaVersion = 1,
            trackKey = trackKey,
            provider = lyricsDocument.provider,
            lyricsDocument = lyricsDocument,
            fetchedAtMs = now,
            expiresAtMs = now + ttlMs,
            sourceQuality = if (lyricsDocument.isSynced) 1.0 else 0.5,
            syncPrecision = lyricsDocument.syncPrecision,
            matchConfidence = matchConfidence
        )

        memoryCache[trackKey] = entry

        val file = getCacheFile(trackKey) ?: return
        try {
            file.writeText(entry.toJson().toString(), Charsets.UTF_8)
            LyricaLogger.d(TAG, "Saved lyrics to cache for trackKey=$trackKey (provider=${lyricsDocument.provider})")
        } catch (e: Exception) {
            LyricaLogger.w(TAG, "Failed writing cache to disk for key=$trackKey: ${e.message}")
        }
    }

    fun clear() {
        memoryCache.clear()
        cacheDir?.listFiles()?.forEach { it.delete() }
        LyricaLogger.i(TAG, "Lyrics cache cleared")
    }

    private fun getCacheFile(trackKey: String): File? {
        if (cacheDir == null) return null
        return File(cacheDir, "$trackKey.json")
    }
}
