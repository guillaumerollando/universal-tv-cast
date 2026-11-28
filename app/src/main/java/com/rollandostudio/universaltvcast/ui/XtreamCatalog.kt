package com.rollandostudio.universaltvcast.ui

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.rollandostudio.universaltvcast.R
import com.rollandostudio.universaltvcast.cast.TvCast
import com.rollandostudio.universaltvcast.data.Xtream
import java.text.Normalizer

/** Un film ou une série d'un des comptes, pour la mosaïque. */
internal data class VodItem(val account: Xtream.Account, val movie: Xtream.Movie?, val series: Xtream.Series?) {
    val name get() = movie?.name ?: series?.name.orEmpty()
    val image get() = movie?.poster ?: series?.cover
    val rating get() = movie?.rating ?: series?.rating
    val category get() = movie?.category ?: series?.category
    val key get() = account.key + (movie?.id?.let { "m$it" } ?: "s${series?.id}")
}

private fun fold(s: String) = Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase()

/**
 * Films ou séries ([kind] null : les deux) des abonnements IPTV (Xtream), en mur d'affiches comme la vidéothèque du réseau : catégories du
 * fournisseur (puces au-dessus, gérées par l'écran Chaînes), recherche, et une série ouvre ses saisons et épisodes.
 * [onCategories] : les catégories trouvées, pour les puces ; [category] : celle choisie (son nom), ou null.
 */
@Composable
internal fun XtreamCatalog(
    kind: Xtream.Kind?,
    accounts: List<Xtream.Account>,
    query: String,
    category: String?,
    onCategories: (List<String>) -> Unit,
    state: LazyGridState,
    contentPadding: PaddingValues,
    onClose: () -> Unit,
) {
    var attempt by remember { mutableIntStateOf(0) }
    // Le catalogue (tous les comptes) et le nom de chaque catégorie, chargés une fois.
    val loaded by produceState<Pair<List<VodItem>, Map<String, String>>?>(null, kind, accounts, attempt) {
        value = null
        value = runCatching {
            val items = ArrayList<VodItem>()
            val names = HashMap<String, String>()
            val fresh = attempt > 0
            for (a in accounts) runCatching {
                if (kind != Xtream.Kind.SERIES) {
                    Xtream.categories(a, Xtream.Kind.MOVIES, fresh).forEach { names[a.key + "|m" + it.id] = it.name }
                    Xtream.movies(a, fresh).forEach { items += VodItem(a, it, null) }
                }
                if (kind != Xtream.Kind.MOVIES) {
                    Xtream.categories(a, Xtream.Kind.SERIES, fresh).forEach { names[a.key + "|s" + it.id] = it.name }
                    Xtream.series(a, fresh).forEach { items += VodItem(a, null, it) }
                }
            }
            // Nouveautés d'abord (les séries n'ont pas de date d'ajout : après les films récents, dans l'ordre du fournisseur).
            if (kind != Xtream.Kind.SERIES) items.sortByDescending { it.movie?.added ?: 0L }
            items to names
        }.getOrElse { android.util.Log.i("TvCastIptv", "catalogue Xtream illisible : $it"); emptyList<VodItem>() to emptyMap() }
    }
    val catOf = { v: VodItem -> loaded?.second?.get(v.account.key + (if (v.movie != null) "|m" else "|s") + v.category) }
    // Catégories : dans l'ordre du fournisseur, sans celles restées vides.
    LaunchedEffect(loaded) {
        val l = loaded ?: return@LaunchedEffect
        onCategories(l.first.mapNotNull(catOf).distinct())
    }
    val shown = remember(loaded, query, category) {
        val q = fold(query.trim())
        loaded?.first.orEmpty().filter { (category == null || catOf(it) == category) && (q.isEmpty() || fold(it.name).contains(q)) }
    }
    var open by remember { mutableStateOf<VodItem?>(null) }
    val c = MaterialTheme.colorScheme
    when {
        loaded == null -> Column(Modifier.fillMaxWidth().padding(contentPadding).padding(top = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.xtream_loading), color = c.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        }
        loaded!!.first.isEmpty() -> Column(Modifier.fillMaxWidth().padding(contentPadding).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stringResource(R.string.xtream_empty), color = c.onSurfaceVariant, textAlign = TextAlign.Center)
            TextButton(onClick = { attempt++ }) { Text(stringResource(R.string.web_retry)) }
        }
        shown.isEmpty() -> Text(stringResource(R.string.iptv_none_found), color = c.onSurfaceVariant,
            modifier = Modifier.padding(contentPadding).padding(16.dp))
        else -> LazyVerticalGrid(
            columns = GridCells.Adaptive(104.dp),
            state = state,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = contentPadding.calculateTopPadding(),
                bottom = contentPadding.calculateBottomPadding()),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(shown, key = { it.key }) { v ->
                VodTile(v) {
                    if (v.movie != null) {
                        TvCast.open(Xtream.movieMedia(v.account, v.movie)); Nav.tab.value = Tab.PLAYER; onClose()
                    } else open = v
                }
            }
        }
    }
    open?.series?.let { s -> SeriesSheet(open!!.account, s, onDismiss = { open = null }, onPlayed = { open = null; onClose() }) }
}

@Composable
internal fun VodTile(v: VodItem, showName: Boolean = true, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    Column(Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).semantics { contentDescription = v.name }) {
        Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(12.dp))
            .background(Brush.verticalGradient(listOf(c.surfaceContainerHighest, c.surfaceContainerHigh)))) {
            // En attendant l'affiche (ou sans affiche) : le titre sur une jaquette sobre.
            Column(Modifier.fillMaxSize().padding(10.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(if (v.movie != null) Icons.Rounded.Movie else Icons.Rounded.VideoLibrary, null, tint = c.primary.copy(alpha = 0.8f), modifier = Modifier.size(28.dp))
                Spacer(Modifier.height(6.dp))
                Text(v.name, style = MaterialTheme.typography.labelMedium, color = c.onSurfaceVariant, textAlign = TextAlign.Center,
                    maxLines = 4, overflow = TextOverflow.Ellipsis)
            }
            RemoteImage(v.image, Modifier.fillMaxSize(), maxSide = 400)
            // Rangées de l'accueil (sans titre dessous) : le titre en bas de l'image, sur un dégradé, pour les images
            // tirées du film qui ne le portent pas.
            if (!showName && v.image != null && v.movie?.url?.contains("archive.org/download") == true && v.image?.contains("wikimedia") != true)
                Text(v.name, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = Color.White,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.8f))))
                        .padding(start = 8.dp, end = 8.dp, top = 18.dp, bottom = 8.dp))
            v.rating?.let { r ->
                Text("★ %.1f".format(if (r > 10) r / 10 else r), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold,
                    color = Color.White, modifier = Modifier.align(Alignment.TopEnd).padding(6.dp)
                        .clip(RoundedCornerShape(8.dp)).background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 6.dp, vertical = 2.dp))
            }
        }
        if (showName) {
            Spacer(Modifier.height(4.dp))
            Text(v.name, style = MaterialTheme.typography.labelMedium, maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 2.dp))
        }
    }
}

/** Une rangée de l'accueil : son titre, ce qu'elle ouvre avec « Tout voir » (sorte et catégorie), ses affiches. */
internal data class VodRow(val title: String?, val kind: Xtream.Kind, val category: String?, val items: List<VodItem>)

/**
 * Les rangées de l'accueil pour les abonnements Xtream, comme Netflix : les films (les plus récents d'abord), les
 * séries, puis chaque catégorie du fournisseur (au moins 4 titres), comme les rangées « pour ce soir » de Netflix. [title] null : « Films » / « Séries ».
 */
internal suspend fun xtreamHomeRows(accounts: List<Xtream.Account>, perRow: Int, maxCategories: Int = Int.MAX_VALUE): List<VodRow> {
    val movies = ArrayList<VodItem>(); val series = ArrayList<VodItem>()
    val names = HashMap<String, String>()
    for (a in accounts) runCatching {
        Xtream.categories(a, Xtream.Kind.MOVIES).forEach { names[a.key + "|m" + it.id] = it.name }
        Xtream.categories(a, Xtream.Kind.SERIES).forEach { names[a.key + "|s" + it.id] = it.name }
        Xtream.movies(a).forEach { movies += VodItem(a, it, null) }
        Xtream.series(a).forEach { series += VodItem(a, null, it) }
    }
    movies.sortByDescending { it.movie?.added ?: 0L }
    val rows = ArrayList<VodRow>()
    if (movies.isNotEmpty()) rows += VodRow(null, Xtream.Kind.MOVIES, null, movies.take(perRow + 1))
    if (series.isNotEmpty()) rows += VodRow(null, Xtream.Kind.SERIES, null, series.take(perRow + 1))
    // Catégories : celles d'au moins 4 titres, films puis séries (celles qui ont les nouveautés d'abord).
    fun cats(items: List<VodItem>, kind: Xtream.Kind, tag: String) = items.groupBy { names[it.account.key + "|" + tag + it.category] }
        .filterKeys { it != null }.filterValues { it.size >= 4 }.map { (name, list) -> VodRow(name, kind, name, list.take(perRow + 1)) }
    rows += (cats(movies, Xtream.Kind.MOVIES, "m") + cats(series, Xtream.Kind.SERIES, "s")).take(maxCategories)
    return rows
}

/** Une série : son affiche, son résumé, ses saisons (puces) et leurs épisodes ; toucher un épisode le lance. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SeriesSheet(a: Xtream.Account, s: Xtream.Series, onDismiss: () -> Unit, onPlayed: () -> Unit) {
    val c = MaterialTheme.colorScheme
    var attempt by remember { mutableIntStateOf(0) }
    val info by produceState<Result<Xtream.SeriesInfo>?>(null, s.id, attempt) { value = runCatching { Xtream.seriesInfo(a, s) } }
    var season by remember { mutableStateOf<Int?>(null) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Box(Modifier.width(96.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(12.dp)).background(c.surfaceContainerHighest)) {
                    RemoteImage(s.cover, Modifier.fillMaxSize(), maxSide = 400)
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(s.name, style = MaterialTheme.typography.titleLarge, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    listOfNotNull(s.year, s.rating?.let { "★ %.1f".format(if (it > 10) it / 10 else it) }).joinToString(" · ").takeIf { it.isNotEmpty() }?.let {
                        Text(it, style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant)
                    }
                    (info?.getOrNull()?.plot ?: s.plot)?.let {
                        Spacer(Modifier.height(6.dp))
                        Text(it, style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant, maxLines = 6, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            val r = info
            when {
                r == null -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                r.isFailure || r.getOrNull()!!.episodes.isEmpty() -> Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.xtream_error), color = c.onSurfaceVariant, textAlign = TextAlign.Center)
                    TextButton(onClick = { attempt++ }) { Text(stringResource(R.string.web_retry)) }
                }
                else -> {
                    val si = r.getOrNull()!!
                    val medias = remember(si) { Xtream.episodeMedias(a, s, si) }
                    val seasons = remember(si) { si.episodes.map { it.season }.distinct() }
                    val current = season ?: seasons.first()
                    if (seasons.size > 1) LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(seasons) { n -> FilterChip(selected = n == current, onClick = { season = n }, label = { Text(stringResource(R.string.lan_season, n)) }) }
                    }
                    Spacer(Modifier.height(8.dp))
                    val eps = si.episodes.withIndex().filter { it.value.season == current }
                    LazyColumn(Modifier.height(420.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(eps, key = { it.value.id }) { (i, e) ->
                            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.surfaceContainerLow)
                                .clickable { TvCast.open(medias[i]); Nav.tab.value = Tab.PLAYER; onPlayed() }.padding(8.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.width(112.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(10.dp)).background(c.surfaceContainerHighest),
                                    contentAlignment = Alignment.Center) {
                                    Icon(Icons.Rounded.PlayArrow, null, tint = c.primary)
                                    RemoteImage(e.image, Modifier.fillMaxSize(), maxSide = 400)
                                }
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(stringResource(R.string.browser_next_episode, e.number), style = MaterialTheme.typography.labelMedium, color = c.primary)
                                    if (e.title.isNotEmpty()) Text(e.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}


/**
 * Onglet « Rechercher » (seulement avec un abonnement IPTV ou le catalogue libre) : chercher un film ou une série par
 * son titre, ou découvrir (sans texte : les nouveautés, par catégorie). Tout vient des listes de l'utilisateur ; même
 * recherche en pilule et mêmes puces que l'écran Chaînes, même mur d'affiches.
 */
@Composable
internal fun SearchScreen(accounts: List<Xtream.Account>) {
    var query by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }
    var kind by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<String?>(null) }
    var cats by remember(kind) { mutableStateOf<List<String>>(emptyList()) }
    var cat by androidx.compose.runtime.saveable.rememberSaveable(kind) { mutableStateOf<String?>(null) }
    val k = kind?.let { Xtream.Kind.valueOf(it) }
    val c = MaterialTheme.colorScheme
    val grid = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
    Column(Modifier.fillMaxSize()) {
        SearchPill(query, { query = it }, R.string.xtream_search)
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 10.dp)) {
            item { Pill(stringResource(R.string.xtream_all), kind == null) { kind = null } }
            item { Pill(stringResource(R.string.fav_icon_movie), kind == "MOVIES", icon = Icons.Rounded.Movie) { kind = "MOVIES" } }
            if (accounts.any { it != Xtream.FREE })
                item { Pill(stringResource(R.string.fav_icon_series), kind == "SERIES", icon = Icons.Rounded.VideoLibrary) { kind = "SERIES" } }
            if (cats.size > 1) {
                item { Box(Modifier.padding(horizontal = 4.dp).width(1.dp).height(20.dp).background(c.outlineVariant)) }
                items(cats) { g -> Pill(g, cat == g) { cat = if (cat == g) null else g } }
            }
        }
        XtreamCatalog(
            kind = k, accounts = accounts, query = query, category = cat, onCategories = { cats = it }, state = grid,
            contentPadding = PaddingValues(top = 0.dp, bottom = 16.dp + LocalBottomInset.current),
            onClose = {},
        )
    }
}
