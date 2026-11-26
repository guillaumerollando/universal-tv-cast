package studio.rollando.castor.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import studio.rollando.castor.R
import studio.rollando.castor.cast.Castor
import studio.rollando.castor.cast.Media
import studio.rollando.castor.data.Channels
import studio.rollando.castor.data.Iptv
import studio.rollando.castor.data.IptvStore
import java.text.Normalizer
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.semantics.selected
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource

/** Liste officielle de chaînes gratuites (Free-TV), proposée pour essayer sans rien chercher. */
val FREE_TV_LIST: String get() = freeChannelsList()

/**
 * Chaînes gratuites du pays du téléphone, dans l'annuaire iptv-org (une liste par pays, bien entretenue : en France,
 * ~170 chaînes sur 230 répondent, contre 10 sur 33 dans Free-TV). Sans pays connu : par langue.
 */
fun freeChannelsList(locale: java.util.Locale = java.util.Locale.getDefault()): String {
    val cc = locale.country.lowercase().let { if (it == "gb") "uk" else it }
    if (cc.length == 2) return "https://iptv-org.github.io/iptv/countries/$cc.m3u"
    val lang = runCatching { locale.isO3Language }.getOrNull()?.takeIf { it.length == 3 } ?: "eng"
    return "https://iptv-org.github.io/iptv/languages/$lang.m3u"
}

private fun fold(s: String) = Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase()

/**
 * Toutes les chaînes, quelle que soit la liste d'où elles viennent, plus celles regardées par leur lien.
 * Null tant que les listes se lisent.
 */
@Composable
fun rememberAllChannels(tick: Int = 0): List<Iptv.Channel>? {
    val lists by IptvStore.lists.collectAsState()
    val recents by studio.rollando.castor.data.Library.recents.collectAsState()
    val fromLists by produceState<List<Iptv.Channel>?>(null, lists, tick) { value = IptvStore.allChannels() }
    return remember(fromLists, recents) {
        fromLists?.let { base ->
            val urls = base.map { it.url }.toHashSet()
            val names = base.map { fold(it.name).filter(Char::isLetterOrDigit) }.toHashSet()
            val extra = recents.filter { it.live && it.url !in urls && fold(it.channelName).filter(Char::isLetterOrDigit) !in names }
                .distinctBy { it.url }
                .map { Iptv.Channel(it.channelName, it.url, it.poster, null, it.headers) }
            extra + base
        }
    }
}

/** Les chaînes : toutes ensemble, une recherche, une mosaïque. Les listes et les chaînes masquées se gèrent dans ⋮. */
@Composable
fun IptvScreen(onClose: () -> Unit, startWith: String? = null) {
    BackHandler(onBack = onClose)
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val lists by IptvStore.lists.collectAsState()
    val hidden by IptvStore.hidden.collectAsState()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var adding by remember { mutableStateOf(false) }
    var showLists by remember { mutableStateOf(false) }
    var showHidden by remember { mutableStateOf(false) }
    var guideFor by remember { mutableStateOf<IptvStore.Playlist?>(null) }
    var refreshTick by remember { mutableStateOf(0) }
    val errNotList = stringResource(R.string.iptv_err_not_list)
    val errLoad = stringResource(R.string.iptv_err_load)
    val all = rememberAllChannels(refreshTick)

    fun import(source: String) {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            runCatching { IptvStore.import(ctx, source) }
                .onSuccess { adding = false; refreshTick++ }
                .onFailure { error = if (it is IptvStore.NotAList) errNotList else errLoad }
            busy = false
        }
    }
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let { import(it.toString()) }
    }
    // Les listes en ligne se remettent à jour toutes seules une fois par jour.
    LaunchedEffect(Unit) { IptvStore.refreshStale(ctx); refreshTick++ }
    val hiddenChannels = remember(all, hidden) { all.orEmpty().filter { it.url in hidden } }

    // Page de détail plein écran, au style de l'appli : son fond sombre et sa lueur verte en haut.
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background, contentColor = MaterialTheme.colorScheme.onBackground) {
    Box(Modifier.fillMaxSize()) {
        Ambient(null)
        Column(Modifier.fillMaxSize().statusBarsPadding().imePadding()) {
            Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.cd_back)) }
                Text(stringResource(R.string.home_channels), style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f).semantics { heading() })
                if (lists.isNotEmpty()) {
                    IconButton(onClick = { adding = true }) { Icon(Icons.Rounded.Add, stringResource(R.string.iptv_add)) }
                    var menu by remember { mutableStateOf(false) }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, stringResource(R.string.iptv_my_lists)) }
                        DropdownMenu(menu, onDismissRequest = { menu = false }, shape = RoundedCornerShape(16.dp)) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.iptv_my_lists)) }, onClick = { menu = false; showLists = true })
                            if (hiddenChannels.isNotEmpty()) DropdownMenuItem(
                                text = { Text(stringResource(R.string.iptv_hidden_title) + " (${hiddenChannels.size})") },
                                onClick = { menu = false; showHidden = true },
                            )
                        }
                    }
                }
            }
            if (busy) Row(Modifier.fillMaxWidth().padding(16.dp, 4.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(12.dp))
                Text(stringResource(R.string.iptv_loading), style = MaterialTheme.typography.bodyMedium)
            }
            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(16.dp, 4.dp))
            }
            if (lists.isEmpty()) AddListPanel(busy, onImport = ::import, onPickFile = { pickFile.launch(arrayOf("*/*")) })
            else ChannelBrowser(all, onClose)
        }
    }
    }
    if (adding) AlertDialog(
        onDismissRequest = { adding = false },
        title = { Text(stringResource(R.string.iptv_add)) },
        text = { AddListPanel(busy, onImport = ::import, onPickFile = { pickFile.launch(arrayOf("*/*")) }, compact = true) },
        confirmButton = {},
        dismissButton = { TextButton(onClick = { adding = false }) { Text(stringResource(R.string.action_cancel)) } },
    )
    // Mes listes : d'où viennent les chaînes ; mettre à jour, supprimer.
    if (showLists) AlertDialog(
        onDismissRequest = { showLists = false },
        title = { Text(stringResource(R.string.iptv_my_lists)) },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(lists, key = { it.id }) { p ->
                    var m by remember { mutableStateOf(false) }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(p.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(pluralStringResource(R.plurals.iptv_count, p.count, p.count), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Box {
                            IconButton(onClick = { m = true }) { Icon(Icons.Rounded.MoreVert, p.name) }
                            DropdownMenu(m, onDismissRequest = { m = false }) {
                                if (p.isRemote) DropdownMenuItem(text = { Text(stringResource(R.string.iptv_update)) },
                                    onClick = { m = false; import(p.source) })
                                DropdownMenuItem(text = { Text(stringResource(R.string.iptv_guide)) },
                                    onClick = { m = false; guideFor = p })
                                DropdownMenuItem(text = { Text(stringResource(R.string.iptv_remove)) },
                                    onClick = { m = false; IptvStore.remove(p.id); refreshTick++ })
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { showLists = false; adding = true }) { Text(stringResource(R.string.iptv_add)) } },
        dismissButton = { TextButton(onClick = { showLists = false }) { Text(stringResource(R.string.action_close)) } },
    )
    // Chaînes masquées : les réafficher une par une, ou toutes.
    if (showHidden) AlertDialog(
        onDismissRequest = { showHidden = false },
        title = { Text(stringResource(R.string.iptv_hidden_title)) },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(hiddenChannels, key = { it.url }) { ch ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ChannelLogo(ch.name, ch.logo, Modifier.size(width = 64.dp, height = 40.dp))
                        Spacer(Modifier.width(12.dp))
                        Text(ch.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        TextButton(onClick = { IptvStore.unhide(ch.url) }) { Text(stringResource(R.string.iptv_unhide)) }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { IptvStore.unhideAll(hiddenChannels.map { it.url }); showHidden = false }) {
                Text(stringResource(R.string.iptv_unhide_all))
            }
        },
        dismissButton = { TextButton(onClick = { showHidden = false }) { Text(stringResource(R.string.action_close)) } },
    )
    LaunchedEffect(hiddenChannels.isEmpty()) { if (hiddenChannels.isEmpty()) showHidden = false }
    // Adresse du guide des programmes d'une liste (facultative), pour les fournisseurs qui la donnent à part.
    guideFor?.let { p ->
        var url by remember(p.id) { mutableStateOf(p.guide.orEmpty()) }
        AlertDialog(
            onDismissRequest = { guideFor = null },
            title = { Text(stringResource(R.string.iptv_guide)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(R.string.iptv_guide_help), style = MaterialTheme.typography.bodyMedium)
                    OutlinedTextField(
                        value = url, onValueChange = { url = it }, singleLine = true,
                        label = { Text(stringResource(R.string.iptv_guide_hint)) }, placeholder = { Text("https://…") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = { TextButton(onClick = { IptvStore.setGuide(p.id, url); guideFor = null }) { Text(stringResource(R.string.action_save)) } },
            dismissButton = { TextButton(onClick = { guideFor = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
private fun AddListPanel(busy: Boolean, onImport: (String) -> Unit, onPickFile: () -> Unit, compact: Boolean = false) {
    var url by rememberSaveable { mutableStateOf("") }
    val valid = url.trim().startsWith("http")
    Column(
        if (compact) Modifier else Modifier.verticalScroll(rememberScrollState()).padding(16.dp).navigationBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (!compact) Text(stringResource(R.string.iptv_intro), style = MaterialTheme.typography.bodyLarge)
        OutlinedTextField(
            value = url, onValueChange = { url = it }, singleLine = true,
            label = { Text(stringResource(R.string.iptv_url_hint)) },
            placeholder = { Text("https://…") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { if (valid) onImport(url.trim()) }),
            modifier = Modifier.fillMaxWidth(),
        )
        FilledTonalButton(onClick = { onImport(url.trim()) }, enabled = valid && !busy, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.iptv_import))
        }
        OutlinedButton(onClick = onPickFile, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Rounded.FolderOpen, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.iptv_file))
        }
        // La liste gratuite (Free-TV, avec son guide des programmes) : proposée tant qu'elle n'est pas importée.
        val lists by IptvStore.lists.collectAsState()
        if (lists.none { it.source == FREE_TV_LIST }) {
            Card(
                onClick = { if (!busy) onImport(FREE_TV_LIST) },
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            ) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Public, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(16.dp))
                    Column {
                        Text(stringResource(R.string.iptv_try_free), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.iptv_try_free_sub), style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        if (!compact) Text(stringResource(R.string.iptv_note), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ChannelBrowser(channels: List<Iptv.Channel>?, onClose: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var group by rememberSaveable { mutableStateOf<String?>(null) }
    val all = channels.orEmpty()
    val groups = remember(all) {
        // Le pays du téléphone en premier (les listes classées par pays les nomment en anglais), puis les plus fournis.
        val here = setOf(java.util.Locale.getDefault().getDisplayCountry(java.util.Locale.ENGLISH),
            java.util.Locale.getDefault().displayCountry).map { fold(it) }
        all.mapNotNull { it.group }.groupingBy { it }.eachCount().entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { fold(it.key) in here }.thenByDescending { it.value })
            .map { it.key }.take(60)
    }
    val hidden by IptvStore.hidden.collectAsState()
    val recents by studio.rollando.castor.data.Library.recents.collectAsState()
    val recent = remember(all, recents, hidden) {
        val byUrl = all.associateBy { it.url }
        // Par son flux, sinon par son nom (une chaîne présente dans deux listes n'en garde qu'un).
        val byName = all.associateBy { fold(it.name).filter(Char::isLetterOrDigit) }
        recents.filter { it.live }.mapNotNull { byUrl[it.url] ?: byName[fold(it.channelName).filter(Char::isLetterOrDigit)] }
            .filter { it.url !in hidden }.distinctBy { it.url }.take(12)
    }
    val shown = remember(all, query, group, hidden) {
        val q = fold(query.trim())
        val list = all.filter { it.url !in hidden && (group == null || it.group == group) && (q.isEmpty() || fold(it.name).contains(q)) }
        // Sans recherche ni catégorie : les chaînes du pays du téléphone d'abord (l'ordre reste celui des listes).
        val here = java.util.Locale.getDefault().country.lowercase()
        if (q.isEmpty() && group == null && here.length == 2) list.sortedByDescending { it.country == here } else list
    }
    var lastHidden by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(lastHidden) { if (lastHidden != null) { kotlinx.coroutines.delay(5000); lastHidden = null } }
    fun play(ch: Iptv.Channel) { playChannel(ch); onClose() }
    fun hide(ch: Iptv.Channel) { IptvStore.hide(ch.url); lastHidden = ch.url }
    // Recherche et catégories : elles s'effacent quand on descend dans la mosaïque et reviennent dès qu'on remonte.
    val density = androidx.compose.ui.platform.LocalDensity.current
    var headerPx by remember { mutableStateOf(0) }
    var shift by remember { mutableStateOf(0f) }
    val conn = remember {
        object : androidx.compose.ui.input.nestedscroll.NestedScrollConnection {
            override fun onPreScroll(available: androidx.compose.ui.geometry.Offset, source: androidx.compose.ui.input.nestedscroll.NestedScrollSource): androidx.compose.ui.geometry.Offset {
                shift = (shift + available.y).coerceIn(-headerPx.toFloat(), 0f)
                return androidx.compose.ui.geometry.Offset.Zero
            }
        }
    }
    val headerDp = with(density) { headerPx.toDp() }
    val glass = remember { dev.chrisbanes.haze.HazeState() }
    val c = MaterialTheme.colorScheme
    val gridState = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
    // Rien dessous tant qu'on est tout en haut : pas de verre fumé (pas de bandeau inutile).
    val overContent by remember { androidx.compose.runtime.derivedStateOf { gridState.firstVisibleItemIndex > 0 || gridState.firstVisibleItemScrollOffset > 0 } }
    Box(Modifier.fillMaxSize().nestedScroll(conn).clipToBounds()) {
        Box(Modifier.fillMaxSize().hazeSource(glass)) {
        when {
            channels == null -> Box(Modifier.fillMaxWidth().padding(top = headerDp + 32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            shown.isEmpty() -> Text(stringResource(R.string.iptv_none_found), modifier = Modifier.padding(start = 16.dp, top = headerDp + 16.dp),
                color = c.onSurfaceVariant)
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(84.dp),
                state = gridState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = ChannelTiles.edge, end = ChannelTiles.edge, top = headerDp,
                    bottom = 16.dp + androidx.compose.foundation.layout.WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()),
                horizontalArrangement = Arrangement.spacedBy(ChannelTiles.gap),
                verticalArrangement = Arrangement.spacedBy(ChannelTiles.gap),
            ) {
                // Les chaînes qu'on regarde, en premier (sans recherche ni filtre en cours).
                if (recent.isNotEmpty() && query.isBlank() && group == null) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        SectionTitle(stringResource(R.string.iptv_recent), Modifier.padding(horizontal = 6.dp).semantics { heading() })
                    }
                    // En mosaïque, comme toutes les chaînes : on les voit d'un coup d'œil, sans faire défiler.
                    items(recent, key = { "r" + it.url }) { ch ->
                        ChannelTile(ch, Modifier, onClick = { play(ch) }, onHide = { hide(ch) }, onForget = {
                            recents.firstOrNull { it.url == ch.url }?.let { studio.rollando.castor.data.Library.removeRecent(it.key) }
                        })
                    }
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        SectionTitle(stringResource(R.string.iptv_all_channels), Modifier.padding(start = 6.dp, end = 6.dp, top = 8.dp).semantics { heading() })
                    }
                }
                items(shown, key = { it.url }) { ch -> ChannelTile(ch, Modifier, onClick = { play(ch) }, onHide = { hide(ch) }) }
            }
        }
        }
        // En-tête en verre fumé, comme la barre d'onglets.
        Column(
            Modifier.fillMaxWidth()
                .onSizeChanged { headerPx = it.height }
                .offset { androidx.compose.ui.unit.IntOffset(0, shift.toInt()) }
                .then(if (overContent && studio.rollando.castor.data.Power.saving.value) Modifier.background(c.surfaceContainer.copy(alpha = 0.97f))
                else if (overContent) Modifier.hazeEffect(glass, HazeStyle(
                    backgroundColor = c.background,
                    tint = HazeTint(Color(0xFF12241F).copy(alpha = 0.78f)),
                    blurRadius = 28.dp, noiseFactor = 0.04f,
                    fallbackTint = HazeTint(Color(0xFF12241F).copy(alpha = 0.96f)),
                )) else Modifier)
                .padding(bottom = 10.dp),
        ) {
            SearchPill(query, { query = it })
            if (groups.size > 1) LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                item { Pill(stringResource(R.string.iptv_all), group == null) { group = null } }
                items(groups) { g -> Pill(g, group == g) { group = if (group == g) null else g } }
            }
        }
        // « Chaîne masquée · Annuler », quelques secondes.
        lastHidden?.let { url ->
            Surface(
                shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.inverseSurface,
                modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(16.dp).fillMaxWidth(),
            ) {
                Row(Modifier.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.iptv_hidden_done), color = MaterialTheme.colorScheme.inverseOnSurface,
                        modifier = Modifier.weight(1f))
                    TextButton(onClick = { IptvStore.unhide(url); lastHidden = null }) {
                        Text(stringResource(R.string.action_undo), color = MaterialTheme.colorScheme.inversePrimary)
                    }
                }
            }
        }
    }
}

/** Recherche en forme de pilule, comme la barre d'adresse du navigateur. */
@Composable
private fun SearchPill(query: String, onChange: (String) -> Unit) {
    val c = MaterialTheme.colorScheme
    androidx.compose.material3.TextField(
        value = query, onValueChange = onChange, singleLine = true,
        leadingIcon = { Icon(Icons.Rounded.Search, null) },
        trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { onChange("") }) { Icon(Icons.Rounded.Close, stringResource(R.string.cd_clear)) } },
        placeholder = { Text(stringResource(R.string.iptv_search)) },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        shape = RoundedCornerShape(50),
        colors = androidx.compose.material3.TextFieldDefaults.colors(
            focusedContainerColor = c.surfaceContainerHigh.copy(alpha = 0.9f), unfocusedContainerColor = c.surfaceContainerHigh.copy(alpha = 0.9f),
            focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
        ),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

/** Filtre rapide, aux couleurs des onglets : ambré quand il est choisi. */
@Composable
private fun Pill(label: String, selected: Boolean, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    Surface(
        onClick = onClick, shape = RoundedCornerShape(50),
        color = if (selected) c.primary else c.surfaceContainerHigh.copy(alpha = 0.9f),
        contentColor = if (selected) c.onPrimary else c.onSurfaceVariant,
        modifier = Modifier.height(36.dp).semantics { this.selected = selected },
    ) {
        Box(Modifier.padding(horizontal = 14.dp), contentAlignment = Alignment.Center) {
            Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1)
        }
    }
}

/**
 * « En ce moment » et « Ensuite » pour la chaîne en cours, d'après le guide des programmes que déclare la liste
 * importée. Rien n'est affiché si aucun guide ne connaît la chaîne.
 */
@Composable
fun NowNext(media: Media, modifier: Modifier = Modifier, compact: Boolean = false) {
    val pool = rememberAllChannels().orEmpty()
    val wanted = studio.rollando.castor.data.Epg.key(media.channel ?: media.title)
    val ch = remember(pool, media.url) {
        pool.firstOrNull { it.url == media.url } ?: pool.firstOrNull { studio.rollando.castor.data.Epg.key(it.name) == wanted }
    }
    val name = ch?.name ?: media.channel ?: media.title
    val country = ch?.country ?: java.util.Locale.getDefault().country.lowercase()
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(60_000); tick++ } }
    val nn by produceState<Pair<studio.rollando.castor.data.Epg.Prog, studio.rollando.castor.data.Epg.Prog?>?>(null, name, country, tick) {
        // Chaque fiche de la chaîne (elle peut venir de plusieurs listes, chacune avec son guide) ; la première qui répond.
        value = runCatching {
            val cands = (listOfNotNull(ch) + IptvStore.entries(media.url, name)).distinctBy { Triple(it.name, it.tvgId, it.country) }
            cands.firstNotNullOfOrNull { c -> studio.rollando.castor.data.Epg.nowNext(c.name, c.country ?: country, c.tvgId) }
                ?: studio.rollando.castor.data.Epg.nowNext(name, country, null)
        }.getOrNull()
    }
    val (now, next) = nn ?: return
    val c = MaterialTheme.colorScheme
    val fmt = remember { java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (!compact) Text(stringResource(R.string.epg_now).uppercase(), style = MaterialTheme.typography.labelSmall, color = c.primary)
        Text(now.title, style = if (compact) MaterialTheme.typography.titleSmall else MaterialTheme.typography.titleMedium,
            maxLines = if (compact) 1 else 2, overflow = TextOverflow.Ellipsis)
        if (!compact) {
            val total = (now.stop - now.start).coerceAtLeast(1)
            val done = ((System.currentTimeMillis() - now.start).toFloat() / total).coerceIn(0f, 1f)
            androidx.compose.material3.LinearProgressIndicator(
                progress = { done }, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).height(3.dp).clip(RoundedCornerShape(2.dp)),
                color = c.primary, trackColor = c.surfaceContainerHighest, drawStopIndicator = {},
            )
            next?.let {
                Text(stringResource(R.string.epg_next) + " · " + fmt.format(java.util.Date(it.start)) + "  " + it.title,
                    style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Lance une chaîne d'une liste sur la télé et passe à l'écran de lecture. */
fun playChannel(ch: Iptv.Channel) {
    Castor.open(Media(
        url = ch.url, title = ch.name, headers = ch.headers, posterUrl = ch.logo, channel = ch.name,
        mime = if (ch.url.substringBefore('?').endsWith(".m3u8", true)) "application/vnd.apple.mpegurl" else null,
    ))
    Nav.tab.value = Tab.PLAYER
}

/**
 * Sous l'écran de lecture d'un direct : zapper sans repasser par la liste. Les chaînes proches (même catégorie,
 * dans la même liste), une chaîne au hasard, et toutes les chaînes.
 */
@Composable
fun ZapSection(media: Media) {
    val lists by IptvStore.lists.collectAsState()
    val hidden by IptvStore.hidden.collectAsState()
    val recents by studio.rollando.castor.data.Library.recents.collectAsState()
    // Toutes les chaînes, d'où qu'elles viennent ; la chaîne en cours retrouvée par son flux ou son nom.
    val pool = rememberAllChannels().orEmpty()
    val currentName = fold(media.channel ?: media.title)
    val current = remember(pool, media.url) { pool.firstOrNull { it.url == media.url } ?: pool.firstOrNull { fold(it.name) == currentName } }
    val suggestions = remember(pool, current, hidden, recents) {
        val ok = pool.filter { it.url !in hidden && it.url != media.url }
        if (ok.isEmpty()) return@remember emptyList()
        // Même catégorie, à partir de la chaîne en cours (comme « chaîne suivante »), sinon les voisines dans la liste.
        val same = current?.group?.let { g -> ok.filter { it.group == g } }.orEmpty()
        val base = same.ifEmpty { ok }
        val start = current?.let { c -> pool.indexOf(c) }?.coerceAtLeast(0) ?: 0
        val after = base.filter { pool.indexOf(it) > start } + base.filter { pool.indexOf(it) <= start }
        // Les chaînes regardées récemment passent devant.
        val recentUrls = recents.filter { it.live }.map { it.url }.toSet()
        (after.filter { it.url in recentUrls } + after.filterNot { it.url in recentUrls }).take(12)
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.zap_title), style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f).semantics { heading() })
            TextButton(onClick = { Nav.iptv.value = "" }) {
                Text(stringResource(if (lists.isEmpty()) R.string.iptv_title else R.string.iptv_all_channels))
                Spacer(Modifier.width(4.dp))
                Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, Modifier.size(18.dp))
            }
        }
        if (suggestions.isNotEmpty()) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(ChannelTiles.gap)) {
                items(suggestions, key = { "z" + it.url }) { ch ->
                    ChannelArt(
                        name = ch.name, logo = ch.logo, streamUrl = null, pad = ChannelTiles.logoPad,
                        textStyle = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.ExtraBold),
                        modifier = Modifier.width(ChannelTiles.width).aspectRatio(16f / 10f).clip(RoundedCornerShape(ChannelTiles.corner))
                            .clickable(role = Role.Button, onClickLabel = ch.name) { playChannel(ch) }
                            .semantics { contentDescription = ch.name },
                    )
                }
            }
            OutlinedButton(
                onClick = { pool.filter { it.url !in hidden && it.url != media.url }.randomOrNull()?.let(::playChannel) },
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                Icon(Icons.Rounded.Shuffle, null)
                Spacer(Modifier.width(10.dp))
                Text(stringResource(R.string.zap_random))
            }
        }
    }
}

/** Case de la mosaïque : le logo (ou le nom) et le nom dessous. Appui long : masquer, ou retirer des récentes. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun ChannelTile(ch: Iptv.Channel, modifier: Modifier, onClick: () -> Unit, onHide: () -> Unit, onForget: (() -> Unit)? = null) {
    var menu by remember { mutableStateOf(false) }
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    val hideLabel = stringResource(R.string.iptv_hide)
    val forgetLabel = stringResource(R.string.iptv_remove_recent)
    Box(modifier) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(ChannelTiles.corner))
                .combinedClickable(role = Role.Button, onClick = onClick, onLongClick = {
                    haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress); menu = true
                })
                .semantics {
                    // Le logo suffit à l'œil ; le nom reste lu par le lecteur d'écran.
                    contentDescription = ch.name
                    customActions = listOfNotNull(
                        androidx.compose.ui.semantics.CustomAccessibilityAction(hideLabel) { onHide(); true },
                        onForget?.let { f -> androidx.compose.ui.semantics.CustomAccessibilityAction(forgetLabel) { f(); true } },
                    )
                }
                .padding(0.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            ChannelArt(
                name = ch.name, logo = ch.logo, streamUrl = null, pad = ChannelTiles.logoPad,
                textStyle = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.ExtraBold),
                modifier = Modifier.fillMaxWidth().aspectRatio(16f / 10f).clip(RoundedCornerShape(ChannelTiles.corner)),
            )
        }
        DropdownMenu(menu, onDismissRequest = { menu = false }, shape = RoundedCornerShape(16.dp)) {
            if (onForget != null) DropdownMenuItem(text = { Text(forgetLabel) }, onClick = { menu = false; onForget() })
            DropdownMenuItem(text = { Text(hideLabel) }, onClick = { menu = false; onHide() })
        }
    }
}

/** Le logo de la chaîne sur un fond assorti ; à défaut, ses initiales sur sa couleur. */
@Composable
fun ChannelLogo(name: String, logo: String?, modifier: Modifier = Modifier.size(width = 64.dp, height = 44.dp)) {
    ChannelArt(
        name = name, logo = logo, streamUrl = null, pad = 6.dp,
        label = name.split(' ').filter { it.isNotBlank() }.take(2).joinToString("") { it.take(1) }.uppercase(),
        textStyle = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.ExtraBold),
        modifier = modifier.clip(RoundedCornerShape(10.dp)),
    )
}
