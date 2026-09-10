package live.lyrica.app.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import live.lyrica.app.core.logging.LyricaLogger
import live.lyrica.app.core.model.LyricsDocument
import live.lyrica.app.core.model.TrackQuery
import live.lyrica.app.engine.LyricsSyncEngine
import live.lyrica.app.engine.SyncState
import live.lyrica.app.mediasession.LyricaMediaSessionListenerService
import live.lyrica.app.metadata.HdCoverArtResolver
import live.lyrica.app.metadata.MetadataResolver
import live.lyrica.app.notification.LyricsNotificationManager
import live.lyrica.app.provider.impl.LrcLibProvider
import live.lyrica.app.provider.impl.LrcMuxProvider
import live.lyrica.app.provider.impl.SpotifyLyricsProvider
import live.lyrica.app.provider.router.LyricsCache
import live.lyrica.app.provider.router.ProviderRouter
import live.lyrica.app.security.SecureTokenStorage
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * LyricaForegroundService — central coordinator of Lyrica Live.
 *
 * Responsibilities:
 *  1. Start as a foreground service (displays dark glassmorphism live lyrics notification)
 *  2. Obtain MediaSession access via [LyricaMediaSessionListenerService]
 *  3. Track playback state and accurate position
 *  4. Resolve lyrics via [ProviderRouter] and HD artwork via [HdCoverArtResolver]
 *  5. Drive [LyricsSyncEngine] at 300ms ticks
 *  6. Update [LyricsNotificationManager] with multi-line lyrics and album art
 *  7. Enforce Kill Switch on app removed from RAM/Recents
 */
class LyricaForegroundService : Service() {

    companion object {
        const val ACTION_SEEK = "live.lyrica.app.ACTION_SEEK"
        const val ACTION_TOGGLE_PLAY = "live.lyrica.app.ACTION_TOGGLE_PLAY"
        const val ACTION_STOP = "live.lyrica.app.ACTION_STOP"
        const val EXTRA_SEEK_POSITION = "seek_position"

        // ── Observable state (shared with UI) ─────────────────────────────
        private val _currentQueryFlow  = MutableStateFlow<TrackQuery?>(null)
        val currentQueryFlow = _currentQueryFlow.asStateFlow()

        private val _currentLyricsFlow = MutableStateFlow<LyricsDocument?>(null)
        val currentLyricsFlow = _currentLyricsFlow.asStateFlow()

        private val _currentAlbumArtFlow = MutableStateFlow<Bitmap?>(null)
        val currentAlbumArtFlow = _currentAlbumArtFlow.asStateFlow()

        private val _syncStateFlow = MutableStateFlow(SyncState())
        val syncStateFlow = _syncStateFlow.asStateFlow()

        private val _isPlayingFlow = MutableStateFlow(false)
        val isPlayingFlow = _isPlayingFlow.asStateFlow()

        private val _positionMsFlow = MutableStateFlow(0L)
        val positionMsFlow = _positionMsFlow.asStateFlow()

        private val _isServiceRunningFlow = MutableStateFlow(false)
        val isServiceRunningFlow = _isServiceRunningFlow.asStateFlow()

        private val _searchStatusFlow = MutableStateFlow<String>("idle") // idle/searching/found/not_found
        val searchStatusFlow = _searchStatusFlow.asStateFlow()

        var instance: LyricaForegroundService? = null
            private set
    }

    private val TAG = "LyricaForegroundSvc"

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var fetchJob: Job? = null
    private var artJob: Job? = null

    private lateinit var notifManager: LyricsNotificationManager
    private lateinit var router: ProviderRouter
    private lateinit var coverArtResolver: HdCoverArtResolver
    private lateinit var storage: SecureTokenStorage

    val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(8, TimeUnit.SECONDS)
            .build()
    }

    // ── MediaSession plumbing ──────────────────────────────────────────────
    private var activeController: MediaController? = null
    private var lastTrackKey: String? = null
    private var isPlaying = false
    private var lastPositionMs = 0L

    private val handler = Handler(Looper.getMainLooper())
    private val tickRunnable = object : Runnable {
        override fun run() {
            if (isPlaying && activeController != null) {
                val pos = estimatePosition()
                _positionMsFlow.value = pos

                val doc = _currentLyricsFlow.value
                val query = _currentQueryFlow.value
                val art = _currentAlbumArtFlow.value
                if (doc != null && query != null) {
                    val state = LyricsSyncEngine.resolveSyncState(doc, pos)
                    _syncStateFlow.value = state
                    notifManager.update(query, doc, state, isPlaying, art)
                }
                handler.postDelayed(this, 300L)
            }
        }
    }

    private val mediaCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) {
            metadata?.let { handleMetadata(it) }
        }
        override fun onPlaybackStateChanged(state: PlaybackState?) {
            state?.let { handlePlaybackState(it) }
        }
        override fun onSessionDestroyed() {
            LyricaLogger.i(TAG, "MediaSession destroyed")
            activeController?.unregisterCallback(this)
            activeController = null
            lastTrackKey = null
            isPlaying = false
            stopTicker()
            resetState()
        }
    }

    // ── Service lifecycle ──────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        instance = this
        _isServiceRunningFlow.value = true

        storage = SecureTokenStorage(this)
        notifManager = LyricsNotificationManager(this)
        val cache = LyricsCache()
        router = ProviderRouter(cache)
        coverArtResolver = HdCoverArtResolver(this, httpClient)

        // Register providers
        registerProviders()

        // Check if there is already an active media session from the listener service
        LyricaMediaSessionListenerService.instance?.requestActiveSessionsRefresh()

        LyricaLogger.i(TAG, "LyricaForegroundService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SEEK -> {
                val pos = intent.getLongExtra(EXTRA_SEEK_POSITION, -1L)
                if (pos >= 0L) {
                    LyricaLogger.d(TAG, "Seek action: ${pos}ms")
                    activeController?.transportControls?.seekTo(pos)
                }
            }
            ACTION_TOGGLE_PLAY -> {
                val state = activeController?.playbackState?.state
                if (state == PlaybackState.STATE_PLAYING) {
                    activeController?.transportControls?.pause()
                } else {
                    activeController?.transportControls?.play()
                }
            }
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                // Called from LyricaMediaSessionListenerService — start foreground
                startForeground(
                    LyricsNotificationManager.NOTIFICATION_ID,
                    notifManager.buildInitialNotification()
                )
            }
        }
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        val killOnClose = storage.getBoolean("kill_switch_on_close", false)
        if (killOnClose) {
            LyricaLogger.i(TAG, "Kill switch activated: App removed from recents/RAM. Stopping Lyrica Live service.")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        _isServiceRunningFlow.value = false
        fetchJob?.cancel()
        artJob?.cancel()
        stopTicker()
        serviceScope.cancel()
        notifManager.cancel()
        activeController?.unregisterCallback(mediaCallback)
        httpClient.dispatcher.executorService.shutdown()
        LyricaLogger.i(TAG, "LyricaForegroundService destroyed")
    }

    // ── Called by LyricaMediaSessionListenerService when sessions change ──

    fun onMediaControllerAvailable(controller: MediaController) {
        if (controller.sessionToken == activeController?.sessionToken) return

        activeController?.unregisterCallback(mediaCallback)
        activeController = controller
        activeController?.registerCallback(mediaCallback)

        LyricaLogger.i(TAG, "Active controller: ${controller.packageName}")

        controller.metadata?.let { handleMetadata(it) }
        controller.playbackState?.let { handlePlaybackState(it) }
    }

    fun onNoMediaController() {
        activeController?.unregisterCallback(mediaCallback)
        activeController = null
        lastTrackKey = null
        isPlaying = false
        stopTicker()
        resetState()
    }

    // ── Internal handlers ──────────────────────────────────────────────────

    private fun handleMetadata(metadata: MediaMetadata) {
        val rawTitle = metadata.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
        val rawArtist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
        val rawAlbum = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM)
        val durationMs = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION)
        val pkg = activeController?.packageName

        val query = MetadataResolver.resolve(rawTitle, rawArtist, rawAlbum, durationMs, pkg)

        if (!MetadataResolver.isQueryable(query)) {
            LyricaLogger.d(TAG, "Track not queryable: '$rawArtist - $rawTitle'")
            return
        }

        // Only re-fetch if track identity changed
        if (query.cacheKey == lastTrackKey) return
        lastTrackKey = query.cacheKey

        LyricaLogger.i(TAG, "Track changed: '${query.artist} - ${query.title}' (raw: '$rawArtist - $rawTitle')")

        // Cancel previous in-flight fetches
        fetchJob?.cancel()
        artJob?.cancel()

        _currentQueryFlow.value = query
        _currentLyricsFlow.value = null
        _currentAlbumArtFlow.value = null
        _syncStateFlow.value = SyncState()
        _searchStatusFlow.value = "searching"

        notifManager.update(query, null, SyncState(), isPlaying, null)

        // Asynchronously resolve HD Artwork
        artJob = serviceScope.launch {
            val art = coverArtResolver.resolveArtwork(query, metadata)
            if (isActive && _currentQueryFlow.value?.cacheKey == query.cacheKey) {
                _currentAlbumArtFlow.value = art
                val doc = _currentLyricsFlow.value
                val pos = estimatePosition()
                val syncState = LyricsSyncEngine.resolveSyncState(doc, pos)
                notifManager.update(query, doc, syncState, isPlaying, art)
            }
        }

        // Asynchronously resolve Lyrics
        fetchJob = serviceScope.launch(Dispatchers.IO) {
            val doc = router.resolve(query)
            if (!isActive) return@launch   // cancelled — new track arrived

            _currentLyricsFlow.value = doc
            _searchStatusFlow.value = if (doc != null) "found" else "not_found"

            if (doc != null) {
                LyricaLogger.i(TAG, "Lyrics found: ${doc.lines.size} lines, precision=${doc.syncPrecision} via ${doc.provider}")
            } else {
                LyricaLogger.i(TAG, "No synced lyrics found for '${query.artist} - ${query.title}'")
            }

            // Update notification immediately with original lyrics (zero latency fallback)
            handler.post {
                val pos = estimatePosition()
                val syncState = LyricsSyncEngine.resolveSyncState(doc, pos)
                _syncStateFlow.value = syncState
                notifManager.update(query, doc, syncState, isPlaying, _currentAlbumArtFlow.value)
            }

            // If Auto-AI is enabled and lyrics found, asynchronously fetch/load translation
            if (doc != null && doc.lines.isNotEmpty()) {
                val aiService = live.lyrica.app.ai.AiLyricsService(applicationContext)
                val autoMode = aiService.getAutoAiMode()
                if (autoMode != live.lyrica.app.ai.AutoAiMode.OFF && aiService.hasKeyForSelectedProvider()) {
                    val isRomanize = autoMode == live.lyrica.app.ai.AutoAiMode.AUTO_ROMANIZE
                    val targetLang = aiService.getPreferredLanguage()
                    val aiResult = aiService.translateOrRomanize(doc, targetLang, isRomanize)
                    if (isActive && aiResult.isSuccess && _currentQueryFlow.value?.cacheKey == query.cacheKey) {
                        val aiDoc = aiResult.getOrNull()
                        if (aiDoc != null) {
                            _currentLyricsFlow.value = aiDoc
                            handler.post {
                                val pos = estimatePosition()
                                val syncState = LyricsSyncEngine.resolveSyncState(aiDoc, pos)
                                _syncStateFlow.value = syncState
                                notifManager.update(query, aiDoc, syncState, isPlaying, _currentAlbumArtFlow.value)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun handlePlaybackState(state: PlaybackState) {
        val wasPlaying = isPlaying
        isPlaying = state.state == PlaybackState.STATE_PLAYING
        lastPositionMs = state.position
        _isPlayingFlow.value = isPlaying

        if (isPlaying != wasPlaying) {
            if (isPlaying) startTicker() else stopTicker()
        }

        // Update notification when play state changes
        val query = _currentQueryFlow.value
        val doc = _currentLyricsFlow.value
        val art = _currentAlbumArtFlow.value
        if (query != null) {
            val pos = estimatePosition()
            val syncState = LyricsSyncEngine.resolveSyncState(doc, pos)
            notifManager.update(query, doc, syncState, isPlaying, art)
        }
    }

    private fun registerProviders() {
        val wordLevel = storage.getBoolean("word_level_sync", true)
        live.lyrica.app.provider.ProviderRegistry.register(
            SpotifyLyricsProvider(applicationContext, httpClient)
        )
        live.lyrica.app.provider.ProviderRegistry.register(
            LrcLibProvider(httpClient)
        )
        live.lyrica.app.provider.ProviderRegistry.register(
            LrcMuxProvider(httpClient, wordLevel)
        )
        LyricaLogger.i(TAG, "Registered ${live.lyrica.app.provider.ProviderRegistry.getAll().size} providers")
    }

    private fun estimatePosition(): Long {
        val state = activeController?.playbackState ?: return lastPositionMs
        val offset = storage.getFloat("sync_offset_ms", 0f).toLong()
        val rawPos = if (state.state != PlaybackState.STATE_PLAYING) {
            state.position
        } else {
            val delta = android.os.SystemClock.elapsedRealtime() - state.lastPositionUpdateTime
            if (delta > 0 && state.playbackSpeed > 0f) {
                state.position + (delta * state.playbackSpeed).toLong()
            } else {
                state.position
            }
        }
        return maxOf(0L, rawPos + offset)
    }

    private fun startTicker() {
        handler.removeCallbacks(tickRunnable)
        handler.post(tickRunnable)
    }

    private fun stopTicker() = handler.removeCallbacks(tickRunnable)

    private fun resetState() {
        _currentQueryFlow.value = null
        _currentLyricsFlow.value = null
        _currentAlbumArtFlow.value = null
        _syncStateFlow.value = SyncState()
        _isPlayingFlow.value = false
        _searchStatusFlow.value = "idle"
        notifManager.cancel()
    }

    fun canSeek(): Boolean {
        val actions = activeController?.playbackState?.actions ?: 0L
        return (actions and PlaybackState.ACTION_SEEK_TO) != 0L
    }

    fun setManualLyrics(query: TrackQuery, doc: LyricsDocument) {
        _currentQueryFlow.value = query
        _currentLyricsFlow.value = doc
        _searchStatusFlow.value = "found"
        val pos = estimatePosition()
        val syncState = LyricsSyncEngine.resolveSyncState(doc, pos)
        _syncStateFlow.value = syncState
        notifManager.update(query, doc, syncState, isPlaying, _currentAlbumArtFlow.value)
    }
}
