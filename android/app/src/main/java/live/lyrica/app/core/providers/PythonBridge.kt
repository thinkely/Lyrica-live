package live.lyrica.app.core.providers

import android.content.Context
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import live.lyrica.app.core.logging.LyricaLogger
import live.lyrica.app.core.model.LyricsDocument
import org.json.JSONObject

/**
 * Thread-safe Chaquopy Python Bridge.
 * Executes Lyrica Python providers directly without running any Flask server.
 */
class PythonBridge(private val context: Context? = null) {
    private val TAG = "PythonBridge"

    init {
        ensurePythonStarted()
    }

    private fun ensurePythonStarted() {
        try {
            if (!Python.isStarted() && context != null) {
                Python.start(AndroidPlatform(context.applicationContext))
                LyricaLogger.i(TAG, "Chaquopy Python runtime initialized")
            }
        } catch (e: Exception) {
            LyricaLogger.w(TAG, "Python initialization note: ${e.message}")
        }
    }

    suspend fun fetchLyricsFromPython(
        providerName: String,
        artist: String,
        song: String,
        album: String? = null,
        durationMs: Long? = null,
        credentialsJson: String = "{}",
        wordLevel: Boolean = true
    ): LyricsDocument? = withContext(Dispatchers.IO) {
        try {
            ensurePythonStarted()
            if (!Python.isStarted()) {
                LyricaLogger.w(TAG, "Python is not started; cannot call python bridge")
                return@withContext null
            }

            val py = Python.getInstance()
            val bridgeModule = py.getModule("lyrica_bridge")

            val startTime = System.currentTimeMillis()
            LyricaLogger.d(TAG, "Calling Python provider '$providerName' for '$artist - $song' (wordLevel=$wordLevel)")

            val resultPy = bridgeModule.callAttr(
                "fetch_lyrics",
                artist,
                song,
                album,
                durationMs?.toInt(),
                providerName,
                credentialsJson,
                wordLevel
            )

            val elapsed = System.currentTimeMillis() - startTime
            val jsonStr = resultPy.toString()
            val jsonObj = JSONObject(jsonStr)

            val status = jsonObj.optString("status", "")
            if (status == "success") {
                val doc = LyricsDocument.fromJson(jsonObj)
                LyricaLogger.i(TAG, "Python provider '$providerName' returned success in ${elapsed}ms (lines=${doc.lines.size}, precision=${doc.syncPrecision})")
                return@withContext doc
            } else {
                val msg = jsonObj.optString("message", "unknown error")
                LyricaLogger.d(TAG, "Python provider '$providerName' returned $status in ${elapsed}ms: $msg")
                return@withContext null
            }
        } catch (e: Exception) {
            LyricaLogger.e(TAG, "Exception calling Python bridge provider '$providerName': ${e.message}", e)
            return@withContext null
        }
    }
}
