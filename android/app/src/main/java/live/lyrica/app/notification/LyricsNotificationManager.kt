package live.lyrica.app.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import live.lyrica.app.R
import live.lyrica.app.core.logging.LyricaLogger
import live.lyrica.app.core.model.LyricsDocument
import live.lyrica.app.core.model.TrackQuery
import live.lyrica.app.engine.SyncState
import live.lyrica.app.service.LyricaForegroundService
import live.lyrica.app.ui.FullLyricsActivity

/**
 * Manages the persistent lyrics notification with custom RemoteViews.
 *
 * Design:
 *   - Collapsed: Custom white card layout with active lyric line in bold Apple Red (#FA233B).
 *   - Expanded: Compact 3-line layout (Previous, bold Red Current, Next) with compact
 *               Prev/Play-Pause/Next buttons (within the 252dp height cap on Android 12+).
 */
class LyricsNotificationManager(private val context: Context) {
    private val TAG = "NotifManager"

    companion object {
        const val CHANNEL_ID = "lyrica_live_lyrics_v2"
        const val NOTIFICATION_ID = 42
    }

    private val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    init {
        createChannel()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Live Lyrics",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Real-time synchronized music lyrics"
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
                lockscreenVisibility = NotificationCompat.VISIBILITY_PUBLIC
            }
            nm.createNotificationChannel(channel)
        }
    }

    fun buildInitialNotification(): android.app.Notification {
        val collapsed = RemoteViews(context.packageName, R.layout.notification_lyrics_collapsed).apply {
            setTextViewText(R.id.notif_collapsed_active_line, "Lyrica Live is active")
            setTextViewText(R.id.notif_collapsed_source, "Waiting for music…")
        }

        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_lyrics_notification)
            .setCustomContentView(collapsed)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setShowWhen(false)
            .build()
    }

    fun update(
        query: TrackQuery?,
        doc: LyricsDocument?,
        syncState: SyncState,
        isPlaying: Boolean
    ) {
        try {
            val builder = buildNotification(query, doc, syncState, isPlaying)
            nm.notify(NOTIFICATION_ID, builder.build())
        } catch (e: Exception) {
            LyricaLogger.e(TAG, "Notification custom view update failed: ${e.message}", e)
            try {
                // Fallback to standard system notification style if custom view inflation fails
                val fallback = buildFallbackNotification(query, doc, syncState, isPlaying)
                nm.notify(NOTIFICATION_ID, fallback.build())
            } catch (fallbackEx: Exception) {
                LyricaLogger.e(TAG, "Fallback notification also failed: ${fallbackEx.message}", fallbackEx)
            }
        }
    }

    private fun buildFallbackNotification(
        query: TrackQuery?,
        doc: LyricsDocument?,
        syncState: SyncState,
        isPlaying: Boolean
    ): NotificationCompat.Builder {
        val currentText = syncState.currentLine?.text ?: "Lyrica Live is active"
        val openIntent = Intent(context, FullLyricsActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPi = PendingIntent.getActivity(
            context, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_lyrics_notification)
            .setContentTitle(if (query != null) "${query.artist} - ${query.title}" else "Lyrica Live")
            .setContentText(currentText)
            .setContentIntent(openPi)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setShowWhen(false)
    }

    private fun buildNotification(
        query: TrackQuery?,
        doc: LyricsDocument?,
        syncState: SyncState,
        isPlaying: Boolean
    ): NotificationCompat.Builder {

        // ── Text resolutions ───────────────────────────────────────────────
        val currentText: String = when {
            syncState.currentLine != null -> {
                if (doc?.hasWordSync == true && syncState.wordIndex >= 0) {
                    buildWordHighlight(syncState)
                } else {
                    syncState.currentLine.text
                }
            }
            doc != null && doc.lines.isNotEmpty() -> "♪ …"
            doc != null -> "♪ …"
            query != null -> "Searching lyrics…"
            else -> "Waiting for music…"
        }

        val prevText = syncState.previousLine?.text ?: ""
        val nextText = syncState.nextLine?.text ?: ""
        val providerBadge = doc?.provider?.uppercase() ?: if (query != null) "SEARCHING" else "LYRICA"

        // ── PendingIntents ─────────────────────────────────────────────────
        val openIntent = Intent(context, FullLyricsActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPi = PendingIntent.getActivity(
            context, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val togglePi = togglePlayPendingIntent()

        // ── 1. Collapsed RemoteViews ───────────────────────────────────────
        val collapsedViews = RemoteViews(context.packageName, R.layout.notification_lyrics_collapsed).apply {
            setTextViewText(R.id.notif_collapsed_active_line, currentText)
            setTextViewText(
                R.id.notif_collapsed_source,
                if (query != null) "${query.artist} - ${query.title}" else "Lyrica Live"
            )
            setImageViewResource(
                R.id.notif_collapsed_btn_play_pause,
                if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play
            )
            setOnClickPendingIntent(R.id.notif_collapsed_btn_play_pause, togglePi)
            setOnClickPendingIntent(R.id.notif_collapsed_root, openPi)
        }

        // ── 2. Expanded RemoteViews ─────────────────────────────────────────
        val expandedViews = RemoteViews(context.packageName, R.layout.notification_lyrics_expanded).apply {
            setTextViewText(R.id.notif_expanded_prev_line, prevText)
            setTextViewText(
                R.id.notif_expanded_current_line,
                if (syncState.currentLine != null) "▶ $currentText" else currentText
            )
            setTextViewText(R.id.notif_expanded_next_line, nextText)
            setTextViewText(R.id.notif_mode_badge, providerBadge)
            setTextViewText(R.id.notif_btn_play_pause, if (isPlaying) "Pause" else "Play")

            setOnClickPendingIntent(R.id.notif_btn_play_pause, togglePi)
            setOnClickPendingIntent(R.id.notif_expanded_root, openPi)

            // Seek actions
            syncState.previousLine?.let { prev ->
                val prevPi = seekPendingIntent(prev.startMs, requestCode = 10)
                setOnClickPendingIntent(R.id.notif_btn_prev, prevPi)
            }
            syncState.nextLine?.let { next ->
                val nextPi = seekPendingIntent(next.startMs, requestCode = 11)
                setOnClickPendingIntent(R.id.notif_btn_next, nextPi)
            }
        }

        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_lyrics_notification)
            .setCustomContentView(collapsedViews)
            .setCustomBigContentView(expandedViews)
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .setContentIntent(openPi)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setShowWhen(false)
    }

    private fun buildWordHighlight(syncState: SyncState): String {
        val line = syncState.currentLine ?: return ""
        val wordIdx = syncState.wordIndex
        if (line.words.isEmpty() || wordIdx < 0) return line.text

        return line.words.mapIndexed { i, word ->
            if (i == wordIdx) word.text.uppercase() else word.text
        }.joinToString(" ")
    }

    private fun seekPendingIntent(positionMs: Long, requestCode: Int): PendingIntent {
        val intent = Intent(context, LyricaForegroundService::class.java).apply {
            action = LyricaForegroundService.ACTION_SEEK
            putExtra(LyricaForegroundService.EXTRA_SEEK_POSITION, positionMs)
        }
        return PendingIntent.getService(
            context, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun togglePlayPendingIntent(): PendingIntent {
        val intent = Intent(context, LyricaForegroundService::class.java).apply {
            action = LyricaForegroundService.ACTION_TOGGLE_PLAY
        }
        return PendingIntent.getService(
            context, 20, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    fun cancel() = nm.cancel(NOTIFICATION_ID)
}
