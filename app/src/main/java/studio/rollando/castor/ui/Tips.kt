package studio.rollando.castor.ui

import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import studio.rollando.castor.cast.Castor
import studio.rollando.castor.net.Net
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BatteryAlert
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import studio.rollando.castor.R
import studio.rollando.castor.cast.CastState
import studio.rollando.castor.data.Background
import studio.rollando.castor.data.Library

/**
 * Conseil préventif : l'appli qui sert la vidéo ou la nôtre risque d'être mis en veille
 * par Android, ce qui fige la télé dès qu'on avance. Une seule carte à la fois ; « Ne plus afficher » la retire.
 */
@Composable
fun BackgroundTip(s: CastState) {
    val context = LocalContext.current
    val dismissed by Library.dismissedTips.collectAsState()
    val media = s.media ?: return
    if (s.onPhone) return // rien n'est relayé vers la télé
    val source = media.sourceApp?.takeIf { media.fromPhoneApp }
    val target: Pair<String, String>? = when {
        source != null && "src:$source" !in dismissed && Background.atRisk(context, source) -> "src:$source" to source
        s.active && "self" !in dismissed && Background.atRisk(context, context.packageName) -> "self" to context.packageName
        else -> null
    }
    target ?: return
    val (key, pkg) = target
    val name = Background.appLabel(context, pkg)
    val c = MaterialTheme.colorScheme
    Card(colors = CardDefaults.cardColors(containerColor = c.tertiaryContainer), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.BatteryAlert, null, tint = c.onTertiaryContainer)
                Spacer(Modifier.width(10.dp))
                Text(
                    stringResource(if (key == "self") R.string.tip_self_title else R.string.tip_source_title, name),
                    style = MaterialTheme.typography.titleSmall, color = c.onTertiaryContainer,
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                stringResource(if (key == "self") R.string.tip_self_body else R.string.tip_source_body, name) + " " +
                    stringResource(Background.howTo()),
                style = MaterialTheme.typography.bodyMedium, color = c.onTertiaryContainer,
            )
            Spacer(Modifier.height(8.dp))
            // Boutons l'un sous l'autre : le libellé (« Ouvrir les réglages de … ») est long sur un petit écran.
            FilledTonalButton(onClick = { Background.openSettings(context, pkg) }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.action_source_settings, name), maxLines = 2,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
            TextButton(onClick = { Library.dismissTip(key) }, modifier = Modifier.align(Alignment.End)) {
                Text(stringResource(R.string.action_dont_show))
            }
        }
    }
}

private enum class Diag { TV, FREEZE, FORMAT, SUBS, HDMI, SLEEP }

private data class Help(val title: Int, val body: Int, val diag: Diag)

private val HELP = listOf(
    Help(R.string.help_tv_title, R.string.help_tv_body, Diag.TV),
    Help(R.string.help_freeze_title, R.string.help_freeze_body, Diag.FREEZE),
    Help(R.string.help_format_title, R.string.help_format_body, Diag.FORMAT),
    Help(R.string.help_subs_title, R.string.help_subs_body, Diag.SUBS),
    Help(R.string.help_hdmi_title, R.string.help_hdmi_body, Diag.HDMI),
    Help(R.string.help_sleep_title, R.string.help_sleep_body, Diag.SLEEP),
)

/** Panneau d'aide : problèmes courants (avec l'état réel et les bons boutons), puis « Signaler un problème ». */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HelpSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val state by studio.rollando.castor.cast.Castor.state.collectAsState()
    // Toujours ouvert en grand : déplier une réponse ne fait plus redescendre le panneau.
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, modifier = Modifier.statusBarsPadding()) {
        LazyColumn(contentPadding = PaddingValues(bottom = 32.dp)) {
            item {
                Text(stringResource(R.string.help_title), style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp).semantics { heading() })
            }
            item { TroubleshootingSection(state, onDismiss) }
            item {
                Column(Modifier.padding(horizontal = 24.dp, vertical = 12.dp)) {
                    Text(stringResource(R.string.help_report_hint), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    FilledTonalButton(onClick = { Report.send(context, state) }, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                        Icon(Icons.Rounded.BugReport, null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.help_report))
                    }
                }
            }
        }
    }
}

/** Les problèmes courants : l'explication, puis ce que l'appli constate et de quoi agir tout de suite. */
@Composable
fun TroubleshootingSection(state: CastState, onClose: () -> Unit = {}) {
    Column(Modifier.padding(horizontal = 24.dp)) {
        Text(stringResource(R.string.help_section), style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(vertical = 8.dp).semantics { heading() })
        HELP.forEach { h ->
            var open by rememberSaveable(h.title) { mutableStateOf(false) }
            val stateText = stringResource(if (open) R.string.state_expanded else R.string.state_collapsed)
            Column(Modifier.fillMaxWidth().animateContentSize()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable(role = Role.Button) { open = !open }
                        .semantics { stateDescription = stateText }
                        .padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(h.title), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    Icon(if (open) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null)
                }
                if (open) {
                    Text(
                        stringResource(h.body) + if (h.diag == Diag.SLEEP) " " + stringResource(Background.howTo()) else "",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                    DiagPanel(h.diag, state, onClose)
                    Spacer(Modifier.height(12.dp))
                }
            }
        }
    }
}

/** Ce que l'appli sait de la situation, avec les boutons utiles. */
@Composable
private fun DiagPanel(d: Diag, s: CastState, onClose: () -> Unit) {
    val context = LocalContext.current
    val c = MaterialTheme.colorScheme
    val embed by Library.embedSubs.collectAsState()
    Card(colors = CardDefaults.cardColors(containerColor = c.surfaceContainerHigh), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            when (d) {
                Diag.TV -> {
                    val ip = Net.localIp()
                    Status(ip != null, if (ip != null) stringResource(R.string.diag_wifi_ok, ip) else stringResource(R.string.diag_wifi_none))
                    Status(s.renderers.isNotEmpty(), stringResource(R.string.diag_tvs_found, s.renderers.size))
                    ActionRow {
                        FilledTonalButton(onClick = { Castor.discover() }, enabled = !s.searching) {
                            Text(stringResource(if (s.searching) R.string.tv_searching else R.string.tv_search_again))
                        }
                        TextButton(onClick = { onClose(); Nav.tvs.value = true }) { Text(stringResource(R.string.tv_add_ip)) }
                    }
                }
                Diag.FREEZE, Diag.SLEEP -> {
                    val self = context.packageName
                    AppStatus(self)
                    val src = s.media?.sourceApp?.takeIf { s.media.fromPhoneApp && it != self }
                    if (d == Diag.FREEZE) {
                        if (src != null) AppStatus(src)
                        else Text(stringResource(R.string.diag_source_unknown), style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
                    }
                }
                Diag.FORMAT -> {
                    val m = s.media
                    if (m == null) Text(stringResource(R.string.diag_no_video), style = MaterialTheme.typography.bodyMedium)
                    else {
                        val r = s.renderer
                        val fmt = when {
                            s.isHls -> "HLS"
                            else -> studio.rollando.castor.net.Mime.ext(m.fileName ?: m.title).ifEmpty {
                                studio.rollando.castor.net.Mime.ext(m.url)
                            }.uppercase().ifEmpty { "?" }
                        }
                        val via = when {
                            r == null -> "—"
                            r.cast && !Castor.usingFallback -> "Google Cast"
                            else -> "DLNA"
                        }
                        Text(stringResource(R.string.diag_current, fmt, via), style = MaterialTheme.typography.bodyMedium)
                        ActionRow {
                            FilledTonalButton(onClick = { onClose(); Castor.cast() }, enabled = r != null) { Text(stringResource(R.string.action_send_again)) }
                            if (r?.cast == true && r.fallback != null && !Castor.usingFallback) {
                                TextButton(onClick = { onClose(); Castor.castWithDlna() }) { Text(stringResource(R.string.action_try_dlna)) }
                            }
                        }
                        if (s.isHls) SwitchLine(stringResource(R.string.settings_continuous), s.continuous) { Castor.setContinuous(it) }
                    }
                }
                Diag.SUBS -> {
                    val r = s.renderer
                    Text(
                        stringResource(when {
                            r == null -> R.string.diag_no_tv
                            r.cast && !Castor.usingFallback -> R.string.diag_tv_cast
                            else -> R.string.diag_tv_dlna
                        }),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    s.media?.let { Status(it.subs.isNotEmpty(), stringResource(R.string.diag_subs_found, it.subs.size)) }
                    SwitchLine(stringResource(R.string.settings_embed), embed) { Library.setEmbedSubs(it) }
                }
                Diag.HDMI -> {
                    Status(s.renderer != null, s.renderer?.name ?: stringResource(R.string.diag_no_tv))
                    if (s.renderer != null) ActionRow {
                        TextButton(onClick = { onClose(); Castor.disconnect() }) { Text(stringResource(R.string.tv_disconnect)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun Status(ok: Boolean, text: String) {
    val c = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            if (ok) Icons.Rounded.CheckCircle else Icons.Rounded.ErrorOutline, null,
            tint = if (ok) c.secondary else c.primary, modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

/** État « arrière-plan » d'une appli, avec le bouton vers ses réglages si elle risque d'être endormie. */
@Composable
private fun AppStatus(pkg: String) {
    val context = LocalContext.current
    val name = Background.appLabel(context, pkg)
    val risk = Background.atRisk(context, pkg)
    Status(!risk, stringResource(if (risk) R.string.diag_app_risk else R.string.diag_app_ok, name))
    if (risk) {
        FilledTonalButton(onClick = { Background.openSettings(context, pkg) }) {
            Text(stringResource(R.string.action_open_settings_of, name), maxLines = 1)
        }
    }
}

@Composable
private fun ActionRow(content: @Composable () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) { content() }
}

@Composable
private fun SwitchLine(label: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().toggleable(on, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(checked = on, onCheckedChange = null)
    }
}
