package studio.rollando.castor.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
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
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import studio.rollando.castor.R
import studio.rollando.castor.data.IptvStore

/**
 * Vidéos gratuites, légales, et autant que possible libres, où l'appli trouve bien la vidéo (chacun vérifié : vidéo
 * détectée, lue sur le téléphone, envoyée à la télé). Quelques-unes pour éveiller la curiosité, pas un catalogue :
 * films du domaine public, télé publique, PeerTube (vidéo libre et décentralisée), films libres de Blender,
 * Wikimedia Commons, et la NASA (œuvres publiques).
 */
private data class StarterSite(val host: String, val name: String, val url: String, val desc: Int, val icon: String)

/** Icônes publiées par les sites eux-mêmes ou par Wikimedia (avant la première visite, l'appli n'en a pas encore). */
private val STARTER_SITES = listOf(
    StarterSite("archive.org", "Internet Archive", "https://archive.org/details/feature_films", R.string.start_archive,
        "https://archive.org/offshoot_assets/apple-touch-icon.png"),
    StarterSite("arte.tv", "Arte", "https://www.arte.tv", R.string.start_arte,
        "https://static-cdn.arte.tv/replay/favicons/favicon-194x194.png"),
    StarterSite("sepiasearch.org", "PeerTube", "https://sepiasearch.org", R.string.start_peertube,
        "https://joinpeertube.org/img/icons/apple-touch-icon.png"),
    StarterSite("video.blender.org", "Blender Studio", "https://video.blender.org/c/blender_open_movies/videos?sort=-views", R.string.start_blender,
        "https://upload.wikimedia.org/wikipedia/commons/thumb/0/0c/Blender_logo_no_text.svg/250px-Blender_logo_no_text.svg.png"),
    StarterSite("commons.wikimedia.org", "Wikimedia Commons", "https://commons.wikimedia.org/w/index.php?title=Special:MediaSearch&type=video&search=film", R.string.start_commons,
        "https://commons.wikimedia.org/static/apple-touch/commons.png"),
    StarterSite("plus.nasa.gov", "NASA+", "https://plus.nasa.gov", R.string.start_nasa,
        "https://plus.nasa.gov/wp-content/uploads/2023/03/cropped-nasa-png-placeholder.png?w=192"),
)

/** Sites gratuits pas encore dans « Mes sites », ni écartés par l'utilisateur (appui long → « Ne plus proposer »). */
private fun starterSitesFor(known: Set<String>, dismissed: Set<String>) = STARTER_SITES.filter { s ->
    s.host !in dismissed && known.none { it.removePrefix("www.").endsWith(s.host) }
}

/** Identifiant de la carte des chaînes gratuites dans les suggestions écartées. */
private const val FREE_TV = "free-tv"

/**
 * Une suggestion qu'on peut écarter : appui long → « Ne plus proposer » (le même geste que pour retirer une carte
 * ailleurs dans l'appli), aussi proposé aux lecteurs d'écran.
 */
@Composable
private fun Dismissable(id: String, onClick: () -> Unit, content: @Composable (Modifier) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    val label = stringResource(R.string.start_dismiss)
    androidx.compose.foundation.layout.Box {
        content(
            Modifier.clip(RoundedCornerShape(18.dp))
                .combinedClickable(
                    role = androidx.compose.ui.semantics.Role.Button,
                    onClick = onClick,
                    onLongClick = { haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress); menu = true },
                )
                .semantics {
                    customActions = listOf(androidx.compose.ui.semantics.CustomAccessibilityAction(label) {
                        studio.rollando.castor.data.Library.dismissStarter(id); true
                    })
                }
        )
        androidx.compose.material3.DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, shape = RoundedCornerShape(16.dp)) {
            androidx.compose.material3.DropdownMenuItem(
                text = { Text(label) },
                leadingIcon = { Icon(androidx.compose.material.icons.Icons.Rounded.Close, null) },
                onClick = { menu = false; studio.rollando.castor.data.Library.dismissStarter(id) },
            )
        }
    }
}

/** « Pour commencer » : de quoi essayer l'appli tout de suite, avec du contenu gratuit et légal. */
@Composable
fun StarterSection(
    onBrowse: (String) -> Unit, onPasteLink: () -> Unit, showChannels: Boolean = true, showSites: Boolean = true,
    /** Sites déjà connus de l'utilisateur (« Mes sites ») : on ne les suggère plus. */
    known: Set<String> = emptySet(),
) {
    val dismissed by studio.rollando.castor.data.Library.dismissedStarters.collectAsState()
    val sites = remember(known, dismissed) { starterSitesFor(known, dismissed) }
    val channels = showChannels && FREE_TV !in dismissed
    if (!channels && !(showSites && sites.isNotEmpty())) return
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionTitle(stringResource(R.string.start_title), Modifier.semantics { heading() })
        if (channels) FreeChannelsCard(onPasteLink)
        if (showSites && sites.isNotEmpty()) StarterSites(onBrowse, known)
    }
}

/**
 * Les chaînes TV gratuites et officielles (liste Free-TV) : on montre le lien, on le copie, on le colle dans
 * « Coller un lien ». L'utilisateur apprend le geste, et comprend qu'il marche avec toute autre liste.
 */
@Composable
fun FreeChannelsCard(onPasteLink: () -> Unit) {
    val ctx = LocalContext.current
    var copied by remember { mutableStateOf(false) }
    // Repliée par défaut : sur l'accueil, elle ne prend qu'une ligne ; on la déplie pour voir le lien.
    var open by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    val c = MaterialTheme.colorScheme
    Dismissable(FREE_TV, onClick = { open = !open }) { press -> Card(
        colors = CardDefaults.cardColors(containerColor = c.surfaceContainerHigh),
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth().then(press).animateContentSize(),
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
                    onClick = {
                        (ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                            .setPrimaryClip(android.content.ClipData.newPlainText("M3U", FREE_TV_LIST))
                        copied = true
                    },
                    modifier = Modifier.weight(1f),
                ) { Text(stringResource(if (copied) R.string.start_copied else R.string.start_copy), maxLines = 1) }
                androidx.compose.material3.OutlinedButton(onClick = onPasteLink, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.home_link), maxLines = 1)
                }
            }
            }
        }
    } }
}

/** Quelques sites gratuits et légaux à essayer dans le navigateur de l'appli. */
@Composable
fun StarterSites(onBrowse: (String) -> Unit, known: Set<String> = emptySet()) {
    val c = MaterialTheme.colorScheme
    val dismissed by studio.rollando.castor.data.Library.dismissedStarters.collectAsState()
    val list = remember(known, dismissed) { starterSitesFor(known, dismissed) }
    if (list.isEmpty()) return
    Text(stringResource(R.string.start_sites_title), style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant)
    // Une rangée qui défile : quelques sites sans allonger l'accueil ; cartes de même hauteur.
    Row(
        Modifier.height(androidx.compose.foundation.layout.IntrinsicSize.Max).horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        list.forEach { s ->
            androidx.compose.foundation.layout.Box(Modifier.width(156.dp).fillMaxHeight()) { Dismissable(s.host, onClick = { onBrowse(s.url) }) { press -> Card(
                colors = CardDefaults.cardColors(containerColor = c.surfaceContainerHigh),
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.fillMaxSize().then(press),
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
            } } }
        }
    }
}

private fun Modifier.androidx_clip() = this.clip(RoundedCornerShape(10.dp))
