package com.rollandostudio.universaltvcast.ui

import androidx.compose.foundation.relocation.bringIntoViewRequester

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
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material.icons.rounded.AutoAwesome
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
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.Color
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
import androidx.compose.ui.unit.sp
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

    val resume = resumeItems(recents, bookmarks)
    val sites = siteItems(bookmarks)
    val channels = recents.filter { it.live }
    val folders by com.rollandostudio.universaltvcast.lan.LanStore.folders.collectAsState()
    val favChannels by Library.favChannels.collectAsState()
    // « Mes favoris » : un seul ordre pour dossiers du réseau, chaînes et sites ; le dernier ajouté à gauche.
    val order by Library.favOrder.collectAsState()
    val favs = remember(folders, favChannels, sites, order) {
        val raw = folders.map { "f:" + it.key to (it as Any) } + favChannels.map { "c:" + it.url to (it as Any) } +
            sites.map { "s:" + it.id to (it as Any) }
        if (order.isEmpty()) raw // premier affichage : l'ordre d'avant, enregistré juste après
        else raw.filter { it.first !in order }.reversed() + raw.filter { it.first in order }.sortedBy { order.indexOf(it.first) }
    }
    androidx.compose.runtime.LaunchedEffect(favs) {
        val keys = favs.map { it.first }
        if (keys != order.filter { k -> keys.contains(k) } || order.isEmpty()) Library.setFavOrder(keys)
    }
    val leftLabel = stringResource(R.string.fav_move_left)
    val rightLabel = stringResource(R.string.fav_move_right)
    val favTile: @Composable (Int) -> Unit = { i ->
        fun move(d: Int): () -> Unit = {
            val keys = favs.map { it.first }.toMutableList()
            java.util.Collections.swap(keys, i, i + d)
            Library.setFavOrder(keys)
        }
        val moves = buildList {
            if (i > 0) add(leftLabel to move(-1))
            if (i < favs.size - 1) add(rightLabel to move(1))
        }
        when (val x = favs[i].second) {
            is com.rollandostudio.universaltvcast.lan.LanStore.Folder -> FolderTile(x, extraMenu = moves)
            is Library.FavChannel -> FavChannelTile(x, onBrowse, moves)
            is SiteItem -> SiteTile(x, extraMenu = moves, tile = true) { onBrowse(it) }
        }
    }
    // « Tout voir » : 1 = tout « Reprendre », 2 = tous les favoris.
    var showAll by rememberSaveable { androidx.compose.runtime.mutableIntStateOf(0) }
    if (showAll == 1 && resume.isNotEmpty() || showAll == 2 && favs.isNotEmpty()) {
        BackHandler { showAll = 0 }
        if (showAll == 1) AllResume(resume, onBrowse) { showAll = 0 }
        else Column(Modifier.fillMaxSize()) {
            Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { showAll = 0 }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.cd_back)) }
                Text(stringResource(R.string.home_favorites), style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
            }
            // Tous les favoris en grille, mêmes tuiles ; les déplacements s'y animent aussi.
            androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
                columns = androidx.compose.foundation.lazy.grid.GridCells.Adaptive(ChannelTiles.width),
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 16.dp + LocalBottomInset.current),
                horizontalArrangement = Arrangement.spacedBy(ChannelTiles.gap, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(favs.size, key = { favs[it].first }) { i -> Box(Modifier.animateItem()) { favTile(i) } }
            }
        }
        return
    }

    // Abonnement IPTV « Xtream » : ses films et séries en rangées d'affiches, sous les chaînes (comme Netflix).
    val iptvLists by com.rollandostudio.universaltvcast.data.IptvStore.lists.collectAsState()
    val freeFilms by com.rollandostudio.universaltvcast.data.Xtream.freeOn.collectAsState()
    val vodAccounts = remember(iptvLists, freeFilms) { com.rollandostudio.universaltvcast.data.Xtream.accounts(iptvLists, freeFilms) }
    val vodRows by androidx.compose.runtime.produceState(emptyList<VodRow>(), vodAccounts) {
        value = if (vodAccounts.isEmpty()) emptyList() else xtreamHomeRows(vodAccounts, ROW_MAX)
    }
    var vodSeries by remember { mutableStateOf<VodItem?>(null) }
    vodSeries?.series?.let { sr -> SeriesSheet(vodSeries!!.account, sr, onDismiss = { vodSeries = null }, onPlayed = { vodSeries = null }) }

    // Habitué (des séries en cours, des sites en favori, un abonnement) : ce qu'il regarde d'abord, les façons de
    // caster en une rangée compacte. Nouveau venu : la présentation et les grandes tuiles.
    if (resume.isNotEmpty() || sites.isNotEmpty() || channels.isNotEmpty() || folders.isNotEmpty() || favChannels.isNotEmpty() || vodAccounts.isNotEmpty()) {
        // « Coller un lien » de la carte des chaînes gratuites : l'accueil remonte jusqu'à la tuile « Lien » et la fait
        // briller, comme pour un nouveau venu (on saura où la trouver la prochaine fois).
        val guideScope = androidx.compose.runtime.rememberCoroutineScope()
        val linkSpot = remember { androidx.compose.foundation.relocation.BringIntoViewRequester() }
        var showLink by remember { mutableStateOf(false) }
        androidx.compose.runtime.LaunchedEffect(showLink) { if (showLink) { kotlinx.coroutines.delay(3200); showLink = false } }
        LazyColumn(
            contentPadding = PaddingValues(top = 2.dp, bottom = 8.dp + LocalBottomInset.current),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // Chaque rubrique : son titre collé à son contenu ; l'espace entre rubriques marque la hiérarchie.
            // Chaque rangée : 8 éléments au plus, puis une tuile « Tout voir » à leur format (comme Netflix, Canal+).
            if (resume.isNotEmpty()) item {
                Column {
                    SectionHeader(stringResource(R.string.home_resume), null) {}
                    // Même écart entre cartes que toutes les autres rangées.
                    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(ChannelTiles.gap)) {
                        items(resume.take(ROW_MAX), key = { "r" + it.key }) { r -> ResumeCard(r, onBrowse) }
                        if (resume.size > ROW_MAX) item(key = "r-all") { SeeAllCard { showAll = 1 } }
                    }
                }
            }
            // « Mes favoris » juste sous « Reprendre » : dossiers du réseau mis en ★ (NAS, ordinateur), chaînes en ★, sites.
            if (sites.isNotEmpty() || folders.isNotEmpty() || favChannels.isNotEmpty()) item {
                Column {
                    SectionHeader(stringResource(if (folders.isEmpty() && favChannels.isEmpty()) R.string.home_sites else R.string.home_favorites), null) {}
                    // Même rangée que les chaînes : tuiles 16:10 serrées ; un favori déplacé glisse à sa nouvelle place.
                    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(ChannelTiles.gap)) {
                        items(minOf(favs.size, ROW_MAX), key = { favs[it].first }) { i -> Box(Modifier.animateItem()) { favTile(i) } }
                        if (favs.size > ROW_MAX) item(key = "f-all") {
                            SeeAllTile(stringResource(R.string.home_see_all), Modifier.width(ChannelTiles.width).aspectRatio(16f / 10f), ChannelTiles.corner) { showAll = 2 }
                        }
                    }
                }
            }
            if (channels.isNotEmpty()) item {
                Column {
                    SectionHeader(stringResource(R.string.home_channels), null) {}
                    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(ChannelTiles.gap)) {
                        items(channels.take(ROW_MAX), key = { "c" + it.key }) { ch -> ChannelTile(ch, onBrowse) }
                        // Toujours au bout : toutes les chaînes des listes importées (bien plus que les récentes).
                        item(key = "c-all") {
                            SeeAllTile(stringResource(R.string.home_see_all), Modifier.width(ChannelTiles.width).aspectRatio(16f / 10f), ChannelTiles.corner) {
                                Nav.iptv.value = ""
                            }
                        }
                    }
                }
            }
            item { Column(Modifier.bringIntoViewRequester(linkSpot)) {
                SectionHeader(stringResource(R.string.home_cast_more), null) {}
                CastActionsRow(onBrowse = { onBrowse(null) }, onPickPhotos = onPickPhotos, onNetwork = onNetwork,
                    onPasteLink = { showLink = false; onPasteLink() }, onPickFile = onPickFile, showLists = channels.isEmpty(), highlightLink = showLink)
            } }
            // Films et séries de l'abonnement (ou du catalogue libre), après les façons de caster : ces rangées peuvent
            // défiler longtemps, « Caster autre chose » reste à portée. Une rangée d'affiches par rubrique, « Tout voir » au bout.
            items(vodRows.size, key = { "v$it" + vodRows[it].kind + vodRows[it].category }) { i ->
                val row = vodRows[i]
                Column {
                    SectionHeader(row.title ?: stringResource(if (row.kind == com.rollandostudio.universaltvcast.data.Xtream.Kind.MOVIES) R.string.fav_icon_movie else R.string.fav_icon_series), null) {}
                    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(ChannelTiles.gap)) {
                        items(row.items.take(ROW_MAX), key = { it.key }) { v ->
                            Box(Modifier.width(VOD_WIDTH)) {
                                VodTile(v, showName = false) {
                                    if (v.movie != null) {
                                        TvCast.open(com.rollandostudio.universaltvcast.data.Xtream.movieMedia(v.account, v.movie)); Nav.tab.value = Tab.PLAYER
                                    } else vodSeries = v
                                }
                            }
                        }
                        if (row.items.size > ROW_MAX) item(key = "v-all") {
                            SeeAllTile(stringResource(R.string.home_see_all), Modifier.width(VOD_WIDTH).aspectRatio(2f / 3f), 12.dp) {
                                Nav.vodStart.value = row.kind.name to row.category; Nav.iptv.value = ""
                            }
                        }
                    }
                }
            }
            // La découverte continue pas à pas : ce qui n'a pas encore été essayé reste proposé (une liste de chaînes,
            // un site), et disparaît une fois fait.
            // Abonné à un fournisseur IPTV : plus un débutant, la découverte et l'astuce du bas n'ont plus lieu d'être.
            if (vodAccounts.isEmpty()) item {
                val lists by com.rollandostudio.universaltvcast.data.IptvStore.lists.collectAsState()
                // Sites gratuits suggérés tant que l'accueil n'est pas chargé (moins de 6 sites), sauf ceux déjà connus.
                StarterSection(onBrowse = { onBrowse(it) }, onPasteLink = {
                    guideScope.launch { linkSpot.bringIntoView(); showLink = true }
                },
                    showChannels = lists.isEmpty(), showSites = sites.size < 6, known = sites.map { it.host }.toSet())
            }
            // L'astuce reste utile après les premiers essais : en bas de page, discrète.
            if (vodAccounts.isEmpty()) item { OtherAppTip() }
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

        // Les quatre façons de caster, en grandes tuiles aux couleurs des actions (les mêmes que « Caster autre chose »).
        item {
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    BigActionTile(Icons.Rounded.Public, stringResource(R.string.home_browse_tile), stringResource(R.string.home_browse_short), ActionColors.WEB, Modifier.weight(1f)) { onBrowse(null) }
                    BigActionTile(Icons.Rounded.PhotoLibrary, stringResource(R.string.home_photos), stringResource(R.string.home_photos_short), ActionColors.ALBUM, Modifier.weight(1f), onClick = onPickPhotos)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    BigActionTile(Icons.Rounded.Lan, stringResource(R.string.home_network), stringResource(R.string.home_network_short), ActionColors.NETWORK, Modifier.weight(1f), onClick = onNetwork)
                    BigActionTile(Icons.Rounded.Link, stringResource(R.string.home_link), stringResource(R.string.home_link_short), ActionColors.LINK, Modifier.weight(1f),
                        highlight = showLinkTile, onClick = { showLinkTile = false; onPasteLink() })
                }
            }
        }
        // Les autres sources (listes de chaînes, fichiers) : en tuiles, comme sur l'accueil d'un habitué.
        item {
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(ChannelTiles.gap)) {
                item { ActionTile(Icons.Rounded.LiveTv, stringResource(R.string.home_lists_tile), ActionColors.LISTS) { Nav.iptv.value = "" } }
                item { ActionTile(Icons.Rounded.AutoAwesome, stringResource(R.string.amb_title), AMBIANCE_COLOR) { Nav.ambiance.value = true } }
                item { ActionTile(Icons.Rounded.FolderOpen, stringResource(R.string.home_files_tile), ActionColors.FILES, onClick = onPickFile) }
            }
        }

        // Nouveau venu : du contenu gratuit et légal pour essayer l'appli tout de suite.
        item {
            StarterSection(onBrowse = { onBrowse(it) }, onPasteLink = {
                scope.launch { listState.animateScrollToItem(1); showLinkTile = true }
            })
        }

        // Astuce : ouvrir une vidéo depuis une autre appli (« Partager », « Ouvrir avec »).
        item { OtherAppTip() }
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
private fun ResumeCard(r: ResumeItem, onBrowse: (String?) -> Unit, modifier: Modifier = Modifier.width(220.dp)) {
    val resuming by Nav.resuming.collectAsState()
    val badge = when {
        r.season != null && r.episode != null -> stringResource(R.string.resume_season_episode, r.season, r.episode)
        r.episode != null -> stringResource(R.string.bookmark_episode, r.episode)
        else -> null
    }
    // « Reprendre » n'est pas un lieu de favoris : seulement « Retirer » (série finie, film vu…).
    val menu = listOf(stringResource(if (r.episode != null) R.string.resume_series_done else R.string.resume_remove) to {
        if (r.recent != null) Library.removeRecent(r.key)
        if (r.bookmark != null) Library.removeBookmark(r.bookmark)
    })
    PosterCard(
        title = r.title, image = r.image, host = r.host, badge = badge, starred = false,
        onClick = {
            val page = r.openUrl
            // Vidéo trouvée sur un site : son lien a souvent expiré. La page de l'épisode est rouverte en
            // arrière-plan, sa vidéo repérée et lancée là où on s'était arrêté, sans passer par le site (ses pubs,
            // son lecteur). Introuvable : le navigateur montre la page.
            if (page != null) {
                if (Nav.resuming.value == null) { Nav.resuming.value = page; Nav.nextEpisode.value = page }
            } else r.recent?.let {
                // Vidéo du NAS ou de l'ordinateur : vérifiée (appareil allumé ?) avant d'être relancée.
                if (com.rollandostudio.universaltvcast.lan.LanStore.isLan(it.url)) { Nav.nextEpisode.value = it.url; return@let }
                TvCast.open(it.toMedia())
                Nav.tab.value = Tab.PLAYER
            }
        },
        menu = menu, modifier = modifier, progress = r.progress,
        loading = resuming != null && resuming == r.openUrl,
    )
}

/** Chaîne en favori : son logo, ouverte d'un appui (sur son site si elle en vient), retirée par appui long. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun FavChannelTile(ch: Library.FavChannel, onBrowse: (String?) -> Unit, extraMenu: List<Pair<String, () -> Unit>> = emptyList()) {
    var rename by remember { mutableStateOf(false) }
    if (rename) RenameDialog(ch.name, onDismiss = { rename = false }) { Library.renameFavChannel(ch.url, it) }
    val menu = listOf(stringResource(R.string.fav_rename) to { rename = true }) + extraMenu +
        listOf(stringResource(R.string.cd_bookmark_remove) to { Library.toggleFavChannel(ch) })
    // La chaîne telle quelle : son logo sur son fond, comme dans « Chaînes ».
    FavTileFrame(ch.name, menu, showLabel = false, onClick = {
        if (ch.page != null) onBrowse(ch.page)
        else playChannel(com.rollandostudio.universaltvcast.data.Iptv.Channel(ch.name, ch.url, ch.logo, headers = ch.headers))
    }) {
        ChannelArt(name = ch.name, logo = ch.logo, streamUrl = ch.url, pad = ChannelTiles.logoPad,
            textStyle = MaterialTheme.typography.titleSmall.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.ExtraBold),
            modifier = Modifier.matchParentSize())
    }
}

/**
 * Chaîne en direct : tuile à sa couleur (connue, sinon tirée de son nom), son nom, « ● Direct ».
 * Un toucher la relance au direct ; appui long : favori, renommer, retirer.
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
            val fav = Library.favChannels.collectAsState().value.any { it.url == r.url }
            androidx.compose.material3.DropdownMenuItem(
                text = { Text(stringResource(if (fav) R.string.cd_bookmark_remove else R.string.cd_bookmark_add)) },
                onClick = { menu = false; Library.toggleFavChannel(Library.FavChannel(r.url, name, r.poster, r.headers, r.pageUrl?.takeIf { it.startsWith("http") })) },
            )
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




/**
 * Titre de rubrique de l'accueil, au plus près de son contenu : le lien « Tout voir » éventuel reste petit pour ne pas
 * grandir la ligne.
 */
@Composable
private fun SectionHeader(title: String, action: String?, onAction: () -> Unit) {
    // Ligne de titre de hauteur fixe (avec ou sans lien à droite) : le même écart titre → contenu partout.
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 6.dp).height(SECTION_TITLE_HEIGHT), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleLarge.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, fontSize = 20.sp),
            modifier = Modifier.weight(1f).semantics { heading() })
        if (action != null) TextButton(onClick = onAction, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp)) {
            Text(action, style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** Nombre d'éléments d'une rangée de l'accueil avant la tuile « Tout voir ». */
private const val ROW_MAX = 8
/** Largeur d'une affiche (2:3) dans les rangées Films et Séries. */
private val VOD_WIDTH = 112.dp

/** Dernière tuile d'une rangée : « Tout voir », au format de ses voisines. */
@Composable
private fun SeeAllTile(label: String, modifier: Modifier, corner: androidx.compose.ui.unit.Dp, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    Surface(onClick = onClick, shape = RoundedCornerShape(corner), color = c.surfaceContainerHigh, modifier = modifier) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Box(Modifier.size(30.dp).clip(CircleShape).background(c.primary.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
                Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, tint = c.primary, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.height(4.dp))
            Text(label, style = MaterialTheme.typography.labelMedium, color = c.primary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 4.dp))
        }
    }
}

/**
 * « Tout voir » de « Reprendre » : exactement une carte comme ses voisines (même largeur, image 16:9 aux mêmes coins,
 * titre dessous à la même place), avec la flèche à la place de l'image.
 */
@Composable
private fun SeeAllCard(onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    Column(Modifier.width(220.dp).clip(RoundedCornerShape(14.dp)).clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = onClick)) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(14.dp)).background(c.surfaceContainerHigh),
            contentAlignment = Alignment.Center) {
            Box(Modifier.size(40.dp).clip(CircleShape).background(c.primary.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
                Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, tint = c.primary, modifier = Modifier.size(22.dp))
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(stringResource(R.string.home_see_all), style = MaterialTheme.typography.titleSmall.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold),
            color = c.primary, maxLines = 1, modifier = Modifier.padding(horizontal = 2.dp))
    }
}

/** Couleur de chaque façon de caster : la même partout (tuiles de l'accueil, grandes tuiles du nouveau venu). */
internal object ActionColors {
    // Couleurs franches (pas pastel) : elles gardent du caractère une fois fondues dans le fond sombre des tuiles.
    const val WEB = 0xFF2F7BF5
    const val ALBUM = 0xFFE0457B
    const val NETWORK = 0xFF13A86F
    const val LINK = 0xFFF08A24
    const val LISTS = 0xFF7C4DFF
    const val FILES = 0xFF4E7396
    const val MOVIES = 0xFFE0563A
}

/**
 * « Caster autre chose » : une rangée de tuiles au format des favoris et des chaînes, une couleur par action.
 * [highlightLink] : la tuile « Lien » s'allume (on vient de copier le lien des chaînes gratuites).
 */
@Composable
private fun CastActionsRow(
    onBrowse: () -> Unit, onPickPhotos: () -> Unit, onNetwork: () -> Unit, onPasteLink: () -> Unit, onPickFile: () -> Unit,
    showLists: Boolean, highlightLink: Boolean,
) {
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(ChannelTiles.gap)) {
        // Pas de « Sites web » ici : l'onglet Navigateur est là pour ça (il reste dans les grandes tuiles du nouveau venu).
        item { ActionTile(Icons.Rounded.PhotoLibrary, stringResource(R.string.home_photos), ActionColors.ALBUM, onClick = onPickPhotos) }
        item { ActionTile(Icons.Rounded.AutoAwesome, stringResource(R.string.amb_title), AMBIANCE_COLOR) { Nav.ambiance.value = true } }
        item { ActionTile(Icons.Rounded.Lan, stringResource(R.string.home_network), ActionColors.NETWORK, onClick = onNetwork) }
        item { ActionTile(Icons.Rounded.Link, stringResource(R.string.home_link_tile), ActionColors.LINK, highlight = highlightLink, onClick = onPasteLink) }
        if (showLists) item { ActionTile(Icons.Rounded.LiveTv, stringResource(R.string.home_lists_tile), ActionColors.LISTS) { Nav.iptv.value = "" } }
        item { ActionTile(Icons.Rounded.FolderOpen, stringResource(R.string.home_files_tile), ActionColors.FILES, onClick = onPickFile) }
    }
}

/** Une tuile d'action, au format des favoris : fond à sa couleur, icône et nom en blanc. */
@Composable
private fun ActionTile(icon: ImageVector, label: String, color: Long, highlight: Boolean = false, onClick: () -> Unit) {
    FavTileFrame(label, emptyList(), onClick, background = favBrush(Color(color)),
        modifier = Modifier.highlightGlow(highlight, ChannelTiles.corner)) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.align(Alignment.Center).padding(bottom = 14.dp).size(24.dp))
    }
}

/** Grande tuile du nouveau venu : la même couleur que la petite, avec une ligne d'explication. */
@Composable
private fun BigActionTile(icon: ImageVector, title: String, sub: String, color: Long, modifier: Modifier, highlight: Boolean = false, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = RoundedCornerShape(22.dp), color = Color.Transparent,
        modifier = modifier.height(118.dp).highlightGlow(highlight, 22.dp)) {
        Column(Modifier.background(favBrush(Color(color))).padding(16.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Box(Modifier.size(42.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = Color.White, modifier = Modifier.size(24.dp))
            }
            Column {
                Text(title, style = MaterialTheme.typography.titleMedium.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold),
                    color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(sub, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.85f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/**
 * « C'est ici » : une lueur ambrée qui pulse autour de la tuile (sans contour), allumée et éteinte en fondu.
 */
private fun Modifier.highlightGlow(highlight: Boolean, corner: androidx.compose.ui.unit.Dp): Modifier = composed {
    val c = MaterialTheme.colorScheme
    val level by androidx.compose.animation.core.animateFloatAsState(
        if (highlight) 1f else 0f,
        androidx.compose.animation.core.tween(if (highlight) 450 else 1100, easing = androidx.compose.animation.core.FastOutSlowInEasing),
        label = "repère",
    )
    val pulse = androidx.compose.animation.core.rememberInfiniteTransition(label = "repère")
    val beat by pulse.animateFloat(0.25f, 1f, androidx.compose.animation.core.infiniteRepeatable(
        androidx.compose.animation.core.tween(650), androidx.compose.animation.core.RepeatMode.Reverse), label = "repère")
    val glow = beat * level
    if (level <= 0f) this else this
        .drawBehind {
            val r = size.maxDimension * (0.95f + 0.35f * glow)
            drawCircle(
                androidx.compose.ui.graphics.Brush.radialGradient(
                    listOf(c.primary.copy(alpha = 0.85f * glow), c.primary.copy(alpha = 0.35f * glow), Color.Transparent),
                    center = center, radius = r,
                ),
                radius = r, center = center,
            )
        }
        .shadow((18 * glow).dp, RoundedCornerShape(corner), clip = false, ambientColor = c.primary, spotColor = c.primary)
}

/** Astuce « Depuis une autre appli » : partager ou « Ouvrir avec » une vidéo vers Universal TV Cast. */
@Composable
private fun OtherAppTip() {
    val c = MaterialTheme.colorScheme
    Row(
        Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(ChannelTiles.corner + 2.dp))
            .background(c.surfaceContainer).padding(14.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(Icons.AutoMirrored.Rounded.OpenInNew, null, tint = c.primary)
        Spacer(Modifier.width(12.dp))
        Column {
            Text(stringResource(R.string.home_other_title), style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(2.dp))
            Text(stringResource(R.string.home_other_body), style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant)
        }
    }
}
