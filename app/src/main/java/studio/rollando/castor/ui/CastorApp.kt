package studio.rollando.castor.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CastorApp() {
    val context = LocalContext.current
    val state by Castor.state.collectAsState()
    val tab by Nav.tab.collectAsState()
    var showTvs by rememberSaveable { mutableStateOf(false) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var showLink by rememberSaveable { mutableStateOf(false) }
    val browser = remember { BrowserController(context) }

    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        Castor.open(Media(url = uri.toString(), title = Intake.nameOf(context, uri), mime = context.contentResolver.getType(uri)))
        Nav.tab.value = Tab.PLAYER
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(painterResource(R.drawable.ic_logo), null, Modifier.size(28.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(10.dp))
                        Text(stringResource(R.string.app_name))
                    }
                },
                actions = {
                    TvChip(state.renderer?.name, state.searching) { showTvs = true }
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Rounded.Settings, stringResource(R.string.cd_settings))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = tab == Tab.HOME, onClick = { Nav.tab.value = Tab.HOME },
                    icon = { Icon(Icons.Rounded.Home, null) }, label = { Text(stringResource(R.string.tab_home)) },
                )
                NavigationBarItem(
                    selected = tab == Tab.BROWSER, onClick = { Nav.tab.value = Tab.BROWSER },
                    icon = { Icon(Icons.Rounded.Public, null) }, label = { Text(stringResource(R.string.tab_browser)) },
                )
                NavigationBarItem(
                    selected = tab == Tab.PLAYER, onClick = { Nav.tab.value = Tab.PLAYER },
                    icon = {
                        BadgedBox(badge = { if (state.active) Badge(containerColor = MaterialTheme.colorScheme.primary) }) {
                            Icon(if (state.active) Icons.Rounded.CastConnected else Icons.Rounded.Cast, null)
                        }
                    },
                    label = { Text(stringResource(R.string.tab_player)) },
                )
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (tab) {
                Tab.HOME -> HomeScreen(
                    onBrowse = { url -> url?.let { browser.load(it) }; Nav.tab.value = Tab.BROWSER },
                    onPickFile = { pickFile.launch(arrayOf("video/*", "audio/*")) },
                    onPasteLink = { showLink = true },
                )
                Tab.BROWSER -> BrowserScreen(browser)
                Tab.PLAYER -> PlayerScreen(state, onChooseTv = { showTvs = true })
            }
        }
    }

    if (showTvs) TvSheet(onDismiss = { showTvs = false })
    if (showSettings) SettingsSheet(onDismiss = { showSettings = false })
    if (showLink) LinkDialog(onDismiss = { showLink = false })
}

@Composable
private fun TvChip(name: String?, searching: Boolean, onClick: () -> Unit) {
    val label = name ?: stringResource(R.string.tv_choose)
    val cd = stringResource(R.string.cd_choose_tv)
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        color = if (name != null) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = if (name != null) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(end = 4.dp).height(40.dp).semantics { contentDescription = "$cd : $label" },
    ) {
        Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (searching && name == null) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            else Icon(Icons.Rounded.Tv, null, Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 140.dp))
            Icon(Icons.Rounded.KeyboardArrowDown, null, Modifier.size(18.dp))
        }
    }
}



@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TvSheet(onDismiss: () -> Unit) {
    val state by Castor.state.collectAsState()
    ModalBottomSheet(onDismissRequest = onDismiss) {
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
                            supportingContent = listOfNotNull(r.manufacturer, r.model, r.host).distinct().joinToString(" · ").takeIf { it.isNotBlank() }
                                ?.let { { Text(it) } },
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
    var text by rememberSaveable { mutableStateOf("") }
    val valid = text.trim().startsWith("http")
    fun go() {
        if (!valid) return
        val url = text.trim()
        Castor.open(Media(url = url, title = Uri.decode(url.substringBefore('?').substringAfterLast('/')).ifBlank { url }))
        Nav.tab.value = Tab.PLAYER
        onDismiss()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.link_dialog_title)) },
        text = {
            OutlinedTextField(
                value = text, onValueChange = { text = it }, singleLine = true,
                placeholder = { Text("https://…") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { go() }),
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = { TextButton(onClick = ::go, enabled = valid) { Text(stringResource(R.string.action_cast)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val adBlock by Library.adBlock.collectAsState()
    val continuous by Library.continuousHls.collectAsState()
    ModalBottomSheet(onDismissRequest = onDismiss) {
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
                    FilledTonalButton(onClick = {
                        runCatching {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://rollandostudio.com"))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        }
                    }) { Text(stringResource(R.string.about_more)) }
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
