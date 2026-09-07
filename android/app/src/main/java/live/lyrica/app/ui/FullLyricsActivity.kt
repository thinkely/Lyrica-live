package live.lyrica.app.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBackIosNew
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import live.lyrica.app.core.model.LyricLine
import live.lyrica.app.service.LyricaForegroundService

class FullLyricsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            LyricaTheme {
                FullLyricsScreen(
                    onBack = { finish() },
                    onSeek = { posMs ->
                        val intent = android.content.Intent(
                            this,
                            live.lyrica.app.service.LyricaForegroundService::class.java
                        ).apply {
                            action = LyricaForegroundService.ACTION_SEEK
                            putExtra(LyricaForegroundService.EXTRA_SEEK_POSITION, posMs)
                        }
                        startService(intent)
                    },
                    onTogglePlay = {
                        val intent = android.content.Intent(
                            this,
                            live.lyrica.app.service.LyricaForegroundService::class.java
                        ).apply {
                            action = LyricaForegroundService.ACTION_TOGGLE_PLAY
                        }
                        startService(intent)
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
    val track     by LyricaForegroundService.currentQueryFlow.collectAsState()
    val doc       by LyricaForegroundService.currentLyricsFlow.collectAsState()
    val syncState by LyricaForegroundService.syncStateFlow.collectAsState()
    val isPlaying by LyricaForegroundService.isPlayingFlow.collectAsState()
    val status    by LyricaForegroundService.searchStatusFlow.collectAsState()

    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    // User scroll suppression — same logic from previous version
    var lastUserScrollMs by remember { mutableLongStateOf(0L) }
    val SCROLL_PAUSE_MS = 5000L

    LaunchedEffect(listState.isScrollInProgress) {
        if (listState.isScrollInProgress) lastUserScrollMs = System.currentTimeMillis()
    }

    LaunchedEffect(syncState.lineIndex) {
        val elapsed = System.currentTimeMillis() - lastUserScrollMs
        if (elapsed >= SCROLL_PAUSE_MS && syncState.lineIndex >= 0 && doc != null) {
            val target = maxOf(0, syncState.lineIndex - 2)
            scope.launch { listState.animateScrollToItem(target) }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            track?.rawTitle?.ifBlank { track?.title ?: "Lyrics" } ?: "Lyrics",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = LabelPrimary,
                            maxLines = 1
                        )
                        Text(
                            track?.rawArtist?.ifBlank { track?.artist ?: "" } ?: "",
                            fontSize = 12.sp,
                            color = LabelSecondary,
                            maxLines = 1
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBackIosNew, contentDescription = "Back", tint = AppleRed)
                    }
                },
                actions = {
                    doc?.let {
                        Text(
                            it.provider.uppercase(),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = AppleRed,
                            modifier = Modifier.padding(end = 8.dp)
                        )
                    }
                    IconButton(onClick = onTogglePlay) {
                        Icon(
                            if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (isPlaying) "Pause" else "Play",
                            tint = AppleRed
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.White)
            )
        },
        containerColor = SystemGray6
    ) { pad ->
        val theDoc = doc
        if (theDoc != null && theDoc.lines.isNotEmpty()) {
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(
                    top = 24.dp, bottom = 120.dp, start = 20.dp, end = 20.dp
                ),
                modifier = Modifier.fillMaxSize().padding(pad),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                itemsIndexed(
                    theDoc.lines,
                    key = { idx, line -> line.id.ifEmpty { idx.toString() } }
                ) { idx, line ->
                    val isActive = idx == syncState.lineIndex
                    LyricLineRow(
                        line = line,
                        isActive = isActive,
                        currentWordIdx = if (isActive) syncState.wordIndex else -1,
                        onClick = {
                            lastUserScrollMs = 0L   // Reset scroll suppression on seek tap
                            onSeek(line.startMs)
                        }
                    )
                }
            }
        } else {
            Box(
                modifier = Modifier.fillMaxSize().padding(pad),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(32.dp)
                ) {
                    if (track != null) {
                        CircularProgressIndicator(color = AppleRed, modifier = Modifier.size(32.dp))
                        Spacer(Modifier.height(16.dp))
                        Text(
                            "Searching for lyrics",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = LabelPrimary
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "LRCLIB · LRCMux",
                            fontSize = 13.sp,
                            color = LabelSecondary
                        )
                        if (status == "not_found") {
                            Spacer(Modifier.height(10.dp))
                            Text(
                                "No synchronized lyrics found",
                                fontSize = 13.sp,
                                color = SystemGray2,
                                textAlign = TextAlign.Center
                            )
                        }
                    } else {
                        Icon(
                            Icons.Default.PlayArrow,
                            contentDescription = null,
                            tint = SystemGray3,
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "Start playing music",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = LabelSecondary
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LyricLineRow(
    line: LyricLine,
    isActive: Boolean,
    currentWordIdx: Int,
    onClick: () -> Unit
) {
    val sizeAnim by animateFloatAsState(
        targetValue = if (isActive) 1f else 0.85f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "lyricSize"
    )
    val alphaAnim by animateFloatAsState(
        targetValue = if (isActive) 1f else 0.35f,
        animationSpec = tween(200),
        label = "lyricAlpha"
    )

    val fontSize = (20 * sizeAnim).sp
    val activeColor = ActiveLyricColor
    val inactiveColor = LabelPrimary.copy(alpha = alphaAnim)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp, horizontal = 4.dp)
    ) {
        if (isActive && line.hasWordSync && currentWordIdx >= 0) {
            // ── Word-level karaoke rendering ──────────────────────────────
            Text(
                text = buildAnnotatedString {
                    line.words.forEachIndexed { idx, word ->
                        val isHighlighted = idx <= currentWordIdx
                        withStyle(
                            SpanStyle(
                                color = if (isHighlighted) activeColor else LabelPrimary.copy(alpha = 0.3f),
                                fontWeight = if (isHighlighted) FontWeight.Bold else FontWeight.Normal,
                                fontSize = fontSize
                            )
                        ) {
                            append(word.text)
                            if (idx < line.words.lastIndex) append(" ")
                        }
                    }
                }
            )
        } else {
            // ── Line-level rendering ───────────────────────────────────────
            Text(
                text = line.text,
                fontSize = fontSize,
                fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                color = if (isActive) activeColor else inactiveColor,
                lineHeight = (fontSize.value * 1.4).sp
            )
        }
    }
}
