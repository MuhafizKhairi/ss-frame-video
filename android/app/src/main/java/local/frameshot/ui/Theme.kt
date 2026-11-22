package local.frameshot.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

object Brand {
    val indigo = Color(0xFF6366F2)
    val violet = Color(0xFF9E5CF7)
    val pink = Color(0xFFED4D99)
    val accent = violet
    val gradient = Brush.linearGradient(listOf(indigo, violet, pink))
    val softGradient = Brush.linearGradient(listOf(indigo.copy(alpha = 0.16f), pink.copy(alpha = 0.12f)))
    val green = Color(0xFF34C759)
    val orange = Color(0xFFFF9F0A)
    val red = Color(0xFFFF453A)
}

private val Light = lightColorScheme(
    primary = Brand.violet,
    onPrimary = Color.White,
    secondary = Brand.indigo,
    background = Color(0xFFF7F7FB),
    onBackground = Color(0xFF1C1B22),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1C1B22),
    surfaceVariant = Color(0xFFEFEFF5),
    onSurfaceVariant = Color(0xFF6B6A75),
    surfaceContainer = Color(0xFFF2F2F7),
    surfaceContainerHigh = Color(0xFFFFFFFF),
    error = Brand.red,
)

private val Dark = darkColorScheme(
    primary = Brand.violet,
    onPrimary = Color.White,
    secondary = Brand.indigo,
    background = Color(0xFF121218),
    onBackground = Color(0xFFECEBF2),
    surface = Color(0xFF1B1B23),
    onSurface = Color(0xFFECEBF2),
    surfaceVariant = Color(0xFF26262F),
    onSurfaceVariant = Color(0xFF9D9CA8),
    surfaceContainer = Color(0xFF1B1B23),
    surfaceContainerHigh = Color(0xFF24242D),
    error = Brand.red,
)

@Composable
fun FrameshotTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
}

/** Warna turunan seperti `Color.primary.opacity(x)` di versi macOS. */
object Tone {
    val primary: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.onSurface
    val secondary: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.onSurfaceVariant
    val tertiary: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f)
    @Composable @ReadOnlyComposable fun fill(alpha: Float) = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha)
    val cardFill: Color @Composable @ReadOnlyComposable get() = fill(0.045f)
    val cardStroke: Color @Composable @ReadOnlyComposable get() = fill(0.08f)
}
