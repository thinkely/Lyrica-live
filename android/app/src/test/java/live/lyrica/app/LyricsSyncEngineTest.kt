package live.lyrica.app

import live.lyrica.app.core.engine.LyricsSyncEngine
import live.lyrica.app.core.model.LyricLine
import live.lyrica.app.core.model.LyricsDocument
import live.lyrica.app.core.model.SyncPrecision
import live.lyrica.app.core.model.SyllableTiming
import live.lyrica.app.core.model.WordTiming
import org.junit.Assert.*
import org.junit.Test

class LyricsSyncEngineTest {

    private val sampleDoc = LyricsDocument(
        provider = "lrclib",
        artist = "The Weeknd",
        title = "Blinding Lights",
        syncPrecision = SyncPrecision.LINE,
        lines = listOf(
            LyricLine(id = "l1", startMs = 10000L, endMs = 15000L, text = "I've been tryna call"),
            LyricLine(
                id = "l2",
                startMs = 15000L,
                endMs = 20000L,
                text = "I've been on my own",
                words = listOf(
                    WordTiming(startMs = 15000L, endMs = 16000L, text = "I've"),
                    WordTiming(startMs = 16000L, endMs = 17500L, text = "been"),
                    WordTiming(
                        startMs = 17500L,
                        endMs = 20000L,
                        text = "alone",
                        syllables = listOf(
                            SyllableTiming(startMs = 17500L, endMs = 18500L, text = "a"),
                            SyllableTiming(startMs = 18500L, endMs = 20000L, text = "lone")
                        )
                    )
                )
            ),
            LyricLine(id = "l3", startMs = 20000L, endMs = 25000L, text = "Maybe you can show me how to love")
        )
    )

    @Test
    fun testSyncPositionBeforeFirstLine() {
        val state = LyricsSyncEngine.resolveSyncState(sampleDoc, 5000L)
        assertEquals(-1, state.lineIndex)
        assertNull(state.currentLine)
        assertEquals("I've been tryna call", state.nextLine?.text)
    }

    @Test
    fun testSyncPositionInFirstLine() {
        val state = LyricsSyncEngine.resolveSyncState(sampleDoc, 12000L)
        assertEquals(0, state.lineIndex)
        assertEquals("I've been tryna call", state.currentLine?.text)
        assertEquals("I've been on my own", state.nextLine?.text)
    }

    @Test
    fun testSyncPositionWordAndSyllableResolution() {
        val state = LyricsSyncEngine.resolveSyncState(sampleDoc, 18000L)
        assertEquals(1, state.lineIndex)
        assertEquals(2, state.wordIndex)
        assertEquals("alone", state.currentWord?.text)
        assertEquals(0, state.syllableIndex)
        assertEquals("a", state.currentSyllable?.text)
    }

    @Test
    fun testSeekingJumpForwardAndBackward() {
        // Jump forward to line 3
        val stateAfter = LyricsSyncEngine.resolveSyncState(sampleDoc, 22000L)
        assertEquals(2, stateAfter.lineIndex)
        assertEquals("Maybe you can show me how to love", stateAfter.currentLine?.text)

        // Jump backward to line 1
        val stateBefore = LyricsSyncEngine.resolveSyncState(sampleDoc, 11000L)
        assertEquals(0, stateBefore.lineIndex)
        assertEquals("I've been tryna call", stateBefore.currentLine?.text)
    }
}
