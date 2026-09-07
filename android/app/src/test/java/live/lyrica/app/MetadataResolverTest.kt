package live.lyrica.app

import live.lyrica.app.metadata.MetadataResolver
import org.junit.Assert.*
import org.junit.Test

class MetadataResolverTest {

    @Test
    fun testStripNoiseTags() {
        val title1 = "Blinding Lights (Official Music Video)"
        val resolved1 = MetadataResolver.resolve(title1, "The Weeknd", "After Hours", 200000L)
        assertEquals("Blinding Lights", resolved1.title)
        assertEquals("The Weeknd", resolved1.artist)

        val title2 = "Starboy [4K Remastered] ft. Daft Punk"
        val resolved2 = MetadataResolver.resolve(title2, "The Weeknd", null, 230000L)
        assertEquals("Starboy", resolved2.title)
    }

    @Test
    fun testNormalizeArtistFeat() {
        val resolved = MetadataResolver.resolve("Die For You", "The Weeknd feat. Ariana Grande", null, 210000L)
        assertEquals("The Weeknd", resolved.artist)
    }

    @Test
    fun testYouTubeNoiseRemoval() {
        val rawTitle = "Lady Gaga, Bruno Mars - Die With A Smile (Official Music Video)"
        val resolved = MetadataResolver.resolve(rawTitle, "Lady Gaga", null, 250000L, "com.google.android.apps.youtube.music")
        assertEquals("Die With A Smile", resolved.title)
    }

    @Test
    fun testQueryableCheck() {
        val valid = MetadataResolver.resolve("Blinding Lights", "The Weeknd", null, 200000L)
        assertTrue(MetadataResolver.isQueryable(valid))

        val invalid = MetadataResolver.resolve("Unknown Track", "Unknown Artist", null, 0L)
        assertFalse(MetadataResolver.isQueryable(invalid))
    }
}
