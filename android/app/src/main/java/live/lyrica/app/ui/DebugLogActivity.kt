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
import androidx.compose.material.icons.filled.ArrowBackIosNew
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
import androidx.compose.ui.text.style.TextAlign
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

    var logEntries by remember { mutableStateOf(LyricaLogger.logBuffer.toList()) }
    var filterLevel by remember { mutableStateOf<LyricaLogger.Level?>(null) }
    var autoScroll by remember { mutableStateOf(true) }

    val listState = rememberLazyListState()

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
                        Text("Debug Logs", fontWeight = FontWeight.Bold, fontSize = 17.sp)
                        Text(
                            "${filtered.size} entries  •  DEV ONLY",
                            fontSize = 11.sp,
                            color = Color(0xFFD97706)
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBackIosNew, contentDescription = "Back", tint = AppleRed)
                    }
                },
                actions = {
                    IconButton(onClick = {
                        val text = LyricaLogger.getAllLogsAsText()
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("Lyrica Logs", text))
                        Toast.makeText(context, "Copied ${logEntries.size} entries", Toast.LENGTH_SHORT).show()
                    }) {
                        Icon(Icons.Default.ContentCopy, contentDescription = "Copy", tint = AppleRed)
                    }
                    IconButton(onClick = {
                        LyricaLogger.clearBuffer()
                        logEntries = emptyList()
                    }) {
                        Icon(Icons.Default.Delete, contentDescription = "Clear", tint = Color(0xFFEF4444))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.White)
            )
        },
        containerColor = SystemGray6
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding)
        ) {
            // Level filter chips
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                LevelChip("ALL", filterLevel == null, AppleRed) { filterLevel = null }
                LevelChip("D", filterLevel == LyricaLogger.Level.DEBUG, SystemGray) { filterLevel = LyricaLogger.Level.DEBUG }
                LevelChip("I", filterLevel == LyricaLogger.Level.INFO, Color(0xFF22C55E)) { filterLevel = LyricaLogger.Level.INFO }
                LevelChip("W", filterLevel == LyricaLogger.Level.WARN, Color(0xFFF59E0B)) { filterLevel = LyricaLogger.Level.WARN }
                LevelChip("E", filterLevel == LyricaLogger.Level.ERROR, Color(0xFFEF4444)) { filterLevel = LyricaLogger.Level.ERROR }

                Spacer(Modifier.weight(1f))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Scroll", fontSize = 11.sp, color = LabelSecondary)
                    Switch(
                        checked = autoScroll,
                        onCheckedChange = { autoScroll = it },
                        modifier = Modifier.height(24.dp).padding(start = 4.dp),
                        colors = SwitchDefaults.colors(checkedTrackColor = AppleRed, checkedThumbColor = Color.White)
                    )
                }
            }

            HorizontalDivider(color = Separator)

            if (filtered.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "No log entries yet.\nStart playing music to see activity.",
                        color = LabelSecondary,
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center
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
private fun LevelChip(label: String, selected: Boolean, color: Color, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        color = if (selected) color.copy(alpha = 0.15f) else Color.Transparent,
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
        LyricaLogger.Level.DEBUG -> SystemGray
        LyricaLogger.Level.INFO  -> Color(0xFF22C55E)
        LyricaLogger.Level.WARN  -> Color(0xFFF59E0B)
        LyricaLogger.Level.ERROR -> Color(0xFFEF4444)
    }
    val bgColor = when (entry.level) {
        LyricaLogger.Level.ERROR -> Color(0xFFEF4444).copy(alpha = 0.05f)
        LyricaLogger.Level.WARN  -> Color(0xFFF59E0B).copy(alpha = 0.04f)
        else -> Color.Transparent
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(bgColor)
            .padding(horizontal = 10.dp, vertical = 3.dp)
    ) {
        Text(
            text = entry.level.name.take(1),
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            color = levelColor,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.width(12.dp)
        )
        Spacer(Modifier.width(6.dp))
        Column {
            Row {
                Text(
                    text = entry.tag,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = AppleRed,
                    fontFamily = FontFamily.Monospace
                )
                Spacer(Modifier.width(6.dp))
                val fmt = remember { java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US) }
                Text(
                    text = fmt.format(java.util.Date(entry.timestampMs)),
                    fontSize = 10.sp,
                    color = LabelTertiary,
                    fontFamily = FontFamily.Monospace
                )
            }
            Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
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
    HorizontalDivider(color = Separator.copy(alpha = 0.5f), thickness = 0.5.dp)
}
