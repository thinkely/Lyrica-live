package live.lyrica.app.ui

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
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
                    },
                    onOpenDebugLogs = {
                        startActivity(Intent(this, DebugLogActivity::class.java))
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
    onOpenFullLyrics: () -> Unit,
    onOpenDebugLogs: () -> Unit
) {
    val isConnected by LyricaMediaSessionListenerService.isServiceConnectedFlow.collectAsState()
    val track by LyricaMediaSessionListenerService.currentTrackFlow.collectAsState()
    val lyricsDoc by LyricaMediaSessionListenerService.currentLyricsFlow.collectAsState()
    val syncState by LyricaMediaSessionListenerService.syncStateFlow.collectAsState()
    val searchStatus by LyricaMediaSessionListenerService.searchStatusFlow.collectAsState()

    var geniusToken by remember { mutableStateOf(secureStorage.getString("genius_token") ?: "") }
    var appleDevToken by remember { mutableStateOf(secureStorage.getString("apple_developer_token") ?: "") }
    var hostedUrl by remember { mutableStateOf(secureStorage.getString("hosted_url") ?: "") }
    var wordLevelSync by remember { mutableStateOf(secureStorage.getBoolean("word_level_sync", true)) }

    var showPermissionSteps by remember { mutableStateOf(false) }

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
                                text = "v1.1",
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
            // ── 1. Permission Onboarding Card ──────────────────────────────
            AnimatedVisibility(
                visible = !isConnected,
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF2D1F0A)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(18.dp)) {
                        // Header row
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = Color(0xFFFBBF24).copy(alpha = 0.15f),
                                modifier = Modifier.size(36.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        Icons.Default.Lock,
                                        contentDescription = null,
                                        tint = Color(0xFFFBBF24),
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = "One-time Permission Needed",
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFFFBBF24),
                                    fontSize = 15.sp
                                )
                                Text(
                                    text = "Required to read currently playing music",
                                    color = TextMutedDark,
                                    fontSize = 12.sp
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        Text(
                            text = "Lyrica Live uses Android's Notification Access to detect what music you're playing " +
                                    "in Spotify, YouTube Music, or any other player. " +
                                    "Your music data never leaves your phone.",
                            color = TextSecondaryDark,
                            fontSize = 13.sp,
                            lineHeight = 20.sp
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        // Expandable how-to steps
                        TextButton(
                            onClick = { showPermissionSteps = !showPermissionSteps },
                            contentPadding = PaddingValues(0.dp)
                        ) {
                            Text(
                                text = if (showPermissionSteps) "Hide steps ▲" else "How to grant it ▼",
                                color = PrimaryIndigoLight,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }

                        AnimatedVisibility(visible = showPermissionSteps) {
                            Column(modifier = Modifier.padding(top = 4.dp)) {
                                PermissionStep(number = "1", text = "Tap 'Grant Permission' below")
                                PermissionStep(number = "2", text = "Find 'Lyrica Live' in the list")
                                PermissionStep(number = "3", text = "Toggle the switch, then press Back")
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        Button(
                            onClick = onOpenSettings,
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFBBF24)),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(
                                Icons.Default.OpenInNew,
                                contentDescription = null,
                                tint = Color.Black,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Grant Permission", fontWeight = FontWeight.Bold, color = Color.Black)
                        }
                    }
                }
            }

            if (!isConnected) Spacer(modifier = Modifier.height(16.dp))

            // ── 2. Now Playing & Live Lyric Card ────────────────────────────
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
                                    ?: when (searchStatus) {
                                        "searching" -> "Searching in LRCLIB & LRCMux..."
                                        "not_found" -> "No synced lyrics found"
                                        "found" -> "♪ Waiting for line ♪"
                                        else -> if (lyricsDoc != null) "♪ Waiting for line ♪" else "♪ Start playing music ♪"
                                    }
                                Text(
                                    text = currentText,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (syncState.currentLine != null) ActiveLyricHighlight else TextSecondaryDark
                                )
                                lyricsDoc?.provider?.let { provider ->
                                    val precisionLabel = lyricsDoc!!.syncPrecision.name.lowercase()
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = "via ${provider.uppercase()}  •  $precisionLabel sync",
                                        fontSize = 11.sp,
                                        color = TextSecondaryDark
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))
                        Button(
                            onClick = onOpenFullLyrics,
                            colors = ButtonDefaults.buttonColors(containerColor = PrimaryIndigo),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Fullscreen, contentDescription = null, tint = Color.White)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Open Full Lyrics", color = Color.White, fontWeight = FontWeight.SemiBold)
                        }
                    } else {
                        Text(
                            text = if (isConnected) "No active music playback detected." else "Waiting for permission...",
                            fontSize = 14.sp,
                            color = TextSecondaryDark
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Start playing music in Spotify, YouTube Music, or any media player.",
                            fontSize = 12.sp,
                            color = TextMutedDark
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // ── 3. Lyrics Preferences ─────────────────────────────────────
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(
                        text = "LYRICS PREFERENCES",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = PrimaryIndigoLight,
                        letterSpacing = 1.sp
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Word-level sync",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = TextPrimaryDark
                            )
                            Text(
                                text = "Highlights individual words as they're sung (LRCMux only)",
                                fontSize = 12.sp,
                                color = TextMutedDark
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Switch(
                            checked = wordLevelSync,
                            onCheckedChange = {
                                wordLevelSync = it
                                secureStorage.putBoolean("word_level_sync", it)
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = PrimaryIndigo
                            )
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // ── 4. Provider Credentials ────────────────────────────────────
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(
                        text = "OPTIONAL API KEYS",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = PrimaryIndigoLight,
                        letterSpacing = 1.sp
                    )
                    Text(
                        text = "LRCLIB and LRCMux work without any keys. Add these only if you need additional sources.",
                        fontSize = 12.sp,
                        color = TextMutedDark,
                        modifier = Modifier.padding(top = 4.dp, bottom = 14.dp)
                    )

                    OutlinedTextField(
                        value = geniusToken,
                        onValueChange = {
                            geniusToken = it
                            secureStorage.putString("genius_token", it.trim())
                        },
                        label = { Text("Genius API Client Token") },
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
                        label = { Text("Apple Music Developer Token") },
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

            Spacer(modifier = Modifier.height(16.dp))

            // ── Debug Logs (DEV ONLY) ──────────────────────────────────────
            OutlinedButton(
                onClick = onOpenDebugLogs,
                shape = RoundedCornerShape(10.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF374151)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    Icons.Default.BugReport,
                    contentDescription = null,
                    tint = Color(0xFF6B7280),
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    "View Debug Logs",
                    color = Color(0xFF6B7280),
                    fontSize = 13.sp
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Debug log viewer is for testing only and will be removed in release builds.",
                fontSize = 10.sp,
                color = TextMutedDark.copy(alpha = 0.5f),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

/**
 * A numbered step row for the permission onboarding guide.
 */
@Composable
private fun PermissionStep(number: String, text: String) {
    Row(
        modifier = Modifier.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            shape = RoundedCornerShape(50),
            color = PrimaryIndigo.copy(alpha = 0.2f),
            modifier = Modifier.size(24.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = number,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = PrimaryIndigoLight
                )
            }
        }
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = text,
            fontSize = 13.sp,
            color = TextSecondaryDark
        )
    }
}
