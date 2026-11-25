package studio.rollando.castor.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.em
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


/** Inter Display (titres, extra-gras, lettres resserrées : l'esprit des applis de streaming) et Inter (le reste). */
val InterDisplay = FontFamily(Font(R.font.inter_display_extrabold, FontWeight.ExtraBold))
val Inter = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
)

private val base = Typography()
private fun TextStyle.display() = copy(fontFamily = InterDisplay, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.02).em)
private fun TextStyle.ui(w: FontWeight) = copy(fontFamily = Inter, fontWeight = w)

private val Type = Typography(
    displayLarge = base.displayLarge.display(),
    displayMedium = base.displayMedium.display(),
    displaySmall = base.displaySmall.display(),
    headlineLarge = base.headlineLarge.display(),
    headlineMedium = base.headlineMedium.display(),
    headlineSmall = base.headlineSmall.display(),
    titleLarge = base.titleLarge.display().copy(letterSpacing = (-0.01).em),
    titleMedium = base.titleMedium.ui(FontWeight.SemiBold),
    titleSmall = base.titleSmall.ui(FontWeight.SemiBold),
    bodyLarge = base.bodyLarge.ui(FontWeight.Normal),
    bodyMedium = base.bodyMedium.ui(FontWeight.Normal),
    bodySmall = base.bodySmall.ui(FontWeight.Normal),
    labelLarge = base.labelLarge.ui(FontWeight.SemiBold),
    labelMedium = base.labelMedium.ui(FontWeight.SemiBold),
    labelSmall = base.labelSmall.ui(FontWeight.SemiBold),
)

@Composable
fun CastorTheme(content: @Composable () -> Unit) {
    // Parti pris : thème sombre uniquement (ambre sur noir, usage le soir devant la télé).
    MaterialTheme(colorScheme = Dark, typography = Type, content = content)
}
