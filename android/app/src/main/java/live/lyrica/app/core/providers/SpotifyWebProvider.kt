package live.lyrica.app.core.providers

import live.lyrica.app.core.logging.LyricaLogger
import live.lyrica.app.core.model.LyricsDocument

/**
 * Experimental isolated Spotify Web session lyrics provider.
 * Does NOT scrape entire cookie jars or bypass DRM/anti-bot systems;
 * interacts only within an isolated user web session if enabled.
 */
class SpotifyWebProvider {
    private val TAG = "SpotifyWebProvider"

    var isEnabled: Boolean = false
    private var accessToken: String? = null

    fun updateSessionToken(token: String?) {
        accessToken = token
        LyricaLogger.i(TAG, "Spotify Web session token updated")
    }

    val isEligible: Boolean
        get() = isEnabled && !accessToken.isNullOrBlank()

    suspend fun fetchLyrics(artist: String, song: String): LyricsDocument? {
        if (!isEligible) return null
        LyricaLogger.d(TAG, "Attempting Spotify Web lyrics fetch for '$artist - $song'")
        // Spotify Web API extraction contract
        return null
    }
}
