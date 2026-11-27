package com.rollandostudio.universaltvcast.ui

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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Computer
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
import com.rollandostudio.universaltvcast.R
import com.rollandostudio.universaltvcast.data.Bookmark
import com.rollandostudio.universaltvcast.data.Library
import java.io.File

/** Logos des sites (favicons), donnés par le navigateur lui-même et gardés sur le téléphone. */
object Favicons {
    internal fun file(ctx: Context, host: String) = File(File(ctx.filesDir, "favicons").apply { mkdirs() }, host.replace('/', '_') + ".png")

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
    val ordered = bookmarks.filter { it.isSeries }
    androidx.compose.foundation.lazy.LazyRow(
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        items(ordered.size, key = { ordered[it].key }) { i ->
            val b = ordered[i]
            PosterCard(
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
    onClick: () -> Unit, menu: List<Pair<String, () -> Unit>>, modifier: Modifier = Modifier.width(200.dp),
    /** Avancement dans la vidéo (0 à 1), montré par une fine barre au bas de l'image. */
    progress: Float? = null,
    /** Vidéo en cours de reprise : un rond de chargement sur l'image. */
    loading: Boolean = false,
) {
    val c = MaterialTheme.colorScheme
    var open by remember { mutableStateOf(false) }
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    Box {
        Column(
            modifier.clip(RoundedCornerShape(20.dp)).background(c.surfaceContainerHigh)
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
                if (loading) Box(Modifier.matchParentSize().background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.45f)), contentAlignment = Alignment.Center) {
                    androidx.compose.material3.CircularProgressIndicator(Modifier.size(36.dp), color = androidx.compose.ui.graphics.Color.White, strokeWidth = 3.dp)
                }
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
                if (progress != null) Box(
                    Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp)
                        .background(androidx.compose.ui.graphics.Color.White.copy(alpha = 0.25f)),
                ) { Box(Modifier.fillMaxWidth(progress.coerceIn(0.02f, 1f)).height(4.dp).background(c.primary)) }
                // Suivie en favori : elle reste ici même quand on ne la regarde plus depuis longtemps.
                if (starred) Box(
                    Modifier.align(Alignment.TopEnd).padding(8.dp).size(26.dp).clip(CircleShape)
                        .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.45f)),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Rounded.Star, null, tint = c.primary, modifier = Modifier.size(16.dp)) }
            }
            // Le titre seul : on voit le site en ouvrant la carte (et son logo quand il n'y a pas d'image).
            Text(title, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp).height(40.dp))
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

/** Un site de « Mes sites » : en favori (site ou page sans épisode), ou simplement déjà utilisé pour caster. */
data class SiteItem(val id: String, val host: String, val title: String, val url: String, val bookmark: Bookmark?, val time: Long)

/** Premiers mots d'adresse qui ne disent rien du site (« video.blender.org », « tube-maternelle… », « m.site.fr »). */
private val GENERIC_SUBDOMAINS = setOf("www", "m", "video", "videos", "tv", "tube", "watch", "play", "player", "media", "stream", "live", "replay", "plus", "app", "web")

/** Nom court d'un site : « video.blender.org » → « Blender », « commons.wikimedia.org » → « Commons ». */
fun shortSiteName(host: String): String {
    val labels = host.removePrefix("www.").split('.')
    val name = labels.dropLast(1).firstOrNull { it !in GENERIC_SUBDOMAINS } ?: labels.first()
    return name.replaceFirstChar { it.uppercase() }
}

fun siteItems(recents: List<com.rollandostudio.universaltvcast.data.Recent>, bookmarks: List<Bookmark>, hidden: Set<String>): List<SiteItem> {
    val fav = bookmarks.filter { !it.isSeries && it.host !in hidden }.map {
        // Nom court sur la tuile : « Otakubinge - Accueil » → « Otakubinge ».
        SiteItem(it.key, it.host, it.title.split(Regex("\\s+[-|·•–—:]\\s+")).first().trim().ifBlank { it.host }, it.url, it, it.time)
    }
    // Sites où l'on a casté, ou suivi une série, sans les avoir mis en favori.
    val pages = recents.filter { it.isWeb }.map { it.pageUrl!! to it.time } + bookmarks.filter { it.isSeries }.map { it.url to it.time }
    val used = pages.groupBy { Library.hostOf(it.first) }
        .filter { (h, _) -> h.isNotEmpty() && h !in hidden && fav.none { f -> f.host == h } }
        .map { (h, l) ->
            val p = android.net.Uri.parse(l.first().first)
            SiteItem(h, h, shortSiteName(h), "${p.scheme}://${p.authority}/", null, l.maxOf { it.second })
        }
    return fav.sortedByDescending { it.time } + used.sortedByDescending { it.time }
}

/** Petite tuile de site : son logo et son nom. Appui long : favori, ne plus proposer. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun SiteTile(s: SiteItem, modifier: Modifier = Modifier.width(80.dp), onOpen: (String) -> Unit) {
    val c = MaterialTheme.colorScheme
    var open by remember { mutableStateOf(false) }
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    val menu = buildList {
        if (s.bookmark != null) add(stringResource(R.string.cd_bookmark_remove) to { Library.removeBookmark(s.bookmark) })
        else add(stringResource(R.string.resume_follow) to { Library.toggleBookmark(s.title, s.url) })
        add(stringResource(R.string.site_hide) to { Library.hideSite(s.host) })
    }
    Box(modifier) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))
                .combinedClickable(
                    role = Role.Button, onClick = { onOpen(s.url) },
                    onLongClick = { haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress); open = true },
                )
                .semantics {
                    contentDescription = s.title + ", " + s.host
                    customActions = menu.map { (label, action) -> CustomAccessibilityAction(label) { action(); true } }
                }
                .padding(vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box {
                SiteIcon(s.host, 52)
                if (s.bookmark != null) Box(
                    Modifier.align(Alignment.TopEnd).offset(x = 6.dp, y = (-6).dp).size(20.dp).clip(CircleShape).background(c.surface),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Rounded.Star, null, tint = c.primary, modifier = Modifier.size(14.dp)) }
            }
            Spacer(Modifier.height(6.dp))
            Text(s.title, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 2.dp))
        }
        CardMenu(open, { open = false }, menu)
    }
}

/** Dossier du réseau en favori (« Films » du NAS) : ouvert directement d'un appui ; appui long pour le retirer. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun FolderTile(f: com.rollandostudio.universaltvcast.lan.LanStore.Folder, modifier: Modifier = Modifier.width(80.dp)) {
    val c = MaterialTheme.colorScheme
    var open by remember { mutableStateOf(false) }
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    val menu = listOf(stringResource(R.string.cd_bookmark_remove) to {
        com.rollandostudio.universaltvcast.lan.LanStore.toggleFolder(f.device(), f.ref, f.title)
    })
    Box(modifier) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))
                .combinedClickable(
                    role = Role.Button,
                    onClick = { Nav.networkFolder.value = f; Nav.network.value = true },
                    onLongClick = { haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress); open = true },
                )
                .semantics {
                    contentDescription = f.title + ", " + f.deviceName
                    customActions = menu.map { (label, action) -> CustomAccessibilityAction(label) { action(); true } }
                }
                .padding(vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(Modifier.size(52.dp).clip(RoundedCornerShape(15.dp)).background(c.primary.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center) {
                // L'appareil entier (NAS, ordinateur) ou un de ses dossiers.
                val icon = when {
                    f.ref.isNotEmpty() -> Icons.Rounded.Folder
                    f.kind == com.rollandostudio.universaltvcast.lan.LanDevice.Kind.MEDIA_SERVER -> Icons.Rounded.Dns
                    else -> Icons.Rounded.Computer
                }
                Icon(icon, null, tint = c.primary, modifier = Modifier.size(28.dp))
            }
            Spacer(Modifier.height(6.dp))
            Text(f.title, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 2.dp))
        }
        CardMenu(open, { open = false }, menu)
    }
}

/** Tous les sites, en grille (accueil du navigateur, « Tout voir »). */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun SitesGrid(sites: List<SiteItem>, modifier: Modifier = Modifier, onOpen: (String) -> Unit) {
    androidx.compose.foundation.layout.FlowRow(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        sites.forEach { s -> SiteTile(s, onOpen = onOpen) }
    }
}
