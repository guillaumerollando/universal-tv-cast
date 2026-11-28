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
import kotlinx.coroutines.launch
import androidx.compose.runtime.collectAsState
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Lan
import androidx.compose.material.icons.rounded.LiveTv
import androidx.compose.material.icons.rounded.AccountBalance
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Tune
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
import com.rollandostudio.universaltvcast.data.OpenSources
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
internal fun VodTile(v: VodItem, onClick: () -> Unit) {
    // Image tirée du film (catalogue libre sans vraie affiche) : le titre dans l'image.
    val frame = v.image != null && v.movie?.url?.contains("archive.org/download") == true && v.image?.contains("wikimedia") != true
    MediaCard(v.name, v.image, caption = frame, rating = v.rating,
        icon = if (v.movie != null) Icons.Rounded.Movie else Icons.Rounded.VideoLibrary, onClick = onClick)
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


/** Les films et séries des comptes dont le titre contient [query] (catalogues déjà en cache : instantané). */
internal suspend fun vodSearch(accounts: List<Xtream.Account>, query: String): List<VodItem> {
    val q = fold(query.trim())
    val out = ArrayList<VodItem>()
    for (a in accounts) runCatching {
        Xtream.movies(a).filter { fold(it.name).contains(q) }.forEach { out += VodItem(a, it, null) }
        Xtream.series(a).filter { fold(it.name).contains(q) }.forEach { out += VodItem(a, null, it) }
    }
    return out.take(80)
}

/**
 * Onglet « Rechercher » : pour qui a une source (abonnement IPTV, Films libres, PeerTube, Internet Archive) ou un
 * appareil du réseau. Sans texte : découvrir le catalogue (abonnements, Films libres). Avec un texte : une rangée par
 * source (tes films et séries, ton réseau, PeerTube, Internet Archive), chacune arrivant à son rythme. Tout vient des
 * sources que l'utilisateur a ajoutées.
 */
@Composable
internal fun SearchScreen(accounts: List<Xtream.Account>) {
    var query by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }
    var kind by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<String?>(null) }
    var cats by remember(kind) { mutableStateOf<List<String>>(emptyList()) }
    var cat by androidx.compose.runtime.saveable.rememberSaveable(kind) { mutableStateOf<String?>(null) }
    val open by OpenSources.on.collectAsState()
    val k = kind?.let { Xtream.Kind.valueOf(it) }
    val c = MaterialTheme.colorScheme
    val grid = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
    // Petite pause après la frappe avant d'interroger les sources en ligne.
    var settled by remember { mutableStateOf("") }
    LaunchedEffect(query) { kotlinx.coroutines.delay(if (query.isBlank()) 0 else 450); settled = query.trim() }
    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { SearchPill(query, { query = it }, R.string.search_hint) }
            androidx.compose.material3.IconButton(onClick = { Nav.sources.value = true }, modifier = Modifier.padding(end = 8.dp)) {
                Icon(Icons.Rounded.Tune, stringResource(R.string.sources_title))
            }
        }
        if (settled.length >= 2) {
            MixedResults(settled, accounts, open)
        } else if (accounts.isNotEmpty()) {
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
                kind = k, accounts = accounts, query = "", category = cat, onCategories = { cats = it }, state = grid,
                contentPadding = PaddingValues(top = 0.dp, bottom = 16.dp + LocalBottomInset.current),
                onClose = {},
            )
        } else {
            // Rien à découvrir sans catalogue : on dit où l'on cherche, et comment ajouter une source.
            Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Rounded.Search, null, tint = c.primary, modifier = Modifier.size(40.dp))
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.search_empty), textAlign = TextAlign.Center, color = c.onSurfaceVariant)
                Spacer(Modifier.height(12.dp))
                androidx.compose.material3.FilledTonalButton(onClick = { Nav.sources.value = true }) { Text(stringResource(R.string.sources_add)) }
            }
        }
    }
}

/**
 * Résultats pour [q], en une grille qui défile comme le reste de la page : un titre par source (tes films et séries,
 * à la télé, ton réseau, PeerTube, Internet Archive), chacune arrivant à son rythme ; une source vide ne s'affiche pas.
 */
@Composable
private fun MixedResults(q: String, accounts: List<Xtream.Account>, open: Set<OpenSources.Source>) {
    val vod by produceState<List<VodItem>?>(null, q, accounts) { value = if (accounts.isEmpty()) emptyList() else vodSearch(accounts, q) }
    val tv by produceState<List<TvHit>?>(null, q) { value = runCatching { tvSearch(q) }.getOrDefault(emptyList()) }
    val lan by produceState<List<OpenSources.Hit>?>(null, q) { value = if (OpenSources.hasLan()) runCatching { OpenSources.lan(q) }.getOrDefault(emptyList()) else emptyList() }
    val pt by produceState<List<OpenSources.Hit>?>(null, q, open) {
        value = if (OpenSources.Source.PEERTUBE in open) runCatching { OpenSources.peertube(q) }.getOrDefault(emptyList()) else emptyList()
    }
    val ia by produceState<List<OpenSources.Hit>?>(null, q, open) {
        value = if (OpenSources.Source.ARCHIVE in open) runCatching { OpenSources.archive(q) }.getOrDefault(emptyList()) else emptyList()
    }
    var series by remember { mutableStateOf<VodItem?>(null) }
    series?.series?.let { sr -> SeriesSheet(series!!.account, sr, onDismiss = { series = null }, onPlayed = { series = null }) }
    val sections = listOf(vod, tv, lan, pt, ia)
    val c = MaterialTheme.colorScheme
    val full: androidx.compose.foundation.lazy.grid.LazyGridItemSpanScope.() -> androidx.compose.foundation.lazy.grid.GridItemSpan =
        { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }
    // Vignette large (vidéo, chaîne) : toute la largeur sur un téléphone (pas de trou à côté), deux affiches au-delà.
    val wide: androidx.compose.foundation.lazy.grid.LazyGridItemSpanScope.() -> androidx.compose.foundation.lazy.grid.GridItemSpan =
        { androidx.compose.foundation.lazy.grid.GridItemSpan(if (maxLineSpan <= 3) maxLineSpan else 2) }
    fun androidx.compose.foundation.lazy.grid.LazyGridScope.header(title: String) = item(span = full) {
        Text(title, style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold), modifier = Modifier.padding(start = 4.dp, top = 10.dp, bottom = 2.dp))
    }
    fun androidx.compose.foundation.lazy.grid.LazyGridScope.hits(title: String, list: List<OpenSources.Hit>) {
        header(title)
        items(list, key = { it.key }, span = { if (it.wide) wide() else androidx.compose.foundation.lazy.grid.GridItemSpan(1) }) { h -> HitCard(h) }
    }
    val tYours = stringResource(R.string.search_yours); val tTv = stringResource(R.string.search_tv); val tLan = stringResource(R.string.search_lan)
    val tNone = stringResource(R.string.search_none)
    // On fait défiler les résultats : le clavier s'en va (toute la place aux résultats).
    val gridState = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    LaunchedEffect(gridState.isScrollInProgress) { if (gridState.isScrollInProgress) { keyboard?.hide(); focus.clearFocus() } }
    LazyVerticalGrid(
        state = gridState,
        columns = GridCells.Adaptive(104.dp),
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 16.dp + LocalBottomInset.current),
        horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (!vod.isNullOrEmpty()) {
            header(tYours)
            items(vod!!, key = { it.key }) { v ->
                VodTile(v) { if (v.movie != null) { TvCast.open(Xtream.movieMedia(v.account, v.movie)); Nav.tab.value = Tab.PLAYER } else series = v }
            }
        }
        if (!tv.isNullOrEmpty()) {
            header(tTv)
            items(tv!!, key = { "tv" + it.channel.url }) { t -> TvHitCard(t) }
        }
        if (!lan.isNullOrEmpty()) hits(tLan, lan!!)
        if (!pt.isNullOrEmpty()) hits("PeerTube", pt!!)
        if (!ia.isNullOrEmpty()) hits("Internet Archive", ia!!)
        when {
            sections.any { it == null } -> item(span = full) {
                Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(28.dp)) }
            }
            sections.all { it.isNullOrEmpty() } -> item(span = full) {
                Text(tNone, color = c.onSurfaceVariant, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(24.dp))
            }
        }
    }
}

/** La carte d'une vidéo de source ouverte ou du réseau : toucher cherche l'adresse de la vidéo, puis la lance. */
@Composable
internal fun HitCard(h: OpenSources.Hit, modifier: Modifier = Modifier) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var busy by remember { mutableStateOf(false) }
    MediaCard(h.title, h.image, modifier, wide = h.wide, caption = h.caption, busy = busy) {
        busy = true
        scope.launch {
            val m = runCatching { h.open() }.getOrNull()
            busy = false
            if (m != null) { TvCast.open(m); Nav.tab.value = Tab.PLAYER }
            else android.widget.Toast.makeText(ctx, R.string.search_unavailable, android.widget.Toast.LENGTH_SHORT).show()
        }
    }
}

/**
 * « Sources » : ce que l'appli peut parcourir et chercher. Sources ouvertes (gratuites et légales) d'un interrupteur ;
 * abonnements IPTV et appareils du réseau rappelés, avec leur écran pour les ajouter.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SourcesSheet(onDismiss: () -> Unit) {
    val open by OpenSources.on.collectAsState()
    val free by Xtream.freeOn.collectAsState()
    val c = MaterialTheme.colorScheme
    ModalBottomSheet(onDismissRequest = onDismiss) {
        LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Text(stringResource(R.string.sources_title), style = MaterialTheme.typography.titleLarge)
                Text(stringResource(R.string.sources_sub), style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant)
                Spacer(Modifier.height(6.dp))
            }
            item { SourceRow(Icons.Rounded.Movie, stringResource(R.string.free_title), stringResource(R.string.free_sub), free) { Xtream.setFree(it) } }
            item { SourceRow(Icons.Rounded.PlayCircle, "PeerTube", stringResource(R.string.sources_peertube), OpenSources.Source.PEERTUBE in open) { OpenSources.set(OpenSources.Source.PEERTUBE, it) } }
            item { SourceRow(Icons.Rounded.AccountBalance, "Internet Archive", stringResource(R.string.sources_archive), OpenSources.Source.ARCHIVE in open) { OpenSources.set(OpenSources.Source.ARCHIVE, it) } }
            item {
                Spacer(Modifier.height(6.dp))
                Text(stringResource(R.string.sources_yours), style = MaterialTheme.typography.titleSmall, color = c.primary)
            }
            item {
                SourceLink(Icons.Rounded.LiveTv, stringResource(R.string.sources_iptv), stringResource(R.string.sources_iptv_sub)) {
                    // Un abonnement s'ajoute en collant le lien du fournisseur (comme toute liste de chaînes).
                    onDismiss(); Nav.pasteLink.value = true
                }
            }
            item {
                SourceLink(Icons.Rounded.Lan, stringResource(R.string.home_network), stringResource(R.string.sources_lan_sub)) {
                    onDismiss(); Nav.network.value = true
                }
            }
        }
    }
}

@Composable
private fun SourceRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, sub: String, on: Boolean, onChange: (Boolean) -> Unit) {
    val c = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.surfaceContainerLow).clickable { onChange(!on) }.padding(14.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = c.primary, modifier = Modifier.size(26.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(sub, style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
        }
        Spacer(Modifier.width(8.dp))
        androidx.compose.material3.Switch(on, onCheckedChange = onChange)
    }
}

@Composable
private fun SourceLink(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, sub: String, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = c.onSurfaceVariant, modifier = Modifier.size(26.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(sub, style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
        }
        Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, tint = c.onSurfaceVariant)
    }
}

/** Une chaîne trouvée par la recherche : par son nom, ou par ce qu'elle diffuse en ce moment ([now]). */
internal data class TvHit(val channel: com.rollandostudio.universaltvcast.data.Iptv.Channel, val now: String?)

/**
 * « À la télé » : les chaînes des listes importées dont le nom, ou le programme en cours (guide des programmes),
 * contient [query]. Le guide est relu au plus pour 400 chaînes (les premières des listes, pays du téléphone d'abord).
 */
internal suspend fun tvSearch(query: String): List<TvHit> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
    val q = fold(query.trim())
    val all = com.rollandostudio.universaltvcast.data.IptvStore.allChannels()
    if (all.isEmpty()) return@withContext emptyList()
    val here = java.util.Locale.getDefault().country.lowercase()
    val pool = all.distinctBy { fold(it.name) }.sortedByDescending { it.country == here }.take(400)
    // Par le nom d'abord (tout de suite), puis par le programme en cours : le guide (téléchargé la première fois) a
    // 8 secondes au plus ; ce qui n'est pas prêt est laissé de côté.
    val byName = pool.filter { fold(it.name).contains(q) }.take(24)
    val now = HashMap<String, String>()
    val more = ArrayList<TvHit>()
    kotlinx.coroutines.withTimeoutOrNull(8000) {
        for (ch in pool) {
            val title = runCatching { com.rollandostudio.universaltvcast.data.Epg.nowNext(ch.name, ch.country, ch.tvgId) }.getOrNull()?.first?.title ?: continue
            now[ch.url] = title
            if (ch !in byName && fold(title).contains(q) && more.size < 24) more += TvHit(ch, title)
        }
    }
    byName.map { TvHit(it, now[it.url]) } + more
}

/** Carte d'une chaîne trouvée : son logo (format des chaînes), et ce qui passe en ce moment dessous. */
@Composable
private fun TvHitCard(t: TvHit) {
    val c = MaterialTheme.colorScheme
    Column(Modifier.clip(RoundedCornerShape(12.dp)).clickable { playChannel(t.channel); Nav.tab.value = Tab.PLAYER }) {
        ChannelArt(
            name = t.channel.name, logo = t.channel.logo, streamUrl = null, pad = ChannelTiles.logoPad,
            textStyle = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.ExtraBold),
            modifier = Modifier.fillMaxWidth().aspectRatio(16f / 10f).clip(RoundedCornerShape(12.dp)),
        )
        t.now?.let {
            Spacer(Modifier.height(4.dp))
            Text(it, style = MaterialTheme.typography.labelMedium, color = c.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 2.dp))
        }
    }
}
