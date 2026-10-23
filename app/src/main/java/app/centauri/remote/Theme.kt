package app.centauri.remote

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

// GitHub's Primer palette (light and dark default) mapped onto Material 3 roles: canvas for the
// background, "subtle" canvas for cards, accent blue as primary, attention yellow for things in
// progress (heating), danger red for errors. outline is Primer's emphasis border (≥ 3:1 on every
// surface, for switches and tray rings); outlineVariant its default border, for card edges.
private val GitHubLight = lightColorScheme(
    primary = Color(0xFF0969DA), onPrimary = Color.White,
    primaryContainer = Color(0xFFDDF4FF), onPrimaryContainer = Color(0xFF0A3069),
    inversePrimary = Color(0xFF4493F8),
    secondary = Color(0xFF59636E), onSecondary = Color.White,
    secondaryContainer = Color(0xFFDDF4FF), onSecondaryContainer = Color(0xFF0A3069),
    tertiary = Color(0xFF9A6700), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFF8C5), onTertiaryContainer = Color(0xFF4D2D00),
    error = Color(0xFFD1242F), onError = Color.White,
    errorContainer = Color(0xFFFFEBE9), onErrorContainer = Color(0xFF82071E),
    background = Color.White, onBackground = Color(0xFF1F2328),
    surface = Color.White, onSurface = Color(0xFF1F2328),
    surfaceVariant = Color(0xFFF6F8FA), onSurfaceVariant = Color(0xFF59636E),
    surfaceTint = Color(0xFF0969DA),
    inverseSurface = Color(0xFF25292E), inverseOnSurface = Color.White,
    outline = Color(0xFF818B98), outlineVariant = Color(0xFFD1D9E0),
    surfaceBright = Color.White, surfaceDim = Color(0xFFEFF2F5),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF6F8FA),
    surfaceContainer = Color(0xFFF6F8FA), surfaceContainerHigh = Color(0xFFF6F8FA),
    surfaceContainerHighest = Color(0xFFF6F8FA),
)

private val GitHubDark = darkColorScheme(
    primary = Color(0xFF4493F8), onPrimary = Color(0xFF0D1117),
    primaryContainer = Color(0xFF1F6FEB), onPrimaryContainer = Color.White,
    inversePrimary = Color(0xFF0969DA),
    secondary = Color(0xFF9198A1), onSecondary = Color(0xFF0D1117),
    secondaryContainer = Color(0xFF132339), onSecondaryContainer = Color(0xFF79C0FF),
    tertiary = Color(0xFFD29922), onTertiary = Color(0xFF0D1117),
    tertiaryContainer = Color(0xFF2E2111), onTertiaryContainer = Color(0xFFE3B341),
    error = Color(0xFFF85149), onError = Color(0xFF0D1117),
    errorContainer = Color(0xFF3C1618), onErrorContainer = Color(0xFFFFA198),
    background = Color(0xFF0D1117), onBackground = Color(0xFFF0F6FC),
    surface = Color(0xFF0D1117), onSurface = Color(0xFFF0F6FC),
    surfaceVariant = Color(0xFF212830), onSurfaceVariant = Color(0xFF9198A1),
    surfaceTint = Color(0xFF4493F8),
    inverseSurface = Color(0xFFF0F6FC), inverseOnSurface = Color(0xFF0D1117),
    outline = Color(0xFF6E7681), outlineVariant = Color(0xFF3D444D),
    surfaceBright = Color(0xFF212830), surfaceDim = Color(0xFF010409),
    surfaceContainerLowest = Color(0xFF010409), surfaceContainerLow = Color(0xFF151B23),
    surfaceContainer = Color(0xFF151B23), surfaceContainerHigh = Color(0xFF151B23),
    surfaceContainerHighest = Color(0xFF151B23),
)

/** GitHub's colours, light or dark following the system; or, with [dynamic] on Android 12+, the wallpaper's. */
@Composable
fun CentauriTheme(dynamic: Boolean = false, content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val scheme = when {
        dynamic && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> GitHubDark
        else -> GitHubLight
    }
    MaterialTheme(colorScheme = scheme) {
        Surface(Modifier.fillMaxSize(), content = content)
    }
}
