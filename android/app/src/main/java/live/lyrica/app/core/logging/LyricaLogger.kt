package live.lyrica.app.core.logging

import android.util.Log
import java.util.regex.Pattern

/**
 * Centralized logging system with automatic redaction of sensitive credentials.
 */
object LyricaLogger {
    enum class Level { DEBUG, INFO, WARN, ERROR }

    var isDebugEnabled: Boolean = true

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
            Log.d("Lyrica::$tag", redact(message))
        }
    }

    fun i(tag: String, message: String) {
        Log.i("Lyrica::$tag", redact(message))
    }

    fun w(tag: String, message: String, throwable: Throwable? = null) {
        if (throwable != null) {
            Log.w("Lyrica::$tag", redact(message), throwable)
        } else {
            Log.w("Lyrica::$tag", redact(message))
        }
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        if (throwable != null) {
            Log.e("Lyrica::$tag", redact(message), throwable)
        } else {
            Log.e("Lyrica::$tag", redact(message))
        }
    }
}
