package live.lyrica.app.metadata

import live.lyrica.app.core.model.TrackQuery

/**
 * Resolves and cleans raw Android MediaSession metadata into a reliable TrackQuery.
 *
 * Android MediaSession metadata is notoriously dirty:
 *   - "Blinding Lights (Official Video)" → "Blinding Lights"
 *   - "Starboy [4K Remastered] ft. Daft Punk" → "Starboy"
 *   - "Lady Gaga, Bruno Mars - Die With A Smile (Official Music Video)" → "Die With A Smile"
 *   - "The Weeknd VEVO" → "The Weeknd"
 *   - "Song feat. Artist" → "Song"
 *   - "Track - Remastered 2021" → "Track"
 */
object MetadataResolver {

    // ── Bracket & tag stripping patterns ──────────────────────────────────
    private val BRACKET_NOISE_PATTERN = Regex(
        """\s*[\(\[][^\)\]]*(official|video|audio|remaster|4k|hd|live|visualizer|lyric|explicit|clean|edit|version|feat|ft\.|prod|deluxe|bonus|soundtrack|ost)[^\)\]]*[\)\]]""",
        RegexOption.IGNORE_CASE
    )

    private val TITLE_STRIP_SUFFIXES = listOf(
        Regex("""\s*-\s*(remaster(ed)?(\s+\d{4})?)\s*$""", RegexOption.IGNORE_CASE),
        Regex("""\s*-\s*(official\s*(music\s*)?video|lyric\s*video|audio|live(\s*version)?)\s*$""", RegexOption.IGNORE_CASE),
        Regex("""\s*(feat\.?|ft\.?)\s+.+$""", RegexOption.IGNORE_CASE)
    )

    // ── Artist cleaning patterns ───────────────────────────────────────────
    private val ARTIST_STRIP_PATTERNS = listOf(
        Regex("""\s*(VEVO|Official|Music|Topic)\s*$""", RegexOption.IGNORE_CASE),
        Regex("""\s*[\(\[].*?[\)\]]"""),             // anything in brackets
        Regex("""\s*,\s*.+$"""),                      // strip "Artist, OtherArtist" -> primary
        Regex("""\s*&\s*.+$"""),                      // strip "Artist & OtherArtist" -> primary
        Regex("""\s*(feat\.?|ft\.?|with|prod\.?)\s+.+$""", RegexOption.IGNORE_CASE)
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
        val cleanArtist = cleanArtist(rawArtist ?: "")
        val cleanTitle = cleanTitle(rawTitle ?: "", cleanArtist)

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

    private fun cleanTitle(raw: String, cleanArtist: String): String {
        var title = raw.trim()

        // 1. Remove bracketed noise tags (repeat to catch chained brackets)
        while (BRACKET_NOISE_PATTERN.containsMatchIn(title)) {
            title = BRACKET_NOISE_PATTERN.replace(title, "").trim()
        }

        // 2. Remove trailing suffix markers
        for (pattern in TITLE_STRIP_SUFFIXES) {
            title = pattern.replace(title, "").trim()
        }

        // 3. Handle YouTube-style "Artist - Title" embedded in title field
        if (title.contains(" - ") || title.contains(" — ") || title.contains(" – ")) {
            val parts = title.split(Regex("""\s+[-—–]\s+"""))
            if (parts.size >= 2) {
                val left = parts[0].trim()
                val right = parts.subList(1, parts.size).joinToString(" - ").trim()

                // If left matches the artist or contains multi-artists, right is the song title
                if (cleanArtist.isNotBlank() && (left.contains(cleanArtist, ignoreCase = true) || cleanArtist.contains(left, ignoreCase = true))) {
                    title = right
                } else if (left.contains(",") || left.contains("&") || left.contains("ft.", ignoreCase = true)) {
                    // Likely artist list on left e.g. "Lady Gaga, Bruno Mars - Die With A Smile"
                    title = right
                }
            }
        }

        // 4. Collapse multiple whitespace & strip residual punctuation
        title = title.replace(Regex("\\s{2,}"), " ").trim()
        title = title.trimEnd('-', '—', '–', ',', '.', ':').trim()

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
