package studio.rollando.castor.ui

import androidx.compose.animation.animateContentSize
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
 * Conseil préventif : l'appli qui sert la vidéo (Stremio…) ou Castor lui-même risque d'être mis en veille
 * par Android, ce qui fige la télé dès qu'on avance. Une seule carte à la fois ; « Ne plus afficher » la retire.
 */
@Composable
fun BackgroundTip(s: CastState) {
    val context = LocalContext.current
    val dismissed by Library.dismissedTips.collectAsState()
    val media = s.media ?: return
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
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                FilledTonalButton(onClick = { Background.openSettings(context, pkg) }) {
                    Text(stringResource(R.string.action_source_settings, name), maxLines = 1)
                }
                TextButton(onClick = { Library.dismissTip(key) }) { Text(stringResource(R.string.action_dont_show)) }
            }
        }
    }
}

private data class Help(val title: Int, val body: Int, val ownSettings: Boolean = false)

private val HELP = listOf(
    Help(R.string.help_tv_title, R.string.help_tv_body),
    Help(R.string.help_freeze_title, R.string.help_freeze_body),
    Help(R.string.help_format_title, R.string.help_format_body),
    Help(R.string.help_subs_title, R.string.help_subs_body),
    Help(R.string.help_hdmi_title, R.string.help_hdmi_body),
    Help(R.string.help_sleep_title, R.string.help_sleep_body, ownSettings = true),
)

/** Rubrique « Dépannage » des réglages : les problèmes courants et leur solution. */
@Composable
fun TroubleshootingSection() {
    val context = LocalContext.current
    Column(Modifier.padding(horizontal = 16.dp)) {
        Text(stringResource(R.string.help_section), style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(vertical = 8.dp).semantics { heading() })
        HELP.forEach { h ->
            var open by rememberSaveable(h.title) { mutableStateOf(false) }
            val stateText = stringResource(if (open) R.string.state_expanded else R.string.state_collapsed)
            Column(
                Modifier
                    .fillMaxWidth()
                    .animateContentSize()
                    .clickable(role = Role.Button) { open = !open }
                    .semantics { stateDescription = stateText }
                    .padding(vertical = 10.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(h.title), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    Icon(if (open) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null)
                }
                if (open) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(h.body) + if (h.ownSettings) " " + stringResource(Background.howTo()) else "",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (h.ownSettings) {
                        TextButton(onClick = { Background.openSettings(context, context.packageName) }) {
                            Text(stringResource(R.string.action_source_settings, stringResource(R.string.app_name)))
                        }
                    }
                }
            }
        }
    }
}

