package live.lyrica.app.provider.impl

import android.content.Context
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import live.lyrica.app.core.logging.LyricaLogger
import live.lyrica.app.core.model.LyricsDocument
import live.lyrica.app.core.model.TrackQuery
import live.lyrica.app.provider.LyricsProvider
import kotlin.coroutines.resume

/**
 * Abstract base for providers that require JavaScript rendering (e.g. Genius, AZLyrics).
 *
 * Subclass this and implement:
 *   - [buildUrl] — the URL to load in the WebView
 *   - [extractionJs] — JavaScript that runs after page load and returns the lyrics JSON
 *   - [parseExtractionResult] — convert the JS result string to a LyricsDocument
 *
 * Note: WebView must run on the main thread. This class handles the
 * coroutine suspend/resume handoff automatically.
 *
 * Usage (future community extension):
 *   class GeniusProvider(ctx: Context) : WebViewProvider(ctx) {
 *       override fun buildUrl(query: TrackQuery) = "https://genius.com/search?q=..."
 *       override val extractionJs = "JSON.stringify({lyrics: document.querySelector('.lyrics')?.innerText})"
 *       override fun parseExtractionResult(json: String, query: TrackQuery) = ...
 *   }
 */
abstract class WebViewProvider(protected val context: Context) : LyricsProvider {

    private val TAG = "WebViewProvider"

    /** Timeout for WebView page load + JS extraction */
    private val TIMEOUT_MS = 15_000L

    /** Build the URL to load for this query */
    abstract fun buildUrl(query: TrackQuery): String

    /** JavaScript string to evaluate after page load; must return a JSON string */
    abstract val extractionJs: String

    /** Parse the JSON string returned by [extractionJs] into a LyricsDocument */
    abstract fun parseExtractionResult(json: String, query: TrackQuery): LyricsDocument?

    override suspend fun search(query: TrackQuery): LyricsDocument? {
        return try {
            withTimeout(TIMEOUT_MS) {
                loadAndExtract(query)
            }
        } catch (e: Exception) {
            LyricaLogger.w(TAG, "$id WebView error: ${e.message}")
            null
        }
    }

    private suspend fun loadAndExtract(query: TrackQuery): LyricsDocument? =
        suspendCancellableCoroutine { cont ->
            val url = buildUrl(query)
            LyricaLogger.d(TAG, "$id WebView loading: $url")

            // WebView MUST be created on main thread
            val webView = WebView(context)
            webView.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                userAgentString = "Mozilla/5.0 (Android; Mobile) LyricaLive/2.0"
            }

            val bridge = object {
                @JavascriptInterface
                fun onResult(json: String) {
                    LyricaLogger.d(TAG, "$id WebView extraction result length=${json.length}")
                    val doc = try { parseExtractionResult(json, query) } catch (_: Exception) { null }
                    webView.post { webView.destroy() }
                    if (cont.isActive) cont.resume(doc)
                }
            }
            webView.addJavascriptInterface(bridge, "LyricaBridge")

            webView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, pageUrl: String?) {
                    view?.evaluateJavascript(
                        "javascript:(function(){ try { LyricaBridge.onResult($extractionJs); } catch(e) { LyricaBridge.onResult(JSON.stringify({error: e.toString()})); } })()",
                        null
                    )
                }
            }

            cont.invokeOnCancellation {
                webView.post { webView.destroy() }
            }

            webView.loadUrl(url)
        }
}
