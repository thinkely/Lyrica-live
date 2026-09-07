package live.lyrica.app.engine

import live.lyrica.app.core.model.LyricLine
import live.lyrica.app.core.model.LyricWord
import live.lyrica.app.core.model.LyricsDocument

data class SyncState(
    val lineIndex: Int = -1,
    val currentLine: LyricLine? = null,
    val previousLine: LyricLine? = null,
    val nextLine: LyricLine? = null,
    val wordIndex: Int = -1,
    val currentWord: LyricWord? = null
)

/**
 * Resolves which lyric line (and word) is active for a given playback position.
 * Uses binary search for O(log n) line resolution.
 */
object LyricsSyncEngine {

    fun resolveSyncState(doc: LyricsDocument?, positionMs: Long): SyncState {
        if (doc == null || doc.lines.isEmpty()) return SyncState()

        val lines = doc.lines
        val lineIdx = findLineIndex(lines, positionMs)

        if (lineIdx < 0) {
            return SyncState(nextLine = lines.firstOrNull())
        }

        val current = lines[lineIdx]
        val prev = if (lineIdx > 0) lines[lineIdx - 1] else null
        val next = if (lineIdx + 1 < lines.size) lines[lineIdx + 1] else null

        // Word resolution
        var wordIdx = -1
        var currentWord: LyricWord? = null
        if (current.hasWordSync) {
            wordIdx = findWordIndex(current.words, positionMs)
            if (wordIdx >= 0) currentWord = current.words[wordIdx]
        }

        return SyncState(
            lineIndex = lineIdx,
            currentLine = current,
            previousLine = prev,
            nextLine = next,
            wordIndex = wordIdx,
            currentWord = currentWord
        )
    }

    private fun findLineIndex(lines: List<LyricLine>, positionMs: Long): Int {
        var low = 0
        var high = lines.size - 1
        var bestIndex = -1

        while (low <= high) {
            val mid = (low + high) ushr 1
            val line = lines[mid]
            when {
                positionMs in line.startMs until line.endMs -> return mid
                positionMs < line.startMs -> high = mid - 1
                else -> { bestIndex = mid; low = mid + 1 }
            }
        }
        return bestIndex
    }

    private fun findWordIndex(words: List<LyricWord>, positionMs: Long): Int {
        for (i in words.indices) {
            if (positionMs in words[i].startMs until words[i].endMs) return i
        }
        // If past all words, return last word index (keep last word highlighted)
        return if (words.isNotEmpty() && positionMs >= words.last().startMs) words.size - 1 else -1
    }
}
