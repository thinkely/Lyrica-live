package live.lyrica.app.core.mediasession

import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import live.lyrica.app.core.logging.LyricaLogger
import live.lyrica.app.core.model.NormalizedTrack
import live.lyrica.app.core.resolver.TrackIdentityResolver

/**
 * Monitors active Android MediaSessions, tracks playback state/position, and controls seeking.
 */
class MediaSessionMonitor(private val context: Context) {
    private val TAG = "MediaSessionMonitor"

    interface Listener {
        fun onTrackChanged(track: NormalizedTrack)
        fun onPlaybackPositionTick(positionMs: Long, isPlaying: Boolean)
        fun onPlaybackStateChanged(isPlaying: Boolean)
        fun onSessionDisconnected()
    }

    private val listeners = mutableListOf<Listener>()
    private var activeController: MediaController? = null

    /**
     * The stable key of the last track reported to listeners.
     * Cleared to null when a session is destroyed so that the same track resuming
     * in a new session is correctly treated as a new track event.
     */
    private var lastTrackKey: String? = null
    private var isPlaying: Boolean = false
    private var lastReportedPositionMs: Long = 0L

    private val handler = Handler(Looper.getMainLooper())
    private val tickerRunnable = object : Runnable {
        override fun run() {
            if (isPlaying && activeController != null) {
                val currentPos = estimateCurrentPosition()
                listeners.forEach { it.onPlaybackPositionTick(currentPos, isPlaying) }
                handler.postDelayed(this, 300L) // 300ms smooth ticker
            }
        }
    }

    fun addListener(listener: Listener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener)
        }
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    private val callback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) {
            super.onMetadataChanged(metadata)
            handleMetadataChange(metadata)
        }

        override fun onPlaybackStateChanged(state: PlaybackState?) {
            super.onPlaybackStateChanged(state)
            handlePlaybackStateChange(state)
        }

        override fun onSessionDestroyed() {
            super.onSessionDestroyed()
            LyricaLogger.i(TAG, "Active MediaSession destroyed: ${activeController?.packageName}")
            activeController?.unregisterCallback(this)
            activeController = null
            // ── Clear lastTrackKey so the same track resuming in a new session
            //    is correctly treated as a fresh track-change event. ──────────
            lastTrackKey = null
            isPlaying = false
            stopTicker()
            listeners.forEach { it.onSessionDisconnected() }
        }
    }

    fun updateActiveSessions(controllers: List<MediaController>) {
        // Pick the first actively playing session, or first valid session
        val candidate = controllers.firstOrNull {
            it.playbackState?.state == PlaybackState.STATE_PLAYING
        } ?: controllers.firstOrNull()

        if (candidate != null && candidate.sessionToken != activeController?.sessionToken) {
            switchController(candidate)
        } else if (candidate == null && activeController != null) {
            activeController?.unregisterCallback(callback)
            activeController = null
            lastTrackKey = null
            isPlaying = false
            stopTicker()
            listeners.forEach { it.onSessionDisconnected() }
        }
    }

    private fun switchController(newController: MediaController) {
        activeController?.unregisterCallback(callback)
        activeController = newController
        activeController?.registerCallback(callback)

        LyricaLogger.i(TAG, "Switched active MediaController to: ${newController.packageName}")

        handleMetadataChange(newController.metadata)
        handlePlaybackStateChange(newController.playbackState)
    }

    private fun handleMetadataChange(metadata: MediaMetadata?) {
        if (metadata == null) return

        val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
        val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: metadata.getString(MediaMetadata.METADATA_KEY_AUTHOR)
        val album = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM)
        val duration = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION)
        val mediaId = metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID)
        val pkg = activeController?.packageName

        val track = TrackIdentityResolver.resolve(
            rawTitle = title,
            rawArtist = artist,
            rawAlbum = album,
            durationMs = duration,
            mediaId = mediaId,
            packageName = pkg
        )

        // Only notify when track identity meaningfully changes (stableKey)
        if (track.stableKey != lastTrackKey) {
            lastTrackKey = track.stableKey
            LyricaLogger.i(TAG, "Track changed: '${track.normalizedArtist} - ${track.normalizedTitle}' (key=${track.stableKey})")
            listeners.forEach { it.onTrackChanged(track) }
        }
    }

    private fun handlePlaybackStateChange(state: PlaybackState?) {
        if (state == null) return

        val wasPlaying = isPlaying
        isPlaying = state.state == PlaybackState.STATE_PLAYING
        lastReportedPositionMs = state.position

        if (isPlaying != wasPlaying) {
            LyricaLogger.d(TAG, "Playback state changed: isPlaying=$isPlaying (state=${state.state})")
            listeners.forEach { it.onPlaybackStateChanged(isPlaying) }
            if (isPlaying) {
                startTicker()
            } else {
                stopTicker()
                // Emit one last position update on pause/stop
                listeners.forEach { it.onPlaybackPositionTick(state.position, isPlaying) }
            }
        }
    }

    private fun startTicker() {
        handler.removeCallbacks(tickerRunnable)
        handler.post(tickerRunnable)
    }

    private fun stopTicker() {
        handler.removeCallbacks(tickerRunnable)
    }

    fun estimateCurrentPosition(): Long {
        val state = activeController?.playbackState ?: return lastReportedPositionMs
        if (state.state != PlaybackState.STATE_PLAYING) {
            return state.position
        }
        val timeDelta = System.currentTimeMillis() - state.lastPositionUpdateTime
        val speed = state.playbackSpeed
        return state.position + (timeDelta * speed).toLong()
    }

    fun canSeek(): Boolean {
        val actions = activeController?.playbackState?.actions ?: 0L
        return (actions and PlaybackState.ACTION_SEEK_TO) != 0L
    }

    fun seekTo(positionMs: Long) {
        if (canSeek()) {
            LyricaLogger.i(TAG, "Seeking active MediaController to ${positionMs}ms")
            activeController?.transportControls?.seekTo(positionMs)
        } else {
            LyricaLogger.d(TAG, "Seek requested to ${positionMs}ms but player does not support seeking")
        }
    }

    fun togglePlayPause() {
        val controller = activeController ?: return
        val state = controller.playbackState?.state
        if (state == PlaybackState.STATE_PLAYING) {
            controller.transportControls.pause()
        } else {
            controller.transportControls.play()
        }
    }
}
