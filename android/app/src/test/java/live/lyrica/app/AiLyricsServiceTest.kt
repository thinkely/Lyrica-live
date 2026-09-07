package live.lyrica.app

import live.lyrica.app.ai.AiLanguages
import live.lyrica.app.ai.AiProvider
import live.lyrica.app.core.model.LyricLine
import live.lyrica.app.core.model.LyricsDocument
import live.lyrica.app.core.model.SyncPrecision
import org.junit.Assert.*
import org.junit.Test

class AiLyricsServiceTest {

    @Test
    fun testProviderDefaultsAndPresets() {
        val groq = AiProvider.GROQ
        assertEquals("openai/gpt-oss-120b", groq.defaultModel)
        assertTrue(groq.popularModels.contains("openai/gpt-oss-120b"))

        val openRouter = AiProvider.OPENROUTER
        assertEquals("openrouter/free", openRouter.defaultModel)
        assertTrue(openRouter.popularModels.contains("openrouter/free"))
    }

    @Test
    fun testAiLanguagesList() {
        assertTrue(AiLanguages.POPULAR_LANGUAGES.contains("English"))
        assertTrue(AiLanguages.POPULAR_LANGUAGES.contains("Hindi"))
        assertTrue(AiLanguages.POPULAR_LANGUAGES.contains("Spanish"))
        assertTrue(AiLanguages.POPULAR_LANGUAGES.contains("Japanese"))
        assertTrue(AiLanguages.POPULAR_LANGUAGES.contains("Punjabi"))
    }

    @Test
    fun testLinePreservationStructure() {
        val originalDoc = LyricsDocument(
            provider = "lrclib",
            artist = "Diljit Dosanjh",
            title = "G.O.A.T.",
            syncPrecision = SyncPrecision.LINE,
            lines = listOf(
                LyricLine(id = "1", startMs = 10000L, endMs = 15000L, text = "Ni tu ta ferr jatt da pyar goriye"),
                LyricLine(id = "2", startMs = 15000L, endMs = 20000L, text = "Oh chal dass hi dinna je gal tauri ni")
            )
        )

        assertEquals(2, originalDoc.lines.size)
        assertEquals(10000L, originalDoc.lines[0].startMs)
        assertEquals(15000L, originalDoc.lines[0].endMs)
    }
}
