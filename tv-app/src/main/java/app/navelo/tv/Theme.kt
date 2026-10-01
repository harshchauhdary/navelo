package app.navelo.tv

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme
import androidx.tv.material3.lightColorScheme

val NaveloNavy = Color(0xFF07141D)
val NaveloNavyRaised = Color(0xFF102633)
val NaveloMint = Color(0xFF6FE7C0)
val NaveloAmber = Color(0xFFFFBE63)
val NaveloMist = Color(0xFFE8F2F2)
val NaveloMuted = Color(0xFFA8BBC0)
val NaveloError = Color(0xFFFF8D86)

private val DarkColors = darkColorScheme(
    primary = NaveloMint,
    onPrimary = Color(0xFF002019),
    secondary = NaveloAmber,
    onSecondary = Color(0xFF2A1800),
    background = NaveloNavy,
    onBackground = NaveloMist,
    surface = NaveloNavyRaised,
    onSurface = NaveloMist,
    surfaceVariant = Color(0xFF173340),
    onSurfaceVariant = NaveloMuted,
    error = NaveloError,
    onError = Color(0xFF310200),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF006B55),
    onPrimary = Color.White,
    secondary = Color(0xFF8B5700),
    onSecondary = Color.White,
    background = Color(0xFFF4FAF8),
    onBackground = Color(0xFF102025),
    surface = Color.White,
    onSurface = Color(0xFF102025),
    surfaceVariant = Color(0xFFDCEAE7),
    onSurfaceVariant = Color(0xFF455B60),
    error = Color(0xFFBA1A1A),
    onError = Color.White,
)

@Composable
fun NaveloTheme(dark: Boolean = true, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
    ) {
        CompositionLocalProvider(
            androidx.tv.material3.LocalContentColor provides (if (dark) DarkColors else LightColors).onBackground,
            androidx.compose.material3.LocalContentColor provides (if (dark) DarkColors else LightColors).onBackground,
            content = content,
        )
    }
}
