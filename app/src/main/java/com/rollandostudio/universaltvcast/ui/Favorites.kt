package com.rollandostudio.universaltvcast.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
    // Comme les applis de streaming : l'image au premier plan, sans cadre ; le titre et l'épisode juste dessous.
    Box {
        Column(
            modifier.clip(RoundedCornerShape(14.dp))
                .combinedClickable(
                    role = Role.Button, onClick = onClick,
                    onLongClick = { haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress); open = true },
                )
                .semantics {
                    contentDescription = title + (badge?.let { ", $it" } ?: "") + (if (host.isNotEmpty()) ", $host" else "")
                    customActions = menu.map { (label, action) -> CustomAccessibilityAction(label) { action(); true } }
                },
        ) {
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(14.dp)).background(c.surfaceContainerHighest)) {
                if (image != null) RemoteImage(image, Modifier.matchParentSize(), maxSide = 480)
                else Box(Modifier.matchParentSize(), contentAlignment = Alignment.Center) {
                    if (host.isNotEmpty()) SiteIcon(host, 44) else Icon(Icons.Rounded.Movie, null, Modifier.size(40.dp), tint = c.onSurfaceVariant)
                }
                if (loading) Box(Modifier.matchParentSize().background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.45f)), contentAlignment = Alignment.Center) {
                    androidx.compose.material3.CircularProgressIndicator(Modifier.size(36.dp), color = androidx.compose.ui.graphics.Color.White, strokeWidth = 3.dp)
                }
                // Avancement : fine barre arrondie au bas de l'image.
                if (progress != null) Box(
                    Modifier.align(Alignment.BottomStart).padding(horizontal = 10.dp, vertical = 8.dp).fillMaxWidth().height(4.dp)
                        .clip(RoundedCornerShape(2.dp)).background(androidx.compose.ui.graphics.Color.White.copy(alpha = 0.3f)),
                ) { Box(Modifier.fillMaxWidth(progress.coerceIn(0.03f, 1f)).height(4.dp).clip(RoundedCornerShape(2.dp)).background(c.primary)) }
                // Suivie en favori : elle reste ici même quand on ne la regarde plus depuis longtemps.
                if (starred) Box(
                    Modifier.align(Alignment.TopEnd).padding(8.dp).size(26.dp).clip(CircleShape)
                        .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.45f)),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Rounded.Star, null, tint = c.primary, modifier = Modifier.size(16.dp)) }
            }
            Spacer(Modifier.height(8.dp))
            Text(title, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 2.dp))
            // Dessous : l'épisode où l'on en est, sinon « Reprendre » (un film).
            Text(badge ?: stringResource(R.string.bookmark_resume), style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 2.dp))
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
    // Seulement les sites mis en favori par l'utilisateur : un site d'où vient une vidéo n'y entre pas tout seul.
    return bookmarks.filter { !it.isSeries }.map {
        // Nom choisi, sinon le titre court de la page : « Otakubinge - Accueil » → « Otakubinge ».
        SiteItem(it.key, it.host, it.name ?: it.title.split(Regex("\\s+[-|·•–—:]\\s+")).first().trim().ifBlank { it.host }, it.url, it, it.time)
    }.sortedByDescending { it.time }
}

/**
 * Tuile de « Mes favoris », la même pour un dossier du réseau, une chaîne ou un site : l'image à gauche (icône,
 * logo), le nom et ce que c'est dessous. Un appui ouvre ; un appui long montre [menu].
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun FavTileFrame(
    title: String, subtitle: String, menu: List<Pair<String, () -> Unit>>, onClick: () -> Unit,
    modifier: Modifier = Modifier.width(176.dp), art: @Composable () -> Unit,
) {
    val c = MaterialTheme.colorScheme
    var open by remember { mutableStateOf(false) }
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    Box(modifier) {
        Row(
            Modifier.fillMaxWidth().height(64.dp).clip(RoundedCornerShape(16.dp)).background(c.surfaceContainerHigh)
                .combinedClickable(
                    role = Role.Button, onClick = onClick,
                    onLongClick = { haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress); open = true },
                )
                .semantics {
                    contentDescription = "$title, $subtitle"
                    customActions = menu.map { (label, action) -> CustomAccessibilityAction(label) { action(); true } }
                }
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) { art() }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(subtitle, style = MaterialTheme.typography.labelSmall, color = c.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        CardMenu(open, { open = false }, menu)
    }
}

/** Petite tuile de site : son logo et son nom. Appui long : favori, ne plus proposer. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun SiteTile(s: SiteItem, modifier: Modifier = Modifier.width(80.dp), extraMenu: List<Pair<String, () -> Unit>> = emptyList(),
             tile: Boolean = false, onOpen: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf(false) }
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    // Appui long : renommer, déplacer, retirer.
    val menu = buildList {
        if (s.bookmark != null) add(stringResource(R.string.fav_rename) to { rename = true })
        addAll(extraMenu)
        if (s.bookmark != null) add(stringResource(R.string.cd_bookmark_remove) to { Library.removeBookmark(s.bookmark) })
    }
    if (rename && s.bookmark != null) RenameDialog(s.title, onDismiss = { rename = false }) { Library.renameBookmark(s.bookmark, it) }
    if (tile) {
        FavTileFrame(s.title, s.host, menu, onClick = { onOpen(s.url) }, modifier = modifier) { SiteIcon(s.host, 44) }
        return
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
            SiteIcon(s.host, 52)
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
fun FolderTile(f: com.rollandostudio.universaltvcast.lan.LanStore.Folder, modifier: Modifier = Modifier.width(176.dp),
               extraMenu: List<Pair<String, () -> Unit>> = emptyList()) {
    val c = MaterialTheme.colorScheme
    var open by remember { mutableStateOf(false) }
    var edit by remember { mutableStateOf(false) }
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    // Appui long : personnaliser (nom, icône, couleur) ou retirer.
    val menu = listOf(
        stringResource(R.string.fav_customize) to { edit = true },
    ) + extraMenu + listOf(
        stringResource(R.string.cd_bookmark_remove) to { com.rollandostudio.universaltvcast.lan.LanStore.toggleFolder(f.device(), f.ref, f.title) },
    )
    if (edit) FolderStyleDialog(f, justAdded = false) { edit = false }
    val tint = f.color?.let { androidx.compose.ui.graphics.Color(it) } ?: c.primary
    // Sous le nom : l'appareil où est le dossier ; pour l'appareil entier, « Réseau ».
    FavTileFrame(f.name, if (f.ref.isEmpty()) stringResource(R.string.home_network) else f.deviceName, menu, onClick = { Nav.networkFolder.value = f; Nav.network.value = true }, modifier = modifier) {
        // L'icône choisie, sinon : l'appareil entier (NAS, ordinateur) ou un de ses dossiers.
        Box(Modifier.fillMaxSize().background(tint.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
            Icon(FolderStyle.icon(f), null, tint = tint, modifier = Modifier.size(24.dp))
        }
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
