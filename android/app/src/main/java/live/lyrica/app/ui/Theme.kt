package live.lyrica.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val BackgroundDark = Color(0xFF0C0E14)
val SurfaceDark = Color(0xFF161922)
val SurfaceVariantDark = Color(0xFF222634)
val PrimaryIndigo = Color(0xFF6366F1)
val PrimaryIndigoLight = Color(0xFFA5B4FC)
val SecondaryEmerald = Color(0xFF10B981)
val TextPrimaryDark = Color(0xFFF8FAFC)
val TextSecondaryDark = Color(0xFF94A3B8)
val TextMutedDark = Color(0xFF475569)
val ActiveLyricHighlight = Color(0xFFE0E7FF)

private val DarkColorScheme = darkColorScheme(
    primary = PrimaryIndigo,
    onPrimary = Color.White,
    primaryContainer = SurfaceVariantDark,
    onPrimaryContainer = PrimaryIndigoLight,
    secondary = SecondaryEmerald,
    onSecondary = Color.White,
    background = BackgroundDark,
    onBackground = TextPrimaryDark,
    surface = SurfaceDark,
    onSurface = TextPrimaryDark,
    surfaceVariant = SurfaceVariantDark,
    onSurfaceVariant = TextSecondaryDark
)

@Composable
fun LyricaTheme(
    darkTheme: Boolean = true, // Lyrica Live is dark-first
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        content = content
    )
}
