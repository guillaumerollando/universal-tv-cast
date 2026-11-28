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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Casino
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.rollandostudio.universaltvcast.R
import com.rollandostudio.universaltvcast.lan.Catalog
import com.rollandostudio.universaltvcast.lan.LanDevice
import com.rollandostudio.universaltvcast.lan.LanEntry
import com.rollandostudio.universaltvcast.lan.LanStore
import com.rollandostudio.universaltvcast.lan.Lan
import com.rollandostudio.universaltvcast.lan.Smb
import com.rollandostudio.universaltvcast.net.Mime
import kotlinx.coroutines.launch

/** Images posées à côté des vidéos (conventions Kodi / Jellyfin / Plex) : des affiches, pas des photos à montrer. */
private val ART_NAMES = setOf("poster", "folder", "cover", "fanart", "banner", "thumb", "backdrop", "landscape",
    "clearlogo", "logo", "clearart", "disc", "background", "movie", "show", "season")
private val SEASON = Regex("(?i)^(?:saison|season|staffel|temporada|stagione|seizoen|sezon|s)\\s*0*(\\d{1,2})$")
private val SERIES_PARENT = Regex("(?i)s[ée]ries|series|\\btv\\b|shows?|anim[eé]s?|dessins?|cartoons?")

private fun base(name: String) = name.substringBeforeLast('.').lowercase()

/** L'ordre des vidéos qu'on lira à la suite (épisode suivant) : saison, épisode, puis nom. */
internal fun playOrder(videos: List<LanEntry>): List<LanEntry> = videos.sortedWith(
    compareBy<LanEntry>({ Catalog.parse(it.name).season ?: 0 }, { Catalog.parse(it.name).episode ?: 0 })
        .then { a, b -> Lan.natural(a.name, b.name) })

/**
 * Un dossier du réseau en vidéothèque : dossiers d'abord, puis les films en mosaïque d'affiches ; un dossier
 * d'épisodes devient une série (affiche, saisons, épisodes avec leur image et leur titre). Musique et autres fichiers
 * à la fin, en liste.
 */
@Composable
internal fun LanFolderView(
    entries: List<LanEntry>,
    /** Noms des dossiers au-dessus (le dernier est le parent), pour reconnaître « Séries › Breaking Bad › Saison 1 ». */
    parents: List<String>,
    sort: String,
    sortDesc: Boolean,
    hideWatched: Boolean,
    showDetails: Boolean = false,
    onFolder: (LanEntry) -> Unit,
    onPlay: (LanEntry, List<LanEntry>, Map<String, Catalog.Info>) -> Unit,
    load: suspend (LanEntry) -> List<LanEntry> = { emptyList() },
) {
    val c = MaterialTheme.colorScheme
    val watched by LanStore.watched.collectAsState()
    val videos0 = entries.filter { !it.folder && it.kind == LanEntry.Kind.VIDEO }
    val images = entries.filter { !it.folder && it.kind == LanEntry.Kind.PHOTO }
    val videoBases = videos0.map { base(it.name) }.toSet()
    fun isArt(img: LanEntry): Boolean {
        val b = base(img.name)
        return videos0.isNotEmpty() && (b in ART_NAMES || b in videoBases || videoBases.any { v -> b.startsWith("$v-") } ||
            ART_NAMES.any { b.endsWith("-$it") })
    }
    val art = images.filter(::isArt)
    val folderPoster = art.firstOrNull { base(it.name) in setOf("poster", "folder", "cover", "show", "movie") }
    fun sidecar(v: LanEntry): LanEntry? {
        val b = base(v.name)
        return art.firstOrNull { base(it.name) == "$b-poster" } ?: art.firstOrNull { base(it.name) == b }
            ?: art.firstOrNull { base(it.name) == "$b-thumb" }
    }
    // Image voisine : chemin content:// pour un dossier partagé, adresse http (ou sa miniature) pour un serveur multimédia.
    fun uriOf(e: LanEntry): String? = e.device?.let { d ->
        if (d.kind == LanDevice.Kind.SHARE) Smb.contentUri(d, e.ref).toString() else e.thumb ?: e.ref.takeIf { it.startsWith("http") }
    }

    // Titres et affiches : d'abord le nom du fichier (tout de suite), puis l'annuaire, au fil de l'eau.
    val infos = remember(entries) { mutableStateMapOf<String, Catalog.Info>() }
    LaunchedEffect(entries) {
        videos0.forEach { v -> infos[v.name] = Catalog.parse(v.name) }
        videos0.forEach { v -> launch { infos[v.name] = Catalog.info(v.name, url = lanUrl(v)) } }
    }
    fun info(v: LanEntry) = infos[v.name] ?: Catalog.parse(v.name)

    val episodesMode = videos0.isNotEmpty() && videos0.count { Catalog.parse(it.name).isEpisode } * 2 >= videos0.size
    // Genre choisi (puces) : seulement pour les films.
    var genre by androidx.compose.runtime.saveable.rememberSaveable(entries) { androidx.compose.runtime.mutableStateOf<String?>(null) }
    val visible = videos0.filter { !hideWatched || watched[it.url]?.finished != true }
        .filter { genre == null || genre in info(it).genres }
    // Croissant : A→Z, plus ancien, plus petite note ; « décroissant » inverse. Le titre départage toujours de A à Z.
    val byTitle = Comparator<LanEntry> { a, b -> Lan.natural(info(a).title, info(b).title) }
    // Sans valeur (année ou note inconnue) : toujours à la fin, quel que soit le sens.
    fun by(key: (LanEntry) -> Double?): Comparator<LanEntry> = Comparator<LanEntry> { a, b ->
        val x = key(a); val y = key(b)
        when {
            x == null && y == null -> 0
            x == null -> 1
            y == null -> -1
            else -> if (sortDesc) y.compareTo(x) else x.compareTo(y)
        }
    }.then(byTitle)
    val videos = when {
        episodesMode -> playOrder(visible)
        sort == "recent" -> visible.sortedWith(by { it.date?.toDouble() })
        sort == "year" -> visible.sortedWith(by { info(it).year?.toDouble() })
        sort == "rating" -> visible.sortedWith(by { info(it).rating })
        else -> visible.sortedWith(if (sortDesc) byTitle.reversed() else byTitle)
    }
    val folders = entries.filter { it.folder }.let { f ->
        when {
            sort == "recent" -> f.sortedWith(compareBy<LanEntry> { it.date ?: 0L }.let { c -> if (sortDesc) c.reversed() else c })
            sort == "name" && sortDesc -> f.reversed()
            else -> f
        }
    }
    // Dossier d'une série (« Breaking Bad » avec « Saison 1 », « Saison 2 ») : vue série, saisons dépliables.
    val seasonFolders = entries.filter { it.folder && SEASON.find(it.name.trim()) != null }
        .sortedBy { SEASON.find(it.name.trim())!!.groupValues[1].toInt() }
    val seriesRoot = seasonFolders.isNotEmpty() && seasonFolders.size * 2 >= entries.count { it.folder } && videos0.size <= 2
    val seasonEps = remember(entries) { mutableStateMapOf<String, List<LanEntry>>() }
    val expanded = remember(entries) { mutableStateMapOf<String, Boolean>() }
    var seriesInfo by remember(entries) { androidx.compose.runtime.mutableStateOf<Catalog.Info?>(null) }
    if (seriesRoot) LaunchedEffect(entries) {
        launch { seriesInfo = parents.lastOrNull()?.let { Catalog.series(it) } ?: parents.lastOrNull()?.let { Catalog.Info(it) } }
        seasonFolders.forEach { s ->
            launch {
                val eps = load(s).filter { !it.folder && it.kind == LanEntry.Kind.VIDEO }
                seasonEps[s.ref] = eps
                eps.forEach { v -> infos[v.name] = Catalog.parse(v.name) }
                eps.forEach { v -> launch { infos[v.name] = Catalog.info(v.name, url = lanUrl(v)) } }
            }
        }
    }
    // Saison ouverte d'office : la première qui a encore des épisodes à voir.
    LaunchedEffect(seasonEps.size, watched) {
        if (!seriesRoot || expanded.isNotEmpty() || seasonEps.size < seasonFolders.size) return@LaunchedEffect
        val first = seasonFolders.firstOrNull { s -> seasonEps[s.ref].orEmpty().any { watched[lanUrl(it)]?.finished != true } } ?: seasonFolders.first()
        expanded[first.ref] = true
    }
    val genres = if (episodesMode || seriesRoot) emptyList() else videos0.flatMap { info(it).genres }.groupingBy { it }.eachCount()
        .entries.sortedByDescending { it.value }.map { it.key }
    val others = entries.filter { !it.folder && it.kind != LanEntry.Kind.VIDEO && it !in art }
    val order = playOrder(videos0)
    // L'affiche posée à côté du film (serveur multimédia : adresse http) part avec lui : lecteur, « Reprendre ».
    fun withArt(): Map<String, Catalog.Info> = videos0.associate { v ->
        val i = info(v)
        v.name to (sidecar(v)?.let(::uriOf)?.takeIf { it.startsWith("http") }?.let { i.copy(poster = it) } ?: i)
    }
    val play = { v: LanEntry -> onPlay(v, order, withArt()) }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(108.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 4.dp, bottom = 24.dp + LocalBottomInset.current),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(if (showDetails) 12.dp else 10.dp),
    ) {
        val full: (androidx.compose.foundation.lazy.grid.LazyGridItemSpanScope) -> GridItemSpan = { GridItemSpan(it.maxLineSpan) }
        if (episodesMode) {
            item(span = full) { SeriesHeader(videos0.firstOrNull()?.let { info(it) }, folderPoster?.let(::uriOf)) }
        }
        // « Au hasard » (un film pas encore vu, parmi ceux affichés) et les genres présents dans le dossier.
        if (!episodesMode && videos0.size >= 2) item(span = full, key = "chips") {
            androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    androidx.compose.material3.AssistChip(
                        onClick = {
                            val pool = videos.filter { watched[it.url]?.finished != true }.ifEmpty { videos }
                            pool.randomOrNull()?.let(play)
                        },
                        label = { Text(stringResource(R.string.lan_random)) },
                        leadingIcon = { Icon(Icons.Rounded.Casino, null, Modifier.size(18.dp)) },
                    )
                }
                if (genres.size >= 2) {
                    item {
                        androidx.compose.material3.FilterChip(selected = genre == null, onClick = { genre = null },
                            label = { Text(stringResource(R.string.lan_all)) })
                    }
                    items(genres.size) { i ->
                        val g = genres[i]
                        androidx.compose.material3.FilterChip(selected = genre == g, onClick = { genre = if (genre == g) null else g },
                            label = { Text(genreLabel(g)) })
                    }
                }
            }
        }
        if (seriesRoot) {
            // Toute la série dans l'ordre : « Épisode suivant » passe de la saison 1 à la saison 2.
            val allEps = playOrder(seasonFolders.flatMap { seasonEps[it.ref].orEmpty() })
            item(span = full, key = "series") { SeriesHeader(seriesInfo ?: allEps.firstOrNull()?.let { info(it) }, folderPoster?.let(::uriOf)) }
            seasonFolders.forEach { s ->
                val eps = playOrder(seasonEps[s.ref].orEmpty()).filter { !hideWatched || watched[lanUrl(it)]?.finished != true }
                val open = expanded[s.ref] == true
                item(span = full, key = "h" + s.ref) {
                    SeasonHeader(s.name.trim(), seasonEps[s.ref]?.size, seasonEps[s.ref]?.count { watched[lanUrl(it)]?.finished == true } ?: 0, open) {
                        expanded[s.ref] = !open
                    }
                }
                if (open) items(eps, key = { "e" + it.ref }, span = { GridItemSpan(maxLineSpan) }) { v ->
                    EpisodeRow(v, info(v), null, watched[lanUrl(v)]) { onPlay(v, allEps, infos.toMap()) }
                }
            }
        }
        items(if (seriesRoot) folders - seasonFolders.toSet() else folders, key = { "d" + it.ref }) { f -> FolderPoster(f, parents, showDetails) { onFolder(f) } }
        if (episodesMode) {
            videos.groupBy { info(it).season ?: 0 }.toSortedMap().forEach { (season, eps) ->
                item(span = full, key = "s$season") {
                    Text(stringResource(R.string.lan_season, season), style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = 6.dp).semantics { heading() })
                }
                items(eps, key = { "e" + it.ref }, span = { GridItemSpan(maxLineSpan) }) { v ->
                    EpisodeRow(v, info(v), sidecar(v)?.let(::uriOf), watched[v.url]) { play(v) }
                }
            }
        } else {
            // Badge sur l'affiche : ce qui sert au tri en cours (année, note).
            val badge: (Catalog.Info) -> String? = when (sort) {
                "year" -> { i -> i.year?.toString() }
                "rating" -> { i -> i.rating?.let { "★ %.1f".format(it) } }
                else -> { _ -> null }
            }
            items(videos, key = { "v" + it.ref }) { v ->
                PosterTile(v, info(v), sidecar(v)?.let(::uriOf) ?: info(v).cover, watched[v.url], showDetails, badge(info(v))) { play(v) }
            }
        }
        if (others.isNotEmpty()) {
            if (videos0.isNotEmpty()) item(span = full) {
                Text(stringResource(R.string.lan_other_files), style = MaterialTheme.typography.titleSmall, color = c.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp))
            }
            items(others, key = { "o" + it.ref }, span = { GridItemSpan(maxLineSpan) }) { e -> EntryRowPublic(e) { onPlayOther(e, others, onPlay) } }
        }
    }
}

/** Photos et musique d'un dossier de vidéos : lues comme avant (album, lecture). */
private fun onPlayOther(e: LanEntry, others: List<LanEntry>, onPlay: (LanEntry, List<LanEntry>, Map<String, Catalog.Info>) -> Unit) =
    onPlay(e, others, emptyMap())

private val LanEntry.url: String
    get() = if (device?.kind == LanDevice.Kind.SHARE) Smb.contentUri(device, ref).toString() else ref

/** Adresse de lecture d'une vidéo (celle que l'historique et « vu » retiennent). */
internal fun lanUrl(e: LanEntry): String = e.url

@Composable
private fun PosterArt(poster: String?, local: String?, fallback: String, icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier) {
    val c = MaterialTheme.colorScheme
    Box(modifier.clip(RoundedCornerShape(12.dp))
        .background(Brush.verticalGradient(listOf(c.surfaceContainerHighest, c.surfaceContainerHigh)))) {
        // En attendant l'affiche (ou sans affiche) : une jaquette sobre avec le titre.
        Column(Modifier.fillMaxSize().padding(10.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, null, tint = c.primary.copy(alpha = 0.8f), modifier = Modifier.size(30.dp))
            Spacer(Modifier.height(8.dp))
            Text(fallback, style = MaterialTheme.typography.labelMedium, color = c.onSurfaceVariant, textAlign = TextAlign.Center,
                maxLines = 4, overflow = TextOverflow.Ellipsis)
        }
        when {
            local?.startsWith("/") == true -> FileImage(local, Modifier.fillMaxSize())
            local != null -> LocalImage(local, Modifier.fillMaxSize(), side = 400)
            poster != null -> RemoteImage(poster, Modifier.fillMaxSize(), maxSide = 400)
        }
    }
}

/** Jaquette déjà réduite et rangée dans le cache de l'appli. */
@Composable
private fun FileImage(path: String, modifier: Modifier) {
    val img by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, path) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { android.graphics.BitmapFactory.decodeFile(path)?.asImageBitmap() }.getOrNull()
        }
    }
    androidx.compose.animation.AnimatedVisibility(img != null, modifier = modifier, enter = androidx.compose.animation.fadeIn()) {
        img?.let { androidx.compose.foundation.Image(it, null, contentScale = androidx.compose.ui.layout.ContentScale.Crop) }
    }
}

@Composable
private fun WatchOverlay(w: LanStore.Watch?, modifier: Modifier = Modifier, checkAtStart: Boolean = false) {
    val c = MaterialTheme.colorScheme
    Box(modifier) {
        w?.progress?.let { p ->
            Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp).background(Color.Black.copy(alpha = 0.5f))) {
                Box(Modifier.fillMaxWidth(p).height(4.dp).background(c.primary))
            }
        }
        if (w?.finished == true) Box(
            Modifier.align(if (checkAtStart) Alignment.TopStart else Alignment.TopEnd).padding(6.dp).size(22.dp).clip(CircleShape).background(c.primary),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Rounded.Check, stringResource(R.string.lan_watched), tint = c.onPrimary, modifier = Modifier.size(15.dp)) }
    }
}

/** Nom du genre dans la langue de l'appli (l'annuaire le donne en anglais). */
@Composable
private fun genreLabel(code: String): String {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val id = remember(code) {
        ctx.resources.getIdentifier("genre_" + code.replace("-", "").replace(" ", "").lowercase(), "string", ctx.packageName)
    }
    return if (id != 0) stringResource(id) else code
}

/**
 * Un film : l'affiche seule par défaut (le titre y est déjà, comme sur les applis de streaming) ; le titre n'est
 * écrit que sur une jaquette sans image. [badge] : l'année ou la note quand on trie dessus, en haut à droite.
 * [details] : titre, année et note sous l'affiche (option « Titres sous les affiches »).
 */
@Composable
private fun PosterTile(v: LanEntry, info: Catalog.Info, local: String?, w: LanStore.Watch?, details: Boolean, badge: String?, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val desc = listOfNotNull(info.title, info.year?.toString(), info.rating?.let { "★ %.1f".format(it) }).joinToString(", ")
    Column(Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).semantics { contentDescription = desc }) {
        Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f)) {
            PosterArt(info.poster ?: v.thumb, local, info.title, Icons.Rounded.Movie, Modifier.fillMaxSize())
            WatchOverlay(w, Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp)), checkAtStart = badge != null && !details)
            if (badge != null && !details) Text(
                badge, style = MaterialTheme.typography.labelMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                color = Color.White,
                modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).clip(RoundedCornerShape(8.dp))
                    .background(Color.Black.copy(alpha = 0.62f)).padding(horizontal = 7.dp, vertical = 2.dp),
            )
        }
        if (details) {
            Spacer(Modifier.height(6.dp))
            Text(info.title, style = MaterialTheme.typography.labelLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val sub = listOfNotNull(info.year?.toString(), info.rating?.let { "★ %.1f".format(it) })
            if (sub.isNotEmpty()) Text(sub.joinToString("  ·  "), style = MaterialTheme.typography.labelSmall, color = c.onSurfaceVariant)
        }
    }
}

/** Dossier en tuile : l'affiche de la série ou du film quand on la reconnaît, sinon une jaquette « dossier ». */
@Composable
private fun FolderPoster(f: LanEntry, parents: List<String>, details: Boolean = false, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val season = SEASON.find(f.name.trim())
    val art by produceState<Catalog.Info?>(null, f.ref) {
        value = when {
            !Catalog.online -> null
            season != null -> parents.lastOrNull()?.let { Catalog.series(it) }
            Catalog.parse(f.name).year != null -> Catalog.info(f.name, asSeries = false).takeIf { it.poster != null }
            parents.lastOrNull()?.let { SERIES_PARENT.containsMatchIn(it) } == true -> Catalog.series(f.name)
            else -> null
        }
    }
    val title = if (season != null) f.name else art?.title ?: f.name
    Column(Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).semantics { contentDescription = title }) {
        Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f)) {
            PosterArt(art?.poster, null, f.name, Icons.Rounded.Folder, Modifier.fillMaxSize())
            // Petite étiquette « dossier » sur une affiche, pour la distinguer d'un film.
            if (art?.poster != null) Box(
                Modifier.align(Alignment.TopStart).padding(6.dp).size(22.dp).clip(RoundedCornerShape(6.dp)).background(c.surface.copy(alpha = 0.85f)),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Rounded.Folder, null, tint = c.primary, modifier = Modifier.size(14.dp)) }
        }
        // Sous l'affiche : le nom, sauf sur une affiche reconnue (le titre y est) ; toujours pour une saison.
        if (details || season != null || art?.poster == null) {
            Spacer(Modifier.height(6.dp))
            Text(title, style = MaterialTheme.typography.labelLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun SeriesHeader(info: Catalog.Info?, local: String?) {
    info ?: return
    Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        PosterArt(info.poster, local, info.title, Icons.Rounded.Movie, Modifier.width(92.dp).aspectRatio(2f / 3f))
        Spacer(Modifier.width(14.dp))
        Text(info.title, style = MaterialTheme.typography.headlineSmall, maxLines = 3, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).semantics { heading() })
    }
}

/** Titre d'une saison, touché pour la déplier ou la replier ; nombre d'épisodes et combien sont vus. */
@Composable
private fun SeasonHeader(title: String, count: Int?, seen: Int, open: Boolean, onToggle: () -> Unit) {
    val c = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().padding(top = 6.dp).clip(RoundedCornerShape(14.dp)).background(c.surfaceContainerHigh)
        .clickable(onClick = onToggle).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            if (count != null) Text(
                if (seen > 0) "${androidx.compose.ui.res.pluralStringResource(R.plurals.lan_episodes, count, count)} · $seen ✓"
                else androidx.compose.ui.res.pluralStringResource(R.plurals.lan_episodes, count, count),
                style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
        }
        if (count == null) androidx.compose.material3.CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        else Icon(if (open) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null, tint = c.onSurfaceVariant)
    }
}

@Composable
private fun EpisodeRow(v: LanEntry, info: Catalog.Info, local: String?, w: LanStore.Watch?, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val label = info.episode?.let { n -> info.episodeTitle?.let { "$n. $it" } ?: "S%02dE%02d".format(info.season ?: 0, n) } ?: info.title
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(140.dp).aspectRatio(16f / 9f)) {
            PosterArt(info.still ?: v.thumb, local, "", Icons.Rounded.Movie, Modifier.fillMaxSize())
            WatchOverlay(w, Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp)))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
                color = if (w?.finished == true) c.onSurfaceVariant else c.onSurface)
            val sub = listOfNotNull(v.duration?.let { formatLanDuration(it) }, v.size?.takeIf { it > 0 }?.let { formatLanSize(it) },
                Mime.ext(v.name).uppercase().takeIf { it.isNotEmpty() })
            if (sub.isNotEmpty()) Text(sub.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
        }
    }
}
