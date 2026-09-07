package live.lyrica.app.ui

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import live.lyrica.app.ai.AiLyricsService
import live.lyrica.app.ai.LyricsDisplayMode
import live.lyrica.app.core.model.LyricLine
import live.lyrica.app.core.model.LyricsDocument
import live.lyrica.app.security.SecureTokenStorage
import live.lyrica.app.service.LyricaForegroundService
import live.lyrica.app.ui.components.AiSettingsDialog

class FullLyricsActivity : ComponentActivity() {
    private lateinit var aiService: AiLyricsService

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        aiService = AiLyricsService(this)
        setContent {
            LyricaTheme {
                FullLyricsScreen(
                    aiService = aiService,
                    onBack = { finish() },
                    onSeek = { posMs ->
                        val intent = android.content.Intent(
                            this,
                            LyricaForegroundService::class.java
                        ).apply {
                            action = LyricaForegroundService.ACTION_SEEK
                            putExtra(LyricaForegroundService.EXTRA_SEEK_POSITION, posMs)
                        }
                        startService(intent)
                    },
                    onTogglePlay = {
                        val intent = android.content.Intent(
                            this,
                            LyricaForegroundService::class.java
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
    aiService: AiLyricsService,
    onBack: () -> Unit,
    onSeek: (Long) -> Unit,
    onTogglePlay: () -> Unit
) {
    val context = LocalContext.current
    val track     by LyricaForegroundService.currentQueryFlow.collectAsState()
    val doc       by LyricaForegroundService.currentLyricsFlow.collectAsState()
    val syncState by LyricaForegroundService.syncStateFlow.collectAsState()
    val isPlaying by LyricaForegroundService.isPlayingFlow.collectAsState()
    val status    by LyricaForegroundService.searchStatusFlow.collectAsState()

    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    // AI Translation state
    var displayMode by remember { mutableStateOf(LyricsDisplayMode.ORIGINAL) }
    var translatedDoc by remember { mutableStateOf<LyricsDocument?>(null) }
    var romanizedDoc by remember { mutableStateOf<LyricsDocument?>(null) }
    var isAiLoading by remember { mutableStateOf(false) }
    var showAiSettings by remember { mutableStateOf(false) }

    // Reset AI state when track changes
    LaunchedEffect(track?.cacheKey) {
        displayMode = LyricsDisplayMode.ORIGINAL
        translatedDoc = null
        romanizedDoc = null
        isAiLoading = false
    }

    fun requestTranslation(mode: LyricsDisplayMode) {
        val originalDoc = doc ?: return
        val targetLang = aiService.getPreferredLanguage()

        if (!aiService.hasKeyForSelectedProvider()) {
            showAiSettings = true
            Toast.makeText(context, "Please set up your AI API Key first", Toast.LENGTH_SHORT).show()
            return
        }

        if (mode == LyricsDisplayMode.TRANSLATE || mode == LyricsDisplayMode.DUAL) {
            if (translatedDoc != null) {
                displayMode = mode
                return
            }
            isAiLoading = true
            scope.launch {
                val res = aiService.translateOrRomanize(originalDoc, targetLang, isRomanize = false)
                isAiLoading = false
                res.fold(
                    onSuccess = {
                        translatedDoc = it
                        displayMode = mode
                    },
                    onFailure = {
                        Toast.makeText(context, "Translation failed: ${it.message}", Toast.LENGTH_LONG).show()
                    }
                )
            }
        } else if (mode == LyricsDisplayMode.TRANSLITERATE) {
            if (romanizedDoc != null) {
                displayMode = mode
                return
            }
            isAiLoading = true
            scope.launch {
                val res = aiService.translateOrRomanize(originalDoc, targetLang, isRomanize = true)
                isAiLoading = false
                res.fold(
                    onSuccess = {
                        romanizedDoc = it
                        displayMode = mode
                    },
                    onFailure = {
                        Toast.makeText(context, "Romanization failed: ${it.message}", Toast.LENGTH_LONG).show()
                    }
                )
            }
        } else {
            displayMode = LyricsDisplayMode.ORIGINAL
        }
    }

    // User scroll suppression
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

    if (showAiSettings) {
        AiSettingsDialog(
            aiService = aiService,
            onDismiss = { showAiSettings = false },
            onSaved = {
                Toast.makeText(context, "AI Settings Saved", Toast.LENGTH_SHORT).show()
            }
        )
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
                    IconButton(onClick = { showAiSettings = true }) {
                        Icon(Icons.Default.Translate, contentDescription = "AI Translation Settings", tint = AppleRed)
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
        val originalDoc = doc
        if (originalDoc != null && originalDoc.lines.isNotEmpty()) {
            // Determine active doc to display
            val activeLinesDoc = when (displayMode) {
                LyricsDisplayMode.TRANSLATE -> translatedDoc ?: originalDoc
                LyricsDisplayMode.TRANSLITERATE -> romanizedDoc ?: originalDoc
                LyricsDisplayMode.DUAL, LyricsDisplayMode.ORIGINAL -> originalDoc
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(pad)
            ) {
                // ── AI Translation Mode Selector ───────────────────────────
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.White)
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    LyricsDisplayMode.values().forEach { mode ->
                        val isSelected = displayMode == mode
                        FilterChip(
                            selected = isSelected,
                            onClick = {
                                if (mode == LyricsDisplayMode.ORIGINAL) {
                                    displayMode = mode
                                } else {
                                    requestTranslation(mode)
                                }
                            },
                            label = { Text(mode.displayName, fontSize = 12.sp, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = AppleRed.copy(alpha = 0.15f),
                                selectedLabelColor = AppleRed
                            )
                        )
                    }

                    if (isAiLoading) {
                        Spacer(Modifier.weight(1f))
                        CircularProgressIndicator(
                            color = AppleRed,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }

                HorizontalDivider(color = Separator)

                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(
                        top = 16.dp, bottom = 120.dp, start = 20.dp, end = 20.dp
                    ),
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    itemsIndexed(
                        activeLinesDoc.lines,
                        key = { idx, line -> line.id.ifEmpty { idx.toString() } }
                    ) { idx, line ->
                        val isActive = idx == syncState.lineIndex
                        val subtitleLine = if (displayMode == LyricsDisplayMode.DUAL) {
                            translatedDoc?.lines?.getOrNull(idx)?.text
                        } else null

                        LyricLineRow(
                            line = line,
                            subtitle = subtitleLine,
                            isActive = isActive,
                            currentWordIdx = if (isActive && displayMode == LyricsDisplayMode.ORIGINAL) syncState.wordIndex else -1,
                            onClick = {
                                lastUserScrollMs = 0L   // Reset scroll suppression on seek tap
                                onSeek(line.startMs)
                            }
                        )
                    }
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
    subtitle: String?,
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
            .padding(vertical = 4.dp, horizontal = 4.dp)
    ) {
        Column {
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
                    lineHeight = (fontSize.value * 1.35).sp
                )
            }

            // Dual mode translation subtitle
            if (!subtitle.isNullOrBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    fontSize = (fontSize.value * 0.75).sp,
                    color = if (isActive) AppleRed.copy(alpha = 0.85f) else LabelTertiary,
                    fontStyle = FontStyle.Italic,
                    lineHeight = (fontSize.value * 1.05).sp
                )
            }
        }
    }
}
