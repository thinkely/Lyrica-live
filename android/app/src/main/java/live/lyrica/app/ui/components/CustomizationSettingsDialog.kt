package live.lyrica.app.ui.components

import android.content.Context
import android.content.Intent
import android.widget.Toast
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import live.lyrica.app.provider.impl.SpotifyLyricsProvider
import live.lyrica.app.security.SecureTokenStorage
import live.lyrica.app.ui.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomizationSettingsDialog(
    storage: SecureTokenStorage,
    onDismiss: () -> Unit,
    onConnectSpotify: () -> Unit
) {
    val context = LocalContext.current

    var killSwitch by remember { mutableStateOf(storage.getBoolean("kill_switch_on_close", false)) }
    var wordLevel by remember { mutableStateOf(storage.getBoolean("word_level_sync", true)) }
    var hdArt by remember { mutableStateOf(storage.getBoolean("hd_album_art", true)) }
    var syncOffset by remember { mutableFloatStateOf(storage.getFloat("sync_offset_ms", 0f)) }

    val hasSpotify = remember { mutableStateOf(!storage.getString(SpotifyLyricsProvider.KEY_SP_DC).isNullOrBlank()) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Color.White,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "App Customization & Settings",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = LabelPrimary
                )
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "Close", tint = LabelSecondary)
                }
            }

            // ── 1. Kill Switch ─────────────────────────────────────────────
            SettingCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Kill Switch (RAM / Recents)", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        Text(
                            "Automatically stop Lyrica Live and dismiss notifications when swiped away from Recents / RAM.",
                            fontSize = 12.sp,
                            color = LabelSecondary,
                            lineHeight = 16.sp
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Switch(
                        checked = killSwitch,
                        onCheckedChange = {
                            killSwitch = it
                            storage.putBoolean("kill_switch_on_close", it)
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = AppleRed
                        )
                    )
                }
            }

            // ── 2. Spotify Direct Extraction ──────────────────────────────
            SettingCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Spotify Lyrics Connection", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        Text(
                            if (hasSpotify.value) "Authenticated via Spotify Web Session"
                            else "Sign in to extract Color Lyrics directly from Spotify",
                            fontSize = 12.sp,
                            color = if (hasSpotify.value) Color(0xFF2E7D32) else LabelSecondary
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    if (hasSpotify.value) {
                        OutlinedButton(
                            onClick = {
                                storage.putString(SpotifyLyricsProvider.KEY_SP_DC, "")
                                storage.putString(SpotifyLyricsProvider.KEY_ACCESS_TOKEN, "")
                                hasSpotify.value = false
                                Toast.makeText(context, "Spotify disconnected", Toast.LENGTH_SHORT).show()
                            },
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = AppleRed),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                        ) {
                            Text("Disconnect", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }
                    } else {
                        Button(
                            onClick = onConnectSpotify,
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1DB954)),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                        ) {
                            Text("Sign In", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }

            // ── 3. High-Definition Album Art ───────────────────────────────
            SettingCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("HD Album Art Resolution", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        Text(
                            "Fetch 1000x1000 covers from iTunes, Deezer & MusicBrainz",
                            fontSize = 12.sp,
                            color = LabelSecondary
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Switch(
                        checked = hdArt,
                        onCheckedChange = {
                            hdArt = it
                            storage.putBoolean("hd_album_art", it)
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = AppleRed
                        )
                    )
                }
            }

            // ── 4. Word-Level Sync ─────────────────────────────────────────
            SettingCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Karaoke Word Highlight", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        Text(
                            "Highlight exact words as they are sung (when available)",
                            fontSize = 12.sp,
                            color = LabelSecondary
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Switch(
                        checked = wordLevel,
                        onCheckedChange = {
                            wordLevel = it
                            storage.putBoolean("word_level_sync", it)
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = AppleRed
                        )
                    )
                }
            }

            // ── 5. Sync Offset Slider ──────────────────────────────────────
            SettingCard {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Sync Offset Adjustment", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        Text(
                            "${syncOffset.toInt()} ms",
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            color = AppleRed
                        )
                    }
                    Slider(
                        value = syncOffset,
                        onValueChange = {
                            syncOffset = it
                            storage.putFloat("sync_offset_ms", it)
                        },
                        valueRange = -500f..500f,
                        steps = 19, // 50ms intervals
                        colors = SliderDefaults.colors(
                            thumbColor = AppleRed,
                            activeTrackColor = AppleRed
                        )
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("-500ms (Earlier)", fontSize = 11.sp, color = LabelTertiary)
                        Text("0ms", fontSize = 11.sp, color = LabelTertiary)
                        Text("+500ms (Later)", fontSize = 11.sp, color = LabelTertiary)
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingCard(content: @Composable () -> Unit) {
    Surface(
        color = SystemGray6,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Box(modifier = Modifier.padding(14.dp)) {
            content()
        }
    }
}
