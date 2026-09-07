package live.lyrica.app.ui

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import live.lyrica.app.provider.ProviderRegistry
import live.lyrica.app.security.SecureTokenStorage
import live.lyrica.app.service.LyricaForegroundService

class MainActivity : ComponentActivity() {
    private lateinit var storage: SecureTokenStorage

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        storage = SecureTokenStorage(this)
        setContent {
            LyricaTheme {
                MainScreen(
                    storage = storage,
                    onGrantPermission = { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) },
                    onOpenFullLyrics  = { startActivity(Intent(this, FullLyricsActivity::class.java)) },
                    onOpenDebugLogs   = { startActivity(Intent(this, DebugLogActivity::class.java)) }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    storage: SecureTokenStorage,
    onGrantPermission: () -> Unit,
    onOpenFullLyrics: () -> Unit,
    onOpenDebugLogs: () -> Unit
) {
    val isRunning by LyricaForegroundService.isServiceRunningFlow.collectAsState()
    val track     by LyricaForegroundService.currentQueryFlow.collectAsState()
    val doc       by LyricaForegroundService.currentLyricsFlow.collectAsState()
    val syncState by LyricaForegroundService.syncStateFlow.collectAsState()
    val isPlaying by LyricaForegroundService.isPlayingFlow.collectAsState()
    val status    by LyricaForegroundService.searchStatusFlow.collectAsState()

    val wordLevel = remember { mutableStateOf(storage.getBoolean("word_level_sync", true)) }
    var showPermSteps by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Lyrica Live",
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp
                    )
                },
                actions = {
                    IconButton(onClick = onOpenDebugLogs) {
                        Icon(Icons.Default.BugReport, contentDescription = "Debug", tint = SystemGray2)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.White,
                    titleContentColor = LabelPrimary
                )
            )
        },
        containerColor = SystemGray6
    ) { pad ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(pad)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // ── 1. Permission Card (hidden when running) ───────────────────
            AnimatedVisibility(!isRunning, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                LyricaCard {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.MusicNote,
                                contentDescription = null,
                                tint = AppleRed,
                                modifier = Modifier.size(22.dp)
                            )
                            Spacer(Modifier.width(10.dp))
                            Column {
                                Text("Enable Media Access", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                                Text(
                                    "Required to detect what's playing",
                                    fontSize = 12.sp,
                                    color = LabelSecondary
                                )
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "Lyrica Live reads the currently playing song from Spotify, YouTube Music, or any media player. Your music data stays on your phone.",
                            fontSize = 13.sp,
                            color = LabelSecondary,
                            lineHeight = 20.sp
                        )
                        TextButton(
                            onClick = { showPermSteps = !showPermSteps },
                            contentPadding = PaddingValues(0.dp)
                        ) {
                            Text(
                                if (showPermSteps) "Hide steps ▲" else "How to enable ▼",
                                color = AppleRed,
                                fontSize = 13.sp
                            )
                        }
                        AnimatedVisibility(showPermSteps) {
                            Column {
                                PermStep("1", "Tap 'Grant Access' below")
                                PermStep("2", "Find 'Lyrica Live' in the list")
                                PermStep("3", "Toggle it on, then go back")
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Button(
                            onClick = onGrantPermission,
                            colors = ButtonDefaults.buttonColors(containerColor = AppleRed),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Grant Access", fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }

            // ── 2. Now Playing Card ────────────────────────────────────────
            LyricaCard {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "NOW PLAYING",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = AppleRed,
                        letterSpacing = 1.sp
                    )
                    Spacer(Modifier.height(10.dp))

                    if (track != null) {
                        Text(
                            track!!.rawTitle.ifBlank { track!!.title },
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = LabelPrimary
                        )
                        Text(
                            track!!.rawArtist.ifBlank { track!!.artist },
                            fontSize = 14.sp,
                            color = LabelSecondary
                        )

                        Spacer(Modifier.height(14.dp))

                        // Live lyric line
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(SystemGray6)
                                .clickable(onClick = onOpenFullLyrics)
                                .padding(14.dp)
                        ) {
                            AnimatedContent(
                                targetState = syncState.currentLine?.text ?: when (status) {
                                    "searching" -> "Searching in LRCLIB & LRCMux…"
                                    "not_found" -> "No synchronized lyrics found"
                                    "found" -> "♪"
                                    else -> "♪"
                                },
                                transitionSpec = { fadeIn() togetherWith fadeOut() },
                                label = "lyricLine"
                            ) { text ->
                                Text(
                                    text = text,
                                    fontSize = 15.sp,
                                    fontWeight = if (syncState.currentLine != null) FontWeight.SemiBold else FontWeight.Normal,
                                    color = if (syncState.currentLine != null) ActiveLyricColor else LabelSecondary
                                )
                            }
                        }

                        doc?.let {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "via ${it.provider.uppercase()}  ·  ${it.syncPrecision.name.lowercase()} sync",
                                fontSize = 11.sp,
                                color = LabelTertiary
                            )
                        }

                        Spacer(Modifier.height(14.dp))

                        Button(
                            onClick = onOpenFullLyrics,
                            colors = ButtonDefaults.buttonColors(containerColor = AppleRed),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Lyrics, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Full Lyrics", fontWeight = FontWeight.SemiBold)
                        }
                    } else {
                        Text(
                            if (isRunning) "No media playing — start any music app."
                            else "Waiting for permission…",
                            fontSize = 14.sp,
                            color = LabelSecondary,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp)
                        )
                    }
                }
            }

            // ── 3. Preferences ────────────────────────────────────────────
            LyricaCard {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "PREFERENCES",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = AppleRed,
                        letterSpacing = 1.sp
                    )
                    Spacer(Modifier.height(8.dp))
                    HorizontalDivider(color = Separator)
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Word-level sync", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            Text(
                                "Karaoke-style word highlighting (LRCMux)",
                                fontSize = 12.sp,
                                color = LabelSecondary
                            )
                        }
                        Switch(
                            checked = wordLevel.value,
                            onCheckedChange = {
                                wordLevel.value = it
                                storage.putBoolean("word_level_sync", it)
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = AppleRed
                            )
                        )
                    }

                    Spacer(Modifier.height(8.dp))
                    HorizontalDivider(color = Separator)
                    Spacer(Modifier.height(8.dp))

                    // Provider status
                    Text("Active Providers", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(6.dp))
                    ProviderRegistry.getAll().forEach { provider ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(provider.displayName, fontSize = 13.sp, color = LabelSecondary)
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = if (provider.isAvailable()) Color(0xFFE8F5E9) else Color(0xFFFBE9E7)
                            ) {
                                Text(
                                    if (provider.isAvailable()) "Active" else "Unavailable",
                                    fontSize = 11.sp,
                                    color = if (provider.isAvailable()) Color(0xFF2E7D32) else AppleRed,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun LyricaCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        content()
    }
}

@Composable
private fun PermStep(num: String, text: String) {
    Row(modifier = Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Surface(shape = RoundedCornerShape(50), color = SystemGray5, modifier = Modifier.size(22.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Text(num, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = LabelSecondary)
            }
        }
        Spacer(Modifier.width(10.dp))
        Text(text, fontSize = 13.sp, color = LabelSecondary)
    }
}
