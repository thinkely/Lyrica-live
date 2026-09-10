package live.lyrica.app.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
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
 * Manages the persistent lyrics notification — MusicXMatch style.
 *
 * Design:
 *   - Collapsed: Rounded album art | bold song title | current lyric line.
 *   - Expanded:  Album art header + source badge, then a large bold active lyric
 *               followed by cascading faded upcoming lines (no transport controls —
 *               the OS media player handles that).
 *
 * Key: NO DecoratedCustomViewStyle — that wrapper was causing the "notification
 * inside a notification" look. We use raw custom views on a transparent background
 * so the OS draws its own notification surface naturally.
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
            setTextViewText(R.id.notif_collapsed_track_info, "Lyrica Live")
            setTextViewText(R.id.notif_collapsed_active_line, "♪ Waiting for music…")
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
        isPlaying: Boolean,
        albumArt: Bitmap? = null
    ) {
        try {
            val builder = buildNotification(query, doc, syncState, isPlaying, albumArt)
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
            .setContentTitle(if (query != null) "${query.artist} — ${query.title}" else "Lyrica Live")
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
        isPlaying: Boolean,
        albumArt: Bitmap?
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

        val trackInfo = if (query != null && query.title.isNotBlank()) {
            val artist = query.rawArtist.ifBlank { query.artist }
            val title = query.rawTitle.ifBlank { query.title }
            if (artist.isNotBlank()) "$artist — $title" else title
        } else {
            "Lyrica Live"
        }

        val prevLine = if (idx > 0 && idx < lines.size) lines[idx - 1] else null
        val currLine = if (idx >= 0 && idx < lines.size) lines[idx] else null
        val nextLine1 = if (idx >= 0 && idx + 1 < lines.size) lines[idx + 1] else if (idx < 0 && lines.isNotEmpty()) lines.getOrNull(0) else null
        val nextLine2 = if (idx >= 0 && idx + 2 < lines.size) lines[idx + 2] else if (idx < 0 && lines.size > 1) lines.getOrNull(1) else null
        val nextLine3 = if (idx >= 0 && idx + 3 < lines.size) lines[idx + 3] else if (idx < 0 && lines.size > 2) lines.getOrNull(2) else null
        val nextLine4 = if (idx >= 0 && idx + 4 < lines.size) lines[idx + 4] else if (idx < 0 && lines.size > 3) lines.getOrNull(3) else null

        val prevText = prevLine?.text ?: ""
        val nextText1 = nextLine1?.text ?: ""
        val nextText2 = nextLine2?.text ?: ""
        val nextText3 = nextLine3?.text ?: ""
        val nextText4 = nextLine4?.text ?: ""

        // Derive clean provider / mode badge
        val providerBadge = when {
            doc == null && query != null -> "SEARCHING"
            doc == null -> "LYRICA"
            doc.provider.contains("Romanized", ignoreCase = true) -> "ROMANIZED"
            doc.provider.contains("Translated", ignoreCase = true) -> "TRANSLATED"
            doc.provider.contains("spotify", ignoreCase = true) -> "SPOTIFY"
            else -> doc.provider.substringBefore(" ").uppercase().take(12)
        }

        // Rounded album art
        val roundedArt = albumArt?.let { getRoundedCornerBitmap(it, 16f) }

        // ── PendingIntents ─────────────────────────────────────────────────
        val openIntent = Intent(context, FullLyricsActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPi = PendingIntent.getActivity(
            context, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // ── 1. Collapsed RemoteViews ───────────────────────────────────────
        // Track info (bold) on top, current lyric line beneath it.
        val collapsedViews = RemoteViews(context.packageName, R.layout.notification_lyrics_collapsed).apply {
            setTextViewText(R.id.notif_collapsed_track_info, trackInfo)
            setTextViewText(R.id.notif_collapsed_active_line, currentText)

            if (roundedArt != null) {
                setImageViewBitmap(R.id.notif_collapsed_art, roundedArt)
            } else {
                setImageViewResource(R.id.notif_collapsed_art, R.drawable.ic_lyrics_notification)
            }

            setOnClickPendingIntent(R.id.notif_collapsed_root, openPi)
        }

        // ── 2. Expanded RemoteViews (Multi-line preview with in-line seek) ──
        val expandedViews = RemoteViews(context.packageName, R.layout.notification_lyrics_expanded).apply {
            setTextViewText(R.id.notif_expanded_track_info, trackInfo)
            setTextViewText(R.id.notif_expanded_line_prev, prevText)
            setTextViewText(R.id.notif_expanded_line_curr, currentText)
            setTextViewText(R.id.notif_expanded_line_next1, nextText1)
            setTextViewText(R.id.notif_expanded_line_next2, nextText2)
            setTextViewText(R.id.notif_expanded_line_next3, nextText3)
            setTextViewText(R.id.notif_expanded_line_next4, nextText4)
            setTextViewText(R.id.notif_mode_badge, providerBadge)

            if (roundedArt != null) {
                setImageViewBitmap(R.id.notif_expanded_art, roundedArt)
            } else {
                setImageViewResource(R.id.notif_expanded_art, R.drawable.ic_lyrics_notification)
            }

            // Root click opens full screen lyrics activity
            setOnClickPendingIntent(R.id.notif_expanded_root, openPi)

            // Direct line tapping seeks playback to that exact lyric timestamp!
            prevLine?.let { setOnClickPendingIntent(R.id.notif_expanded_line_prev, createSeekPendingIntent(it.startMs, 101)) }
            currLine?.let { setOnClickPendingIntent(R.id.notif_expanded_line_curr, createSeekPendingIntent(it.startMs, 102)) }
            nextLine1?.let { setOnClickPendingIntent(R.id.notif_expanded_line_next1, createSeekPendingIntent(it.startMs, 103)) }
            nextLine2?.let { setOnClickPendingIntent(R.id.notif_expanded_line_next2, createSeekPendingIntent(it.startMs, 104)) }
            nextLine3?.let { setOnClickPendingIntent(R.id.notif_expanded_line_next3, createSeekPendingIntent(it.startMs, 105)) }
            nextLine4?.let { setOnClickPendingIntent(R.id.notif_expanded_line_next4, createSeekPendingIntent(it.startMs, 106)) }
        }

        // NOTE: No DecoratedCustomViewStyle — that was causing the "notification inside
        // notification" look by wrapping our custom view inside a system chrome frame.
        // We set the large icon for the system chrome header (small icon area).
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_lyrics_notification)
            .setLargeIcon(roundedArt)
            .setCustomContentView(collapsedViews)
            .setCustomBigContentView(expandedViews)
            .setContentIntent(openPi)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
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

    private fun getRoundedCornerBitmap(bitmap: Bitmap, cornerRadiusDp: Float): Bitmap {
        val density = context.resources.displayMetrics.density
        val radiusPx = cornerRadiusDp * density

        val output = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val rect = Rect(0, 0, bitmap.width, bitmap.height)
        val rectF = RectF(rect)

        canvas.drawRoundRect(rectF, radiusPx, radiusPx, paint)
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        canvas.drawBitmap(bitmap, rect, rect, paint)

        return output
    }

    private fun createSeekPendingIntent(positionMs: Long, requestCode: Int): PendingIntent {
        val intent = Intent(context, LyricaForegroundService::class.java).apply {
            action = LyricaForegroundService.ACTION_SEEK
            putExtra(LyricaForegroundService.EXTRA_SEEK_POSITION, positionMs)
        }
        return PendingIntent.getService(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    fun cancel() = nm.cancel(NOTIFICATION_ID)
}
