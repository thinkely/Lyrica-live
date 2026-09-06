package live.lyrica.app.core.engine

import live.lyrica.app.core.model.LyricLine
import live.lyrica.app.core.model.LyricsDocument
import live.lyrica.app.core.model.SyllableTiming
import live.lyrica.app.core.model.WordTiming

data class SyncState(
    val lineIndex: Int = -1,
    val currentLine: LyricLine? = null,
    val wordIndex: Int = -1,
    val currentWord: WordTiming? = null,
    val syllableIndex: Int = -1,
    val currentSyllable: SyllableTiming? = null,
    val nextLine: LyricLine? = null,
    val previousLine: LyricLine? = null
)

/**
 * LyricsSyncEngine resolves active line, word, and syllable based on discrete
 * timestamp ranges: line.startMs <= positionMs < line.endMs.
 */
object LyricsSyncEngine {

    fun resolveSyncState(lyricsDoc: LyricsDocument?, positionMs: Long): SyncState {
        if (lyricsDoc == null || lyricsDoc.lines.isEmpty()) {
            return SyncState()
        }

        val lines = lyricsDoc.lines
        val lineIdx = findLineIndex(lines, positionMs)

        if (lineIdx < 0) {
            // Before the first lyric line
            val firstLine = lines.firstOrNull()
            return SyncState(
                lineIndex = -1,
                currentLine = null,
                nextLine = firstLine,
                previousLine = null
            )
        }

        val currentLine = lines[lineIdx]
        val prevLine = if (lineIdx > 0) lines[lineIdx - 1] else null
        val nextLine = if (lineIdx + 1 < lines.size) lines[lineIdx + 1] else null

        // Word-level resolution
        var wordIdx = -1
        var currentWord: WordTiming? = null
        var syllableIdx = -1
        var currentSyllable: SyllableTiming? = null

        if (currentLine.words.isNotEmpty()) {
            wordIdx = findWordIndex(currentLine.words, positionMs)
            if (wordIdx >= 0) {
                currentWord = currentLine.words[wordIdx]
                if (currentWord.syllables.isNotEmpty()) {
                    syllableIdx = findSyllableIndex(currentWord.syllables, positionMs)
                    if (syllableIdx >= 0) {
                        currentSyllable = currentWord.syllables[syllableIdx]
                    }
                }
            }
        }

        return SyncState(
            lineIndex = lineIdx,
            currentLine = currentLine,
            wordIndex = wordIdx,
            currentWord = currentWord,
            syllableIndex = syllableIdx,
            currentSyllable = currentSyllable,
            nextLine = nextLine,
            previousLine = prevLine
        )
    }

    private fun findLineIndex(lines: List<LyricLine>, positionMs: Long): Int {
        var low = 0
        var high = lines.size - 1
        var bestIndex = -1

        while (low <= high) {
            val mid = (low + high) ushr 1
            val line = lines[mid]

            if (positionMs in line.startMs until line.endMs) {
                return mid
            } else if (positionMs < line.startMs) {
                high = mid - 1
            } else {
                bestIndex = mid
                low = mid + 1
            }
        }
        return bestIndex
    }

    private fun findWordIndex(words: List<WordTiming>, positionMs: Long): Int {
        for (i in words.indices) {
            val w = words[i]
            if (positionMs in w.startMs until w.endMs) {
                return i
            }
        }
        return -1
    }

    private fun findSyllableIndex(syllables: List<SyllableTiming>, positionMs: Long): Int {
        for (i in syllables.indices) {
            val s = syllables[i]
            if (positionMs in s.startMs until s.endMs) {
                return i
            }
        }
        return -1
    }
}
