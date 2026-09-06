package live.lyrica.app

import live.lyrica.app.core.cache.LocalLyricsCache
import live.lyrica.app.core.model.LyricLine
import live.lyrica.app.core.model.LyricsDocument
import live.lyrica.app.core.model.SyncPrecision
import org.junit.Assert.*
import org.junit.Test

class LocalLyricsCacheTest {

    private val sampleDoc = LyricsDocument(
        provider = "lrclib",
        artist = "Coldplay",
        title = "Yellow",
        syncPrecision = SyncPrecision.LINE,
        lines = listOf(
            LyricLine(id = "l1", startMs = 15000L, endMs = 20000L, text = "Look at the stars")
        )
    )

    @Test
    fun testCachePutAndGetMemory() {
        val cache = LocalLyricsCache(null) // in-memory only
        val key = "coldplay:::yellow:::120"

        assertNull(cache.get(key))

        cache.put(key, sampleDoc, ttlMs = 60000L)
        val retrieved = cache.get(key)
        assertNotNull(retrieved)
        assertEquals("Coldplay", retrieved?.artist)
        assertEquals("Yellow", retrieved?.title)
    }

    @Test
    fun testCacheExpiration() {
        val cache = LocalLyricsCache(null)
        val key = "coldplay:::yellow:::120"

        // Expired immediately (-1ms ttl)
        cache.put(key, sampleDoc, ttlMs = -100L)
        assertNull(cache.get(key))
    }
}
