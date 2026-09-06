package live.lyrica.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import live.lyrica.app.core.logging.LyricaLogger

class DebugLogActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            LyricaTheme {
                DebugLogScreen(onBack = { finish() })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebugLogScreen(onBack: () -> Unit) {
    val context = LocalContext.current

    // Collect log buffer as live state that refreshes every second
    var logEntries by remember { mutableStateOf(LyricaLogger.logBuffer.toList()) }
    var filterLevel by remember { mutableStateOf<LyricaLogger.Level?>(null) }
    var autoScroll by remember { mutableStateOf(true) }

    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    // Refresh logs every 800ms
    LaunchedEffect(Unit) {
        while (true) {
            logEntries = LyricaLogger.logBuffer.toList()
            if (autoScroll && logEntries.isNotEmpty()) {
                listState.animateScrollToItem(logEntries.size - 1)
            }
            kotlinx.coroutines.delay(800L)
        }
    }

    val filtered = if (filterLevel != null) logEntries.filter { it.level == filterLevel } else logEntries

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            "Debug Logs",
                            fontWeight = FontWeight.Bold,
                            fontSize = 17.sp,
                            color = TextPrimaryDark
                        )
                        Text(
                            "${filtered.size} entries  •  DEV ONLY",
                            fontSize = 11.sp,
                            color = Color(0xFFFBBF24)
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = TextPrimaryDark)
                    }
                },
                actions = {
                    // Copy all logs
                    IconButton(onClick = {
                        val text = LyricaLogger.getAllLogsAsText()
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("Lyrica Logs", text))
                        Toast.makeText(context, "Copied ${logEntries.size} log entries", Toast.LENGTH_SHORT).show()
                    }) {
                        Icon(Icons.Default.ContentCopy, contentDescription = "Copy logs", tint = PrimaryIndigoLight)
                    }
                    // Clear logs
                    IconButton(onClick = {
                        LyricaLogger.clearBuffer()
                        logEntries = emptyList()
                    }) {
                        Icon(Icons.Default.Delete, contentDescription = "Clear logs", tint = Color(0xFFEF4444))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF0D0D14))
            )
        },
        containerColor = Color(0xFF0D0D14)
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Level filter chips
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                LevelChip("ALL", filterLevel == null, Color(0xFF6366F1)) { filterLevel = null }
                LevelChip("DEBUG", filterLevel == LyricaLogger.Level.DEBUG, Color(0xFF6B7280)) { filterLevel = LyricaLogger.Level.DEBUG }
                LevelChip("INFO", filterLevel == LyricaLogger.Level.INFO, Color(0xFF22C55E)) { filterLevel = LyricaLogger.Level.INFO }
                LevelChip("WARN", filterLevel == LyricaLogger.Level.WARN, Color(0xFFFBBF24)) { filterLevel = LyricaLogger.Level.WARN }
                LevelChip("ERROR", filterLevel == LyricaLogger.Level.ERROR, Color(0xFFEF4444)) { filterLevel = LyricaLogger.Level.ERROR }
            }

            // Auto-scroll toggle
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Auto-scroll", fontSize = 12.sp, color = TextMutedDark)
                Spacer(modifier = Modifier.width(8.dp))
                Switch(
                    checked = autoScroll,
                    onCheckedChange = { autoScroll = it },
                    modifier = Modifier.height(24.dp),
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = PrimaryIndigo
                    )
                )
            }

            HorizontalDivider(color = SurfaceVariantDark, modifier = Modifier.padding(vertical = 4.dp))

            if (filtered.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "No log entries yet.\nStart playing music to see activity.",
                        color = TextMutedDark,
                        fontSize = 13.sp,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 32.dp)
                ) {
                    itemsIndexed(filtered) { _, entry ->
                        LogRow(entry)
                    }
                }
            }
        }
    }
}

@Composable
private fun LevelChip(
    label: String,
    selected: Boolean,
    color: Color,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        color = if (selected) color.copy(alpha = 0.3f) else Color.Transparent,
        border = androidx.compose.foundation.BorderStroke(
            width = 1.dp,
            color = if (selected) color else color.copy(alpha = 0.4f)
        )
    ) {
        Text(
            text = label,
            fontSize = 11.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = color,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
        )
    }
}

@Composable
private fun LogRow(entry: LyricaLogger.LogEntry) {
    val levelColor = when (entry.level) {
        LyricaLogger.Level.DEBUG -> Color(0xFF6B7280)
        LyricaLogger.Level.INFO  -> Color(0xFF22C55E)
        LyricaLogger.Level.WARN  -> Color(0xFFFBBF24)
        LyricaLogger.Level.ERROR -> Color(0xFFEF4444)
    }
    val bgColor = when (entry.level) {
        LyricaLogger.Level.ERROR -> Color(0xFFEF4444).copy(alpha = 0.05f)
        LyricaLogger.Level.WARN  -> Color(0xFFFBBF24).copy(alpha = 0.03f)
        else -> Color.Transparent
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(bgColor)
            .padding(horizontal = 10.dp, vertical = 3.dp)
    ) {
        // Level badge
        Text(
            text = entry.level.name.take(1),
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            color = levelColor,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.width(12.dp)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Column {
            // Tag + timestamp
            Row {
                Text(
                    text = entry.tag,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = PrimaryIndigoLight,
                    fontFamily = FontFamily.Monospace
                )
                Spacer(modifier = Modifier.width(6.dp))
                val fmt = remember { java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US) }
                Text(
                    text = fmt.format(java.util.Date(entry.timestampMs)),
                    fontSize = 10.sp,
                    color = TextMutedDark,
                    fontFamily = FontFamily.Monospace
                )
            }
            // Message (horizontal scroll for long lines)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
            ) {
                Text(
                    text = entry.message,
                    fontSize = 11.sp,
                    color = levelColor.copy(alpha = 0.9f),
                    fontFamily = FontFamily.Monospace,
                    maxLines = 3
                )
            }
        }
    }
    HorizontalDivider(color = SurfaceVariantDark.copy(alpha = 0.3f), thickness = 0.5.dp)
}
