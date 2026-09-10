package live.lyrica.app.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import live.lyrica.app.core.logging.LyricaLogger
import live.lyrica.app.provider.impl.SpotifyLyricsProvider
import live.lyrica.app.security.SecureTokenStorage

class SpotifyLoginActivity : ComponentActivity() {

    private val TAG = "SpotifyLoginActivity"
    private lateinit var storage: SecureTokenStorage

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        storage = SecureTokenStorage(this)

        setContent {
            LyricaTheme {
                SpotifyLoginScreen(
                    onBack = { finish() },
                    onLoginSuccess = { spDc ->
                        storage.putString(SpotifyLyricsProvider.KEY_SP_DC, spDc)
                        Toast.makeText(this, "Connected to Spotify Lyrics!", Toast.LENGTH_SHORT).show()
                        setResult(RESULT_OK)
                        finish()
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpotifyLoginScreen(
    onBack: () -> Unit,
    onLoginSuccess: (String) -> Unit
) {
    var webViewInstance by remember { mutableStateOf<WebView?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var capturedSpDc by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Connect Spotify", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        Text("Sign in to unlock direct Spotify lyrics", fontSize = 12.sp, color = LabelSecondary)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { webViewInstance?.reload() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Reload")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.White,
                    titleContentColor = LabelPrimary
                )
            )
        }
    ) { pad ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(pad)
        ) {
            AndroidView(
                factory = { ctx ->
                    WebView(ctx).apply {
                        @SuppressLint("SetJavaScriptEnabled")
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.userAgentString = "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

                        val cookieManager = CookieManager.getInstance()
                        cookieManager.setAcceptCookie(true)
                        cookieManager.setAcceptThirdPartyCookies(this, true)

                        webViewClient = object : WebViewClient() {
                            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                isLoading = true
                                checkCookies(url, cookieManager) { spDc ->
                                    capturedSpDc = spDc
                                    onLoginSuccess(spDc)
                                }
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                isLoading = false
                                checkCookies(url, cookieManager) { spDc ->
                                    capturedSpDc = spDc
                                    onLoginSuccess(spDc)
                                }
                            }

                            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                                val url = request?.url?.toString()
                                checkCookies(url, cookieManager) { spDc ->
                                    capturedSpDc = spDc
                                    onLoginSuccess(spDc)
                                }
                                return false
                            }
                        }

                        loadUrl("https://accounts.spotify.com/en/login")
                        webViewInstance = this
                    }
                },
                modifier = Modifier.fillMaxSize()
            )

            if (isLoading) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter),
                    color = Color(0xFF1DB954) // Spotify Green
                )
            }
        }
    }
}

private fun checkCookies(url: String?, cookieManager: CookieManager, onFound: (String) -> Unit) {
    if (url == null) return
    val cookieStr = cookieManager.getCookie("https://open.spotify.com")
        ?: cookieManager.getCookie("https://spotify.com")
        ?: cookieManager.getCookie(url) ?: return

    val cookies = cookieStr.split(";").map { it.trim() }
    for (cookie in cookies) {
        if (cookie.startsWith("sp_dc=")) {
            val spDc = cookie.substringAfter("sp_dc=").trim()
            if (spDc.isNotBlank()) {
                onFound(spDc)
                return
            }
        }
    }
}
