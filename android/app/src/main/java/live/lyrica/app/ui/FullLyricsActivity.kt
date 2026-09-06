package live.lyrica.app.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import live.lyrica.app.core.mediasession.LyricaMediaSessionListenerService
import live.lyrica.app.core.model.LyricLine

class FullLyricsActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            LyricaTheme {
                FullLyricsScreen(
                    onBack = { finish() },
                    onSeek = { posMs ->
                        LyricaMediaSessionListenerService.instance?.mediaSessionMonitor?.seekTo(posMs)
                    },
                    onTogglePlay = {
                        LyricaMediaSessionListenerService.instance?.mediaSessionMonitor?.togglePlayPause()
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FullLyricsScreen(
    onBack: () -> Unit,
    onSeek: (Long) -> Unit,
    onTogglePlay: () -> Unit
) {
    val track by LyricaMediaSessionListenerService.currentTrackFlow.collectAsState()
    val lyricsDoc by LyricaMediaSessionListenerService.currentLyricsFlow.collectAsState()
    val syncState by LyricaMediaSessionListenerService.syncStateFlow.collectAsState()
    val isPlaying by LyricaMediaSessionListenerService.isPlayingFlow.collectAsState()
    val positionMs by LyricaMediaSessionListenerService.playbackPositionFlow.collectAsState()

    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    // Smooth auto-scroll to current lyric line
    LaunchedEffect(syncState.lineIndex) {
        if (syncState.lineIndex >= 0 && lyricsDoc != null && lyricsDoc!!.lines.isNotEmpty()) {
            val target = maxOf(0, syncState.lineIndex - 2)
            coroutineScope.launch {
                listState.animateScrollToItem(target)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = track?.rawTitle?.ifBlank { "Lyrica Live" } ?: "No Media Playing",
                            fontSize = 17.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = TextPrimaryDark,
                            maxLines = 1
                        )
                        Text(
                            text = track?.rawArtist?.ifBlank { "Detecting..." } ?: "",
                            fontSize = 13.sp,
                            color = TextSecondaryDark,
                            maxLines = 1
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = TextPrimaryDark)
                    }
                },
                actions = {
                    lyricsDoc?.provider?.let { provider ->
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = SurfaceVariantDark,
                            modifier = Modifier.padding(end = 8.dp)
                        ) {
                            Text(
                                text = provider.uppercase(),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = PrimaryIndigoLight,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }
                    IconButton(onClick = onTogglePlay) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (isPlaying) "Pause" else "Play",
                            tint = PrimaryIndigoLight
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BackgroundDark)
            )
        },
        containerColor = BackgroundDark
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            val doc = lyricsDoc
            if (doc != null && doc.lines.isNotEmpty()) {
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(top = 32.dp, bottom = 120.dp, start = 20.dp, end = 20.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    itemsIndexed(doc.lines, key = { idx, line -> line.id.ifEmpty { "$idx" } }) { idx, line ->
                        val isActive = idx == syncState.lineIndex
                        LyricLineItem(
                            line = line,
                            isActive = isActive,
                            currentWordIndex = if (isActive) syncState.wordIndex else -1,
                            onClick = { onSeek(line.startMs) }
                        )
                    }
                }
            } else if (doc != null && doc.plainLyrics.isNotBlank()) {
                // Plain unsynced lyrics fallback
                LazyColumn(
                    contentPadding = PaddingValues(24.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    item {
                        Text(
                            text = doc.plainLyrics,
                            color = TextSecondaryDark,
                            fontSize = 18.sp,
                            lineHeight = 28.sp,
                            textAlign = TextAlign.Start
                        )
                    }
                }
            } else {
                // Empty / Searching state
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    CircularProgressIndicator(color = PrimaryIndigo, modifier = Modifier.size(36.dp))
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = if (track != null) "Searching lyrics for '${track?.normalizedTitle}'..." else "Waiting for music playback...",
                        color = TextSecondaryDark,
                        fontSize = 14.sp,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

@Composable
fun LyricLineItem(
    line: LyricLine,
    isActive: Boolean,
    currentWordIndex: Int,
    onClick: () -> Unit
) {
    val textColor by animateColorAsState(
        targetValue = if (isActive) ActiveLyricHighlight else TextMutedDark,
        animationSpec = tween(durationMillis = 200),
        label = "lyricTextColor"
    )

    val bgColor by animateColorAsState(
        targetValue = if (isActive) SurfaceVariantDark.copy(alpha = 0.5f) else BackgroundDark,
        animationSpec = tween(durationMillis = 200),
        label = "lyricBgColor"
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(bgColor)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        if (line.words.isNotEmpty() && isActive) {
            // Word-level rendering
            Row(modifier = Modifier.fillMaxWidth()) {
                line.words.forEachIndexed { wIdx, word ->
                    val isWordActive = wIdx == currentWordIndex
                    Text(
                        text = "${word.text} ",
                        fontSize = if (isActive) 22.sp else 17.sp,
                        fontWeight = if (isWordActive) FontWeight.ExtraBold else FontWeight.SemiBold,
                        color = if (isWordActive) Color.White else PrimaryIndigoLight
                    )
                }
            }
        } else {
            Text(
                text = line.text,
                fontSize = if (isActive) 21.sp else 17.sp,
                fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                color = textColor,
                lineHeight = 28.sp
            )
        }
    }
}
