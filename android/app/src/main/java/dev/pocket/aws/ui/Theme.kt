package dev.pocket.aws.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

@Immutable
data class Palette(
    val bg: Color, val surface: Color, val surface2: Color, val border: Color,
    val text: Color, val muted: Color, val faint: Color,
    val accent: Color, val onAccent: Color, val good: Color, val warn: Color, val bad: Color,
    val dark: Boolean,
)

private val Dark = Palette(
    bg = Color(0xFF0B0C0E), surface = Color(0xFF141518), surface2 = Color(0xFF1C1E22), border = Color(0xFF24262C),
    text = Color(0xFFF2F3F5), muted = Color(0xFF8D919A), faint = Color(0xFF5A5E66),
    accent = Color(0xFF7C9CFF), onAccent = Color(0xFF0B0C0E), good = Color(0xFF4ADE80), warn = Color(0xFFFBBF24), bad = Color(0xFFF87171),
    dark = true,
)
private val Light = Palette(
    bg = Color(0xFFFAFAFA), surface = Color(0xFFFFFFFF), surface2 = Color(0xFFF2F3F5), border = Color(0xFFE6E7EA),
    text = Color(0xFF0E0F12), muted = Color(0xFF6B6F78), faint = Color(0xFFA3A7AF),
    accent = Color(0xFF3B5BFD), onAccent = Color.White, good = Color(0xFF16A34A), warn = Color(0xFFD97706), bad = Color(0xFFDC2626),
    dark = false,
)

val LocalPalette = staticCompositionLocalOf { Dark }
val P: Palette @Composable get() = LocalPalette.current

val Mono = FontFamily.Monospace

@Composable
fun PocketTheme(content: @Composable () -> Unit) {
    val p = if (isSystemInDarkTheme()) Dark else Light
    val scheme = (if (p.dark) darkColorScheme() else lightColorScheme()).copy(
        primary = p.accent, onPrimary = p.onAccent, background = p.bg, onBackground = p.text,
        surface = p.surface, onSurface = p.text, surfaceVariant = p.surface2, onSurfaceVariant = p.muted,
        outline = p.border, outlineVariant = p.border, error = p.bad, surfaceContainer = p.surface,
        surfaceContainerHigh = p.surface2, surfaceContainerHighest = p.surface2, surfaceContainerLow = p.surface,
        secondaryContainer = p.surface2, onSecondaryContainer = p.text,
    )
    val base = Typography()
    val type = base.copy(
        headlineMedium = TextStyle(fontSize = 28.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.6).sp, lineHeight = 34.sp),
        titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.3).sp),
        titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium, letterSpacing = (-0.1).sp),
        bodyLarge = TextStyle(fontSize = 15.sp, lineHeight = 21.sp),
        bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 19.sp),
        bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 16.sp),
        labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium),
        labelMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.2.sp),
        labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.4.sp),
    )
    CompositionLocalProvider(LocalPalette provides p) {
        MaterialTheme(colorScheme = scheme, typography = type, content = content)
    }
}
