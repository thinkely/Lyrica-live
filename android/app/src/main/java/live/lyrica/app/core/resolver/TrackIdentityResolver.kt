package live.lyrica.app.core.resolver

import live.lyrica.app.core.logging.LyricaLogger
import live.lyrica.app.core.model.NormalizedTrack
import java.text.Normalizer
import java.util.regex.Pattern

/**
 * Normalizes raw metadata from Android MediaSession and generates stable track identities.
 */
object TrackIdentityResolver {
    private const val TAG = "TrackResolver"

    private val FEAT_PATTERN = Pattern.compile(
        "\\s*[\\(\\[\\{]?(?:feat\\.?|featuring|ft\\.?|with)\\s+[^()\\[\\]{}]+[\\)\\]\\}]?",
        Pattern.CASE_INSENSITIVE
    )

    private val EXTRA_TAGS_PATTERN = Pattern.compile(
        "\\s*[\\(\\[\\{][^()\\[\\]{}]*(?:remaster|deluxe|bonus|edit|official|lyric video|live|acoustic|mono|stereo|anniversary|edition|version|audio|\\b\\d{4}\\b)[^()\\[\\]{}]*[\\)\\]\\}]",
        Pattern.CASE_INSENSITIVE
    )

    private val SPECIAL_CHARS_PATTERN = Pattern.compile("[^\\w\\s]")
    private val WHITESPACE_PATTERN = Pattern.compile("\\s+")

    fun normalizeText(input: String?): String {
        if (input.isNullOrBlank()) return ""

        // Unicode NFKD decomposition
        val decomposed = Normalizer.normalize(input, Normalizer.Form.NFKD)
        val strippedAccents = decomposed.replace("\\p{M}+".toRegex(), "")

        var clean = FEAT_PATTERN.matcher(strippedAccents).replaceAll("")
        clean = EXTRA_TAGS_PATTERN.matcher(clean).replaceAll("")
        clean = SPECIAL_CHARS_PATTERN.matcher(clean).replaceAll(" ")
        clean = WHITESPACE_PATTERN.matcher(clean).replaceAll(" ").trim().lowercase()

        return clean
    }

    fun resolve(
        rawTitle: String?,
        rawArtist: String?,
        rawAlbum: String? = null,
        durationMs: Long = 0L,
        mediaId: String? = null,
        packageName: String? = null
    ): NormalizedTrack {
        val normTitle = normalizeText(rawTitle)
        val normArtist = normalizeText(rawArtist)

        // Calculate confidence score
        var confidence = 1.0
        if (rawTitle.isNullOrBlank() || normTitle.isEmpty()) {
            confidence -= 0.5
        }
        if (rawArtist.isNullOrBlank() || normArtist.isEmpty()) {
            confidence -= 0.3
        }
        if (durationMs <= 0L) {
            confidence -= 0.1
        }

        val resolved = NormalizedTrack(
            rawArtist = rawArtist ?: "",
            normalizedArtist = normArtist,
            rawTitle = rawTitle ?: "",
            normalizedTitle = normTitle,
            rawAlbum = rawAlbum,
            durationMs = durationMs,
            mediaId = mediaId,
            packageName = packageName,
            confidenceScore = maxOf(0.0, confidence)
        )

        LyricaLogger.d(TAG, "Resolved track: '$normArtist' - '$normTitle' (key=${resolved.stableKey}, confidence=$confidence)")
        return resolved
    }
}
