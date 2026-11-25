package studio.rollando.castor.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import studio.rollando.castor.R
import studio.rollando.castor.data.Bookmark
import studio.rollando.castor.data.Library
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

/** Rangée des favoris (accueil du navigateur, et sites sur l'accueil) : les séries et films d'abord, les sites ensuite. */
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
            if (b.isSite) SiteCard(b, onOpen)
            else PosterCard(
                title = b.title, image = b.image, host = b.host,
                badge = b.episode?.let { stringResource(R.string.bookmark_episode, it) }, starred = true,
                onClick = { onOpen(b.url) },
                menu = listOf(stringResource(R.string.cd_bookmark_remove) to { Library.removeBookmark(b) }),
            )
        }
    }
}

/**
 * Carte d'une série ou d'un film (« Reprendre » et favoris) : grande image, pastille de l'épisode où l'on en est,
 * titre et site. Un toucher reprend ; un appui long propose de la suivre en favori ou de la retirer.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun PosterCard(
    title: String, image: String?, host: String, badge: String?, starred: Boolean,
    onClick: () -> Unit, menu: List<Pair<String, () -> Unit>>,
) {
    val c = MaterialTheme.colorScheme
    var open by remember { mutableStateOf(false) }
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    Box {
        Column(
            Modifier.width(200.dp).clip(RoundedCornerShape(20.dp)).background(c.surfaceContainerHigh)
                .combinedClickable(
                    role = Role.Button, onClick = onClick,
                    onLongClick = { haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress); open = true },
                )
                .semantics {
                    contentDescription = title + (badge?.let { ", $it" } ?: "") + (if (host.isNotEmpty()) ", $host" else "")
                    customActions = menu.map { (label, action) -> CustomAccessibilityAction(label) { action(); true } }
                },
        ) {
            Box(Modifier.fillMaxWidth().height(112.dp).background(c.surfaceContainerHighest)) {
                if (image != null) RemoteImage(image, Modifier.matchParentSize(), maxSide = 480)
                else Box(Modifier.matchParentSize(), contentAlignment = Alignment.Center) {
                    if (host.isNotEmpty()) SiteIcon(host, 44) else Icon(Icons.Rounded.Movie, null, Modifier.size(40.dp), tint = c.onSurfaceVariant)
                }
                Box(Modifier.matchParentSize().background(androidx.compose.ui.graphics.Brush.verticalGradient(
                    0.45f to androidx.compose.ui.graphics.Color.Transparent, 1f to androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.75f))))
                // Pastille : épisode où l'on en est, ou « Reprendre » pour un film.
                Row(
                    Modifier.align(Alignment.BottomStart).padding(8.dp).clip(RoundedCornerShape(50)).background(c.primary)
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.PlayArrow, null, tint = c.onPrimary, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(badge ?: stringResource(R.string.bookmark_resume), style = MaterialTheme.typography.labelMedium, color = c.onPrimary)
                }
                // Suivie en favori : elle reste ici même quand on ne la regarde plus depuis longtemps.
                if (starred) Box(
                    Modifier.align(Alignment.TopEnd).padding(8.dp).size(26.dp).clip(CircleShape)
                        .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.45f)),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Rounded.Star, null, tint = c.primary, modifier = Modifier.size(16.dp)) }
            }
            Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.height(44.dp))
                if (host.isNotEmpty()) Row(verticalAlignment = Alignment.CenterVertically) {
                    SiteIcon(host, 18)
                    Spacer(Modifier.width(6.dp))
                    Text(host, style = MaterialTheme.typography.labelMedium, color = c.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        CardMenu(open, { open = false }, menu)
    }
}

/** Site entier : son logo, son nom, l'étiquette « Site ». Ce favori ne change jamais d'adresse. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun SiteCard(b: Bookmark, onOpen: (String) -> Unit) {
    val c = MaterialTheme.colorScheme
    var open by remember { mutableStateOf(false) }
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    val menu = listOf(stringResource(R.string.cd_bookmark_remove) to { Library.removeBookmark(b) })
    Box {
        Box(
            Modifier.width(132.dp).height(176.dp).clip(RoundedCornerShape(20.dp)).background(c.surfaceContainerHigh)
                .combinedClickable(
                    role = Role.Button, onClick = { onOpen(b.url) },
                    onLongClick = { haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress); open = true },
                )
                .semantics {
                    contentDescription = b.title + ", " + b.host
                    customActions = menu.map { (label, action) -> CustomAccessibilityAction(label) { action(); true } }
                },
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
        }
        CardMenu(open, { open = false }, menu)
    }
}

@Composable
private fun CardMenu(open: Boolean, onDismiss: () -> Unit, menu: List<Pair<String, () -> Unit>>) {
    DropdownMenu(expanded = open, onDismissRequest = onDismiss, shape = RoundedCornerShape(16.dp)) {
        menu.forEach { (label, action) ->
            DropdownMenuItem(text = { Text(label) }, onClick = { onDismiss(); action() })
        }
    }
}
