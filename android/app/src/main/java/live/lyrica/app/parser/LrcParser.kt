package live.lyrica.app.parser

import live.lyrica.app.core.model.LyricLine
import live.lyrica.app.core.model.SyncPrecision

/**
 * Parses raw LRC (Lyric) format strings into a list of [LyricLine].
 *
 * LRC format:
 *   [mm:ss.xx] Lyric text here
 *   [00:27.16] I've been tryna call
 *   [00:29.96] I've been on my own for long enough
 *
 * LRCLIB returns this format in the `syncedLyrics` field.
 * Duration in LRCLIB is in seconds (Double); we receive it already converted to ms.
 */
object LrcParser {

    /** Matches `[mm:ss.xx]` or `[mm:ss.xxx]` LRC timestamp tags */
    private val LRC_LINE_REGEX = Regex("""^\[(\d{1,3}):(\d{2})\.(\d{2,3})\]\s*(.*)$""")

    /** Matches instrumental/empty marker lines */
    private val EMPTY_MARKERS = setOf("", "♪", "♫", "...", "…")

    /**
     * Parse an LRC string into a sorted list of LyricLines.
     *
     * @param lrcString  Raw LRC string from LRCLIB `syncedLyrics` field
     * @param trackDurationMs  Total track duration in ms (used for last line's end time)
     * @return Sorted list of [LyricLine], or empty list if parsing fails
     */
    fun parse(lrcString: String?, trackDurationMs: Long = 0L): List<LyricLine> {
        if (lrcString.isNullOrBlank()) return emptyList()

        data class RawEntry(val startMs: Long, val text: String)

        val raw = mutableListOf<RawEntry>()

        for (line in lrcString.lines()) {
            val trimmed = line.trim()
            val match = LRC_LINE_REGEX.matchEntire(trimmed) ?: continue

            val minutes = match.groupValues[1].toLongOrNull() ?: continue
            val seconds = match.groupValues[2].toLongOrNull() ?: continue
            val centis  = match.groupValues[3]
            val text    = match.groupValues[4].trim()

            // Handle both 2-digit (centiseconds) and 3-digit (milliseconds) fractional part
            val fracMs = when (centis.length) {
                2 -> centis.toLongOrNull()?.times(10) ?: 0L
                3 -> centis.toLongOrNull() ?: 0L
                else -> 0L
            }

            val startMs = minutes * 60_000L + seconds * 1_000L + fracMs
            raw.add(RawEntry(startMs, text))
        }

        if (raw.isEmpty()) return emptyList()

        // Sort by timestamp (LRC files should be sorted, but ensure it)
        raw.sortBy { it.startMs }

        return raw.mapIndexed { idx, entry ->
            val endMs = if (idx + 1 < raw.size) raw[idx + 1].startMs
                        else if (trackDurationMs > 0) trackDurationMs
                        else entry.startMs + 5_000L   // 5s fallback for last line

            LyricLine(
                id = "lrc_$idx",
                startMs = entry.startMs,
                endMs = endMs,
                text = entry.text,
                words = emptyList()   // LRC format is line-level only
            )
        }.filter { it.text.isNotBlank() }   // Drop blank / instrumental markers
    }

    /**
     * Detect sync precision from a parsed line list.
     */
    fun detectPrecision(lines: List<LyricLine>): SyncPrecision {
        return when {
            lines.isEmpty() -> SyncPrecision.NONE
            lines.any { it.hasWordSync } -> SyncPrecision.WORD
            else -> SyncPrecision.LINE
        }
    }
}
