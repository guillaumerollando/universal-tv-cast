package studio.rollando.castor.ui

import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.HelpOutline
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material.icons.rounded.CastConnected
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material.icons.rounded.WifiFind
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.statusBars
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.asPaddingValues
import dev.chrisbanes.haze.hazeSource
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.MutableStateFlow
import studio.rollando.castor.BuildConfig
import studio.rollando.castor.R
import studio.rollando.castor.browser.BrowserController
import studio.rollando.castor.browser.BrowserScreen
import studio.rollando.castor.cast.Castor
import studio.rollando.castor.cast.Intake
import studio.rollando.castor.cast.Media
import studio.rollando.castor.data.Library

enum class Tab { HOME, BROWSER, PLAYER }

/** Onglet affiché ; les intentions venues d'autres applis basculent sur « Lecture ». */
object Nav {
    val tab = MutableStateFlow(Tab.HOME)
    val openUrl = MutableStateFlow<String?>(null)
    /** Panneau d'aide ouvert (bouton « ? », secousse, lien dans une erreur). */
    val help = MutableStateFlow(false)
    /** Demande d'ouvrir le choix de la télé (depuis l'aide). */
    val tvs = MutableStateFlow(false)
    /** Écran des listes de chaînes ouvert : id de la liste à montrer ("" : la première). */
    val iptv = MutableStateFlow<String?>(null)
    /** Le navigateur affiche son bouton « N vidéos » (en bas à droite). */
    val browserVideoButton = MutableStateFlow(false)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CastorApp() {
    val context = LocalContext.current
    val state by Castor.state.collectAsState()
    val tab by Nav.tab.collectAsState()
    var showTvs by rememberSaveable { mutableStateOf(false) }
    val tvsRequest by Nav.tvs.collectAsState()
    LaunchedEffect(tvsRequest) { if (tvsRequest) { showTvs = true; Nav.tvs.value = false } }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var showLink by rememberSaveable { mutableStateOf(false) }
    val browser = remember { BrowserController(context) }
    // Ce qui défile sous la barre d'onglets, flouté derrière elle.
    val haze = remember { dev.chrisbanes.haze.HazeState() }
    // Ouvrir une page dans le navigateur depuis un autre écran (épisode à reprendre…).
    val urlRequest by Nav.openUrl.collectAsState()
    LaunchedEffect(urlRequest) {
        urlRequest?.let { browser.load(it); Nav.tab.value = Tab.BROWSER; Nav.openUrl.value = null }
    }

    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        Castor.open(Media(url = uri.toString(), title = Intake.nameOf(context, uri), mime = context.contentResolver.getType(uri)))
        Nav.tab.value = Tab.PLAYER
    }
    // Sélecteur de photos et vidéos d'Android : fiable sur tous les téléphones, sans autorisation à demander.
    val pickVideo = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        Castor.open(Media(url = uri.toString(), title = Intake.nameOf(context, uri), mime = context.contentResolver.getType(uri)))
        Nav.tab.value = Tab.PLAYER
    }
    val pickPhotos = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(100)) { uris: List<Uri> ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        Castor.openPhotos(uris)
        Nav.tab.value = Tab.PLAYER
    }

    // Ambiance derrière l'en-tête : l'image floutée du film en cours, sinon un dégradé vert sombre.
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
    Ambient(state.media?.backdropUrl?.takeIf { tab == Tab.PLAYER && state.slides == null })
    // Vidéo sur le téléphone : sa lumière éclaire le haut de l'écran, en-tête compris, comme un éclairage d'ambiance.
    val glowArgb by studio.rollando.castor.cast.PhoneEngine.glow.collectAsState()
    val iptvNow by Nav.iptv.collectAsState()
    val glowColor by androidx.compose.animation.animateColorAsState(
        if (state.onPhone && tab == Tab.PLAYER && iptvNow == null && glowArgb != null) androidx.compose.ui.graphics.Color(glowArgb!!)
        else androidx.compose.ui.graphics.Color.Transparent,
        // Court : la lueur suit l'image presque en direct (une couleur toutes les 0,4 s, fondue en 0,45 s).
        androidx.compose.animation.core.tween(450), label = "lueur",
    )
    val statusTop = androidx.compose.foundation.layout.WindowInsets.statusBars.getTop(androidx.compose.ui.platform.LocalDensity.current)
    if (glowColor.alpha > 0f) Box(
        Modifier.fillMaxWidth().height(700.dp).drawBehind {
            // Centrée sur la vidéo : barre d'état + en-tête (64 dp) + marge, puis le milieu de la vidéo (16:9).
            val videoH = (size.width - 32.dp.toPx()) * 9f / 16f
            val cy = statusTop + 72.dp.toPx() + videoH / 2
            drawRect(androidx.compose.ui.graphics.Brush.radialGradient(
                listOf(glowColor.copy(alpha = 0.6f * glowColor.alpha), glowColor.copy(alpha = 0.22f * glowColor.alpha), androidx.compose.ui.graphics.Color.Transparent),
                center = androidx.compose.ui.geometry.Offset(size.width / 2, cy),
                radius = size.width * 1.05f,
            ))
        },
    )
    Scaffold(
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onBackground,
        topBar = {
            // Barre maison : le nom en deux étages (« UNIVERSAL » au-dessus de « TV Cast ») reste compact ;
            // c'est le nom de la télé qui se raccourcit.
            // Navigateur : pas d'en-tête d'appli, toute la place pour le site (la barre d'onglets permet d'en sortir).
            if (tab != Tab.BROWSER) {
            Surface(color = androidx.compose.ui.graphics.Color.Transparent, contentColor = MaterialTheme.colorScheme.onSurface) {
                Row(
                    Modifier.fillMaxWidth().statusBarsPadding().height(64.dp).padding(start = 16.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(painterResource(R.drawable.ic_logo), null, Modifier.size(30.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    val appName = stringResource(R.string.app_name)
                    Column(Modifier.clearAndSetSemantics { heading(); contentDescription = appName }) {
                        Text(
                            "UNIVERSAL", maxLines = 1, softWrap = false,
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = 10.sp, lineHeight = 11.sp, letterSpacing = 2.2.sp, fontWeight = FontWeight.Bold,
                            ),
                        )
                        Text(
                            "TV Cast", maxLines = 1, softWrap = false,
                            style = MaterialTheme.typography.headlineSmall.copy(fontSize = 23.sp, lineHeight = 24.sp),
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                        TvChip(state.renderer?.name, state.searching, casting = state.active && !state.onPhone) { showTvs = true }
                    }
                    IconButton(onClick = { Nav.help.value = true }) {
                        Icon(Icons.AutoMirrored.Rounded.HelpOutline, stringResource(R.string.cd_help))
                    }
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Rounded.Settings, stringResource(R.string.cd_settings))
                    }
                }
            }
            }
        },
        bottomBar = { PillTabBar(tab, state.active, haze) { Nav.iptv.value = null; Nav.tab.value = it } },
    ) { padding ->
        // Les écrans défilent sous la barre d'onglets (sauf le navigateur) et finissent au-dessus d'elle.
        val bottom = padding.calculateBottomPadding()
        Box(Modifier.padding(top = padding.calculateTopPadding()).fillMaxSize()
            // Pas de flou derrière la barre d'onglets sur le navigateur : la capture continue de la page web la faisait
            // clignoter (et la page ne passe pas sous la barre).
            .then(if (tab != Tab.BROWSER) Modifier.hazeSource(haze) else Modifier)
            .then(if (tab == Tab.BROWSER) Modifier.padding(bottom = bottom) else Modifier)) {
            androidx.compose.runtime.CompositionLocalProvider(LocalBottomInset provides if (tab == Tab.BROWSER) 0.dp else bottom) {
            when (tab) {
                Tab.HOME -> HomeScreen(
                    onBrowse = { url -> url?.let { browser.load(it) }; Nav.tab.value = Tab.BROWSER },
                    onPickFile = { pickFile.launch(arrayOf("video/*", "audio/*")) },
                    onPickVideo = { pickVideo.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)) },
                    onPickPhotos = { pickPhotos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) },
                    onPasteLink = { showLink = true },
                )
                Tab.BROWSER -> BrowserScreen(browser)
                Tab.PLAYER -> PlayerScreen(state, onChooseTv = { showTvs = true })
            }
            }
        }
    }
    }

    // Lecture sur le téléphone, téléphone tourné : la vidéo en plein écran.
    val landscape = androidx.compose.ui.platform.LocalConfiguration.current.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    if (state.onPhone && landscape && tab == Tab.PLAYER && Nav.iptv.collectAsState().value == null) PhoneFullscreen(state)
    // Page de détail « Chaînes » : plein écran, sans l'en-tête ni la barre d'onglets de l'appli.
    val iptvOpen by Nav.iptv.collectAsState()
    if (iptvOpen != null) IptvScreen(onClose = { Nav.iptv.value = null })
    // Lecture sur le téléphone, ailleurs que sur l'écran de lecture : la vidéo continue en vignette.
    val playingOnPhone = state.onPhone && (state.active || state.phase == studio.rollando.castor.cast.Phase.SENDING)
    if (playingOnPhone && !landscape && (tab != Tab.PLAYER || iptvOpen != null)) {
        // Sur le navigateur, quand le bouton « 1 vidéo » est affiché, la vignette se tient au-dessus de lui.
        val videoButton by Nav.browserVideoButton.collectAsState()
        val bottomBar = if (iptvOpen != null) 0.dp else if (tab == Tab.BROWSER && videoButton) 88.dp + 72.dp else 88.dp
        MiniPlayer(state, bottom = bottomBar + 12.dp + androidx.compose.foundation.layout.WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding())
    }
    if (showTvs) TvSheet(onDismiss = { showTvs = false })
    if (showSettings) SettingsSheet(onDismiss = { showSettings = false })
    val showHelp by Nav.help.collectAsState()
    if (showHelp) HelpSheet(onDismiss = { Nav.help.value = false })
    if (showLink) LinkDialog(onDismiss = { showLink = false })
}

@Composable
private fun TvChip(name: String?, searching: Boolean, casting: Boolean, onClick: () -> Unit) {
    val label = name ?: stringResource(R.string.tv_choose)
    val cd = stringResource(R.string.cd_choose_tv)
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        // Vert seulement quand une vidéo passe vraiment sur la télé : choisie ne veut pas dire allumée (une télé en veille répond).
        color = if (name != null && casting) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = if (name != null && casting) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.height(40.dp).semantics { contentDescription = "$cd : $label" },
    ) {
        Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (searching && name == null) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            else Icon(Icons.Rounded.Tv, null, Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false))
            Icon(Icons.Rounded.KeyboardArrowDown, null, Modifier.size(18.dp))
        }
    }
}



@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TvSheet(onDismiss: () -> Unit) {
    val state by Castor.state.collectAsState()
    ModalBottomSheet(onDismissRequest = onDismiss, modifier = Modifier.statusBarsPadding()) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 24.dp)) {
            Text(stringResource(R.string.tv_sheet_title), style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(12.dp))
            if (state.renderers.isEmpty()) {
                Column(Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    if (state.searching) {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(12.dp))
                        Text(stringResource(R.string.tv_searching), style = MaterialTheme.typography.bodyLarge)
                    } else {
                        Icon(Icons.Rounded.WifiFind, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.height(8.dp))
                        Text(stringResource(R.string.tv_none_title), style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(4.dp))
                        Text(stringResource(R.string.tv_none_body), style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            } else {
                LazyColumn {
                    items(state.renderers, key = { it.udn }) { r ->
                        val selected = r.udn == state.renderer?.udn
                        ListItem(
                            headlineContent = { Text(r.name) },
                            supportingContent = {
                                Text(
                                    listOfNotNull(
                                        stringResource(if (r.cast) R.string.tv_type_cast else R.string.tv_type_dlna),
                                        r.model?.takeIf { it != r.name },
                                    ).joinToString(" · ")
                                )
                            },
                            leadingContent = { Icon(Icons.Rounded.Tv, null) },
                            trailingContent = { RadioButton(selected = selected, onClick = null) },
                            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                            modifier = Modifier
                                .padding(vertical = 3.dp)
                                .selectable(selected = selected, role = Role.RadioButton) { Castor.chooseTv(r); onDismiss() },
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            if (state.renderer != null) {
                OutlinedButton(
                    onClick = { Castor.disconnect(); onDismiss() },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                ) {
                    Icon(Icons.Rounded.LinkOff, null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.tv_disconnect, state.renderer!!.name), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.height(8.dp))
            }
            FilledTonalButton(onClick = { Castor.discover() }, enabled = !state.searching, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                Icon(Icons.Rounded.Refresh, null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(if (state.searching) R.string.tv_searching else R.string.tv_search_again))
            }
            var manual by rememberSaveable { mutableStateOf(false) }
            var ip by rememberSaveable { mutableStateOf("") }
            val ipOk = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$").matches(ip.trim())
            if (!manual) {
                TextButton(onClick = { manual = true }, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                    Text(stringResource(R.string.tv_add_ip))
                }
            } else {
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = ip, onValueChange = { ip = it }, singleLine = true,
                    label = { Text(stringResource(R.string.tv_ip_label)) },
                    placeholder = { Text("192.168.1.20") },
                    supportingText = { Text(stringResource(R.string.tv_ip_help)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (ipOk) Castor.addByIp(ip) }),
                    modifier = Modifier.fillMaxWidth(),
                )
                FilledTonalButton(
                    onClick = { Castor.addByIp(ip) }, enabled = ipOk && !state.searching,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                ) { Text(stringResource(R.string.action_add)) }
                state.notice?.takeIf { it.res == R.string.err_ip_not_found }?.let { n ->
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(n.res, n.arg ?: ""), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
private fun LinkDialog(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var text by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val errLoad = stringResource(R.string.iptv_err_load)
    val valid = text.trim().startsWith("http") && !busy
    fun cast(url: String) {
        Castor.open(Media(url = url, title = Uri.decode(url.substringBefore('?').substringAfterLast('/')).ifBlank { url }))
        Nav.tab.value = Tab.PLAYER
        onDismiss()
    }
    fun go() {
        if (!valid) return
        val url = text.trim()
        // Fichier vidéo évident : on l'envoie tout de suite. Sinon, on regarde d'abord si c'est une liste de chaînes.
        if (studio.rollando.castor.net.Mime.ext(url) in setOf("mp4", "mkv", "webm", "avi", "mov", "ts", "mp3", "m4v", "flv")) return cast(url)
        busy = true
        scope.launch {
            if (studio.rollando.castor.data.IptvStore.looksLikeList(url)) {
                runCatching { studio.rollando.castor.data.IptvStore.import(ctx, url) }
                    .onSuccess { Nav.iptv.value = it.id; onDismiss() }
                    .onFailure { error = errLoad; busy = false }
            } else cast(url)
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.link_dialog_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = text, onValueChange = { text = it; error = null }, singleLine = true,
                    placeholder = { Text("https://…") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { go() }),
                    modifier = Modifier.fillMaxWidth(),
                )
                when {
                    busy -> Row(verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text(stringResource(R.string.iptv_loading), style = MaterialTheme.typography.bodyMedium)
                    }
                    error != null -> Text(error!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                    else -> Text(stringResource(R.string.link_dialog_note), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        // « Ouvrir » : une vidéo s'ouvre (puis on choisit télé ou téléphone), une liste de chaînes s'importe.
        confirmButton = { TextButton(onClick = ::go, enabled = valid) { Text(stringResource(R.string.bookmark_open)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val adBlock by Library.adBlock.collectAsState()
    val continuous by Library.continuousHls.collectAsState()
    ModalBottomSheet(onDismissRequest = onDismiss, modifier = Modifier.statusBarsPadding()) {
        LazyColumn(contentPadding = PaddingValues(start = 8.dp, end = 8.dp, bottom = 32.dp)) {
            item {
                Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            }
            item {
                SwitchRow(stringResource(R.string.settings_adblock), stringResource(R.string.settings_adblock_sub), adBlock) {
                    Library.setAdBlock(it)
                }
            }
            item {
                SwitchRow(stringResource(R.string.settings_continuous), stringResource(R.string.settings_continuous_sub), continuous) {
                    Castor.setContinuous(it)
                }
            }
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.settings_clear)) },
                    colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                    modifier = Modifier.selectable(false, role = Role.Button) { Library.clearRecents() },
                )
            }
            item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
            item {
                val embed by Library.embedSubs.collectAsState()
                SwitchRow(stringResource(R.string.settings_embed), stringResource(R.string.settings_embed_sub), embed) {
                    Library.setEmbedSubs(it)
                }
            }
            item {
                val smooth by Library.smoothSlides.collectAsState()
                SwitchRow(stringResource(R.string.settings_smooth_slides), stringResource(R.string.settings_smooth_slides_sub), smooth) {
                    Library.setSmoothSlides(it)
                }
            }
            item {
                val shake by Library.shakeHelp.collectAsState()
                SwitchRow(stringResource(R.string.settings_shake), stringResource(R.string.settings_shake_sub), shake) {
                    Library.setShakeHelp(it)
                }
            }
            item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
            item {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(painterResource(R.drawable.ic_logo), null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleMedium)
                            Text(stringResource(R.string.about_version, BuildConfig.VERSION_NAME),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Text(stringResource(R.string.about_body), style = MaterialTheme.typography.bodyMedium)
                }
            }
            // La signature du studio : bien visible, c'est notre carte de visite (« qualité en guise de promo »).
            item {
                val open = {
                    runCatching {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://rollandostudio.com"))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                    Unit
                }
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp)
                        .clip(RoundedCornerShape(24.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.6f))
                        .clickable(role = Role.Button, onClick = open)
                        .padding(vertical = 22.dp, horizontal = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(stringResource(R.string.about_made_by), style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(10.dp))
                    androidx.compose.foundation.Image(
                        painterResource(R.drawable.rollando_studio), "Rollando Studio",
                        Modifier.width(210.dp),
                    )
                    Spacer(Modifier.height(16.dp))
                    FilledTonalButton(onClick = open) { Text(stringResource(R.string.about_more)) }
                }
            }
        }
    }
}

@Composable
private fun SwitchRow(title: String, sub: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(sub) },
        trailingContent = { Switch(checked = checked, onCheckedChange = null) },
        colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
        modifier = Modifier.selectable(checked, role = Role.Switch) { onChange(!checked) },
    )
}

@Composable
internal fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = modifier.padding(top = 8.dp, bottom = 4.dp))
}

@Composable
internal fun Hidden(visible: Boolean, content: @Composable () -> Unit) {
    AnimatedVisibility(visible) { content() }
}

/**
 * Fond d'ambiance en haut de l'écran, sous la barre d'état et l'en-tête (comme YouTube) : l'image du film,
 * très floutée, ou un dégradé vert sombre ; il se fond dans le fond de l'appli, sans bord net.
 */
@Composable
internal fun Ambient(backdrop: String?) {
    val c = MaterialTheme.colorScheme
    androidx.compose.animation.Crossfade(backdrop, animationSpec = androidx.compose.animation.core.tween(600), label = "ambiance") { url ->
        Box(Modifier.fillMaxWidth().height(460.dp)) {
            if (url != null) {
                RemoteImage(
                    url, maxSide = 480,
                    modifier = Modifier.matchParentSize()
                        .alpha(if (android.os.Build.VERSION.SDK_INT >= 31) 0.55f else 0.3f)
                        .then(if (android.os.Build.VERSION.SDK_INT >= 31) Modifier.blur(56.dp) else Modifier),
                )
            } else {
                Box(
                    Modifier.matchParentSize().background(
                        androidx.compose.ui.graphics.Brush.radialGradient(
                            listOf(c.secondaryContainer.copy(alpha = 0.85f), androidx.compose.ui.graphics.Color.Transparent),
                            center = androidx.compose.ui.geometry.Offset(160f, -80f), radius = 1300f,
                        )
                    )
                )
            }
            Box(
                Modifier.matchParentSize().background(
                    androidx.compose.ui.graphics.Brush.verticalGradient(
                        0f to c.background.copy(alpha = 0.15f), 0.55f to c.background.copy(alpha = 0.6f), 1f to c.background,
                    )
                )
            )
        }
    }
}
