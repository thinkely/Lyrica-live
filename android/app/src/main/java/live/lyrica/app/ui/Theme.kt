package live.lyrica.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// ── Apple Music-inspired design tokens ──────────────────────────────────────
val AppleRed      = Color(0xFFFA233B)   // Apple Music primary red
val AppleRedLight = Color(0xFFFF6B81)   // lighter red for highlights
val SystemGray    = Color(0xFF8E8E93)   // iOS system gray
val SystemGray2   = Color(0xFFAEAEB2)
val SystemGray3   = Color(0xFFC7C7CC)
val SystemGray4   = Color(0xFFD1D1D6)
val SystemGray5   = Color(0xFFE5E5EA)
val SystemGray6   = Color(0xFFF2F2F7)   // background
val LabelPrimary  = Color(0xFF000000)
val LabelSecondary = Color(0xFF3C3C43).copy(alpha = 0.6f)
val LabelTertiary  = Color(0xFF3C3C43).copy(alpha = 0.3f)
val Separator      = Color(0xFF3C3C43).copy(alpha = 0.12f)

// Active lyric line — Apple Music red gradient effect
val ActiveLyricColor = AppleRed

private val LyricaLightColors = lightColorScheme(
    primary          = AppleRed,
    onPrimary        = Color.White,
    primaryContainer = Color(0xFFFFE5E8),
    secondary        = Color(0xFF6C6C70),
    onSecondary      = Color.White,
    background       = SystemGray6,
    onBackground     = LabelPrimary,
    surface          = Color.White,
    onSurface        = LabelPrimary,
    surfaceVariant   = SystemGray5,
    onSurfaceVariant = Color(0xFF3C3C43),
    outline          = Separator
)

@Composable
fun LyricaTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = LyricaLightColors,
        content = content
    )
}
