package live.lyrica.app.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.service.notification.NotificationListenerService
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import live.lyrica.app.mediasession.LyricaMediaSessionListenerService
import live.lyrica.app.provider.ProviderRegistry
import live.lyrica.app.provider.impl.SpotifyLyricsProvider
import live.lyrica.app.security.SecureTokenStorage
import live.lyrica.app.service.LyricaForegroundService
import live.lyrica.app.ui.components.AiSettingsDialog
import live.lyrica.app.ui.components.CustomizationSettingsDialog
import live.lyrica.app.ui.components.ManualLyricsSearchSheet

class MainActivity : ComponentActivity() {
    private lateinit var storage: SecureTokenStorage
    private val isPermissionGrantedState = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        storage = SecureTokenStorage(this)
        checkAndStartServices()

        setContent {
            LyricaTheme {
                MainScreen(
                    storage = storage,
                    isPermissionGranted = isPermissionGrantedState.value,
                    onGrantPermission = { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) },
                    onOpenFullLyrics  = { startActivity(Intent(this, FullLyricsActivity::class.java)) },
                    onOpenDebugLogs   = { startActivity(Intent(this, DebugLogActivity::class.java)) },
                    onOpenSpotifyLogin = { startActivity(Intent(this, SpotifyLoginActivity::class.java)) }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        checkAndStartServices()
    }

    private fun checkAndStartServices() {
        val granted = isNotificationAccessGranted(this)
        isPermissionGrantedState.value = granted

        if (granted) {
            // Start foreground service if not already running
            val fgIntent = Intent(this, LyricaForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(fgIntent)
            } else {
                startService(fgIntent)
            }

            // Re-bind notification listener if app was killed and restored
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                try {
                    NotificationListenerService.requestRebind(
                        ComponentName(this, LyricaMediaSessionListenerService::class.java)
                    )
                } catch (_: Exception) {}
            }
            LyricaMediaSessionListenerService.instance?.requestActiveSessionsRefresh()
        }
    }

    private fun isNotificationAccessGranted(context: Context): Boolean {
        val pkgName = context.packageName
        val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners") ?: ""
        return flat.contains(pkgName)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    storage: SecureTokenStorage,
    isPermissionGranted: Boolean,
    onGrantPermission: () -> Unit,
    onOpenFullLyrics: () -> Unit,
    onOpenDebugLogs: () -> Unit,
    onOpenSpotifyLogin: () -> Unit
) {
    val isRunning  by LyricaForegroundService.isServiceRunningFlow.collectAsState()
    val track      by LyricaForegroundService.currentQueryFlow.collectAsState()
    val doc        by LyricaForegroundService.currentLyricsFlow.collectAsState()
    val albumArt   by LyricaForegroundService.currentAlbumArtFlow.collectAsState()
    val syncState  by LyricaForegroundService.syncStateFlow.collectAsState()
    val isPlaying  by LyricaForegroundService.isPlayingFlow.collectAsState()
    val status     by LyricaForegroundService.searchStatusFlow.collectAsState()

    var showPermSteps by remember { mutableStateOf(false) }
    var showManualSearch by remember { mutableStateOf(false) }
    var showCustomization by remember { mutableStateOf(false) }
    var showAiDialog by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val aiService = remember { live.lyrica.app.ai.AiLyricsService(context) }

    if (showManualSearch) {
        ManualLyricsSearchSheet(
            onDismiss = { showManualSearch = false },
            onLyricsSelected = { query, selectedDoc ->
                LyricaForegroundService.instance?.setManualLyrics(query, selectedDoc)
                onOpenFullLyrics()
            }
        )
    }

    if (showCustomization) {
        CustomizationSettingsDialog(
            storage = storage,
            onDismiss = { showCustomization = false },
            onConnectSpotify = onOpenSpotifyLogin
        )
    }

    if (showAiDialog) {
        AiSettingsDialog(
            aiService = aiService,
            onDismiss = { showAiDialog = false },
            onSaved = { showAiDialog = false }
        )
    }

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
                    IconButton(onClick = { showManualSearch = true }) {
                        Icon(Icons.Default.Search, contentDescription = "Search", tint = LabelPrimary)
                    }
                    IconButton(onClick = { showCustomization = true }) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings", tint = LabelPrimary)
                    }
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
            // ── 1. Permission Card (only shown when permission is NOT granted) ──
            AnimatedVisibility(!isPermissionGranted, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
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
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "NOW PLAYING",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = AppleRed,
                            letterSpacing = 1.sp
                        )
                        if (track != null) {
                            Text(
                                if (isPlaying) "PLAYING" else "PAUSED",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isPlaying) Color(0xFF2E7D32) else LabelTertiary,
                                letterSpacing = 0.5.sp
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))

                    val currentTrack = track
                    if (currentTrack != null) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            // Rounded HD Album Art
                            val art = albumArt
                            if (art != null) {
                                Image(
                                    bitmap = art.asImageBitmap(),
                                    contentDescription = "Album Art",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .size(54.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                )
                                Spacer(Modifier.width(12.dp))
                            }

                            Column(Modifier.weight(1f)) {
                                Text(
                                    currentTrack.rawTitle.ifBlank { currentTrack.title },
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = LabelPrimary,
                                    maxLines = 1
                                )
                                Text(
                                    currentTrack.rawArtist.ifBlank { currentTrack.artist },
                                    fontSize = 14.sp,
                                    color = LabelSecondary,
                                    maxLines = 1
                                )
                            }
                        }

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
                                    "searching" -> "Searching lyrics across LRCLIB, Spotify & LRCMux…"
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
                            Text("Full Lyrics View", fontWeight = FontWeight.SemiBold)
                        }
                    } else {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                if (isRunning) "No media playing — start Spotify, YouTube Music, or any player."
                                else "Waiting for permission…",
                                fontSize = 14.sp,
                                color = LabelSecondary,
                                textAlign = TextAlign.Center
                            )
                            Spacer(Modifier.height(10.dp))
                            OutlinedButton(
                                onClick = { showManualSearch = true },
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Search Lyrics Manually", fontSize = 13.sp)
                            }
                        }
                    }
                }
            }

            // ── 3. Quick Actions & Preferences Card ─────────────────────────
            LyricaCard {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "FEATURES & PROVIDERS",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = AppleRed,
                        letterSpacing = 1.sp
                    )
                    Spacer(Modifier.height(8.dp))
                    HorizontalDivider(color = Separator)
                    Spacer(Modifier.height(8.dp))

                    // AI Translation & Romanize row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("AI Translation & Romanize", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            Text(
                                "${aiService.getSelectedProvider().displayName} · ${aiService.getPreferredLanguage()}",
                                fontSize = 12.sp,
                                color = LabelSecondary
                            )
                        }
                        OutlinedButton(
                            onClick = { showAiDialog = true },
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text(
                                if (aiService.hasKeyForSelectedProvider()) "Configured" else "Set Key",
                                fontSize = 12.sp,
                                color = if (aiService.hasKeyForSelectedProvider()) Color(0xFF2E7D32) else AppleRed,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }

                    Spacer(Modifier.height(8.dp))
                    HorizontalDivider(color = Separator)
                    Spacer(Modifier.height(8.dp))

                    // Customization / Settings shortcut
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showCustomization = true }
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Customization & Kill Switch", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            Text("Configure RAM kill switch, Spotify login, HD art, and sync offsets", fontSize = 12.sp, color = LabelSecondary)
                        }
                        Icon(Icons.Default.ChevronRight, contentDescription = null, tint = LabelTertiary)
                    }

                    Spacer(Modifier.height(8.dp))
                    HorizontalDivider(color = Separator)
                    Spacer(Modifier.height(8.dp))

                    // Active Providers List
                    Text("Active Lyrics Providers", fontSize = 14.sp, fontWeight = FontWeight.Medium)
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
