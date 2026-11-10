package jxpix.proger.mps.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The colours of the app. There are exactly two looks: white with pink and black with pink.
 * Everything custom-drawn (grids, knobs, meters) takes its colours from here.
 */
@Immutable
class MpsColors(
    val dark: Boolean,
    /** Window background. */
    val bg: Color,
    /** Cards, bars, panels. */
    val surface: Color,
    /** Raised or pressed elements on a surface. */
    val surfaceHigh: Color,
    /** Hairlines and borders. */
    val line: Color,
    /** Stronger separators (bar lines of the grids). */
    val lineStrong: Color,
    val accent: Color,
    val onAccent: Color,
    /** Soft pink used for selected backgrounds. */
    val accentSoft: Color,
    val text: Color,
    val textDim: Color,
    /** Empty step of the step sequencer (two shades to group the beats). */
    val stepA: Color,
    val stepB: Color,
    /** Piano roll rows under white / black keys. */
    val rowWhite: Color,
    val rowBlack: Color,
    val keyWhite: Color,
    val keyBlack: Color,
    val record: Color,
    val meterLow: Color,
    val meterMid: Color,
    val meterHigh: Color,
    /** The eight channel colours. */
    val channels: List<Color>,
) {
    fun channel(index: Int): Color = channels[((index % channels.size) + channels.size) % channels.size]
}

private val ChannelColors = listOf(
    Color(0xFFFF4F96), // pink
    Color(0xFFFF8A3D), // orange
    Color(0xFFF5B800), // yellow
    Color(0xFF34C76F), // green
    Color(0xFF16BFB0), // teal
    Color(0xFF3F8EFF), // blue
    Color(0xFF8F63FF), // violet
    Color(0xFFDA4FD0), // magenta
)

val LightMps = MpsColors(
    dark = false,
    bg = Color(0xFFFFFFFF),
    surface = Color(0xFFFFF4F8),
    surfaceHigh = Color(0xFFFFE6F0),
    line = Color(0xFFF6D5E2),
    lineStrong = Color(0xFFE9A9C3),
    accent = Color(0xFFFF3E8A),
    onAccent = Color(0xFFFFFFFF),
    accentSoft = Color(0xFFFFD9E8),
    text = Color(0xFF1E1219),
    textDim = Color(0xFF7D6873),
    stepA = Color(0xFFFBE3EC),
    stepB = Color(0xFFF1EDEF),
    rowWhite = Color(0xFFFFFFFF),
    rowBlack = Color(0xFFFFF1F6),
    keyWhite = Color(0xFFFFFFFF),
    keyBlack = Color(0xFF2A1A22),
    record = Color(0xFFE5173F),
    meterLow = Color(0xFF34C76F),
    meterMid = Color(0xFFF5B800),
    meterHigh = Color(0xFFE5173F),
    channels = ChannelColors,
)

val DarkMps = MpsColors(
    dark = true,
    bg = Color(0xFF000000),
    surface = Color(0xFF131114),
    surfaceHigh = Color(0xFF201C22),
    line = Color(0xFF2A252C),
    lineStrong = Color(0xFF4A3F4C),
    accent = Color(0xFFFF4F96),
    onAccent = Color(0xFFFFFFFF),
    accentSoft = Color(0xFF45142A),
    text = Color(0xFFFDF4F8),
    textDim = Color(0xFFA9989F),
    stepA = Color(0xFF35232E),
    stepB = Color(0xFF1C1A1E),
    rowWhite = Color(0xFF0D0C0E),
    rowBlack = Color(0xFF050505),
    keyWhite = Color(0xFFF4EEF1),
    keyBlack = Color(0xFF1B161A),
    record = Color(0xFFFF4D5E),
    meterLow = Color(0xFF3DDC7E),
    meterMid = Color(0xFFFFCB3D),
    meterHigh = Color(0xFFFF4D5E),
    channels = ChannelColors,
)

val LocalMps = staticCompositionLocalOf { LightMps }

/** Shortcut: `Mps.colors.accent`. */
object Mps {
    val colors: MpsColors
        @Composable @ReadOnlyComposable get() = LocalMps.current
}

private fun scheme(c: MpsColors): ColorScheme {
    val base = if (c.dark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = c.accent,
        onPrimary = c.onAccent,
        primaryContainer = c.accentSoft,
        onPrimaryContainer = c.text,
        secondary = c.accent,
        onSecondary = c.onAccent,
        secondaryContainer = c.accentSoft,
        onSecondaryContainer = c.text,
        tertiary = c.accent,
        onTertiary = c.onAccent,
        tertiaryContainer = c.accentSoft,
        onTertiaryContainer = c.text,
        background = c.bg,
        onBackground = c.text,
        surface = c.bg,
        onSurface = c.text,
        surfaceVariant = c.surfaceHigh,
        onSurfaceVariant = c.textDim,
        surfaceTint = c.accent,
        surfaceDim = c.surface,
        surfaceBright = c.bg,
        surfaceContainerLowest = c.bg,
        surfaceContainerLow = c.surface,
        surfaceContainer = c.surface,
        surfaceContainerHigh = c.surface,
        surfaceContainerHighest = c.surfaceHigh,
        inverseSurface = c.text,
        inverseOnSurface = c.bg,
        inversePrimary = c.accent,
        outline = c.lineStrong,
        outlineVariant = c.line,
        error = c.record,
        onError = Color.White,
        scrim = Color.Black,
    )
}

private val AppTypography = Typography().let { t ->
    val f = FontFamily.Default
    t.copy(
        headlineSmall = t.headlineSmall.copy(fontFamily = f, fontWeight = FontWeight.Bold, fontSize = 22.sp, lineHeight = 28.sp),
        titleLarge = t.titleLarge.copy(fontFamily = f, fontWeight = FontWeight.Bold, fontSize = 20.sp, lineHeight = 26.sp),
        titleMedium = t.titleMedium.copy(fontFamily = f, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp),
        titleSmall = t.titleSmall.copy(fontFamily = f, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp),
        bodyLarge = t.bodyLarge.copy(fontFamily = f, fontSize = 16.sp, lineHeight = 22.sp),
        bodyMedium = t.bodyMedium.copy(fontFamily = f, fontSize = 14.sp, lineHeight = 20.sp),
        bodySmall = t.bodySmall.copy(fontFamily = f, fontSize = 12.sp, lineHeight = 16.sp),
        labelLarge = t.labelLarge.copy(fontFamily = f, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 18.sp),
        labelMedium = t.labelMedium.copy(fontFamily = f, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp),
        labelSmall = t.labelSmall.copy(fontFamily = f, fontWeight = FontWeight.Medium, fontSize = 10.sp, lineHeight = 13.sp, letterSpacing = 0.sp),
    )
}

/** Text style for numbers that change while you drag (they must not jump around). */
val NumberStyle = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum")

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun MpsTheme(dark: Boolean, content: @Composable () -> Unit) {
    val colors = if (dark) DarkMps else LightMps
    CompositionLocalProvider(LocalMps provides colors) {
        MaterialTheme(colorScheme = scheme(colors), typography = AppTypography, shapes = AppShapes, content = content)
    }
}
