package live.lyrica.app.provider.impl

import android.content.Context
import live.lyrica.app.core.logging.LyricaLogger
import live.lyrica.app.core.model.LyricLine
import live.lyrica.app.core.model.LyricWord
import live.lyrica.app.core.model.LyricsDocument
import live.lyrica.app.core.model.SyncPrecision
import live.lyrica.app.core.model.TrackQuery
import live.lyrica.app.provider.LyricsProvider
import live.lyrica.app.security.SecureTokenStorage
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder

/**
 * Spotify Lyrics Provider — extracts rich synchronized lyrics directly from
 * Spotify's Color Lyrics service using authenticated user session cookies (sp_dc).
 */
class SpotifyLyricsProvider(
    private val context: Context,
    private val httpClient: OkHttpClient
) : LyricsProvider {

    override val id: String = "spotify"
    override val displayName: String = "Spotify"
    override val priority: Int = 85 // high priority when authenticated

    private val TAG = "SpotifyProvider"
    private val storage = SecureTokenStorage(context)

    companion object {
        const val KEY_SP_DC = "spotify_sp_dc"
        const val KEY_ACCESS_TOKEN = "spotify_access_token"
        const val KEY_TOKEN_EXPIRES = "spotify_token_expires_at"
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    }

    override fun isAvailable(): Boolean {
        val spDc = storage.getString(KEY_SP_DC)
        return !spDc.isNullOrBlank()
    }

    override suspend fun search(query: TrackQuery): LyricsDocument? {
        val spDc = storage.getString(KEY_SP_DC) ?: return null

        try {
            val token = getOrRefreshAccessToken(spDc) ?: return null

            // 1. Search for Spotify Track ID
            val trackId = searchTrackId(query, token) ?: return null
            LyricaLogger.d(TAG, "Resolved Spotify Track ID: $trackId for '${query.title}'")

            // 2. Query Color Lyrics endpoint
            return fetchColorLyrics(trackId, query, token)
        } catch (e: Exception) {
            LyricaLogger.w(TAG, "Spotify lyrics fetch error for '${query.title}': ${e.message}")
            return null
        }
    }

    private fun getOrRefreshAccessToken(spDc: String): String? {
        val cachedToken = storage.getString(KEY_ACCESS_TOKEN)
        val expiresAt = storage.getLong(KEY_TOKEN_EXPIRES, 0L)
        val now = System.currentTimeMillis()

        if (!cachedToken.isNullOrBlank() && expiresAt > now + 60_000L) {
            return cachedToken
        }

        // Fetch fresh token using sp_dc cookie
        val url = "https://open.spotify.com/get_access_token?reason=transport&productType=web_player"
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Cookie", "sp_dc=$spDc")
            .header("App-Platform", "WebPlayer")
            .build()

        return try {
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    LyricaLogger.w(TAG, "Failed to get Spotify access token, HTTP ${resp.code}")
                    return null
                }
                val body = resp.body?.string() ?: return null
                val json = JSONObject(body)
                val token = json.optString("accessToken")
                val expMs = json.optLong("accessTokenExpirationTimestampMs", now + 3600_000L)

                if (token.isNotBlank()) {
                    storage.putString(KEY_ACCESS_TOKEN, token)
                    storage.putLong(KEY_TOKEN_EXPIRES, expMs)
                    token
                } else null
            }
        } catch (e: Exception) {
            LyricaLogger.e(TAG, "Error refreshing Spotify token: ${e.message}", e)
            null
        }
    }

    private fun searchTrackId(query: TrackQuery, token: String): String? {
        val q = URLEncoder.encode("${query.artist} ${query.title}", "UTF-8")
        val url = "https://api.spotify.com/v1/search?type=track&limit=1&q=$q"
        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .header("User-Agent", USER_AGENT)
            .build()

        return try {
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val body = resp.body?.string() ?: return null
                val json = JSONObject(body)
                val tracks = json.optJSONObject("tracks")?.optJSONArray("items") ?: return null
                if (tracks.length() == 0) return null
                tracks.getJSONObject(0).optString("id").ifBlank { null }
            }
        } catch (e: Exception) {
            LyricaLogger.d(TAG, "Spotify track search failed: ${e.message}")
            null
        }
    }

    private fun fetchColorLyrics(trackId: String, query: TrackQuery, token: String): LyricsDocument? {
        val url = "https://spclient.wg.spotify.com/color-lyrics/v2/track/$trackId?format=json&vocalRemoval=false&market=from_token"
        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .header("User-Agent", USER_AGENT)
            .header("App-Platform", "WebPlayer")
            .build()

        return try {
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    LyricaLogger.d(TAG, "Color lyrics returned HTTP ${resp.code} for track $trackId")
                    return null
                }
                val body = resp.body?.string() ?: return null
                val json = JSONObject(body)
                val lyricsObj = json.optJSONObject("lyrics") ?: return null
                val syncType = lyricsObj.optString("syncType") // LINE_SYNCED, SYLLABLE_SYNCED, UNSYNCED
                val rawLines = lyricsObj.optJSONArray("lines") ?: return null

                val lines = mutableListOf<LyricLine>()
                for (i in 0 until rawLines.length()) {
                    val lineObj = rawLines.getJSONObject(i)
                    val startMs = lineObj.optLong("startTimeMs", 0L)
                    val words = lineObj.optString("words").trim()

                    // Parse syllables/words if available for word-level sync
                    val syllables = lineObj.optJSONArray("syllables")
                    val wordList = mutableListOf<LyricWord>()
                    if (syllables != null && syllables.length() > 0) {
                        for (w in 0 until syllables.length()) {
                            val syl = syllables.getJSONObject(w)
                            val sylStart = syl.optLong("startTimeMs", startMs)
                            val numChars = syl.optInt("numChars", 0)
                            val sylText = syl.optString("text")
                            wordList.add(LyricWord(text = sylText, startMs = sylStart, endMs = sylStart + (numChars * 100L)))
                        }
                    }

                    lines.add(
                        LyricLine(
                            id = "sp_$i",
                            startMs = startMs,
                            endMs = 0L, // will resolve below
                            text = words,
                            words = wordList
                        )
                    )
                }

                if (lines.isEmpty()) return null

                // Compute endMs for each line
                val resolvedLines = lines.mapIndexed { i, line ->
                    val endMs = if (i + 1 < lines.size) lines[i + 1].startMs else (line.startMs + 5000L)
                    line.copy(endMs = endMs)
                }

                val precision = when (syncType) {
                    "SYLLABLE_SYNCED" -> SyncPrecision.WORD
                    "LINE_SYNCED" -> SyncPrecision.LINE
                    else -> if (resolvedLines.any { it.startMs > 0 }) SyncPrecision.LINE else SyncPrecision.NONE
                }

                LyricsDocument(
                    provider = "spotify",
                    artist = query.artist,
                    title = query.title,
                    lines = resolvedLines,
                    syncPrecision = precision
                )
            }
        } catch (e: Exception) {
            LyricaLogger.e(TAG, "Error parsing Spotify lyrics: ${e.message}", e)
            null
        }
    }
}
