package live.lyrica.app.mediasession

import android.content.ComponentName
import android.content.Intent
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import live.lyrica.app.core.logging.LyricaLogger
import live.lyrica.app.service.LyricaForegroundService

/**
 * NotificationListenerService — the only system API that gives unprivileged
 * cross-app MediaSession access on Android.
 *
 * This service's SOLE job is to:
 *  1. Gain the privileged MediaSession manager access
 *  2. Find the active media controller
 *  3. Delegate it to [LyricaForegroundService] which does all the real work
 *
 * This separation keeps the notification listener thin and the
 * foreground service (which owns the persistent notification) in full control.
 */
class LyricaMediaSessionListenerService : NotificationListenerService() {

    companion object {
        private const val TAG = "MediaSessionListener"
        var instance: LyricaMediaSessionListenerService? = null
            private set
    }

    private var mediaSessionManager: MediaSessionManager? = null
    private val sessionListener = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
        updateActiveController(controllers)
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        LyricaLogger.i(TAG, "NotificationListener connected — starting foreground service")

        // Start the foreground service now that we have listener access
        val fgIntent = Intent(this, LyricaForegroundService::class.java)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            startForegroundService(fgIntent)
        } else {
            startService(fgIntent)
        }

        // Register for active session changes
        try {
            val componentName = ComponentName(this, LyricaMediaSessionListenerService::class.java)
            mediaSessionManager = getSystemService(MEDIA_SESSION_SERVICE) as? MediaSessionManager
            mediaSessionManager?.addOnActiveSessionsChangedListener(sessionListener, componentName)

            // Initial discovery
            val controllers = mediaSessionManager?.getActiveSessions(componentName)
            updateActiveController(controllers)
        } catch (e: Exception) {
            LyricaLogger.e(TAG, "Failed to register session listener: ${e.message}", e)
        }
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        instance = null
        LyricaLogger.i(TAG, "NotificationListener disconnected")
        try {
            mediaSessionManager?.removeOnActiveSessionsChangedListener(sessionListener)
        } catch (_: Exception) {}
        LyricaForegroundService.instance?.onNoMediaController()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) = Unit
    override fun onNotificationRemoved(sbn: StatusBarNotification?) = Unit

    fun requestActiveSessionsRefresh() {
        try {
            val componentName = ComponentName(this, LyricaMediaSessionListenerService::class.java)
            val controllers = mediaSessionManager?.getActiveSessions(componentName)
            updateActiveController(controllers)
        } catch (e: Exception) {
            LyricaLogger.w(TAG, "Error refreshing active sessions: ${e.message}")
        }
    }

    private fun updateActiveController(controllers: List<MediaController>?) {
        val fgService = LyricaForegroundService.instance

        if (controllers.isNullOrEmpty()) {
            LyricaLogger.d(TAG, "No active media sessions")
            fgService?.onNoMediaController()
            return
        }

        // Prefer the controller that is actually currently PLAYING, otherwise pick the first
        val best = controllers.firstOrNull { it.playbackState?.state == android.media.session.PlaybackState.STATE_PLAYING }
            ?: controllers.firstOrNull()

        if (best != null) {
            LyricaLogger.d(TAG, "Active media session: ${best.packageName} (state=${best.playbackState?.state})")
            fgService?.onMediaControllerAvailable(best)
        } else {
            fgService?.onNoMediaController()
        }
    }
}
