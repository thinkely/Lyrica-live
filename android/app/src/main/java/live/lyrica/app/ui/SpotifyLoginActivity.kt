package live.lyrica.app.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
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
    var showManualDialog by remember { mutableStateOf(false) }
    var manualCookieText by remember { mutableStateOf("") }

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
                    IconButton(onClick = { showManualDialog = true }) {
                        Icon(Icons.Default.Edit, contentDescription = "Manual Token")
                    }
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
                .background(Color(0xFF121212))
        ) {
            AndroidView(
                factory = { ctx ->
                    WebView(ctx).apply {
                        @SuppressLint("SetJavaScriptEnabled")
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.databaseEnabled = true
                        settings.useWideViewPort = true
                        settings.loadWithOverviewMode = true
                        settings.javaScriptCanOpenWindowsAutomatically = true
                        settings.setSupportMultipleWindows(false)
                        settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                        settings.cacheMode = WebSettings.LOAD_DEFAULT
                        // Modern Chrome User-Agent to ensure full compatibility with Spotify accounts
                        settings.userAgentString = "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36"

                        val cookieManager = CookieManager.getInstance()
                        cookieManager.setAcceptCookie(true)
                        cookieManager.setAcceptThirdPartyCookies(this, true)

                        webChromeClient = object : WebChromeClient() {
                            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                if (newProgress >= 90) isLoading = false
                            }
                        }

                        webViewClient = object : WebViewClient() {
                            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                isLoading = true
                                checkCookies(url, cookieManager) { spDc ->
                                    onLoginSuccess(spDc)
                                }
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                isLoading = false
                                checkCookies(url, cookieManager) { spDc ->
                                    onLoginSuccess(spDc)
                                }
                            }

                            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                                val url = request?.url?.toString()
                                checkCookies(url, cookieManager) { spDc ->
                                    onLoginSuccess(spDc)
                                }
                                return false
                            }

                            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                                super.onReceivedError(view, request, error)
                                LyricaLogger.w("SpotifyLogin", "WebView error: ${error?.description}")
                            }
                        }

                        loadUrl("https://accounts.spotify.com/login?continue=https%3A%2F%2Fopen.spotify.com%2F")
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

        // Manual sp_dc cookie entry dialog fallback
        if (showManualDialog) {
            AlertDialog(
                onDismissRequest = { showManualDialog = false },
                title = { Text("Paste 'sp_dc' Cookie", fontWeight = FontWeight.Bold) },
                text = {
                    Column {
                        Text(
                            "If web login is blocked, you can extract the 'sp_dc' cookie from open.spotify.com and paste it here:",
                            fontSize = 13.sp,
                            color = LabelSecondary
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        OutlinedTextField(
                            value = manualCookieText,
                            onValueChange = { manualCookieText = it },
                            placeholder = { Text("sp_dc cookie value...") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            val trimmed = manualCookieText.trim().removePrefix("sp_dc=").trim(';', ' ', '"')
                            if (trimmed.isNotBlank()) {
                                showManualDialog = false
                                onLoginSuccess(trimmed)
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1DB954))
                    ) {
                        Text("Save & Connect")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showManualDialog = false }) {
                        Text("Cancel")
                    }
                }
            )
        }
    }
}

private fun checkCookies(url: String?, cookieManager: CookieManager, onFound: (String) -> Unit) {
    if (url == null) return
    val cookieStr = cookieManager.getCookie("https://open.spotify.com")
        ?: cookieManager.getCookie("https://accounts.spotify.com")
        ?: cookieManager.getCookie("https://spotify.com")
        ?: cookieManager.getCookie(url) ?: return

    val cookies = cookieStr.split(";").map { it.trim() }
    for (cookie in cookies) {
        if (cookie.startsWith("sp_dc=")) {
            val spDc = cookie.substringAfter("sp_dc=").trim().trim(';', ' ', '"')
            if (spDc.isNotBlank()) {
                onFound(spDc)
                return
            }
        }
    }
}
