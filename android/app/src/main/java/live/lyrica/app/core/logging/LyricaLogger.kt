package live.lyrica.app.core.logging

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.regex.Pattern

/**
 * Centralized logging system with:
 *  - Automatic redaction of sensitive credentials
 *  - In-memory ring buffer for debug log viewer (last 500 entries)
 */
object LyricaLogger {
    enum class Level { DEBUG, INFO, WARN, ERROR }

    var isDebugEnabled: Boolean = true

    // ── In-memory log buffer for the debug log viewer ──────────────────────
    data class LogEntry(
        val level: Level,
        val tag: String,
        val message: String,
        val timestampMs: Long = System.currentTimeMillis()
    ) {
        private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
        fun formatted(): String = "${fmt.format(Date(timestampMs))} [${level.name.take(1)}] $tag: $message"
    }

    private const val MAX_LOG_ENTRIES = 500
    private val _logBuffer = CopyOnWriteArrayList<LogEntry>()
    val logBuffer: List<LogEntry> get() = _logBuffer

    private fun appendToBuffer(entry: LogEntry) {
        _logBuffer.add(entry)
        // Keep ring buffer bounded
        while (_logBuffer.size > MAX_LOG_ENTRIES) {
            _logBuffer.removeAt(0)
        }
    }

    fun clearBuffer() {
        _logBuffer.clear()
    }

    fun getAllLogsAsText(): String {
        return _logBuffer.joinToString("\n") { it.formatted() }
    }

    // Patterns for redacting sensitive secrets
    private val SENSITIVE_PATTERNS = listOf(
        Pattern.compile("(?i)(bearer\\s+)[a-zA-Z0-9_\\-\\.]+", Pattern.CASE_INSENSITIVE),
        Pattern.compile("(?i)(token=)[a-zA-Z0-9_\\-\\.]+", Pattern.CASE_INSENSITIVE),
        Pattern.compile("(?i)(api[_-]?key=)[a-zA-Z0-9_\\-\\.]+", Pattern.CASE_INSENSITIVE),
        Pattern.compile("(?i)(password=)[^&\\s]+", Pattern.CASE_INSENSITIVE),
        Pattern.compile("(?i)(music-user-token:\\s*)[a-zA-Z0-9_\\-\\.]+", Pattern.CASE_INSENSITIVE),
        Pattern.compile("(?i)(authorization:\\s*)[a-zA-Z0-9_\\-\\.]+", Pattern.CASE_INSENSITIVE)
    )

    fun redact(message: String): String {
        var clean = message
        for (pattern in SENSITIVE_PATTERNS) {
            clean = pattern.matcher(clean).replaceAll("$1[REDACTED]")
        }
        return clean
    }

    fun d(tag: String, message: String) {
        if (isDebugEnabled) {
            val red = redact(message)
            appendToBuffer(LogEntry(Level.DEBUG, tag, red))
            try {
                Log.d("Lyrica::$tag", red)
            } catch (_: RuntimeException) {
                println("[DEBUG] Lyrica::$tag: $red")
            }
        }
    }

    fun i(tag: String, message: String) {
        val red = redact(message)
        appendToBuffer(LogEntry(Level.INFO, tag, red))
        try {
            Log.i("Lyrica::$tag", red)
        } catch (_: RuntimeException) {
            println("[INFO] Lyrica::$tag: $red")
        }
    }

    fun w(tag: String, message: String, throwable: Throwable? = null) {
        val red = redact(message)
        appendToBuffer(LogEntry(Level.WARN, tag, red))
        try {
            if (throwable != null) {
                Log.w("Lyrica::$tag", red, throwable)
            } else {
                Log.w("Lyrica::$tag", red)
            }
        } catch (_: RuntimeException) {
            println("[WARN] Lyrica::$tag: $red")
            throwable?.printStackTrace()
        }
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        val red = redact(message)
        appendToBuffer(LogEntry(Level.ERROR, tag, red))
        try {
            if (throwable != null) {
                Log.e("Lyrica::$tag", red, throwable)
            } else {
                Log.e("Lyrica::$tag", red)
            }
        } catch (_: RuntimeException) {
            System.err.println("[ERROR] Lyrica::$tag: $red")
            throwable?.printStackTrace()
        }
    }
}
