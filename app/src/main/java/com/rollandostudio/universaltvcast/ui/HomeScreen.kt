package com.rollandostudio.universaltvcast.ui

import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.semantics.customActions
import androidx.compose.runtime.remember
import androidx.compose.foundation.combinedClickable
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.rollandostudio.universaltvcast.browser.Episodes
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.LiveTv
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Lan
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.rollandostudio.universaltvcast.R
import kotlinx.coroutines.launch
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.drawBehind
import androidx.compose.animation.core.animateFloat
import com.rollandostudio.universaltvcast.cast.TvCast
import com.rollandostudio.universaltvcast.data.Bookmark
import com.rollandostudio.universaltvcast.data.Library
import com.rollandostudio.universaltvcast.data.Recent

@Composable
fun HomeScreen(
    onBrowse: (String?) -> Unit,
    onPickFile: () -> Unit,
    onNetwork: () -> Unit,
    onPickPhotos: () -> Unit,
    onPasteLink: () -> Unit,
) {
    val recents by Library.recents.collectAsState()
    val bookmarks by Library.bookmarks.collectAsState()
    val c = MaterialTheme.colorScheme

    val hidden by Library.hiddenSites.collectAsState()
    val resume = resumeItems(recents, bookmarks)
    val sites = siteItems(recents, bookmarks, hidden)
    val channels = recents.filter { it.live }
    val folders by com.rollandostudio.universaltvcast.lan.LanStore.folders.collectAsState()
    // « Tout voir » : 1 = tout « Reprendre », 2 = tous les sites.
    var showAll by rememberSaveable { androidx.compose.runtime.mutableIntStateOf(0) }
    if (showAll == 1 && resume.isNotEmpty() || showAll == 2 && sites.isNotEmpty()) {
        BackHandler { showAll = 0 }
        if (showAll == 1) AllResume(resume, onBrowse) { showAll = 0 }
        else AllSites(sites, onBrowse) { showAll = 0 }
        return
    }

    // Habitué (des séries en cours, des sites en favori) : ce qu'il regarde d'abord, les façons de caster en
    // une rangée compacte. Nouveau venu : la présentation et les grandes tuiles.
    if (resume.isNotEmpty() || sites.isNotEmpty() || channels.isNotEmpty() || folders.isNotEmpty()) {
        LazyColumn(
            contentPadding = PaddingValues(top = 8.dp, bottom = 8.dp + LocalBottomInset.current),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (resume.isNotEmpty()) {
                item {
                    Row(Modifier.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        SectionTitle(stringResource(R.string.home_resume), Modifier.weight(1f).semantics { heading() })
                        if (resume.size > 2) TextButton(onClick = { showAll = 1 }) {
                            Text(stringResource(R.string.home_see_all))
                            Spacer(Modifier.width(4.dp))
                            Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, Modifier.size(18.dp))
                        }
                    }
                }
                item {
                    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(resume, key = { "r" + it.key }) { r -> ResumeCard(r, onBrowse) }
                    }
                }
            }
            if (channels.isNotEmpty()) {
                item {
                    Row(Modifier.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        SectionTitle(stringResource(R.string.home_channels), Modifier.weight(1f).semantics { heading() })
                        TextButton(onClick = { Nav.iptv.value = "" }) {
                            Text(stringResource(R.string.iptv_all_channels))
                            Spacer(Modifier.width(4.dp))
                            Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, Modifier.size(18.dp))
                        }
                    }
                }
                item {
                    LazyRow(contentPadding = PaddingValues(horizontal = ChannelTiles.edge), horizontalArrangement = Arrangement.spacedBy(ChannelTiles.gap)) {
                        items(channels, key = { "c" + it.key }) { ch -> ChannelTile(ch, onBrowse) }
                    }
                }
            }
            // « Mes favoris » : dossiers du réseau mis en ★ (NAS, ordinateur), puis les sites.
            if (sites.isNotEmpty() || folders.isNotEmpty()) {
                item {
                    Row(Modifier.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        SectionTitle(stringResource(if (folders.isEmpty()) R.string.home_sites else R.string.home_favorites),
                            Modifier.weight(1f).semantics { heading() })
                        if (sites.size > 4) TextButton(onClick = { showAll = 2 }) {
                            Text(stringResource(R.string.home_see_all))
                            Spacer(Modifier.width(4.dp))
                            Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, Modifier.size(18.dp))
                        }
                    }
                }
                item {
                    LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        items(folders, key = { "f" + it.key }) { f -> FolderTile(f, Modifier.width(72.dp)) }
                        items(sites, key = { "s" + it.id }) { s -> SiteTile(s, Modifier.width(72.dp)) { onBrowse(it) } }
                    }
                }
            }
            item { SectionTitle(stringResource(R.string.home_cast_more), Modifier.padding(horizontal = 16.dp).semantics { heading() }) }
            item {
                Column(Modifier.padding(horizontal = 16.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SmallTile(Icons.Rounded.Public, stringResource(R.string.home_browse_tile), Modifier.weight(1f)) { onBrowse(null) }
                        SmallTile(Icons.Rounded.PhotoLibrary, stringResource(R.string.home_photos), Modifier.weight(1f), onClick = onPickPhotos)
                        SmallTile(Icons.Rounded.Lan, stringResource(R.string.home_network), Modifier.weight(1f), onClick = onNetwork)
                        SmallTile(Icons.Rounded.Link, stringResource(R.string.home_link_tile), Modifier.weight(1f), onClick = onPasteLink)
                    }
                    MoreSources(onPickFile, showLists = channels.isEmpty()) // sinon, déjà en tête de « Chaînes »
                }
            }
            // La découverte continue pas à pas : ce qui n'a pas encore été essayé reste proposé (une liste de chaînes,
            // un site), et disparaît une fois fait.
            item {
                val lists by com.rollandostudio.universaltvcast.data.IptvStore.lists.collectAsState()
                // Sites gratuits suggérés tant que l'accueil n'est pas chargé (moins de 6 sites), sauf ceux déjà connus.
                StarterSection(onBrowse = { onBrowse(it) }, onPasteLink = onPasteLink,
                    showChannels = lists.isEmpty(), showSites = sites.size < 6, known = sites.map { it.host }.toSet())
            }
        }
        return
    }

    // « Coller un lien » de la carte des chaînes gratuites : on remonte jusqu'à la tuile et on la met en surbrillance,
    // pour montrer où elle se trouve (on la touchera soi-même la prochaine fois).
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var showLinkTile by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(showLinkTile) { if (showLinkTile) { kotlinx.coroutines.delay(3200); showLinkTile = false } }
    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(top = 8.dp, bottom = 8.dp + LocalBottomInset.current),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Box(
                Modifier
                    .padding(horizontal = 16.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(28.dp))
                    .background(Brush.linearGradient(listOf(c.primaryContainer, c.secondaryContainer)))
                    .castArcs(c.onPrimaryContainer)
                    .padding(22.dp)
            ) {
                Column {
                    Icon(painterResource(R.drawable.ic_logo), null, Modifier.size(36.dp), tint = c.onPrimaryContainer)
                    Spacer(Modifier.height(10.dp))
                    Text(stringResource(R.string.home_title), style = MaterialTheme.typography.headlineSmall,
                        color = c.onPrimaryContainer, modifier = Modifier.semantics { heading() })
                    Spacer(Modifier.height(4.dp))
                    Text(stringResource(R.string.home_subtitle), style = MaterialTheme.typography.bodyLarge,
                        color = c.onPrimaryContainer.copy(alpha = 0.85f))
                }
            }
        }

        // Les quatre façons de caster, en grille.
        item {
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Tile(Icons.Rounded.Public, stringResource(R.string.home_browse_tile), stringResource(R.string.home_browse_short), Modifier.weight(1f)) { onBrowse(null) }
                    Tile(Icons.Rounded.PhotoLibrary, stringResource(R.string.home_photos), stringResource(R.string.home_photos_short), Modifier.weight(1f), onClick = onPickPhotos)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Tile(Icons.Rounded.Lan, stringResource(R.string.home_network), stringResource(R.string.home_network_short), Modifier.weight(1f), onClick = onNetwork)
                    Tile(Icons.Rounded.Link, stringResource(R.string.home_link), stringResource(R.string.home_link_short), Modifier.weight(1f),
                        highlight = showLinkTile, onClick = { showLinkTile = false; onPasteLink() })
                }
                MoreSources(onPickFile)
            }
        }

        // Nouveau venu : du contenu gratuit et légal pour essayer l'appli tout de suite.
        item {
            StarterSection(onBrowse = { onBrowse(it) }, onPasteLink = {
                scope.launch { listState.animateScrollToItem(1); showLinkTile = true }
            })
        }

        item { SectionTitle(stringResource(R.string.home_bookmarks), Modifier.padding(horizontal = 16.dp).semantics { heading() }) }
        item {
            Text(stringResource(R.string.home_bookmarks_empty), style = MaterialTheme.typography.bodyMedium,
                color = c.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp))
        }

        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = c.surfaceContainer), shape = RoundedCornerShape(20.dp),
                modifier = Modifier.padding(horizontal = 16.dp),
            ) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
                    Icon(Icons.AutoMirrored.Rounded.OpenInNew, null, tint = c.secondary)
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(stringResource(R.string.home_other_title), style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(2.dp))
                        Text(stringResource(R.string.home_other_body), style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant)
                    }
                }
            }
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
}

/**
 * Une carte « Reprendre » : une série (ou un film) avec l'épisode où l'on en est. Réunit l'historique (une entrée
 * par série) et les séries en favori, qui y restent même sans avoir été regardées récemment.
 */
internal data class ResumeItem(
    val key: String, val title: String, val image: String?, val host: String,
    val season: Int?, val episode: Int?, val time: Long,
    val recent: Recent?, val bookmark: Bookmark?, val openUrl: String?,
    val progress: Float? = null,
)

internal fun resumeItems(recents: List<Recent>, bookmarks: List<Bookmark>): List<ResumeItem> {
    val series = bookmarks.filter { it.isSeries }
    // Une seule carte par série : l'épisode regardé en dernier (l'historique est rangé du plus récent au plus ancien).
    val latest = recents.filter { !it.live }.fold(emptyList<Recent>()) { acc, r ->
        if (acc.any { it.key == r.key || it.sameSeriesAs(r) }) acc else acc + r
    }
    val fromRecents = latest.mapNotNull { r ->
        val b = series.firstOrNull { it.sameSeries(r) }
        // Une page de site sans numéro d'épisode (accueil, bande-annonce…) : son titre ne dirait rien.
        // Page de site sans épisode : on la garde (film, vidéo à identifiant), sauf l'accueil d'un site et les
        // vidéos courtes (bande-annonce, aperçu), dont le titre ne dirait rien.
        if (r.isWeb && r.episodeNumber == null && b == null && (r.isHomePage || r.dur in 1.0..600.0)) return@mapNotNull null
        var ep = r.episodeNumber
        var open = r.pageUrl?.takeIf { r.isWeb }
        if (r.finished) {
            // Épisode fini : on propose le suivant ; un film (ou un épisode sans suite connue) fini disparaît.
            val cur = if (r.isWeb) r.pageUrl?.let { Episodes.find(it) } else null
            // Le lien repéré sur la page (fiable) avant celui déduit de l'adresse ; dernière partie (« 4/4 ») : pas de suite.
            if (r.nextUrl != null) { ep = cur?.number?.plus(1); open = r.nextUrl }
            else if (cur != null && cur.next.isNotEmpty()) { ep = cur.number + 1; open = cur.next }
            // Suite introuvable : un épisode reste là (« Série terminée » à l'appui long le retire) ; un film disparaît.
            else if (b == null && ep == null) return@mapNotNull null
        }
        // Le favori a pu avancer plus loin (épisode ouvert sans être casté) : on reprend au plus loin.
        val bookmarkAhead = b != null && (b.episode ?: b.titleEpisode ?: 0) > (ep ?: 0)
        ResumeItem(
            key = r.key, title = b?.seriesTitle ?: r.seriesTitle, image = r.poster ?: b?.image,
            host = Uri.parse(r.pageUrl ?: r.url).host?.removePrefix("www.")?.takeIf { it != "127.0.0.1" && it != "localhost" }.orEmpty(),
            season = r.season, episode = if (bookmarkAhead) b!!.episode ?: b.titleEpisode else ep, time = maxOf(r.time, b?.time ?: 0),
            recent = r, bookmark = b, openUrl = if (bookmarkAhead) b!!.url else open,
            progress = if (bookmarkAhead) null else r.progress,
        )
    }
    val onlyBookmarked = series.filter { b -> recents.none { b.sameSeries(it) } }.map { b ->
        ResumeItem(b.key, b.seriesTitle, b.image, b.host, null, b.episode ?: b.titleEpisode, b.time, null, b, b.url)
    }
    return (fromRecents + onlyBookmarked).sortedByDescending { it.time }
}

/** « Tout voir » des sites. */
@Composable
private fun AllSites(sites: List<SiteItem>, onBrowse: (String?) -> Unit, onClose: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.cd_back)) }
            Text(stringResource(R.string.home_sites), style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics { heading() })
        }
        SitesGrid(sites, Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) { onBrowse(it) }
    }
}

/** « Tout voir » : toutes les séries et vidéos à reprendre, en grille. */
@Composable
private fun AllResume(items: List<ResumeItem>, onBrowse: (String?) -> Unit, onClose: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.cd_back)) }
            Text(stringResource(R.string.home_resume), style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics { heading() })
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(160.dp),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp + LocalBottomInset.current),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            gridItems(items, key = { "a" + it.key }) { r -> ResumeCard(r, onBrowse, Modifier.fillMaxWidth()) }
        }
    }
}

@Composable
private fun ResumeCard(r: ResumeItem, onBrowse: (String?) -> Unit, modifier: Modifier = Modifier.width(200.dp)) {
    val resuming by Nav.resuming.collectAsState()
    val badge = when {
        r.season != null && r.episode != null -> stringResource(R.string.resume_season_episode, r.season, r.episode)
        r.episode != null -> stringResource(R.string.bookmark_episode, r.episode)
        else -> null
    }
    val menu = buildList {
        val page = r.openUrl
        if (r.bookmark != null) add(stringResource(R.string.cd_bookmark_remove) to { Library.removeBookmark(r.bookmark) })
        else if (page != null) add(stringResource(R.string.resume_follow) to { Library.toggleBookmark(r.title, page, r.image) })
        if (r.recent != null) add(stringResource(if (r.episode != null) R.string.resume_series_done else R.string.resume_remove) to { Library.removeRecent(r.key) })
    }
    PosterCard(
        title = r.title, image = r.image, host = r.host, badge = badge, starred = r.bookmark != null,
        onClick = {
            val page = r.openUrl
            // Vidéo trouvée sur un site : son lien a souvent expiré. La page de l'épisode est rouverte en
            // arrière-plan, sa vidéo repérée et lancée là où on s'était arrêté, sans passer par le site (ses pubs,
            // son lecteur). Introuvable : le navigateur montre la page.
            if (page != null) {
                if (Nav.resuming.value == null) { Nav.resuming.value = page; Nav.nextEpisode.value = page }
            } else r.recent?.let {
                TvCast.open(it.toMedia())
                Nav.tab.value = Tab.PLAYER
            }
        },
        menu = menu, modifier = modifier, progress = r.progress,
        loading = resuming != null && resuming == r.openUrl,
    )
}

/**
 * Chaîne en direct : tuile à sa couleur (connue, sinon tirée de son nom), son nom, « ● Direct ».
 * Un toucher la relance au direct ; appui long : renommer, retirer.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun ChannelTile(r: Recent, onBrowse: (String?) -> Unit) {
    val c = MaterialTheme.colorScheme
    val name = r.channelName
    var menu by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf(false) }
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    val renameLabel = stringResource(R.string.channel_rename)
    val removeLabel = stringResource(R.string.channel_remove)
    val liveLabel = stringResource(R.string.player_live)
    Box {
        Column(
            Modifier.width(ChannelTiles.width).clip(RoundedCornerShape(ChannelTiles.corner))
                .combinedClickable(
                    role = Role.Button,
                    onClick = {
                        val page = r.pageUrl
                        if (page != null && page.startsWith("http")) onBrowse(page)
                        else { TvCast.open(r.toMedia().copy(startSec = 0.0, title = name)); Nav.tab.value = Tab.PLAYER }
                    },
                    onLongClick = { haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress); menu = true },
                )
                .semantics {
                    contentDescription = "$name, $liveLabel"
                    customActions = listOf(
                        androidx.compose.ui.semantics.CustomAccessibilityAction(renameLabel) { rename = true; true },
                        androidx.compose.ui.semantics.CustomAccessibilityAction(removeLabel) { Library.removeRecent(r.key); true },
                    )
                },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 10f).clip(RoundedCornerShape(ChannelTiles.corner))) {
                ChannelArt(
                    name = name, logo = r.poster, streamUrl = r.url, pad = ChannelTiles.logoPad,
                    textStyle = MaterialTheme.typography.titleMedium.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.ExtraBold),
                    modifier = Modifier.matchParentSize(),
                )
            }
        }
        androidx.compose.material3.DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, shape = RoundedCornerShape(16.dp)) {
            androidx.compose.material3.DropdownMenuItem(text = { Text(renameLabel) }, onClick = { menu = false; rename = true })
            androidx.compose.material3.DropdownMenuItem(text = { Text(removeLabel) }, onClick = { menu = false; Library.removeRecent(r.key) })
        }
    }
    if (rename) {
        var text by remember { mutableStateOf(name) }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { rename = false },
            title = { Text(renameLabel) },
            text = {
                androidx.compose.material3.OutlinedTextField(text, { text = it }, singleLine = true,
                    label = { Text(stringResource(R.string.channel_name)) })
            },
            confirmButton = { TextButton(onClick = { Library.renameChannel(r.key, text); rename = false }) { Text(stringResource(R.string.action_ok)) } },
            dismissButton = { TextButton(onClick = { rename = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

/** Une façon de caster, en petit (accueil des habitués). */
@Composable
private fun SmallTile(icon: ImageVector, title: String, modifier: Modifier, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    Surface(onClick = onClick, shape = RoundedCornerShape(18.dp), color = c.surfaceContainerHigh, modifier = modifier.height(84.dp)) {
        Column(Modifier.padding(horizontal = 4.dp, vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween) {
            Box(Modifier.size(36.dp).clip(CircleShape).background(c.primary.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = c.primary, modifier = Modifier.size(20.dp))
            }
            Text(title, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun Tile(icon: ImageVector, title: String, sub: String, modifier: Modifier, highlight: Boolean = false, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    // Mise en surbrillance (« c'est ici ») : une lueur ambrée qui pulse autour de la tuile, comme la lumière de la vidéo.
    // Pas de contour : la lueur suffit. Elle s'allume et s'éteint en fondu (« level »), jamais d'un coup.
    val level by androidx.compose.animation.core.animateFloatAsState(
        if (highlight) 1f else 0f,
        androidx.compose.animation.core.tween(if (highlight) 450 else 1100, easing = androidx.compose.animation.core.FastOutSlowInEasing),
        label = "repère",
    )
    val pulse = androidx.compose.animation.core.rememberInfiniteTransition(label = "repère")
    val beat by pulse.animateFloat(0.25f, 1f, androidx.compose.animation.core.infiniteRepeatable(
        androidx.compose.animation.core.tween(650), androidx.compose.animation.core.RepeatMode.Reverse), label = "repère")
    val glow = beat * level
    Surface(
        onClick = onClick, shape = RoundedCornerShape(22.dp),
        color = if (level > 0f) c.primary.copy(alpha = (0.10f + 0.10f * beat) * level).compositeOver(c.surfaceContainerHigh) else c.surfaceContainerHigh,
        modifier = modifier.height(118.dp).then(if (level > 0f) Modifier
            .drawBehind {
                // Halo : un dégradé ambré qui déborde largement de la tuile et respire.
                val r = size.maxDimension * (0.95f + 0.35f * glow)
                drawCircle(
                    androidx.compose.ui.graphics.Brush.radialGradient(
                        listOf(c.primary.copy(alpha = 0.85f * glow), c.primary.copy(alpha = 0.35f * glow), androidx.compose.ui.graphics.Color.Transparent),
                        center = center, radius = r,
                    ),
                    radius = r, center = center,
                )
            }
            // Ombre lumineuse ambrée sous la tuile (lumière qui en émane).
            .shadow((22 * glow).dp, RoundedCornerShape(22.dp), clip = false, ambientColor = c.primary, spotColor = c.primary)
        else Modifier),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Box(Modifier.size(42.dp).clip(CircleShape).background(c.primary.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = c.primary, modifier = Modifier.size(24.dp))
            }
            Column {
                Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(sub, style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Les autres sources, en liens discrets : listes de chaînes (IPTV) et fichiers d'autres applis. */
@Composable
private fun MoreSources(onPickFile: () -> Unit, showLists: Boolean = true) {
    Column(Modifier.fillMaxWidth()) {
        if (showLists) TextButton(onClick = { Nav.iptv.value = "" }) {
            Icon(Icons.Rounded.LiveTv, null, Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.iptv_title))
        }
        TextButton(onClick = onPickFile) {
            Icon(Icons.Rounded.FolderOpen, null, Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.home_files_other))
        }
    }
}
