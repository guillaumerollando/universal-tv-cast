package studio.rollando.castor.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.LiveTv
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.animation.animateContentSize
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import studio.rollando.castor.R
import studio.rollando.castor.data.IptvStore

/**
 * Sites gratuits et légaux où l'appli trouve bien la vidéo (vérifié : la vidéo est détectée et peut partir sur la télé).
 * Internet Archive et NASA+ : partout dans le monde ; Arte : surtout en Europe, en plusieurs langues.
 */
private data class StarterSite(val host: String, val name: String, val url: String, val desc: Int, val icon: String)

/** Icônes publiées par les sites eux-mêmes (avant la première visite, l'appli n'en a pas encore). */
private val STARTER_SITES = listOf(
    StarterSite("archive.org", "Internet Archive", "https://archive.org/details/feature_films", R.string.start_archive,
        "https://archive.org/offshoot_assets/apple-touch-icon.png"),
    StarterSite("arte.tv", "Arte", "https://www.arte.tv", R.string.start_arte,
        "https://static-cdn.arte.tv/replay/favicons/favicon-194x194.png"),
    StarterSite("plus.nasa.gov", "NASA+", "https://plus.nasa.gov", R.string.start_nasa,
        "https://plus.nasa.gov/wp-content/uploads/2023/03/cropped-nasa-png-placeholder.png?w=192"),
)

/** « Pour commencer » : de quoi essayer l'appli tout de suite, avec du contenu gratuit et légal. */
@Composable
fun StarterSection(onBrowse: (String) -> Unit, onPasteLink: () -> Unit, showChannels: Boolean = true) {
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionTitle(stringResource(R.string.start_title), Modifier.semantics { heading() })
        if (showChannels) FreeChannelsCard(onPasteLink)
        StarterSites(onBrowse)
    }
}

/**
 * Les chaînes TV gratuites et officielles (liste Free-TV) : on montre le lien, on le copie, on le colle dans
 * « Coller un lien ». L'utilisateur apprend le geste, et comprend qu'il marche avec toute autre liste.
 */
@Composable
fun FreeChannelsCard(onPasteLink: () -> Unit) {
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    // Repliée par défaut : sur l'accueil, elle ne prend qu'une ligne ; on la déplie pour voir le lien.
    var open by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    val c = MaterialTheme.colorScheme
    Card(
        onClick = { open = !open },
        colors = CardDefaults.cardColors(containerColor = c.surfaceContainerHigh),
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth().animateContentSize(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.LiveTv, null, Modifier.size(28.dp), tint = c.primary)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.start_tv_title), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.start_tv_sub), style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
                }
                Icon(if (open) androidx.compose.material.icons.Icons.Rounded.ExpandLess else androidx.compose.material.icons.Icons.Rounded.ExpandMore,
                    null, tint = c.onSurfaceVariant)
            }
            if (open) {
            // Le lien, en entier, sélectionnable : c'est lui qu'on apprend à coller.
            androidx.compose.material3.Surface(shape = RoundedCornerShape(12.dp), color = c.surfaceContainerHighest, modifier = Modifier.fillMaxWidth()) {
                androidx.compose.foundation.text.selection.SelectionContainer {
                    Text(FREE_TV_LIST, style = MaterialTheme.typography.bodySmall.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp))
                }
            }
            Text(stringResource(R.string.start_tv_steps), style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                androidx.compose.material3.FilledTonalButton(
                    onClick = { clipboard.setText(androidx.compose.ui.text.AnnotatedString(FREE_TV_LIST)); copied = true },
                    modifier = Modifier.weight(1f),
                ) { Text(stringResource(if (copied) R.string.start_copied else R.string.start_copy), maxLines = 1) }
                androidx.compose.material3.OutlinedButton(onClick = onPasteLink, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.home_link), maxLines = 1)
                }
            }
            }
        }
    }
}

/** Trois sites gratuits et légaux à essayer dans le navigateur de l'appli. */
@Composable
fun StarterSites(onBrowse: (String) -> Unit) {
    val c = MaterialTheme.colorScheme
    Text(stringResource(R.string.start_sites_title), style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        STARTER_SITES.forEach { s ->
            Card(
                onClick = { onBrowse(s.url) },
                colors = CardDefaults.cardColors(containerColor = c.surfaceContainerHigh),
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.weight(1f),
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    androidx.compose.foundation.layout.Box(Modifier.size(36.dp)) {
                        SiteIcon(s.host, size = 36)
                        RemoteImage(s.icon, Modifier.size(36.dp).androidx_clip(), contentScale = androidx.compose.ui.layout.ContentScale.Crop, maxSide = 128)
                    }
                    Text(s.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(stringResource(s.desc), style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

private fun Modifier.androidx_clip() = this.clip(RoundedCornerShape(10.dp))
