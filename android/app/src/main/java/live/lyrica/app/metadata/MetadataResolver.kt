package live.lyrica.app.metadata

import live.lyrica.app.core.model.TrackQuery

/**
 * Resolves and cleans raw Android MediaSession metadata into a reliable TrackQuery.
 *
 * Android MediaSession metadata is notoriously dirty:
 *   - "Blinding Lights (Official Video)" → "Blinding Lights"
 *   - "The Weeknd VEVO" → "The Weeknd"
 *   - "Song feat. Artist" → "Song"
 *   - "Track - Remastered 2021" → "Track"
 *
 * Strategy:
 *   1. Clean artist name
 *   2. Clean title
 *   3. Return both cleaned and raw values (raw kept for fallback queries)
 */
object MetadataResolver {

    // ── Title cleaning patterns ────────────────────────────────────────────
    private val TITLE_STRIP_PATTERNS = listOf(
        Regex("""\s*[\(\[](feat\.?|ft\.?|with|prod\.?|produced by)[^\)\]]*[\)\]]""", RegexOption.IGNORE_CASE),
        Regex("""\s*[\(\[](official\s*(music\s*)?video|lyric\s*video|audio|visualizer|animated|performance|live)[^\)\]]*[\)\]]""", RegexOption.IGNORE_CASE),
        Regex("""\s*[\(\[](remaster(ed)?(\s+\d{4})?)[^\)\]]*[\)\]]""", RegexOption.IGNORE_CASE),
        Regex("""\s*[\(\[](explicit|clean|radio\s*edit|extended|bonus\s*track|album\s*version)[^\)\]]*[\)\]]""", RegexOption.IGNORE_CASE),
        Regex("""\s*-\s*(remaster(ed)?(\s+\d{4})?)\s*$""", RegexOption.IGNORE_CASE),
        Regex("""\s*-\s*(official\s*(music\s*)?video|lyric\s*video|audio)\s*$""", RegexOption.IGNORE_CASE),
        Regex("""\s*(feat\.?|ft\.?)\s+.+$""", RegexOption.IGNORE_CASE),
    )

    // ── Artist cleaning patterns ───────────────────────────────────────────
    private val ARTIST_STRIP_PATTERNS = listOf(
        Regex("""\s*(VEVO|Official|Music)\s*$""", RegexOption.IGNORE_CASE),
        Regex("""\s*[\(\[].*?[\)\]]"""),             // anything in brackets
        Regex("""\s*,\s*.+$"""),                      // strip "Artist, OtherArtist"
        Regex("""\s*&\s*.+$"""),                      // strip "Artist & OtherArtist"  — keep primary
        Regex("""\s*(feat\.?|ft\.?|with)\s+.+$""", RegexOption.IGNORE_CASE),
    )

    // ── Noise indicators (title is probably unreliable) ───────────────────
    private val TITLE_NOISE = setOf(
        "unknown", "unknown track", "untitled", "-", "--", "null", ""
    )

    /**
     * Build a [TrackQuery] from raw MediaSession metadata.
     * Both cleaned and raw values are preserved for provider fallback strategies.
     */
    fun resolve(
        rawTitle: String?,
        rawArtist: String?,
        rawAlbum: String? = null,
        durationMs: Long = 0L,
        playerPackage: String? = null
    ): TrackQuery {
        val cleanTitle = cleanTitle(rawTitle ?: "")
        val cleanArtist = cleanArtist(rawArtist ?: "")

        return TrackQuery(
            artist = cleanArtist,
            title = cleanTitle,
            album = rawAlbum?.trim()?.ifBlank { null },
            durationMs = durationMs,
            rawArtist = rawArtist?.trim() ?: "",
            rawTitle = rawTitle?.trim() ?: "",
            playerPackage = playerPackage
        )
    }

    private fun cleanTitle(raw: String): String {
        var title = raw.trim()
        for (pattern in TITLE_STRIP_PATTERNS) {
            title = pattern.replace(title, "").trim()
        }
        // Collapse multiple spaces
        title = title.replace(Regex("\\s{2,}"), " ").trim()
        // Strip trailing punctuation left behind
        title = title.trimEnd('-', '—', '–', ',', '.').trim()
        return if (title.lowercase() in TITLE_NOISE) raw.trim() else title
    }

    private fun cleanArtist(raw: String): String {
        var artist = raw.trim()
        for (pattern in ARTIST_STRIP_PATTERNS) {
            artist = pattern.replace(artist, "").trim()
        }
        artist = artist.replace(Regex("\\s{2,}"), " ").trim()
        return artist.ifBlank { raw.trim() }
    }

    /**
     * Returns true if the query has stable enough identity to search.
     * E.g. rejects "Unknown" / empty combinations.
     */
    fun isQueryable(query: TrackQuery): Boolean {
        if (query.title.length < 2) return false
        if (query.title.lowercase() in TITLE_NOISE) return false
        return true
    }
}
