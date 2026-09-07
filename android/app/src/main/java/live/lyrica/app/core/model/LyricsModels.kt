package live.lyrica.app.core.model

/**
 * A single word within a lyric line, with millisecond timestamps.
 * Used for word-level (karaoke-style) synchronization from LRCMux.
 */
data class LyricWord(
    val text: String,
    val startMs: Long,
    val endMs: Long
)

/**
 * A single lyric line with millisecond timestamps and optional word-level data.
 */
data class LyricLine(
    /** Unique stable id within the document */
    val id: String,
    /** Line start in milliseconds */
    val startMs: Long,
    /** Line end in milliseconds (= next line's startMs, or track end) */
    val endMs: Long,
    /** Full text of the line */
    val text: String,
    /** Word-level timings (empty if only line-level sync is available) */
    val words: List<LyricWord> = emptyList()
) {
    val hasWordSync: Boolean get() = words.isNotEmpty()
}

enum class SyncPrecision { NONE, LINE, WORD }

/**
 * Unified lyrics document produced by any LyricsProvider.
 */
data class LyricsDocument(
    /** Provider that produced this document (e.g. "lrclib", "lrcmux") */
    val provider: String,
    val artist: String,
    val title: String,
    /** Synchronized lyric lines (empty if unsupported/not found) */
    val lines: List<LyricLine> = emptyList(),
    val syncPrecision: SyncPrecision = SyncPrecision.NONE,
    /** Whether this is an instrumental track (no lyrics) */
    val isInstrumental: Boolean = false
) {
    val isSynced: Boolean get() = syncPrecision != SyncPrecision.NONE && lines.isNotEmpty()
    val hasWordSync: Boolean get() = syncPrecision == SyncPrecision.WORD && lines.any { it.hasWordSync }
}
