package live.lyrica.app.core.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import live.lyrica.app.R
import live.lyrica.app.core.engine.SyncState
import live.lyrica.app.core.logging.LyricaLogger
import live.lyrica.app.core.mediasession.LyricaMediaSessionListenerService
import live.lyrica.app.core.model.LyricsDocument
import live.lyrica.app.core.model.NormalizedTrack
import live.lyrica.app.ui.FullLyricsActivity

/**
 * Manages the persistent lyrics notification and lock-screen display.
 */
class LyricsNotificationManager(private val context: Context) {
    private val TAG = "NotificationManager"
    private val CHANNEL_ID = "lyrica_live_lyrics_channel"
    private val NOTIFICATION_ID = 1001

    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    init {
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Live Synchronized Lyrics",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Displays real-time synchronized music lyrics"
                setShowBadge(false)
                lockscreenVisibility = NotificationCompat.VISIBILITY_PUBLIC
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    fun showOrUpdateLyricsNotification(
        track: NormalizedTrack,
        lyricsDoc: LyricsDocument?,
        syncState: SyncState,
        isPlaying: Boolean,
        canSeek: Boolean
    ) {
        try {
            val title = if (track.rawTitle.isNotBlank()) track.rawTitle else "Lyrica Live"
            val artist = if (track.rawArtist.isNotBlank()) track.rawArtist else "Now Playing"

            val currentLyricText = syncState.currentLine?.text
                ?: if (lyricsDoc != null && lyricsDoc.isSynced) {
                    syncState.nextLine?.let { "Upcoming: ${it.text}" } ?: "♪ ... ♪"
                } else if (lyricsDoc != null && lyricsDoc.plainLyrics.isNotBlank()) {
                    lyricsDoc.plainLyrics.lines().firstOrNull { it.isNotBlank() } ?: "♪ Lyrica Live ♪"
                } else {
                    "Searching synchronized lyrics..."
                }

            val nextLyricText = syncState.nextLine?.text

            val openIntent = Intent(context, FullLyricsActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val contentPendingIntent = PendingIntent.getActivity(
                context,
                0,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            // Play / Pause Action
            val playPauseIntent = Intent(context, LyricaMediaSessionListenerService::class.java).apply {
                action = "live.lyrica.app.ACTION_TOGGLE_PLAY"
            }
            val playPausePendingIntent = PendingIntent.getService(
                context,
                1,
                playPauseIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val builder = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_lyrics_notification)
                .setContentTitle("$title • $artist")
                .setContentText(currentLyricText)
                .setContentIntent(contentPendingIntent)
                .setOngoing(isPlaying)
                .setOnlyAlertOnce(true)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setCategory(NotificationCompat.CATEGORY_TRANSPORT)

            // Expanded style
            val bigTextStyle = NotificationCompat.BigTextStyle()
                .setBigContentTitle("$title • $artist")
                .bigText(
                    buildString {
                        append("“").append(currentLyricText).append("”")
                        if (!nextLyricText.isNullOrBlank()) {
                            append("\n\nNext: ").append(nextLyricText)
                        }
                        lyricsDoc?.let {
                            append("\n[via ${it.provider.uppercase()}]")
                        }
                    }
                )
            builder.setStyle(bigTextStyle)

            // Add Play/Pause action button
            builder.addAction(
                if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play,
                if (isPlaying) "Pause" else "Play",
                playPausePendingIntent
            )

            // If seek is supported and next line is available, add a quick seek action to next line
            if (canSeek && syncState.nextLine != null) {
                val seekIntent = Intent(context, LyricaMediaSessionListenerService::class.java).apply {
                    action = "live.lyrica.app.ACTION_SEEK"
                    putExtra("seek_position", syncState.nextLine.startMs)
                }
                val seekPendingIntent = PendingIntent.getService(
                    context,
                    2,
                    seekIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                builder.addAction(R.drawable.ic_seek_forward, "Skip to next line", seekPendingIntent)
            }

            notificationManager.notify(NOTIFICATION_ID, builder.build())
        } catch (e: Exception) {
            LyricaLogger.e(TAG, "Failed updating lyrics notification: ${e.message}", e)
        }
    }

    fun cancelNotification() {
        notificationManager.cancel(NOTIFICATION_ID)
    }
}
