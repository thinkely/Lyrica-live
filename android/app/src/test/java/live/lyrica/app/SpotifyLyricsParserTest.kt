package live.lyrica.app

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SpotifyLyricsParserTest {

    @Test
    fun testSpotifyColorLyricsJsonParsing() {
        val jsonStr = """
            {
                "lyrics": {
                    "syncType": "LINE_SYNCED",
                    "lines": [
                        {
                            "startTimeMs": "5000",
                            "words": "Tere naal rehna chaunda aan",
                            "syllables": []
                        },
                        {
                            "startTimeMs": "9000",
                            "words": "Chakda ni phone kehnde, ji",
                            "syllables": []
                        },
                        {
                            "startTimeMs": "14000",
                            "words": "Gallan teriyan yaad aundiyan",
                            "syllables": []
                        }
                    ]
                }
            }
        """.trimIndent()

        val json = JSONObject(jsonStr)
        val lyricsObj = json.getJSONObject("lyrics")
        val syncType = lyricsObj.getString("syncType")
        val rawLines = lyricsObj.getJSONArray("lines")

        assertEquals("LINE_SYNCED", syncType)
        assertEquals(3, rawLines.length())

        val line1 = rawLines.getJSONObject(1)
        assertEquals(9000L, line1.getLong("startTimeMs"))
        assertEquals("Chakda ni phone kehnde, ji", line1.getString("words"))
    }
}
