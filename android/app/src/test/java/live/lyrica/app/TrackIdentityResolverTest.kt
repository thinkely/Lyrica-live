package live.lyrica.app

import live.lyrica.app.core.resolver.TrackIdentityResolver
import org.junit.Assert.*
import org.junit.Test

class TrackIdentityResolverTest {

    @Test
    fun testNormalizeTextRemasterAndFeat() {
        val rawTitle = "Blinding Lights (Remastered 2020) [feat. Rosalia]"
        val normalized = TrackIdentityResolver.normalizeText(rawTitle)
        assertEquals("blinding lights", normalized)

        val rawArtist = "The Weeknd feat. Daft Punk"
        val normalizedArtist = TrackIdentityResolver.normalizeText(rawArtist)
        assertEquals("the weeknd", normalizedArtist)
    }

    @Test
    fun testStableTrackKeyConsistencyAcrossMinorVariations() {
        val track1 = TrackIdentityResolver.resolve(
            rawTitle = "Blinding Lights",
            rawArtist = "The Weeknd",
            durationMs = 200000L
        )

        val track2 = TrackIdentityResolver.resolve(
            rawTitle = "Blinding Lights (Official Audio)",
            rawArtist = "The Weeknd feat. Max Martin",
            durationMs = 202000L // within 5s bucket
        )

        assertEquals(track1.stableKey, track2.stableKey)
        assertTrue(track1.isValidForLyrics)
    }

    @Test
    fun testInvalidMetadataHandling() {
        val track = TrackIdentityResolver.resolve(
            rawTitle = "Unknown",
            rawArtist = "Unknown",
            durationMs = 0L
        )
        assertFalse(track.isValidForLyrics)
    }
}
