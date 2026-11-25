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

/** Liste officielle de chaînes gratuites (Free-TV), proposée pour essayer sans rien chercher. */
const val FREE_TV_LIST = "https://raw.githubusercontent.com/Free-TV/IPTV/master/playlist.m3u8"

private fun fold(s: String) = Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase()

/** Les listes de chaînes importées : on en choisit une, on cherche, on touche une chaîne pour l'envoyer à la télé. */
@Composable
fun IptvScreen(onClose: () -> Unit, startWith: String? = null) {
    BackHandler(onBack = onClose)
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val lists by IptvStore.lists.collectAsState()
    var selected by rememberSaveable { mutableStateOf(startWith) }
    val current = lists.firstOrNull { it.id == selected } ?: lists.firstOrNull()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var adding by remember { mutableStateOf(false) }
    var refreshTick by remember { mutableStateOf(0) }
    val errNotList = stringResource(R.string.iptv_err_not_list)
    val errLoad = stringResource(R.string.iptv_err_load)

    fun import(source: String) {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            runCatching { IptvStore.import(ctx, source) }
                .onSuccess { selected = it.id; adding = false; refreshTick++ }
                .onFailure { error = if (it is IptvStore.NotAList) errNotList else errLoad }
            busy = false
        }
    }
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let { import(it.toString()) }
    }
    // Les listes en ligne se remettent à jour toutes seules une fois par jour.
    LaunchedEffect(Unit) { IptvStore.refreshStale(ctx); refreshTick++ }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().statusBarsPadding().imePadding()) {
            Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.cd_back)) }
                Text(stringResource(R.string.iptv_title), style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f).semantics { heading() })
                if (current != null) {
                    IconButton(onClick = { adding = true }) { Icon(Icons.Rounded.Add, stringResource(R.string.iptv_add)) }
                    var menu by remember { mutableStateOf(false) }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, current.name) }
                        DropdownMenu(menu, onDismissRequest = { menu = false }) {
                            if (current.isRemote) DropdownMenuItem(text = { Text(stringResource(R.string.iptv_update)) },
                                onClick = { menu = false; import(current.source) })
                            DropdownMenuItem(text = { Text(stringResource(R.string.iptv_remove)) },
                                onClick = { menu = false; IptvStore.remove(current.id); selected = null })
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
            if (current == null) {
                AddListPanel(busy, onImport = ::import, onPickFile = { pickFile.launch(arrayOf("*/*")) })
            } else {
                if (lists.size > 1) LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(lists, key = { it.id }) { p ->
                        FilterChip(selected = p.id == current.id, onClick = { selected = p.id }, label = { Text(p.name) })
                    }
                }
                ChannelBrowser(current, refreshTick, onClose)
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
}

@Composable
private fun AddListPanel(busy: Boolean, onImport: (String) -> Unit, onPickFile: () -> Unit, compact: Boolean = false) {
    var url by rememberSaveable { mutableStateOf("") }
    val valid = url.trim().startsWith("http")
    Column(
        (if (compact) Modifier else Modifier.verticalScroll(rememberScrollState()).padding(16.dp)).navigationBarsPadding(),
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
        if (!compact) {
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
            Text(stringResource(R.string.iptv_note), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ChannelBrowser(list: IptvStore.Playlist, refreshTick: Int, onClose: () -> Unit) {
    val channels by produceState<List<Iptv.Channel>?>(null, list.id, refreshTick) { value = IptvStore.channels(list.id) }
    var query by rememberSaveable(list.id) { mutableStateOf("") }
    var group by rememberSaveable(list.id) { mutableStateOf<String?>(null) }
    val all = channels.orEmpty()
    val groups = remember(all) {
        // Le pays du téléphone en premier (les listes classées par pays les nomment en anglais), puis les plus fournis.
        val here = setOf(java.util.Locale.getDefault().getDisplayCountry(java.util.Locale.ENGLISH),
            java.util.Locale.getDefault().displayCountry).map { fold(it) }
        all.mapNotNull { it.group }.groupingBy { it }.eachCount().entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { fold(it.key) in here }.thenByDescending { it.value })
            .map { it.key }.take(60)
    }
    val recents by studio.rollando.castor.data.Library.recents.collectAsState()
    val recent = remember(all, recents) {
        val byUrl = all.associateBy { it.url }
        recents.filter { it.live }.mapNotNull { byUrl[it.url] }.distinctBy { it.url }.take(12)
    }
    val shown = remember(all, query, group) {
        val q = fold(query.trim())
        all.filter { (group == null || it.group == group) && (q.isEmpty() || fold(it.name).contains(q)) }
    }
    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query, onValueChange = { query = it }, singleLine = true,
            leadingIcon = { Icon(Icons.Rounded.Search, null) },
            trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Rounded.Close, stringResource(R.string.cd_clear)) } },
            placeholder = { Text(stringResource(R.string.iptv_search)) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            shape = RoundedCornerShape(28.dp),
            modifier = Modifier.fillMaxWidth().padding(16.dp, 8.dp),
        )
        if (groups.size > 1) LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item { FilterChip(selected = group == null, onClick = { group = null }, label = { Text(stringResource(R.string.iptv_all)) }) }
            items(groups) { g -> FilterChip(selected = group == g, onClick = { group = if (group == g) null else g }, label = { Text(g) }) }
        }
        Text(
            "${list.name} · " + pluralStringResource(R.plurals.iptv_count, shown.size, shown.size),
            style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(16.dp, 8.dp),
        )
        when {
            channels == null -> Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            shown.isEmpty() -> Text(stringResource(R.string.iptv_none_found), modifier = Modifier.padding(16.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                fun play(ch: Iptv.Channel) {
                    Castor.open(Media(
                        url = ch.url, title = ch.name, headers = ch.headers, posterUrl = ch.logo, channel = ch.name,
                        mime = if (ch.url.substringBefore('?').endsWith(".m3u8", true)) "application/vnd.apple.mpegurl" else null,
                    ))
                    Nav.tab.value = Tab.PLAYER
                    onClose()
                }
                // Les chaînes qu'on regarde, en premier (sans recherche ni filtre en cours).
                if (recent.isNotEmpty() && query.isBlank() && group == null) {
                    item {
                        Text(stringResource(R.string.iptv_recent), style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(16.dp, 4.dp).semantics { heading() })
                    }
                    item {
                        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            items(recent, key = { "r" + it.url }) { ch ->
                                Column(Modifier.width(112.dp).clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button) { play(ch) }.padding(2.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally) {
                                    ChannelLogo(ch.name, ch.logo, Modifier.size(width = 108.dp, height = 68.dp))
                                    Spacer(Modifier.height(6.dp))
                                    Text(ch.name, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                    item {
                        Text(stringResource(R.string.iptv_all_channels), style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(16.dp, 16.dp, 16.dp, 4.dp).semantics { heading() })
                    }
                }
                items(shown, key = { it.url }) { ch -> ChannelRow(ch) { play(ch) } }
                item { Spacer(Modifier.navigationBarsPadding()) }
            }
        }
    }
}

@Composable
private fun ChannelRow(ch: Iptv.Channel, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ChannelLogo(ch.name, ch.logo)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(ch.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            ch.group?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
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
