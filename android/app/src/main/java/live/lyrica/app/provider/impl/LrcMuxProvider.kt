package live.lyrica.app.provider.impl

import live.lyrica.app.core.logging.LyricaLogger
import live.lyrica.app.core.model.LyricLine
import live.lyrica.app.core.model.LyricWord
import live.lyrica.app.core.model.LyricsDocument
import live.lyrica.app.core.model.SyncPrecision
import live.lyrica.app.core.model.TrackQuery
import live.lyrica.app.provider.LyricsProvider
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * LRCMux provider — https://api.lrcmux.dev
 *
 * Key schema facts (confirmed from live API probes):
 *   - Do NOT pass `sources=` parameter — it causes 400
 *   - lines[].start / .end are already in MILLISECONDS (integers)
 *   - lines[].words[].start / .end are milliseconds too
 *   - level=word requests word-level timing from providers that support it
 *   - level=line requests line-level only (faster, broader coverage)
 *   - meta.level in response confirms what was actually returned
 *   - track.cover.medium = album art URL (ignored per user preference)
 *
 * API endpoint:
 *   GET https://api.lrcmux.dev/get?artist={}&title={}&format=json&level=word
 */
class LrcMuxProvider(
    private val httpClient: OkHttpClient,
    /** Whether to request word-level sync (user preference) */
    private val wordLevel: Boolean = true
) : LyricsProvider {

    override val id = "lrcmux"
    override val displayName = "LRCMux"
    override val priority = 2   // Try after LRCLIB (but parallel in router)

    private val TAG = "LrcMuxProvider"
    private val BASE = "https://api.lrcmux.dev"
    private val UA = "LyricaLive/2.0 (https://github.com/thinkely/Lyrica-live)"

    override suspend fun search(query: TrackQuery): LyricsDocument? {
        val level = if (wordLevel) "word" else "line"

        val url = okhttp3.HttpUrl.Builder()
            .scheme("https")
            .host("api.lrcmux.dev")
            .addPathSegment("get")
            .addQueryParameter("artist", query.artist.ifBlank { query.rawArtist })
            .addQueryParameter("title", query.title)
            .addQueryParameter("format", "json")
            .addQueryParameter("level", level)
            .build()

        LyricaLogger.d(TAG, "GET /get → ${query.artist} - ${query.title} (level=$level)")

        val json = getJson(url.toString()) ?: return null

        val linesArray = json.optJSONArray("lines")
        if (linesArray == null || linesArray.length() == 0) {
            LyricaLogger.d(TAG, "No lines in LRCMux response for ${query.title}")
            return null
        }

        val lines = mutableListOf<LyricLine>()
        var hasWordSync = false

        for (i in 0 until linesArray.length()) {
            val lineObj = linesArray.optJSONObject(i) ?: continue
            val text = lineObj.optString("text", "").trim()
            if (text.isBlank()) continue

            val startMs = lineObj.optLong("start", -1L)
            val endMs = lineObj.optLong("end", -1L)
            if (startMs < 0L) continue

            val resolvedEnd = if (endMs > startMs) endMs
                              else if (i + 1 < linesArray.length()) {
                                  linesArray.optJSONObject(i + 1)?.optLong("start", startMs + 4000L) ?: (startMs + 4000L)
                              } else startMs + 5000L

            // Parse word-level timings
            val words = mutableListOf<LyricWord>()
            val wordsArray = lineObj.optJSONArray("words")
            if (wordsArray != null && wordLevel) {
                for (w in 0 until wordsArray.length()) {
                    val wObj = wordsArray.optJSONObject(w) ?: continue
                    val wText = wObj.optString("text", "").trim()
                    val wStart = wObj.optLong("start", startMs)
                    val wEnd = wObj.optLong("end", resolvedEnd)
                    if (wText.isNotBlank()) {
                        words.add(LyricWord(wText, wStart, wEnd))
                        hasWordSync = true
                    }
                }
            }

            lines.add(
                LyricLine(
                    id = "lrcmux_$i",
                    startMs = startMs,
                    endMs = resolvedEnd,
                    text = text,
                    words = words
                )
            )
        }

        if (lines.isEmpty()) return null

        val trackObj = json.optJSONObject("track")
        val metaObj = json.optJSONObject("meta")
        val returnedLevel = metaObj?.optString("level", "")
        val precision = when {
            hasWordSync && wordLevel -> SyncPrecision.WORD
            lines.isNotEmpty() -> SyncPrecision.LINE
            else -> SyncPrecision.NONE
        }

        val artist = trackObj?.optString("artist", "")?.ifBlank { query.artist } ?: query.artist
        val title = trackObj?.optString("title", "")?.ifBlank { query.title } ?: query.title

        LyricaLogger.i(TAG, "Parsed ${lines.size} lines for '$artist - $title' (precision=$precision, returnedLevel=$returnedLevel)")

        return LyricsDocument(
            provider = id,
            artist = artist,
            title = title,
            lines = lines,
            syncPrecision = precision
        )
    }

    private fun getJson(url: String): JSONObject? {
        return try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", UA)
                .header("Accept", "application/json")
                .get()
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    LyricaLogger.d(TAG, "LRCMux HTTP ${response.code} for $url")
                    return null
                }
                val body = response.body?.string() ?: return null
                JSONObject(body)
            }
        } catch (e: Exception) {
            LyricaLogger.w(TAG, "LRCMux HTTP error: ${e.message}")
            null
        }
    }
}
