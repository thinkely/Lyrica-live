package live.lyrica.app

import live.lyrica.app.core.model.LyricLine
import live.lyrica.app.core.model.LyricsDocument
import live.lyrica.app.core.model.SyncPrecision
import live.lyrica.app.provider.router.LyricsCache
import org.junit.Assert.*
import org.junit.Test

class LyricsCacheTest {

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
    fun testPutAndGet() {
        val cache = LyricsCache(maxEntries = 5)
        val key = "the_weeknd:::blinding_lights:::200"

        assertNull(cache.get(key))

        cache.put(key, sampleDoc)
        val cached = cache.get(key)
        assertNotNull(cached)
        assertEquals("Coldplay", cached?.artist)
        assertEquals("Yellow", cached?.title)
    }

    @Test
    fun testEvictionOnCapacity() {
        val cache = LyricsCache(maxEntries = 2)

        cache.put("key1", sampleDoc)
        cache.put("key2", sampleDoc)
        cache.put("key3", sampleDoc)

        assertEquals(2, cache.size)
    }
}
