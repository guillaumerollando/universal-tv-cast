package studio.rollando.castor.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import studio.rollando.castor.R
import studio.rollando.castor.data.Bookmark
import java.io.File

/** Logos des sites (favicons), donnés par le navigateur lui-même et gardés sur le téléphone. */
object Favicons {
    private fun file(ctx: Context, host: String) = File(File(ctx.filesDir, "favicons").apply { mkdirs() }, host.replace('/', '_') + ".png")

    fun save(ctx: Context, host: String, icon: Bitmap) {
        if (host.isBlank()) return
        runCatching { file(ctx, host).outputStream().use { icon.compress(Bitmap.CompressFormat.PNG, 100, it) } }
    }

    suspend fun load(ctx: Context, host: String): Bitmap? = withContext(Dispatchers.IO) {
        runCatching { BitmapFactory.decodeFile(file(ctx, host).path) }.getOrNull()
    }
}

/** Logo du site, ou sa première lettre sur une pastille. */
@Composable
fun SiteIcon(host: String, size: Int = 28) {
    val ctx = LocalContext.current
    val icon by produceState<Bitmap?>(null, host) { value = Favicons.load(ctx, host) }
    val c = MaterialTheme.colorScheme
    Box(
        Modifier.size(size.dp).clip(RoundedCornerShape((size / 3.5).dp)).background(c.surfaceContainerHighest),
        contentAlignment = Alignment.Center,
    ) {
        val bmp = icon
        if (bmp != null) Image(bmp.asImageBitmap(), null, Modifier.size((size * 0.75).dp))
        else Text(host.firstOrNull()?.uppercase() ?: "?", style = MaterialTheme.typography.labelLarge, color = c.primary)
    }
}

/** Rangée des favoris (accueil et accueil du navigateur) : les séries et films d'abord, les sites ensuite. */
@Composable
fun FavoritesRow(bookmarks: List<Bookmark>, onOpen: (String) -> Unit) {
    val ordered = bookmarks.filter { !it.isSite } + bookmarks.filter { it.isSite }
    androidx.compose.foundation.lazy.LazyRow(
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        items(ordered.size, key = { ordered[it].key }) { i ->
            val b = ordered[i]
            if (b.isSite) SiteCard(b, onOpen) { studio.rollando.castor.data.Library.removeBookmark(b) }
            else SeriesCard(b, onOpen) { studio.rollando.castor.data.Library.removeBookmark(b) }
        }
    }
}

/**
 * Série ou film : grande image (affiche ou vignette de la page), titre, site et épisode où l'on en est.
 * Le marque-page avance tout seul ; un toucher reprend là où on en est.
 */
@Composable
fun SeriesCard(b: Bookmark, onOpen: (String) -> Unit, onRemove: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val badge = b.episode?.let { stringResource(R.string.bookmark_episode, it) }
    Column(
        Modifier.width(200.dp).clip(RoundedCornerShape(20.dp)).background(c.surfaceContainerHigh)
            .clickable(role = Role.Button) { onOpen(b.url) }
            .semantics { contentDescription = b.title + (badge?.let { ", $it" } ?: "") + ", " + b.host },
    ) {
        Box(Modifier.fillMaxWidth().height(112.dp).background(c.surfaceContainerHighest)) {
            if (b.image != null) RemoteImage(b.image, Modifier.matchParentSize(), maxSide = 480)
            else Box(Modifier.matchParentSize(), contentAlignment = Alignment.Center) { SiteIcon(b.host, 44) }
            Box(Modifier.matchParentSize().background(androidx.compose.ui.graphics.Brush.verticalGradient(
                0.45f to androidx.compose.ui.graphics.Color.Transparent, 1f to androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.75f))))
            // Pastille : épisode en cours (le marque-page qui avance), ou « Film / page ».
            Row(
                Modifier.align(Alignment.BottomStart).padding(8.dp).clip(RoundedCornerShape(50)).background(c.primary)
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(if (badge != null) Icons.Rounded.Bookmark else Icons.Rounded.PlayArrow,
                    null, tint = c.onPrimary, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
                Text(badge ?: stringResource(R.string.bookmark_open), style = MaterialTheme.typography.labelMedium, color = c.onPrimary)
            }
            RemoveButton(Modifier.align(Alignment.TopEnd), onRemove, onImage = true)
        }
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(b.title, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.height(44.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                SiteIcon(b.host, 18)
                Spacer(Modifier.width(6.dp))
                Text(b.host, style = MaterialTheme.typography.labelMedium, color = c.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Site entier : son logo, son nom, l'étiquette « Site ». Ce favori ne change jamais d'adresse. */
@Composable
fun SiteCard(b: Bookmark, onOpen: (String) -> Unit, onRemove: () -> Unit) {
    val c = MaterialTheme.colorScheme
    Box(
        Modifier.width(132.dp).height(176.dp).clip(RoundedCornerShape(20.dp)).background(c.surfaceContainerHigh)
            .clickable(role = Role.Button) { onOpen(b.url) }
            .semantics { contentDescription = b.title + ", " + b.host },
    ) {
        Column(Modifier.padding(14.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(12.dp))
            SiteIcon(b.host, 56)
            Spacer(Modifier.height(12.dp))
            Text(b.title, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            Spacer(Modifier.weight(1f))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Public, null, tint = c.secondary, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.bookmark_site), style = MaterialTheme.typography.labelMedium, color = c.secondary)
            }
        }
        RemoveButton(Modifier.align(Alignment.TopEnd), onRemove, onImage = false)
    }
}

@Composable
private fun RemoveButton(modifier: Modifier, onRemove: () -> Unit, onImage: Boolean) {
    val removeCd = stringResource(R.string.cd_delete)
    Box(
        modifier.padding(6.dp).size(28.dp).clip(CircleShape)
            .background(if (onImage) androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.45f) else androidx.compose.ui.graphics.Color.Transparent)
            .clickable(role = Role.Button) { onRemove() }.semantics { contentDescription = removeCd },
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Rounded.Close, null, modifier = Modifier.size(16.dp),
            tint = if (onImage) androidx.compose.ui.graphics.Color.White else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
