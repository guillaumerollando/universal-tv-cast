package com.rollandostudio.universaltvcast.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Largeur d'une affiche (2:3) dans une rangée ; une vignette large (16:9) prend la place de deux. */
internal val CARD_WIDTH = 112.dp
internal val CARD_WIDE_WIDTH = 200.dp

/**
 * La carte de tout film, série ou vidéo de l'appli (accueil, Rechercher, catalogues, sources), comme Netflix :
 * l'affiche seule. Le titre n'apparaît que s'il le faut :
 * - pas d'image (ou en attendant qu'elle arrive) : le titre au centre d'une jaquette sobre ;
 * - [caption] : l'image n'est pas une affiche (image tirée du film, vignette de vidéo), le titre est posé en bas,
 *   dans l'image, sur un dégradé.
 * [wide] : vignette 16:9 (vidéos) au lieu d'une affiche 2:3. [busy] : on cherche l'adresse de la vidéo.
 */
@Composable
internal fun MediaCard(
    title: String,
    image: String?,
    modifier: Modifier = Modifier,
    wide: Boolean = false,
    caption: Boolean = false,
    rating: Double? = null,
    busy: Boolean = false,
    icon: ImageVector = Icons.Rounded.Movie,
    onClick: () -> Unit,
) {
    val c = MaterialTheme.colorScheme
    Box(
        modifier.fillMaxWidth().aspectRatio(if (wide) 16f / 9f else 2f / 3f).clip(RoundedCornerShape(12.dp))
            .background(Brush.verticalGradient(listOf(c.surfaceContainerHighest, c.surfaceContainerHigh)))
            .clickable(enabled = !busy, onClick = onClick).semantics { contentDescription = title },
    ) {
        Column(Modifier.fillMaxSize().padding(10.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, null, tint = c.primary.copy(alpha = 0.8f), modifier = Modifier.size(28.dp))
            Spacer(Modifier.height(6.dp))
            Text(title, style = MaterialTheme.typography.labelMedium, color = c.onSurfaceVariant, textAlign = TextAlign.Center,
                maxLines = if (wide) 2 else 4, overflow = TextOverflow.Ellipsis)
        }
        RemoteImage(image, Modifier.fillMaxSize(), maxSide = if (wide) 480 else 400)
        if (caption && image != null) Text(
            title, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = Color.White,
            maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.8f))))
                .padding(start = 8.dp, end = 8.dp, top = 18.dp, bottom = 8.dp),
        )
        rating?.let { r ->
            Text("★ %.1f".format(if (r > 10) r / 10 else r), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold,
                color = Color.White, modifier = Modifier.align(Alignment.TopEnd).padding(6.dp)
                    .clip(RoundedCornerShape(8.dp)).background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 6.dp, vertical = 2.dp))
        }
        if (busy) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f)), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(Modifier.size(28.dp), color = Color.White)
        }
    }
}
