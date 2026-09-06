package live.lyrica.app.ui

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import live.lyrica.app.core.mediasession.LyricaMediaSessionListenerService
import live.lyrica.app.security.SecureTokenStorage

class MainActivity : ComponentActivity() {

    private lateinit var secureStorage: SecureTokenStorage

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        secureStorage = SecureTokenStorage(this)

        setContent {
            LyricaTheme {
                MainScreen(
                    secureStorage = secureStorage,
                    onOpenSettings = {
                        startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                    },
                    onOpenFullLyrics = {
                        startActivity(Intent(this, FullLyricsActivity::class.java))
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    secureStorage: SecureTokenStorage,
    onOpenSettings: () -> Unit,
    onOpenFullLyrics: () -> Unit
) {
    val isConnected by LyricaMediaSessionListenerService.isServiceConnectedFlow.collectAsState()
    val track by LyricaMediaSessionListenerService.currentTrackFlow.collectAsState()
    val lyricsDoc by LyricaMediaSessionListenerService.currentLyricsFlow.collectAsState()
    val syncState by LyricaMediaSessionListenerService.syncStateFlow.collectAsState()

    var geniusToken by remember { mutableStateOf(secureStorage.getString("genius_token") ?: "") }
    var appleDevToken by remember { mutableStateOf(secureStorage.getString("apple_developer_token") ?: "") }
    var hostedUrl by remember { mutableStateOf(secureStorage.getString("hosted_url") ?: "") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "Lyrica Live",
                            fontWeight = FontWeight.Bold,
                            fontSize = 20.sp,
                            color = TextPrimaryDark
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = PrimaryIndigo.copy(alpha = 0.2f)
                        ) {
                            Text(
                                text = "v1.0",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = PrimaryIndigoLight,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BackgroundDark)
            )
        },
        containerColor = BackgroundDark
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(20.dp)
        ) {
            // ── 1. Notification Access Permission Card ─────────────────────
            if (!isConnected) {
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = SurfaceVariantDark),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(18.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Warning, contentDescription = null, tint = Color(0xFFFBBF24))
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = "Notification Access Required",
                                fontWeight = FontWeight.Bold,
                                color = TextPrimaryDark,
                                fontSize = 15.sp
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Lyrica Live needs Notification Access permission to monitor Android MediaSessions and detect playing music.",
                            color = TextSecondaryDark,
                            fontSize = 13.sp
                        )
                        Spacer(modifier = Modifier.height(14.dp))
                        Button(
                            onClick = onOpenSettings,
                            colors = ButtonDefaults.buttonColors(containerColor = PrimaryIndigo),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Grant Permission", fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            // ── 2. Now Playing & Live Lyric Card ───────────────────────────
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(
                        text = "NOW PLAYING",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = PrimaryIndigoLight,
                        letterSpacing = 1.sp
                    )
                    Spacer(modifier = Modifier.height(10.dp))

                    if (track != null) {
                        Text(
                            text = track!!.rawTitle.ifBlank { "Unknown Title" },
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimaryDark
                        )
                        Text(
                            text = track!!.rawArtist.ifBlank { "Unknown Artist" },
                            fontSize = 14.sp,
                            color = TextSecondaryDark
                        )

                        Spacer(modifier = Modifier.height(14.dp))

                        // Current synchronized lyric line snippet
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(SurfaceVariantDark)
                                .padding(14.dp)
                        ) {
                            Column {
                                val currentText = syncState.currentLine?.text
                                    ?: if (lyricsDoc != null && lyricsDoc!!.isSynced) "♪ Waiting for line ♪" else "♪ Synchronized Lyrics ♪"
                                Text(
                                    text = currentText,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = ActiveLyricHighlight
                                )
                                lyricsDoc?.provider?.let { provider ->
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = "Source: ${provider.uppercase()}",
                                        fontSize = 11.sp,
                                        color = TextSecondaryDark
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))
                        Button(
                            onClick = onOpenFullLyrics,
                            colors = ButtonDefaults.buttonColors(containerColor = SurfaceVariantDark),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Fullscreen, contentDescription = null, tint = TextPrimaryDark)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Open Full Lyrics", color = TextPrimaryDark)
                        }
                    } else {
                        Text(
                            text = "No active music playback detected.",
                            fontSize = 14.sp,
                            color = TextSecondaryDark
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Start playing music in Spotify, Apple Music, YouTube Music, or any media player.",
                            fontSize = 12.sp,
                            color = TextMutedDark
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // ── 3. Provider Settings & API Keys ────────────────────────────
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(
                        text = "PROVIDER CREDENTIALS (STORED LOCALLY)",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = PrimaryIndigoLight,
                        letterSpacing = 1.sp
                    )
                    Spacer(modifier = Modifier.height(14.dp))

                    OutlinedTextField(
                        value = geniusToken,
                        onValueChange = {
                            geniusToken = it
                            secureStorage.putString("genius_token", it.trim())
                        },
                        label = { Text("Genius API Client Token (Optional)") },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = PrimaryIndigo,
                            unfocusedBorderColor = SurfaceVariantDark
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    OutlinedTextField(
                        value = appleDevToken,
                        onValueChange = {
                            appleDevToken = it
                            secureStorage.putString("apple_developer_token", it.trim())
                        },
                        label = { Text("Apple Music Developer Token (Optional)") },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = PrimaryIndigo,
                            unfocusedBorderColor = SurfaceVariantDark
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    OutlinedTextField(
                        value = hostedUrl,
                        onValueChange = {
                            hostedUrl = it
                            secureStorage.putString("hosted_url", it.trim())
                        },
                        label = { Text("Custom Hosted Lyrica URL (Fallback)") },
                        placeholder = { Text("https://lyrica.vercel.app/api/lyrics") },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = PrimaryIndigo,
                            unfocusedBorderColor = SurfaceVariantDark
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}
