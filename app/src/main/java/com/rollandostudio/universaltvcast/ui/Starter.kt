package com.rollandostudio.universaltvcast.ui

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
import androidx.compose.material.icons.rounded.Movie
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.foundation.background
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
import com.rollandostudio.universaltvcast.R
import com.rollandostudio.universaltvcast.data.IptvStore

/**
 * Vidéos gratuites, légales, et autant que possible libres, où l'appli trouve bien la vidéo (chacun vérifié : vidéo
 * détectée, lue sur le téléphone, envoyée à la télé). Quelques-unes pour éveiller la curiosité, pas un catalogue :
 * films du domaine public, télé publique, PeerTube (vidéo libre et décentralisée), films libres de Blender,
 * Wikimedia Commons, et la NASA (œuvres publiques).
 * [countries] : pays où le site est proposé (codes ISO, en majuscules) ; null = partout. La télé publique d'un pays
 * parle à ses habitants (« Ah, Arte ! ») et ses vidéos y sont visibles, alors qu'ailleurs beaucoup sont bloquées.
 */
private data class StarterSite(
    val host: String, val name: String, val url: String, val desc: Int, val icon: String,
    val countries: Set<String>? = null,
)

/** Pays de l'Union européenne et voisins proches, où Arte est diffusée (en 6 langues). */
private val EUROPE = setOf(
    "FR", "DE", "AT", "CH", "BE", "LU", "NL", "IT", "ES", "PT", "PL", "IE", "GB", "DK", "SE", "FI", "NO", "CZ",
    "SK", "HU", "SI", "HR", "RO", "BG", "GR", "CY", "MT", "EE", "LV", "LT", "MC", "AD", "LI", "SM", "IS",
)

/** Icônes publiées par les sites eux-mêmes ou par Wikimedia (avant la première visite, l'appli n'en a pas encore). */
private val STARTER_SITES = listOf(
    // Télé publique du pays : en tête quand elle correspond au téléphone.
    StarterSite("ardmediathek.de", "ARD Mediathek", "https://www.ardmediathek.de", R.string.start_ard,
        "https://www.ardmediathek.de/apple-touch-icon.png", setOf("DE", "AT", "CH", "LU", "LI")),
    StarterSite("zdf.de", "ZDF", "https://www.zdf.de", R.string.start_zdf,
        "https://upload.wikimedia.org/wikipedia/commons/thumb/2/21/ZDF_logo%21_Logo_2021.svg/250px-ZDF_logo%21_Logo_2021.svg.png",
        setOf("DE", "AT", "CH")),
    StarterSite("rtve.es", "RTVE Play", "https://www.rtve.es/play/", R.string.start_rtve,
        "https://upload.wikimedia.org/wikipedia/commons/thumb/e/ee/Logo_RTVE.svg/250px-Logo_RTVE.svg.png", setOf("ES", "AD")),
    StarterSite("arte.tv", "Arte", "https://www.arte.tv", R.string.start_arte,
        "https://static-cdn.arte.tv/replay/favicons/favicon-194x194.png", EUROPE),
    // Deutsche Welle : télé publique allemande pour le monde, en direct et visible partout (ici les langues où elle a une chaîne).
    StarterSite("dw.com", "DW", "https://www.dw.com/en/live-tv/channel-english", R.string.start_dw,
        "https://upload.wikimedia.org/wikipedia/commons/thumb/7/75/Deutsche_Welle_symbol_2012.svg/250px-Deutsche_Welle_symbol_2012.svg.png"),
    StarterSite("archive.org", "Internet Archive", "https://archive.org/details/feature_films", R.string.start_archive,
        "https://archive.org/offshoot_assets/apple-touch-icon.png"),
    StarterSite("sepiasearch.org", "PeerTube", "https://sepiasearch.org", R.string.start_peertube,
        "https://joinpeertube.org/img/icons/apple-touch-icon.png"),
    StarterSite("video.blender.org", "Blender Studio", "https://video.blender.org/c/blender_open_movies/videos?sort=-views", R.string.start_blender,
        "https://upload.wikimedia.org/wikipedia/commons/thumb/0/0c/Blender_logo_no_text.svg/250px-Blender_logo_no_text.svg.png"),
    StarterSite("commons.wikimedia.org", "Wikimedia Commons", "https://commons.wikimedia.org/w/index.php?title=Special:MediaSearch&type=video&search=film", R.string.start_commons,
        "https://commons.wikimedia.org/static/apple-touch/commons.png"),
    StarterSite("plus.nasa.gov", "NASA+", "https://plus.nasa.gov", R.string.start_nasa,
        "https://plus.nasa.gov/wp-content/uploads/2023/03/cropped-nasa-png-placeholder.png?w=192"),
)

/**
 * Pays du téléphone, sans autorisation ni réseau : celui de la carte SIM, du réseau mobile où l'on se trouve et des
 * réglages de langue (« fr-FR »). Un Français en voyage garde Arte ; un étranger en France la découvre.
 */
private fun phoneCountries(ctx: android.content.Context): Set<String> {
    val tm = ctx.getSystemService(android.telephony.TelephonyManager::class.java)
    return listOfNotNull(
        runCatching { tm?.simCountryIso }.getOrNull(),
        runCatching { tm?.networkCountryIso }.getOrNull(),
        java.util.Locale.getDefault().country,
    ).filter { it.length == 2 }.map { it.uppercase() }.toSet()
}

/**
 * Sites gratuits pas encore dans « Mes sites », ni écartés par l'utilisateur (appui long → « Ne plus proposer »),
 * et proposés dans son pays. La télé de son pays d'abord, puis ce qui se regarde partout.
 */
private fun starterSitesFor(known: Set<String>, dismissed: Set<String>, countries: Set<String>) = STARTER_SITES.filter { s ->
    s.host !in dismissed && known.none { it.removePrefix("www.").endsWith(s.host) } &&
        (s.countries == null || s.countries.any { it in countries })
}.sortedBy { if (it.countries != null) 0 else 1 }

/** Identifiant de la carte des chaînes gratuites dans les suggestions écartées. */
private const val FREE_TV = "free-tv"
private const val FREE_FILMS = "free-films"

/** « Pour commencer » : de quoi essayer l'appli tout de suite, avec du contenu gratuit et légal. */
@Composable
fun StarterSection(
    onBrowse: (String) -> Unit, onPasteLink: () -> Unit, showChannels: Boolean = true, showSites: Boolean = true,
    /** Sites déjà connus de l'utilisateur (« Mes sites ») : on ne les suggère plus. */
    known: Set<String> = emptySet(),
) {
    val dismissed by com.rollandostudio.universaltvcast.data.Library.dismissedStarters.collectAsState()
    val sites = run { val ctx0 = LocalContext.current; remember(known, dismissed) { starterSitesFor(known, dismissed, phoneCountries(ctx0)) } }
    val channels = showChannels && FREE_TV !in dismissed
    // Le catalogue « Films libres » (domaine public, Blender) : proposé tant qu'il n'est pas ajouté.
    val freeOn by com.rollandostudio.universaltvcast.data.Xtream.freeOn.collectAsState()
    val openOn by com.rollandostudio.universaltvcast.data.OpenSources.on.collectAsState()
    val films = !freeOn && openOn.isEmpty() && FREE_FILMS !in dismissed
    if (!channels && !films && !(showSites && sites.isNotEmpty())) return
    var freeTv by remember { mutableStateOf(false) }
    if (freeTv) FreeChannelsDialog(onDismiss = { freeTv = false }, onPasteLink = { freeTv = false; onPasteLink() })
    Column {
        SectionTitle(stringResource(R.string.start_title), Modifier.padding(horizontal = 16.dp).semantics { heading() })
        // Une rangée de cartes (plus larges que les tuiles : on présente ce qu'on ne connaît pas encore) : les chaînes
        // gratuites, puis des sites gratuits et légaux.
        androidx.compose.foundation.lazy.LazyRow(contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(ChannelTiles.gap)) {
            if (channels) item(key = FREE_TV) {
                StarterCard(stringResource(R.string.start_tv_title), stringResource(R.string.start_tv_sub), ActionColors.LISTS,
                    dismissMenu(FREE_TV), onClick = { freeTv = true }) {
                    Icon(Icons.Rounded.LiveTv, null, tint = androidx.compose.ui.graphics.Color.White, modifier = Modifier.size(24.dp))
                }
            }
            if (films) item(key = FREE_FILMS) {
                // Les sources ouvertes (Films libres, PeerTube, Internet Archive) : on choisit lesquelles ajouter.
                StarterCard(stringResource(R.string.sources_card), stringResource(R.string.sources_card_sub), ActionColors.MOVIES,
                    dismissMenu(FREE_FILMS), onClick = { Nav.sources.value = true }) {
                    Icon(Icons.Rounded.Movie, null, tint = androidx.compose.ui.graphics.Color.White, modifier = Modifier.size(24.dp))
                }
            }
            if (showSites) items(sites.size, key = { sites[it].host }) { i -> StarterSiteCard(sites[i], onBrowse) }
        }
    }
}

/** « Ne plus proposer » (appui long) pour une suggestion. */
@Composable
private fun dismissMenu(id: String): List<Pair<String, () -> Unit>> =
    listOf(stringResource(R.string.start_dismiss) to { com.rollandostudio.universaltvcast.data.Library.dismissStarter(id) })

/**
 * Une suggestion de « Pour commencer » : plus large qu'une tuile, pour présenter ce qu'on ne connaît pas encore
 * (son icône, son nom, deux lignes qui donnent envie). Mêmes couleurs que les tuiles. Appui long : « Ne plus proposer ».
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun StarterCard(
    title: String, desc: String, color: Long, menu: List<Pair<String, () -> Unit>>, onClick: () -> Unit,
    icon: @Composable () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    androidx.compose.foundation.layout.Box {
        Column(
            Modifier.width(206.dp).height(112.dp).clip(RoundedCornerShape(ChannelTiles.corner + 2.dp))
                .background(favBrush(androidx.compose.ui.graphics.Color(color)))
                .combinedClickable(
                    role = androidx.compose.ui.semantics.Role.Button, onClick = onClick,
                    onLongClick = { haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress); open = true },
                )
                .semantics {
                    contentDescription = "$title, $desc"
                    customActions = menu.map { (l, a) -> androidx.compose.ui.semantics.CustomAccessibilityAction(l) { a(); true } }
                }
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                icon()
                Spacer(Modifier.width(8.dp))
                Text(title, style = MaterialTheme.typography.titleSmall.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold),
                    color = androidx.compose.ui.graphics.Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(desc, style = MaterialTheme.typography.bodySmall, color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.85f),
                maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
        CardMenu(open, { open = false }, menu)
    }
}

/** Un site suggéré : sa carte, à sa couleur, avec sa description. */
@Composable
private fun StarterSiteCard(s: StarterSite, onBrowse: (String) -> Unit) {
    StarterCard(s.name, stringResource(s.desc), com.rollandostudio.universaltvcast.data.Channels.colorFor(s.host), dismissMenu(s.host),
        onClick = { onBrowse(s.url) }) {
        androidx.compose.foundation.layout.Box(Modifier.size(24.dp)) {
            SiteIcon(s.host, size = 24)
            RemoteImage(s.icon, Modifier.size(24.dp).clip(RoundedCornerShape(6.dp)), contentScale = androidx.compose.ui.layout.ContentScale.Crop, maxSide = 128)
        }
    }
}

/**
 * Les chaînes TV gratuites et officielles (liste Free-TV) : on montre le lien, on le copie, on le colle dans
 * « Coller un lien ». L'utilisateur apprend le geste, et comprend qu'il marche avec toute autre liste.
 */
@Composable
private fun FreeChannelsDialog(onDismiss: () -> Unit, onPasteLink: () -> Unit) {
    val ctx = LocalContext.current
    var copied by remember { mutableStateOf(false) }
    val c = MaterialTheme.colorScheme
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.LiveTv, null, tint = c.primary) },
        title = { Text(stringResource(R.string.start_tv_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.start_tv_sub), style = MaterialTheme.typography.bodyMedium)
                // Le lien, en entier, sélectionnable : c'est lui qu'on apprend à coller.
                androidx.compose.material3.Surface(shape = RoundedCornerShape(12.dp), color = c.surfaceContainerHighest, modifier = Modifier.fillMaxWidth()) {
                    androidx.compose.foundation.text.selection.SelectionContainer {
                        Text(FREE_TV_LIST, style = MaterialTheme.typography.bodySmall.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp))
                    }
                }
                Text(stringResource(R.string.start_tv_steps), style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = onPasteLink) { Text(stringResource(R.string.home_link)) }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = {
                (ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                    .setPrimaryClip(android.content.ClipData.newPlainText("M3U", FREE_TV_LIST))
                copied = true
            }) { Text(stringResource(if (copied) R.string.start_copied else R.string.start_copy)) }
        },
    )
}

/** Quelques sites gratuits et légaux à essayer dans le navigateur de l'appli. */
@Composable
fun StarterSites(onBrowse: (String) -> Unit, known: Set<String> = emptySet()) {
    val c = MaterialTheme.colorScheme
    val dismissed by com.rollandostudio.universaltvcast.data.Library.dismissedStarters.collectAsState()
    val list = run { val ctx0 = LocalContext.current; remember(known, dismissed) { starterSitesFor(known, dismissed, phoneCountries(ctx0)) } }
    if (list.isEmpty()) return
    Text(stringResource(R.string.start_sites_title), style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant)
    // Mêmes tuiles que sur l'accueil.
    androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(ChannelTiles.gap)) {
        items(list.size, key = { list[it].host }) { i -> StarterSiteCard(list[i], onBrowse) }
    }
}

