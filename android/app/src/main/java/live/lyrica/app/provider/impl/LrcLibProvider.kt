package live.lyrica.app.provider.impl

import live.lyrica.app.core.logging.LyricaLogger
import live.lyrica.app.core.model.LyricsDocument
import live.lyrica.app.core.model.SyncPrecision
import live.lyrica.app.core.model.TrackQuery
import live.lyrica.app.parser.LrcParser
import live.lyrica.app.provider.LyricsProvider
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * LRCLIB provider — https://lrclib.net
 *
 * Fetch strategy (two-step):
 *   Step 1: GET /api/get with exact artist+title+duration
 *           → Fast path, usually 200ms. Returns 404 if not found.
 *   Step 2: GET /api/search?track_name=&artist_name=
 *           → Pick best duration-matched result from array.
 *
 * Key schema facts (confirmed from live API):
 *   - syncedLyrics: raw LRC string ("[mm:ss.xx] text\n...")
 *   - duration: SECONDS (Double), not milliseconds
 *   - id field: integer track ID
 *   - 404 response body: {"code": 404, "name": "TrackNotFound", ...}
 *
 * Only synced lyrics are returned. Tracks without syncedLyrics are skipped.
 */
class LrcLibProvider(private val httpClient: OkHttpClient) : LyricsProvider {

    override val id = "lrclib"
    override val displayName = "LRCLIB"
    override val priority = 1

    private val TAG = "LrcLibProvider"
    private val BASE = "https://lrclib.net/api"
    private val UA = "LyricaLive/2.0 (https://github.com/thinkely/Lyrica-live)"

    override suspend fun search(query: TrackQuery): LyricsDocument? {
        // ── Step 1: Exact GET ──────────────────────────────────────────────
        val exactResult = tryExactGet(query)
        if (exactResult != null) return exactResult

        // ── Step 2: Search fallback ────────────────────────────────────────
        val searchResult = trySearch(query)
        if (searchResult != null) return searchResult

        // ── Step 3: Retry with raw (uncleaned) values if they differ ───────
        if (query.rawTitle != query.title || query.rawArtist != query.artist) {
            val rawQuery = query.copy(artist = query.rawArtist, title = query.rawTitle)
            return trySearch(rawQuery)
        }

        return null
    }

    private fun tryExactGet(query: TrackQuery): LyricsDocument? {
        if (query.title.isBlank()) return null

        val url = "$BASE/get".toHttpUrl().newBuilder()
            .addQueryParameter("artist_name", query.artist.ifBlank { query.rawArtist })
            .addQueryParameter("track_name", query.title)
            .apply {
                if (query.album != null) addQueryParameter("album_name", query.album)
                if (query.durationSec > 0) addQueryParameter("duration", query.durationSec.toString())
            }
            .build()

        LyricaLogger.d(TAG, "GET /api/get → ${query.artist} - ${query.title}")

        val json = getJson(url.toString()) ?: return null
        return parseSingleResult(json, query.durationMs)
    }

    private fun trySearch(query: TrackQuery): LyricsDocument? {
        if (query.title.isBlank()) return null

        val url = "$BASE/search".toHttpUrl().newBuilder()
            .addQueryParameter("track_name", query.title)
            .apply {
                if (query.artist.isNotBlank()) addQueryParameter("artist_name", query.artist)
            }
            .build()

        LyricaLogger.d(TAG, "GET /api/search → ${query.artist} - ${query.title}")

        val body = getJson(url.toString()) ?: return null

        // /api/search returns a JSON array
        val array: JSONArray = when {
            body.has("error") -> return null
            else -> try {
                // The search endpoint returns a raw JSON array (not an object)
                JSONArray(body.toString())
            } catch (_: Exception) {
                return null
            }
        }

        // Pick best candidate: prefer duration match; fall back to first result
        val best = pickBestFromArray(array, query.durationMs) ?: return null
        return parseSingleResult(best, query.durationMs)
    }

    private fun pickBestFromArray(array: JSONArray, durationMs: Long): JSONObject? {
        if (array.length() == 0) return null

        // Filter to only results that have syncedLyrics
        val withSync = (0 until array.length())
            .mapNotNull { array.optJSONObject(it) }
            .filter { !it.isNull("syncedLyrics") && it.optString("syncedLyrics").isNotBlank() }

        if (withSync.isEmpty()) return null
        if (durationMs <= 0L) return withSync.first()

        // Score by duration closeness (LRCLIB duration is in seconds)
        return withSync.minByOrNull { result ->
            val resultDurSec = result.optDouble("duration", 0.0)
            val resultDurMs = (resultDurSec * 1000).toLong()
            Math.abs(resultDurMs - durationMs)
        }
    }

    private fun parseSingleResult(json: JSONObject, queryDurationMs: Long): LyricsDocument? {
        // Check for error responses
        if (json.has("code") && json.optInt("code") == 404) return null
        if (json.isNull("syncedLyrics") || json.optString("syncedLyrics").isBlank()) return null

        val lrcString = json.optString("syncedLyrics")
        val trackDurSec = json.optDouble("duration", 0.0)
        val trackDurMs = if (trackDurSec > 0) (trackDurSec * 1000).toLong() else queryDurationMs

        val lines = LrcParser.parse(lrcString, trackDurMs)
        if (lines.isEmpty()) return null

        val artist = json.optString("artistName", "").ifBlank { json.optString("artist", "") }
        val title = json.optString("trackName", "").ifBlank { json.optString("title", "") }

        LyricaLogger.i(TAG, "Parsed ${lines.size} lines for '$artist - $title' (dur=${trackDurMs}ms)")

        return LyricsDocument(
            provider = id,
            artist = artist,
            title = title,
            lines = lines,
            syncPrecision = SyncPrecision.LINE,
            isInstrumental = json.optBoolean("instrumental", false)
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
                val body = response.body?.string() ?: return null
                if (!response.isSuccessful && response.code == 404) {
                    // 404 has a JSON body too, but it's an error
                    return JSONObject(body)   // caller checks for "code": 404
                }
                if (!response.isSuccessful) return null
                JSONObject(body)
            }
        } catch (e: Exception) {
            LyricaLogger.w(TAG, "HTTP error: ${e.message}")
            null
        }
    }
}
