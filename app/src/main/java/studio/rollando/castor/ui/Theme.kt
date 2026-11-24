package studio.rollando.castor.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import studio.rollando.castor.R

// Ambre « castor » sur fond nuit, sarcelle en touche secondaire.
private val Dark = darkColorScheme(
    primary = Color(0xFFFFB86B),
    onPrimary = Color(0xFF3A2000),
    primaryContainer = Color(0xFF5B3A12),
    onPrimaryContainer = Color(0xFFFFDDBA),
    secondary = Color(0xFF7DD3C8),
    onSecondary = Color(0xFF00372F),
    secondaryContainer = Color(0xFF1E4A44),
    onSecondaryContainer = Color(0xFFB6F0E6),
    tertiary = Color(0xFFC9B6FF),
    tertiaryContainer = Color(0xFF3D3520),
    onTertiaryContainer = Color(0xFFFFE6A8),
    background = Color(0xFF0F1115),
    onBackground = Color(0xFFECE7E1),
    surface = Color(0xFF0F1115),
    onSurface = Color(0xFFECE7E1),
    surfaceVariant = Color(0xFF2A2E36),
    onSurfaceVariant = Color(0xFFBDB6AD),
    surfaceContainerLowest = Color(0xFF0B0D10),
    surfaceContainerLow = Color(0xFF15181D),
    surfaceContainer = Color(0xFF1A1D23),
    surfaceContainerHigh = Color(0xFF22262D),
    surfaceContainerHighest = Color(0xFF2B2F37),
    outline = Color(0xFF7A746C),
    outlineVariant = Color(0xFF3A3E46),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF5C1A16),
    onErrorContainer = Color(0xFFFFDAD6),
)

private val Light = lightColorScheme(
    primary = Color(0xFF8A4F00),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDDBA),
    onPrimaryContainer = Color(0xFF2C1600),
    secondary = Color(0xFF006A60),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFB6F0E6),
    onSecondaryContainer = Color(0xFF00201C),
    tertiary = Color(0xFF5B4B9A),
    tertiaryContainer = Color(0xFFFFF0C7),
    onTertiaryContainer = Color(0xFF3A2E00),
    background = Color(0xFFFFFBF7),
    onBackground = Color(0xFF1F1B16),
    surface = Color(0xFFFFFBF7),
    onSurface = Color(0xFF1F1B16),
    surfaceVariant = Color(0xFFF1E4D8),
    onSurfaceVariant = Color(0xFF51453A),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFFBF3EC),
    surfaceContainer = Color(0xFFF6EDE5),
    surfaceContainerHigh = Color(0xFFF0E7DF),
    surfaceContainerHighest = Color(0xFFEAE1D9),
    outline = Color(0xFF84756A),
    outlineVariant = Color(0xFFD6C4B5),
)

@OptIn(ExperimentalTextApi::class)
private fun fredoka(w: Int) = Font(R.font.fredoka, FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w)))

/** Fredoka (déjà utilisée par les jeux du studio) pour les titres et les boutons ; le texte courant reste lisible. */
val Fredoka = FontFamily(fredoka(400), fredoka(500), fredoka(600), fredoka(700))

private val base = Typography()
private val Type = base.copy(
    displaySmall = base.displaySmall.copy(fontFamily = Fredoka, fontWeight = FontWeight.Bold),
    headlineSmall = base.headlineSmall.copy(fontFamily = Fredoka, fontWeight = FontWeight.SemiBold),
    headlineMedium = base.headlineMedium.copy(fontFamily = Fredoka, fontWeight = FontWeight.SemiBold),
    titleLarge = base.titleLarge.copy(fontFamily = Fredoka, fontWeight = FontWeight.SemiBold),
    titleMedium = base.titleMedium.copy(fontFamily = Fredoka, fontWeight = FontWeight.Medium),
    titleSmall = base.titleSmall.copy(fontFamily = Fredoka, fontWeight = FontWeight.Medium),
    labelLarge = base.labelLarge.copy(fontFamily = Fredoka, fontWeight = FontWeight.Medium, fontSize = base.labelLarge.fontSize * 1.05f),
)

@Composable
fun CastorTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, typography = Type, content = content)
}
