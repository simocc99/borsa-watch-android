package it.borsawatch

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Tema chiaro con il blu come colore principale; testi grandi e ben leggibili.
val Blue = Color(0xFF0B5CD5)
val BlueDeep = Color(0xFF083E94)
val BlueSoft = Color(0xFFDCE8FF)
val Ink = Color(0xFF12263F)
val Up = Color(0xFF0A7A3D)
val UpSoft = Color(0xFFDDF3E6)
val Down = Color(0xFFC62828)
val DownSoft = Color(0xFFFBE3E3)
val Warn = Color(0xFFB45309)
val WarnSoft = Color(0xFFFFF3D6)

private val Scheme = lightColorScheme(
    primary = Blue, onPrimary = Color.White,
    primaryContainer = BlueSoft, onPrimaryContainer = Color(0xFF062B66),
    secondary = Color(0xFF2F6FDB), onSecondary = Color.White,
    secondaryContainer = Color(0xFFE8F0FE), onSecondaryContainer = Ink,
    background = Color(0xFFF4F8FF), onBackground = Ink,
    surface = Color.White, onSurface = Ink,
    surfaceVariant = Color(0xFFE8F0FE), onSurfaceVariant = Color(0xFF4A5F7D),
    outline = Color(0xFFB7C9E8), error = Down
)

private val Type = Typography(
    headlineMedium = TextStyle(fontSize = 28.sp, fontWeight = FontWeight.Bold),
    titleLarge = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 19.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 18.sp),
    bodyMedium = TextStyle(fontSize = 16.sp),
    bodySmall = TextStyle(fontSize = 14.sp),
    labelLarge = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
)

@Composable
fun BorsaTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, typography = Type, content = content)
}
