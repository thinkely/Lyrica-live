package live.lyrica.app.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import live.lyrica.app.R
import live.lyrica.app.core.logging.LyricaLogger
import live.lyrica.app.core.model.LyricsDocument
import live.lyrica.app.core.model.TrackQuery
import live.lyrica.app.engine.SyncState
import live.lyrica.app.service.LyricaForegroundService
import live.lyrica.app.ui.FullLyricsActivity

/**
 * Manages the persistent lyrics notification.
 *
 * Design:
 *   - Collapsed: ONLY the current lyric line (no song name, no artist).
 *                If word-level, shows "Word Word *CurrentWord* Word Word".
 *   - Expanded: Three lines — previous (dimmed), current (bold), next (dimmed).
 *               Each line has a seek PendingIntent (via LyricaForegroundService).
 *   - Foreground: The service keeps the notification alive with IMPORTANCE_LOW
 *                 (no sound, no vibration, persistent).
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
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_lyrics_notification)
            .setContentText("Lyrica Live is active")
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
            LyricaLogger.e(TAG, "Notification update failed: ${e.message}", e)
        }
    }

    private fun buildNotification(
        query: TrackQuery?,
        doc: LyricsDocument?,
        syncState: SyncState,
        isPlaying: Boolean
    ): NotificationCompat.Builder {

        // ── Collapsed line (lyrics-only, no song/artist) ───────────────────
        val collapsedText: String = when {
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

        // ── Open full lyrics on tap ────────────────────────────────────────
        val openIntent = Intent(context, FullLyricsActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPi = PendingIntent.getActivity(
            context, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_lyrics_notification)
            .setContentText(collapsedText)
            .setContentIntent(openPi)
            .setOngoing(true)       // Foreground service keeps it persistent
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setShowWhen(false)

        // ── Expanded BigText with 3 lines ──────────────────────────────────
        val expandedText = buildExpandedText(syncState, doc)
        val bigText = NotificationCompat.BigTextStyle()
            .bigText(expandedText)
        builder.setStyle(bigText)

        // ── Actions: seek to prev / play-pause / seek to next ─────────────
        // Seek to previous line
        syncState.previousLine?.let { prev ->
            val seekPrevPi = seekPendingIntent(prev.startMs, requestCode = 10)
            builder.addAction(R.drawable.ic_seek_forward, "← Prev", seekPrevPi)
        }

        // Play/Pause
        val togglePi = togglePlayPendingIntent()
        builder.addAction(
            if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play,
            if (isPlaying) "Pause" else "Play",
            togglePi
        )

        // Seek to next line
        syncState.nextLine?.let { next ->
            val seekNextPi = seekPendingIntent(next.startMs, requestCode = 11)
            builder.addAction(R.drawable.ic_seek_forward, "Next →", seekNextPi)
        }

        return builder
    }

    /**
     * Build word-highlighted line text using Unicode bold simulation.
     * e.g. "I've been tryna *call*" → current word slightly distinguished.
     * We use ALL CAPS for the active word (notification text can't do bold).
     */
    private fun buildWordHighlight(syncState: SyncState): String {
        val line = syncState.currentLine ?: return ""
        val wordIdx = syncState.wordIndex
        if (line.words.isEmpty() || wordIdx < 0) return line.text

        return line.words.mapIndexed { i, word ->
            if (i == wordIdx) word.text.uppercase() else word.text
        }.joinToString(" ")
    }

    /**
     * Build the 3-line expanded notification text:
     *   Previous line (dimmed with ↑ prefix)
     *   Current line  (full brightness, longer)
     *   Next line     (dimmed with ↓ prefix)
     */
    private fun buildExpandedText(syncState: SyncState, doc: LyricsDocument?): String {
        if (doc == null || doc.lines.isEmpty()) {
            return when {
                syncState.currentLine != null -> syncState.currentLine.text
                else -> "Searching for synchronized lyrics…"
            }
        }

        val parts = mutableListOf<String>()

        syncState.previousLine?.let {
            parts.add("  ${it.text}")   // previous — indented
        }

        val current = syncState.currentLine
        if (current != null) {
            parts.add("▶ ${current.text}")   // current — arrow prefix
        } else {
            parts.add("♪ …")
        }

        syncState.nextLine?.let {
            parts.add("  ${it.text}")   // next — indented
        }

        return parts.joinToString("\n")
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
