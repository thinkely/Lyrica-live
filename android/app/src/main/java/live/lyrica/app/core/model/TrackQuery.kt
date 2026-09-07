package live.lyrica.app.core.model

/**
 * A query sent to a LyricsProvider.
 * MetadataResolver produces this from raw Android MediaSession metadata.
 */
data class TrackQuery(
    /** Cleaned artist name (e.g. "The Weeknd", not "The Weeknd VEVO") */
    val artist: String,
    /** Cleaned track title (e.g. "Blinding Lights", not "Blinding Lights (Official Video)") */
    val title: String,
    /** Album name if available */
    val album: String? = null,
    /** Duration in milliseconds (from MediaSession) */
    val durationMs: Long = 0L,
    /** Raw (uncleaned) values kept for fallback queries */
    val rawArtist: String = artist,
    val rawTitle: String = title,
    /** Package name of the media player app (e.g. "com.spotify.music") */
    val playerPackage: String? = null
) {
    /** Duration in seconds (for LRCLIB which uses seconds) */
    val durationSec: Double get() = durationMs / 1000.0

    /** Stable cache key based on normalized values */
    val cacheKey: String by lazy {
        val a = artist.trim().lowercase()
        val t = title.trim().lowercase()
        val d = if (durationMs > 0) (durationMs / 5000) * 5 else 0   // bucket to nearest 5s
        "$a:::$t:::$d"
    }

    /** Whether this query has enough info to search */
    val isSearchable: Boolean
        get() = title.length >= 2 && (artist.isNotBlank() || title.isNotBlank())
}
