package live.lyrica.app.core.mediasession

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.session.MediaSessionManager
import android.service.notification.NotificationListenerService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import live.lyrica.app.core.cache.LocalLyricsCache
import live.lyrica.app.core.engine.LyricsSyncEngine
import live.lyrica.app.core.engine.SyncState
import live.lyrica.app.core.logging.LyricaLogger
import live.lyrica.app.core.model.LyricsDocument
import live.lyrica.app.core.model.NormalizedTrack
import live.lyrica.app.core.notification.LyricsNotificationManager
import live.lyrica.app.core.providers.PythonBridge
import live.lyrica.app.core.providers.ProviderRouter
import live.lyrica.app.security.SecureTokenStorage

/**
 * LyricaMediaSessionListenerService is the privileged Android entry point for
 * NotificationListener and MediaSession access.
 * Manages MediaSessionMonitor lifecycle and coordinates lyrics fetching and synchronization.
 */
class LyricaMediaSessionListenerService : NotificationListenerService(), MediaSessionMonitor.Listener {
    private val TAG = "MediaListenerService"

    companion object {
        var instance: LyricaMediaSessionListenerService? = null
            private set

        private val _currentTrackFlow = MutableStateFlow<NormalizedTrack?>(null)
        val currentTrackFlow = _currentTrackFlow.asStateFlow()

        private val _currentLyricsFlow = MutableStateFlow<LyricsDocument?>(null)
        val currentLyricsFlow = _currentLyricsFlow.asStateFlow()

        private val _syncStateFlow = MutableStateFlow(SyncState())
        val syncStateFlow = _syncStateFlow.asStateFlow()

        private val _playbackPositionFlow = MutableStateFlow(0L)
        val playbackPositionFlow = _playbackPositionFlow.asStateFlow()

        private val _isPlayingFlow = MutableStateFlow(false)
        val isPlayingFlow = _isPlayingFlow.asStateFlow()

        private val _isServiceConnectedFlow = MutableStateFlow(false)
        val isServiceConnectedFlow = _isServiceConnectedFlow.asStateFlow()

        // Lyrics search status emitted to UI
        private val _searchStatusFlow = MutableStateFlow<String?>(null)
        val searchStatusFlow = _searchStatusFlow.asStateFlow()
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** Tracks the currently active lyrics-fetch job so it can be cancelled on track change. */
    private var currentFetchJob: Job? = null

    lateinit var mediaSessionMonitor: MediaSessionMonitor
        private set
    lateinit var lyricsCache: LocalLyricsCache
        private set
    lateinit var secureStorage: SecureTokenStorage
        private set
    lateinit var providerRouter: ProviderRouter
        private set
    lateinit var notificationManager: LyricsNotificationManager
        private set

    private var sessionManager: MediaSessionManager? = null
    private var componentName: ComponentName? = null

    private val sessionsChangedListener = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
        LyricaLogger.d(TAG, "Active sessions changed (count=${controllers?.size ?: 0})")
        controllers?.let { mediaSessionMonitor.updateActiveSessions(it) }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        LyricaLogger.i(TAG, "LyricaMediaSessionListenerService created")

        lyricsCache = LocalLyricsCache(this)
        secureStorage = SecureTokenStorage(this)
        val pythonBridge = PythonBridge(this)
        providerRouter = ProviderRouter(lyricsCache, pythonBridge, secureStorage)
        notificationManager = LyricsNotificationManager(this)

        mediaSessionMonitor = MediaSessionMonitor(this)
        mediaSessionMonitor.addListener(this)
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        LyricaLogger.i(TAG, "NotificationListenerService connected and authorized")
        _isServiceConnectedFlow.value = true

        componentName = ComponentName(this, LyricaMediaSessionListenerService::class.java)
        sessionManager = getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager

        try {
            sessionManager?.addOnActiveSessionsChangedListener(sessionsChangedListener, componentName)
            val controllers = sessionManager?.getActiveSessions(componentName)
            controllers?.let { mediaSessionMonitor.updateActiveSessions(it) }
        } catch (e: SecurityException) {
            LyricaLogger.e(TAG, "SecurityException accessing MediaSessions: ${e.message}")
        }
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        LyricaLogger.w(TAG, "NotificationListenerService disconnected")
        _isServiceConnectedFlow.value = false
        try {
            sessionManager?.removeOnActiveSessionsChangedListener(sessionsChangedListener)
        } catch (_: Exception) {}
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent != null) {
            val action = intent.action
            if (action == "live.lyrica.app.ACTION_SEEK") {
                val seekPositionMs = intent.getLongExtra("seek_position", -1L)
                if (seekPositionMs >= 0L) {
                    mediaSessionMonitor.seekTo(seekPositionMs)
                }
            } else if (action == "live.lyrica.app.ACTION_TOGGLE_PLAY") {
                mediaSessionMonitor.togglePlayPause()
            }
        }
        return START_STICKY
    }

    override fun onTrackChanged(track: NormalizedTrack) {
        // ── Cancel any in-flight fetch from the previous song ──────────────
        currentFetchJob?.cancel()
        LyricaLogger.i(TAG, "Track changed → cancelling any previous fetch. New track: '${track.rawArtist} - ${track.rawTitle}'")

        _currentTrackFlow.value = track
        _currentLyricsFlow.value = null
        _syncStateFlow.value = SyncState()
        _searchStatusFlow.value = "searching"

        currentFetchJob = serviceScope.launch {
            val wordLevel = secureStorage.getBoolean("word_level_sync", true)
            val lyrics = providerRouter.resolveLyrics(track, wordLevel = wordLevel)

            // Guard: if this job was cancelled (new track arrived), do not update state
            if (!isActive) {
                LyricaLogger.d(TAG, "Fetch job cancelled — discarding stale result for '${track.rawTitle}'")
                return@launch
            }

            _currentLyricsFlow.value = lyrics
            _searchStatusFlow.value = if (lyrics != null) "found" else "not_found"

            // Show initial notification
            notificationManager.showOrUpdateLyricsNotification(
                track = track,
                lyricsDoc = lyrics,
                syncState = SyncState(),
                isPlaying = _isPlayingFlow.value,
                canSeek = mediaSessionMonitor.canSeek()
            )
        }
    }

    override fun onPlaybackPositionTick(positionMs: Long, isPlaying: Boolean) {
        _playbackPositionFlow.value = positionMs
        _isPlayingFlow.value = isPlaying

        val doc = _currentLyricsFlow.value
        val track = _currentTrackFlow.value
        if (doc != null && track != null) {
            val syncState = LyricsSyncEngine.resolveSyncState(doc, positionMs)
            _syncStateFlow.value = syncState

            notificationManager.showOrUpdateLyricsNotification(
                track = track,
                lyricsDoc = doc,
                syncState = syncState,
                isPlaying = isPlaying,
                canSeek = mediaSessionMonitor.canSeek()
            )
        }
    }

    override fun onPlaybackStateChanged(isPlaying: Boolean) {
        _isPlayingFlow.value = isPlaying
        val track = _currentTrackFlow.value
        val doc = _currentLyricsFlow.value
        if (track != null) {
            notificationManager.showOrUpdateLyricsNotification(
                track = track,
                lyricsDoc = doc,
                syncState = _syncStateFlow.value,
                isPlaying = isPlaying,
                canSeek = mediaSessionMonitor.canSeek()
            )
        }
    }

    override fun onSessionDisconnected() {
        currentFetchJob?.cancel()
        currentFetchJob = null
        notificationManager.cancelNotification()
        _currentTrackFlow.value = null
        _currentLyricsFlow.value = null
        _syncStateFlow.value = SyncState()
        _isPlayingFlow.value = false
        _searchStatusFlow.value = null
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        _isServiceConnectedFlow.value = false
        currentFetchJob?.cancel()
        mediaSessionMonitor.removeListener(this)
        try {
            sessionManager?.removeOnActiveSessionsChangedListener(sessionsChangedListener)
        } catch (_: Exception) {}
        serviceScope.cancel()
        LyricaLogger.i(TAG, "LyricaMediaSessionListenerService destroyed")
    }
}
