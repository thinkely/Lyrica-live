package live.lyrica.app.ui.components

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import live.lyrica.app.core.model.LyricsDocument
import live.lyrica.app.core.model.TrackQuery
import live.lyrica.app.metadata.MetadataResolver
import live.lyrica.app.provider.router.LyricsCache
import live.lyrica.app.provider.router.ProviderRouter
import live.lyrica.app.service.LyricaForegroundService
import live.lyrica.app.ui.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManualLyricsSearchSheet(
    onDismiss: () -> Unit,
    onLyricsSelected: (TrackQuery, LyricsDocument) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var artistInput by remember { mutableStateOf("") }
    var titleInput by remember { mutableStateOf("") }
    var isSearching by remember { mutableStateOf(false) }
    var searchResultDoc by remember { mutableStateOf<LyricsDocument?>(null) }
    var searchedQuery by remember { mutableStateOf<TrackQuery?>(null) }
    var searchError by remember { mutableStateOf<String?>(null) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Color.White,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Search Lyrics Manually",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = LabelPrimary
                )
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "Close", tint = LabelSecondary)
                }
            }

            Text(
                "Search and view synced or plain lyrics for any song across LRCLIB, Spotify, and LRCMux.",
                fontSize = 13.sp,
                color = LabelSecondary,
                lineHeight = 18.sp
            )

            // Input Fields
            OutlinedTextField(
                value = artistInput,
                onValueChange = { artistInput = it },
                label = { Text("Artist Name") },
                placeholder = { Text("e.g. The Weeknd, Karan Aujla") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp)
            )

            OutlinedTextField(
                value = titleInput,
                onValueChange = { titleInput = it },
                label = { Text("Song Title *") },
                placeholder = { Text("e.g. Blinding Lights, 7.7 Magnitude") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp)
            )

            Button(
                onClick = {
                    if (titleInput.isBlank()) {
                        Toast.makeText(context, "Please enter a song title", Toast.LENGTH_SHORT).show()
                        return@Button
                    }
                    isSearching = true
                    searchError = null
                    searchResultDoc = null

                    scope.launch {
                        val query = MetadataResolver.resolve(titleInput.trim(), artistInput.trim())
                        searchedQuery = query
                        val router = ProviderRouter(LyricsCache())
                        val doc = withContext(Dispatchers.IO) {
                            router.resolve(query)
                        }
                        isSearching = false
                        if (doc != null && doc.lines.isNotEmpty()) {
                            searchResultDoc = doc
                        } else {
                            searchError = "No lyrics found for '$titleInput'"
                        }
                    }
                },
                enabled = !isSearching && titleInput.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = AppleRed),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                if (isSearching) {
                    CircularProgressIndicator(color = Color.White, modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("Searching Providers…", fontWeight = FontWeight.SemiBold)
                } else {
                    Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Search Lyrics", fontWeight = FontWeight.SemiBold)
                }
            }

            // Results View
            searchError?.let { err ->
                Surface(
                    color = Color(0xFFFBE9E7),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        err,
                        color = AppleRed,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }

            searchResultDoc?.let { doc ->
                Surface(
                    color = SystemGray6,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    doc.title,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 15.sp,
                                    color = LabelPrimary
                                )
                                Text(
                                    "${doc.artist} · ${doc.provider.uppercase()} (${doc.lines.size} lines)",
                                    fontSize = 12.sp,
                                    color = LabelSecondary
                                )
                            }
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = Color(0xFFE8F5E9)
                            ) {
                                Text(
                                    doc.syncPrecision.name,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF2E7D32),
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }

                        Spacer(Modifier.height(10.dp))

                        // Preview first 3 lines
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color.White)
                                .padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            doc.lines.take(3).forEach { line ->
                                Text(
                                    line.text,
                                    fontSize = 12.sp,
                                    color = LabelSecondary,
                                    maxLines = 1
                                )
                            }
                            if (doc.lines.size > 3) {
                                Text(
                                    "+ ${doc.lines.size - 3} more lines",
                                    fontSize = 11.sp,
                                    color = LabelTertiary
                                )
                            }
                        }

                        Spacer(Modifier.height(12.dp))

                        Button(
                            onClick = {
                                searchedQuery?.let { q ->
                                    onLyricsSelected(q, doc)
                                    onDismiss()
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = AppleRed),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Set as Current Song & View", fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
    }
}
