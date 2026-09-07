package live.lyrica.app

import live.lyrica.app.core.model.SyncPrecision
import live.lyrica.app.parser.LrcParser
import org.junit.Assert.*
import org.junit.Test

class LrcParserTest {

    @Test
    fun testParseStandardLrc() {
        val lrc = """
            [00:10.50] Yeah, yeah
            [00:15.20] I've been tryna call
            [00:20.00] I've been on my own for long enough
        """.trimIndent()

        val lines = LrcParser.parse(lrc, trackDurationMs = 30000L)
        assertEquals(3, lines.size)

        assertEquals(10500L, lines[0].startMs)
        assertEquals(15200L, lines[0].endMs)
        assertEquals("Yeah, yeah", lines[0].text)

        assertEquals(15200L, lines[1].startMs)
        assertEquals(20000L, lines[1].endMs)
        assertEquals("I've been tryna call", lines[1].text)

        assertEquals(20000L, lines[2].startMs)
        assertEquals(30000L, lines[2].endMs)
        assertEquals("I've been on my own for long enough", lines[2].text)

        assertEquals(SyncPrecision.LINE, LrcParser.detectPrecision(lines))
    }

    @Test
    fun testParseEmptyOrInvalid() {
        val empty = LrcParser.parse("")
        assertTrue(empty.isEmpty())
        assertEquals(SyncPrecision.NONE, LrcParser.detectPrecision(empty))

        val invalid = LrcParser.parse("Just some random text without timestamps")
        assertTrue(invalid.isEmpty())
    }
}
