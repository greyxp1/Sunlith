package io.github.sunsind.sunlith.ui

import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.LocalActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextDirection

/** Whether the app is dark, for the few colors the scheme's roles cannot express. */
val LocalDarkTheme = staticCompositionLocalOf { true }

/** The page behind grouped rows and cards, set clearly apart from them in both themes as in Android's settings. */
val ColorScheme.canvas: Color
    @Composable @ReadOnlyComposable get() = if (LocalDarkTheme.current) surfaceContainerLowest else surfaceContainer

/** Grouped rows, cards and the floating toolbar. */
val ColorScheme.card: Color
    @Composable @ReadOnlyComposable get() = if (LocalDarkTheme.current) surfaceContainerHigh else surfaceContainerLowest

// Text follows its content even when the surrounding controls are mirrored.
private val SunlithTypography = Typography().run {
    fun TextStyle.content() = copy(textDirection = TextDirection.ContentOrLtr)
    Typography(
        displayLarge.content(), displayMedium.content(), displaySmall.content(),
        headlineLarge.content(), headlineMedium.content(), headlineSmall.content(),
        titleLarge.content(), titleMedium.content(), titleSmall.content(),
        bodyLarge.content(), bodyMedium.content(), bodySmall.content(),
        labelLarge.content(), labelMedium.content(), labelSmall.content(),
        displayLargeEmphasized.content(), displayMediumEmphasized.content(), displaySmallEmphasized.content(),
        headlineLargeEmphasized.content(), headlineMediumEmphasized.content(), headlineSmallEmphasized.content(),
        titleLargeEmphasized.content(), titleMediumEmphasized.content(), titleSmallEmphasized.content(),
        bodyLargeEmphasized.content(), bodyMediumEmphasized.content(), bodySmallEmphasized.content(),
        labelLargeEmphasized.content(), labelMediumEmphasized.content(), labelSmallEmphasized.content(),
    )
}

/** Sunlith's grey schemes for Android versions without dynamic color. */
private fun neutralScheme(
    dark: Boolean, background: Color, surface: Color, container: Color, raised: Color, line: Color,
    ink: Color, muted: Color, faint: Color, accent: Color, onAccent: Color, danger: Color,
) = (if (dark) darkColorScheme() else lightColorScheme()).copy(
    primary = accent, onPrimary = onAccent,
    primaryContainer = raised, onPrimaryContainer = ink,
    secondary = muted, onSecondary = onAccent,
    secondaryContainer = raised, onSecondaryContainer = ink,
    tertiary = muted, onTertiary = onAccent,
    background = background, onBackground = ink,
    surface = background, onSurface = ink,
    surfaceVariant = raised, onSurfaceVariant = muted,
    surfaceTint = Color.Transparent,
    outline = faint, outlineVariant = line,
    error = danger, onError = onAccent,
    surfaceContainerLowest = if (dark) background else surface, surfaceContainerLow = surface,
    surfaceContainer = container, surfaceContainerHigh = raised, surfaceContainerHighest = raised,
    surfaceBright = if (dark) raised else surface, surfaceDim = if (dark) background else raised,
    inverseSurface = ink, inverseOnSurface = background, inversePrimary = onAccent,
)

private val NeutralDark = neutralScheme(
    dark = true, background = Color(0xFF0B0B0B), surface = Color(0xFF151515), container = Color(0xFF151515),
    raised = Color(0xFF212121), line = Color(0xFF2A2A2A), ink = Color(0xFFEDEDED), muted = Color(0xFF9C9C9C),
    faint = Color(0xFF8A8A8A), accent = Color(0xFFF2F2F2), onAccent = Color(0xFF0B0B0B), danger = Color(0xFFE0918A),
)

private val NeutralLight = neutralScheme(
    dark = false, background = Color(0xFFFAFAFA), surface = Color(0xFFFFFFFF), container = Color(0xFFEFEFEF),
    raised = Color(0xFFEDEDED), line = Color(0xFFE0E0E0), ink = Color(0xFF121212), muted = Color(0xFF5F5F5F),
    faint = Color(0xFF6B6B6B), accent = Color(0xFF121212), onAccent = Color(0xFFFFFFFF), danger = Color(0xFFB0433B),
)

@Composable
fun isDarkTheme(themeMode: String): Boolean = when (themeMode) {
    "light" -> false
    "system" -> isSystemInDarkTheme()
    else -> true
}

@Composable
fun SunlithTheme(themeMode: String, content: @Composable () -> Unit) {
    val dark = isDarkTheme(themeMode)
    val scheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(LocalContext.current) else dynamicLightColorScheme(LocalContext.current)
        dark -> NeutralDark
        else -> NeutralLight
    }
    CompositionLocalProvider(LocalDarkTheme provides dark, LocalContentColor provides scheme.onSurface) {
        MaterialExpressiveTheme(colorScheme = scheme, typography = SunlithTypography, content = content)
    }
}

@Composable
@ReadOnlyComposable
fun eventTint(argb: Int): Color {
    if (argb == 0) return MaterialTheme.colorScheme.onSurfaceVariant
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(argb or 0xFF000000.toInt(), hsv)
    hsv[1] = hsv[1] * 0.55f
    hsv[2] = if (LocalDarkTheme.current) hsv[2].coerceIn(0.55f, 0.80f) else hsv[2].coerceIn(0.40f, 0.68f)
    return Color(android.graphics.Color.HSVToColor(hsv))
}

/** Edge-to-edge system bars whose icons follow the theme. */
@Composable
fun SystemBarsForPalette() {
    val dark = LocalDarkTheme.current
    val activity = LocalActivity.current as? ComponentActivity
    DisposableEffect(dark, activity) {
        val style = if (dark) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
        activity?.enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
        onDispose { }
    }
}
