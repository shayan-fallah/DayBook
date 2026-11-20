package app.calorietracker.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** Chart and nutrient colors, matching the web app. */
data class Palette(
    val accent: Color,
    val protein: Color,
    val carbs: Color,
    val fat: Color,
    val good: Color,
    val critical: Color,
    val grid: Color,
    val axis: Color,
    val muted: Color,
    val ink2: Color,
    val surface2: Color,
    val star: Color,
)

private val LightPalette = Palette(
    accent = Color(0xFF2A78D6), protein = Color(0xFF2A78D6), carbs = Color(0xFFEB6834), fat = Color(0xFF1BAF7A),
    good = Color(0xFF006300), critical = Color(0xFFD03B3B), grid = Color(0xFFE1E0D9), axis = Color(0xFFC3C2B7),
    muted = Color(0xFF898781), ink2 = Color(0xFF52514E), surface2 = Color(0xFFF0EFEC), star = Color(0xFFE34948),
)

private val DarkPalette = Palette(
    accent = Color(0xFF3987E5), protein = Color(0xFF3987E5), carbs = Color(0xFFD95926), fat = Color(0xFF199E70),
    good = Color(0xFF0CA30C), critical = Color(0xFFE66767), grid = Color(0xFF2C2C2A), axis = Color(0xFF383835),
    muted = Color(0xFF898781), ink2 = Color(0xFFC3C2B7), surface2 = Color(0xFF242422), star = Color(0xFFE66767),
)

val LocalPalette = staticCompositionLocalOf { LightPalette }

/** Also used by the home-screen widgets. */
internal val LightScheme = lightColorScheme(
    primary = Color(0xFF2A78D6), onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE9FA), onPrimaryContainer = Color(0xFF0D366B),
    secondaryContainer = Color(0xFFDCE9FA), onSecondaryContainer = Color(0xFF0D366B),
    background = Color(0xFFF4F4F1), onBackground = Color(0xFF0B0B0B),
    surface = Color(0xFFFCFCFB), onSurface = Color(0xFF0B0B0B),
    surfaceVariant = Color(0xFFF0EFEC), onSurfaceVariant = Color(0xFF52514E),
    surfaceContainerLowest = Color(0xFFFFFFFF), surfaceContainerLow = Color(0xFFFCFCFB),
    surfaceContainer = Color(0xFFFCFCFB), surfaceContainerHigh = Color(0xFFF7F7F5),
    surfaceContainerHighest = Color(0xFFF0EFEC),
    outline = Color(0xFFC3C2B7), outlineVariant = Color(0xFFE1E0D9),
    error = Color(0xFFD03B3B), onError = Color.White,
)

/** Also used by the home-screen widgets. */
internal val DarkScheme = darkColorScheme(
    primary = Color(0xFF3987E5), onPrimary = Color.White,
    primaryContainer = Color(0xFF184F95), onPrimaryContainer = Color(0xFFCDE2FB),
    secondaryContainer = Color(0xFF184F95), onSecondaryContainer = Color(0xFFCDE2FB),
    background = Color(0xFF0D0D0D), onBackground = Color(0xFFF0EFEC),
    surface = Color(0xFF1A1A19), onSurface = Color(0xFFF0EFEC),
    surfaceVariant = Color(0xFF242422), onSurfaceVariant = Color(0xFFC3C2B7),
    surfaceContainerLowest = Color(0xFF0D0D0D), surfaceContainerLow = Color(0xFF1A1A19),
    surfaceContainer = Color(0xFF1A1A19), surfaceContainerHigh = Color(0xFF242422),
    surfaceContainerHighest = Color(0xFF2C2C2A),
    outline = Color(0xFF4A4A46), outlineVariant = Color(0xFF2C2C2A),
    error = Color(0xFFE66767), onError = Color.White,
)

@Composable
fun CalorieTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    CompositionLocalProvider(LocalPalette provides if (dark) DarkPalette else LightPalette) {
        MaterialTheme(colorScheme = if (dark) DarkScheme else LightScheme, content = content)
    }
}
