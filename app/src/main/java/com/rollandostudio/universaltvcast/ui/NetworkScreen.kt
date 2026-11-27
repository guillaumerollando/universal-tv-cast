package com.rollandostudio.universaltvcast.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.rollandostudio.universaltvcast.R
import com.rollandostudio.universaltvcast.cast.Media
import com.rollandostudio.universaltvcast.cast.TvCast
import com.rollandostudio.universaltvcast.lan.Lan
import com.rollandostudio.universaltvcast.lan.LanDevice
import com.rollandostudio.universaltvcast.lan.LanEntry
import com.rollandostudio.universaltvcast.lan.LanStore
import com.rollandostudio.universaltvcast.lan.MediaServers
import com.rollandostudio.universaltvcast.lan.Smb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Un dossier ouvert : l'appareil, l'endroit (identifiant DLNA ou chemin du partage) et son nom affiché. */
private data class Place(val device: LanDevice, val ref: String?, val title: String)

/**
 * « Réseau » : les ordinateurs, NAS et serveurs multimédias de la maison. On les trouve tout seuls, on parcourt
 * leurs dossiers et on envoie une vidéo à la télé d'un appui. Les appareils déjà ouverts sont retenus.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NetworkScreen(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val c = MaterialTheme.colorScheme
    val devices = remember { mutableStateListOf<LanDevice>() }
    var scanning by remember { mutableStateOf(true) }
    var scanTick by remember { mutableStateOf(0) }
    val path = remember { mutableStateListOf<Place>() }
    var entries by remember { mutableStateOf<List<LanEntry>?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var loginFor by remember { mutableStateOf<LanDevice?>(null) }
    var adding by remember { mutableStateOf(false) }
    var savedKeys by remember { mutableStateOf(LanStore.all().map { it.key }.toSet()) }
    val errFolder = stringResource(R.string.net_error)

    fun addDevice(d: LanDevice) {
        val i = devices.indexOfFirst { it.kind == d.kind && it.host == d.host && (it.port == d.port || d.kind == LanDevice.Kind.MEDIA_SERVER) }
        if (i >= 0) {
            // Le nom donné par l'appareil remplace l'adresse affichée en attendant.
            if (devices[i].name == devices[i].host || devices[i].controlUrl == null) devices[i] = d.copy(name = if (d.name == d.host) devices[i].name else d.name)
        } else devices += d
    }

    // Appareils retenus d'abord (affichés tout de suite), puis recherche sur le réseau.
    LaunchedEffect(scanTick) {
        LanStore.all().forEach { s ->
            if (devices.none { it.kind == s.kind && it.host == s.host }) devices += LanDevice(s.kind, s.name, s.host, s.port, location = s.location, share = s.share)
        }
        scanning = true
        withContext(Dispatchers.IO) {
            val a = launch { runCatching { MediaServers.discover(ctx) { d -> scope.launch { addDevice(d) } } } }
            val b = launch { runCatching { Smb.discover { d -> scope.launch { addDevice(d) } } } }
            // Serveurs retenus qui n'ont pas répondu à la recherche : on les interroge directement.
            LanStore.all().filter { it.kind == LanDevice.Kind.MEDIA_SERVER }.forEach { s ->
                launch {
                    runCatching { s.location?.let { MediaServers.describe(it) } ?: MediaServers.probe(s.host, s.port.takeIf { it > 0 }) }
                        .getOrNull()?.let { d -> scope.launch { addDevice(d) } }
                }
            }
            a.join(); b.join()
        }
        scanning = false
    }

    fun open(place: Place) {
        loading = true; error = null; entries = null
        scope.launch {
            val d = place.device
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    when {
                        d.kind == LanDevice.Kind.MEDIA_SERVER -> {
                            val dev = if (d.controlUrl != null) d
                                else d.location?.let { runCatching { MediaServers.describe(it) }.getOrNull() }
                                    ?: MediaServers.probe(d.host, d.port.takeIf { it > 0 }) ?: error("introuvable")
                            MediaServers.browse(dev, place.ref ?: "0").filter { it.folder || it.kind != LanEntry.Kind.PHOTO }
                        }
                        place.ref == null -> Smb.shares(d)
                        else -> Smb.list(d, place.ref)
                    }
                }
            }
            loading = false
            r.onFailure { android.util.Log.i("TvCastLan", "dossier illisible (${d.kind} ${d.address} ${place.ref}) : $it", it) }
            r.onSuccess {
                entries = it
                // Appareil ouvert avec succès : retenu pour la prochaine fois (sans toucher à son identifiant).
                if (place.ref == null && LanStore.find(d.kind, d.host, d.port) == null) {
                    LanStore.save(LanStore.Saved(d.kind, d.name, d.host, d.port, location = d.location, share = d.share))
                    savedKeys = LanStore.all().map { s -> s.key }.toSet()
                }
            }.onFailure { e ->
                if (e is Smb.NeedLogin) { loginFor = d; if (path.lastOrNull() == place) path.removeAt(path.lastIndex) }
                else error = errFolder
            }
        }
    }

    fun go(place: Place) { path += place; open(place) }
    fun up() {
        path.removeAt(path.lastIndex)
        if (path.isEmpty()) { entries = null; error = null; loading = false } else open(path.last())
    }

    fun play(e: LanEntry, all: List<LanEntry>) {
        val d = e.device ?: return
        when (e.kind) {
            LanEntry.Kind.PHOTO -> {
                // Photos du dossier en album, à partir de celle touchée.
                val photos = all.filter { it.kind == LanEntry.Kind.PHOTO }
                val i = photos.indexOf(e).coerceAtLeast(0)
                TvCast.openPhotos((photos.drop(i) + photos.take(i)).map { Smb.contentUri(d, it.ref) })
            }
            else -> {
                val m = if (d.kind == LanDevice.Kind.MEDIA_SERVER) Media(url = e.ref, title = e.name, mime = e.mime, posterUrl = e.thumb)
                else Media(url = Smb.contentUri(d, e.ref).toString(), title = e.name, mime = e.mime, fileName = e.name)
                TvCast.open(m)
            }
        }
        Nav.tab.value = Tab.PLAYER
        onClose()
    }

    BackHandler { if (path.isNotEmpty()) up() else onClose() }

    Surface(Modifier.fillMaxSize(), color = c.background, contentColor = c.onBackground) {
    Box(Modifier.fillMaxSize()) {
        Ambient(null)
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { if (path.isNotEmpty()) up() else onClose() }) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.cd_back))
                }
                Column(Modifier.weight(1f)) {
                    Text(path.lastOrNull()?.title ?: stringResource(R.string.home_network), style = MaterialTheme.typography.titleLarge,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.semantics { heading() })
                    if (path.size > 1) Text(path.first().device.name, style = MaterialTheme.typography.bodySmall,
                        color = c.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (path.isEmpty() && !scanning) IconButton(onClick = { scanTick++ }) {
                    Icon(Icons.Rounded.Refresh, stringResource(R.string.tv_search_again))
                }
            }

            if (path.isEmpty()) {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp, 4.dp, 16.dp, 24.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                            Text(stringResource(R.string.net_found), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                            if (scanning) {
                                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.net_searching), style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
                            }
                        }
                    }
                    items(devices.sortedWith(compareBy<LanDevice> { "${it.kind.name}:${it.host}:${it.port}" !in savedKeys }.thenBy { it.name.lowercase() }),
                        key = { it.kind.name + it.host + it.port }) { d ->
                        var menu by remember { mutableStateOf(false) }
                        val saved = "${d.kind.name}:${d.host}:${d.port}" in savedKeys
                        Box {
                            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(c.surfaceContainerHigh)
                                .combinedClickable(onLongClick = { if (saved) menu = true }) { go(Place(d, null, d.name)) }
                                .padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(42.dp).clip(RoundedCornerShape(12.dp)).background(c.primary.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
                                    Icon(if (d.kind == LanDevice.Kind.MEDIA_SERVER) Icons.Rounded.Dns else Icons.Rounded.Computer, null, tint = c.primary)
                                }
                                Spacer(Modifier.width(14.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(d.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(stringResource(if (d.kind == LanDevice.Kind.MEDIA_SERVER) R.string.net_media_server else R.string.net_shared_folders) +
                                        " · " + (d.model ?: d.address), style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                            DropdownMenu(menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(text = { Text(stringResource(R.string.net_forget)) }, onClick = {
                                    menu = false
                                    LanStore.forget("${d.kind.name}:${d.host}:${d.port}")
                                    Smb.closeAll()
                                    savedKeys = LanStore.all().map { it.key }.toSet()
                                })
                            }
                        }
                    }
                    if (devices.isEmpty() && !scanning) item {
                        Text(stringResource(R.string.net_none), style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 8.dp))
                    }
                    item {
                        TextButton(onClick = { adding = true }) {
                            Icon(Icons.Rounded.Add, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.net_add))
                        }
                    }
                    item { ShareHelp(startOpen = devices.isEmpty() && !scanning) }
                }
            } else {
                when {
                    loading -> Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    error != null -> Text(error!!, color = c.error, modifier = Modifier.padding(16.dp))
                    entries?.isEmpty() == true -> Text(stringResource(R.string.net_empty_folder), color = c.onSurfaceVariant, modifier = Modifier.padding(16.dp))
                    else -> {
                        val list = entries.orEmpty()
                        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp, 0.dp, 12.dp, 24.dp)) {
                            items(list) { e ->
                                EntryRow(e) {
                                    if (e.folder) go(Place(e.device!!, e.ref, e.name)) else play(e, list)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    }

    loginFor?.let { d ->
        LoginDialog(d, onDismiss = { loginFor = null }) {
            loginFor = null
            savedKeys = LanStore.all().map { it.key }.toSet()
            go(Place(d, null, d.name))
        }
    }
    if (adding) AddDialog(onDismiss = { adding = false }) { d ->
        adding = false
        // Ajouté à la main : retenu tout de suite, même si l'identifiant reste à donner.
        val old = LanStore.find(d.kind, d.host, d.port)
        LanStore.save((old ?: LanStore.Saved(d.kind, d.name, d.host, d.port)).copy(location = d.location ?: old?.location, share = d.share ?: old?.share))
        savedKeys = LanStore.all().map { it.key }.toSet()
        devices.removeAll { it.kind == d.kind && it.host == d.host && it.port == d.port }
        devices += d.copy(share = d.share ?: old?.share)
        go(Place(devices.last(), null, d.name))
    }
}

@Composable
private fun EntryRow(e: LanEntry, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick).padding(8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        val icon: ImageVector = when {
            e.folder -> Icons.Rounded.Folder
            e.kind == LanEntry.Kind.AUDIO -> Icons.Rounded.MusicNote
            e.kind == LanEntry.Kind.PHOTO -> Icons.Rounded.Image
            else -> Icons.Rounded.Movie
        }
        Box(Modifier.size(width = 64.dp, height = 40.dp).clip(RoundedCornerShape(8.dp)).background(c.surfaceContainerHigh),
            contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = if (e.folder) c.primary else c.onSurfaceVariant)
            if (e.thumb != null) RemoteImage(e.thumb, Modifier.fillMaxSize(), maxSide = 256)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(e.name, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val info = listOfNotNull(e.duration?.let { formatDuration(it) }, e.size?.takeIf { it > 0 }?.let { formatSize(it) })
            if (info.isNotEmpty()) Text(info.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
        }
    }
}

private fun formatDuration(s: Double): String {
    val t = s.toLong()
    return when {
        t >= 3600 -> "%d h %02d".format(t / 3600, t % 3600 / 60)
        t >= 60 -> "%d min".format(t / 60)
        else -> "%d s".format(t)
    }
}

private fun formatSize(b: Long): String = when {
    b >= 1L shl 30 -> "%.1f Go".format(b / (1L shl 30).toDouble())
    b >= 1L shl 20 -> "%d Mo".format(b / (1L shl 20))
    else -> "%d ko".format((b / 1024).coerceAtLeast(1))
}

/** Comment partager un dossier depuis un ordinateur : Windows, Mac, NAS. Ouvert d'office tant que rien n'est trouvé. */
@Composable
private fun ShareHelp(startOpen: Boolean) {
    val c = MaterialTheme.colorScheme
    var open by remember(startOpen) { mutableStateOf(startOpen) }
    Column(Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(18.dp)).background(c.surfaceContainer)) {
        Row(Modifier.fillMaxWidth().clickable { open = !open }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.net_help_title), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Icon(if (open) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null)
        }
        AnimatedVisibility(open) {
            Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.net_help_intro), style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant)
                HelpBlock("Windows", stringResource(R.string.net_help_windows))
                HelpBlock("Mac", stringResource(R.string.net_help_mac))
                HelpBlock(stringResource(R.string.net_help_nas_title), stringResource(R.string.net_help_nas))
            }
        }
    }
}

@Composable
private fun HelpBlock(title: String, body: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Text(body, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun LoginDialog(d: LanDevice, onDismiss: () -> Unit, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    val saved = remember(d) { LanStore.find(LanDevice.Kind.SHARE, d.host, d.port) }
    var user by remember { mutableStateOf(saved?.user.orEmpty()) }
    var password by remember { mutableStateOf("") }
    var show by remember { mutableStateOf(false) }
    var remember by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    fun submit() {
        busy = true; failed = false
        scope.launch {
            // « PC\nom » ou « nom@domaine » : le domaine est séparé de l'identifiant.
            val (domain, name) = if ('\\' in user) user.substringBefore('\\') to user.substringAfter('\\') else null to user.trim()
            val ok = withContext(Dispatchers.IO) { runCatching { Smb.login(d, name, password, domain, remember) }
                .onFailure { android.util.Log.i("TvCastLan", "connexion refusée : $it", it.cause ?: it) }.isSuccess }
            busy = false
            if (ok) onDone() else failed = true
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.Lock, null) },
        title = { Text(stringResource(R.string.net_login_title, d.name)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.net_login_help), style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(user, { user = it }, singleLine = true, label = { Text(stringResource(R.string.net_user)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next))
                OutlinedTextField(password, { password = it }, singleLine = true, label = { Text(stringResource(R.string.net_password)) },
                    visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (!busy && user.isNotBlank()) submit() }),
                    trailingIcon = {
                        IconButton(onClick = { show = !show }) {
                            Icon(if (show) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, stringResource(R.string.net_password))
                        }
                    })
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { remember = !remember }) {
                    Checkbox(remember, { remember = it })
                    Text(stringResource(R.string.net_remember), style = MaterialTheme.typography.bodyMedium)
                }
                if (failed) Text(stringResource(R.string.net_login_failed), color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            TextButton(onClick = ::submit, enabled = !busy && user.isNotBlank()) {
                if (busy) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp) else Text(stringResource(R.string.net_login))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** Ajout à la main d'un ordinateur ou d'un NAS que la recherche ne trouve pas (autre réseau, nom inconnu…). */
@Composable
private fun AddDialog(onDismiss: () -> Unit, onFound: (LanDevice) -> Unit) {
    val scope = rememberCoroutineScope()
    var address by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    fun submit() {
        val (host0, port) = Lan.parseAddress(address) ?: run { failed = true; return }
        val share = Lan.parseShare(address)
        busy = true; failed = false
        scope.launch {
            val d = withContext(Dispatchers.IO) {
                val host = runCatching { java.net.InetAddress.getByName(host0).hostAddress }.getOrNull() ?: host0
                val name = if (host != host0) host0 else Smb.netbiosName(host) ?: host
                // Port donné : serveur multimédia d'abord (8200, 9000…), sinon dossiers partagés. Sans port : partage d'abord.
                val dlna = { runCatching { MediaServers.probe(host, port.takeIf { it > 0 }) }.getOrNull() }
                val smb = { if (Lan.portOpen(host, if (port > 0) port else 445, 1500)) LanDevice(LanDevice.Kind.SHARE, name, host, port, share = share) else null }
                if (port > 0 && share == null) dlna() ?: smb() else smb() ?: dlna()
            }
            busy = false
            if (d != null) onFound(d) else failed = true
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.net_add)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(address, { address = it; failed = false }, singleLine = true,
                    label = { Text(stringResource(R.string.net_add_hint)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (!busy && address.isNotBlank()) submit() }))
                Text(stringResource(R.string.net_add_help), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (failed) Text(stringResource(R.string.net_add_none), color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            TextButton(onClick = ::submit, enabled = !busy && address.isNotBlank()) {
                if (busy) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp) else Text(stringResource(R.string.action_ok))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
