package com.rollandostudio.universaltvcast.ui

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material.icons.rounded.VisibilityOff
import android.content.Intent
import android.os.Build
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.ui.semantics.Role
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.animation.animateContentSize
import androidx.compose.material.icons.automirrored.rounded.VolumeDown
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Forward10
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.ListItem
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.rollandostudio.universaltvcast.R
import com.rollandostudio.universaltvcast.cast.CastState
import com.rollandostudio.universaltvcast.cast.TvCast
import com.rollandostudio.universaltvcast.cast.Intake
import com.rollandostudio.universaltvcast.cast.Phase
import java.util.Locale
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.draw.drawBehind
import androidx.compose.runtime.collectAsState

@Composable
fun PlayerScreen(state: CastState, onChooseTv: () -> Unit) {
    if (state.slides != null) {
        SlideshowScreen(state)
        return
    }
    val media = state.media
    if (media == null) {
        Empty()
        return
    }
    val c = MaterialTheme.colorScheme
    val film = media.backdropUrl != null
    // Une chaîne reste une chaîne même si son flux ne répond pas (écran de chaîne, zapping pour passer à une autre).
    val channel = state.live || media.channel != null
    val iptvOpen by Nav.iptv.collectAsState()
    // Lueur aux couleurs de l'image : dessinée par l'éclairage de fond de l'appli (voir TvCastApp), en-tête compris.
    LaunchedEffect(state.onPhone, media.url) { com.rollandostudio.universaltvcast.cast.PhoneEngine.glow.value = null }
    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize()) {
    // Sur le téléphone, la vidéo reste en haut pendant qu'on fait défiler le reste.
    if (state.onPhone) Box(Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp)) {
        PhoneVideo(state, active = iptvOpen == null, onGlow = { com.rollandostudio.universaltvcast.cast.PhoneEngine.glow.value = it.toArgb() })
    }
    Box(Modifier.fillMaxWidth().weight(1f)) {
    // L'ambiance (image du film floutée) est dessinée derrière tout l'écran, en-tête compris : voir Ambient().
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (state.onPhone) Unit
        else if (film) FilmHeader(state)
        else if (channel) ChannelHeader(state)
        // « Affiche » : grand cadre qui dit ce qui se passe sur la télé.
        else Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(28.dp))
                .background(Brush.linearGradient(listOf(c.primaryContainer, c.surfaceContainerHighest, c.secondaryContainer))),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.size(96.dp)) {
                    if (state.phase == Phase.PLAYING) CastWaves(c.onPrimaryContainer, size = 150.dp)
                    Icon(if (state.active) Icons.Rounded.Tv else Icons.Rounded.Movie, null, Modifier.size(56.dp), tint = c.onPrimaryContainer)
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    phaseLabel(state), style = MaterialTheme.typography.titleMedium, color = c.onPrimaryContainer,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
                state.renderer?.let {
                    Text(stringResource(R.string.player_on_tv, it.name), style = MaterialTheme.typography.bodyMedium,
                        color = c.onPrimaryContainer.copy(alpha = 0.8f))
                }
            }
            if (state.phase == Phase.LOADING || state.phase == Phase.SENDING || state.phase == Phase.BUFFERING) {
                LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.BottomCenter))
            }
        }

        // Sur le téléphone : la chaîne par son logo (comme partout ailleurs), un film par son titre.
        if (state.onPhone && channel) Row(verticalAlignment = Alignment.CenterVertically) {
            ChannelArt(
                name = media.channel ?: media.title, logo = media.posterUrl, streamUrl = media.url, pad = ChannelTiles.logoPad,
                textStyle = MaterialTheme.typography.titleSmall.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.ExtraBold),
                modifier = Modifier.width(ChannelTiles.width).aspectRatio(16f / 10f).clip(RoundedCornerShape(ChannelTiles.corner))
                    .semantics { contentDescription = media.channel ?: media.title },
            )
            Spacer(Modifier.width(14.dp))
            NowNext(media, Modifier.weight(1f))
        }
        // Sur la télé : le programme en cours sous l'en-tête de la chaîne.
        else if (channel) NowNext(media)
        else if (state.onPhone && film) Text(media.title, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (!film && !channel) Column {
            Text(media.title, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            media.host?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant) }
        }

        // Une carte à la fois : la télécommande dans les notifications d'abord, puis le conseil batterie.
        if (needsNotificationTip(state)) NotificationTip() else BackgroundTip(state)

        state.notice?.let { n ->
            Card(
                colors = CardDefaults.cardColors(containerColor = if (n.error) c.errorContainer else c.secondaryContainer),
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
            ) {
                Row(Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (n.error) Icons.Rounded.ErrorOutline else Icons.Rounded.Info, null,
                        tint = if (n.error) c.onErrorContainer else c.onSecondaryContainer)
                    Spacer(Modifier.width(12.dp))
                    Text(
                        if (n.arg != null) stringResource(n.res, n.arg) else stringResource(n.res),
                        style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f),
                        color = if (n.error) c.onErrorContainer else c.onSecondaryContainer,
                    )
                    IconButton(onClick = TvCast::dismissNotice) { Icon(Icons.Rounded.Close, stringResource(R.string.cd_dismiss)) }
                }
                if (n.error) Row(Modifier.padding(start = 8.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { Nav.help.value = true }) {
                        Text(stringResource(R.string.action_help))
                    }
                    // Chaîne qui ne marche pas : la masquer d'un geste (elle se retrouve dans ⋮ › Chaînes masquées).
                    if (media.channel != null) {
                        val hidden by com.rollandostudio.universaltvcast.data.IptvStore.hidden.collectAsState()
                        val isHidden = media.url in hidden
                        Spacer(Modifier.weight(1f))
                        if (isHidden) Text(stringResource(R.string.iptv_hidden_done), style = MaterialTheme.typography.labelLarge,
                            color = c.onErrorContainer)
                        TextButton(onClick = {
                            if (isHidden) com.rollandostudio.universaltvcast.data.IptvStore.unhide(media.url)
                            else com.rollandostudio.universaltvcast.data.IptvStore.hide(media.url)
                        }) {
                            if (!isHidden) { Icon(Icons.Rounded.VisibilityOff, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)) }
                            Text(stringResource(if (isHidden) R.string.action_undo else R.string.iptv_hide))
                        }
                    }
                }
                val pkg = media.sourceApp
                if (n.res == R.string.notice_source_asleep && pkg != null) {
                    val context = LocalContext.current
                    TextButton(
                        onClick = {
                            runCatching {
                                context.startActivity(
                                    Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg"))
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                )
                            }
                        },
                        modifier = Modifier.padding(start = 8.dp, bottom = 4.dp),
                    ) { Text(stringResource(R.string.action_source_settings, n.arg ?: "")) }
                }
            }
        }

        // Une télé à qui envoyer : celle choisie, ou une déjà vue sur le Wi-Fi, même si elle semble en veille (certaines,
        // comme les Philips Titan OS, ne répondent pas à la recherche une fois au repos). Sans aucune télé : le téléphone.
        val tvAvailable = state.renderer != null || state.renderers.isNotEmpty()
        if (!state.active && !state.onPhone && !tvAvailable) {
            Button(
                onClick = { TvCast.toPhone() },
                enabled = state.phase != Phase.LOADING,
                modifier = Modifier.fillMaxWidth().height(56.dp),
            ) {
                Icon(Icons.Rounded.Smartphone, null)
                Spacer(Modifier.width(10.dp))
                Text(stringResource(R.string.phone_watch), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        } else if (!state.active) {
            Button(
                onClick = { if (state.renderer == null && !state.onPhone) { TvCast.castWhenTvChosen(); onChooseTv() } else TvCast.cast() },
                enabled = state.phase != Phase.LOADING,
                modifier = Modifier.fillMaxWidth().height(56.dp),
            ) {
                Icon(if (state.onPhone) Icons.Rounded.Smartphone else Icons.Rounded.Cast, null)
                Spacer(Modifier.width(10.dp))
                Text(if (state.onPhone) stringResource(R.string.phone_play_here)
                    else state.renderer?.let { stringResource(R.string.action_cast_to, it.name) } ?: stringResource(R.string.action_cast_pick),
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }

        // « Passer l'intro / le récap / le générique » : juste au-dessus de la barre, à droite.
        SkipSegmentButton(state, Modifier.align(Alignment.End))
        Progress(state)
        Controls(state)
        // Un seul écran pour les deux : sur la télé, ou sur ce téléphone (finir l'épisode dans son lit), au même endroit.
        if (tvAvailable || (state.active && !state.onPhone)) OutlinedButton(
            onClick = {
                if (state.onPhone) { TvCast.toTv(); if (state.renderer == null) onChooseTv() } else TvCast.toPhone()
            },
            enabled = state.phase != Phase.LOADING,
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) {
            Icon(if (state.onPhone) Icons.Rounded.Cast else Icons.Rounded.Smartphone, null)
            Spacer(Modifier.width(10.dp))
            Text(stringResource(if (state.onPhone) R.string.phone_watch_tv else R.string.phone_watch))
        }
        // Épisode suivant (lien repéré sur le site, ou déduit de l'adresse) : il s'ouvre dans le navigateur.
        val nextEp = remember(media.nextUrl, media.pageUrl) {
            if (channel) null else media.nextUrl ?: media.pageUrl?.let { com.rollandostudio.universaltvcast.browser.Episodes.find(it)?.next?.takeIf { n -> n.isNotEmpty() } }
        }
        // Chargé en arrière-plan : la vidéo en cours continue jusqu'à ce que l'épisode suivant prenne le relais.
        val nextLoading by Nav.nextLoading.collectAsState()
        if (nextEp != null) OutlinedButton(
            onClick = { Nav.nextEpisode.value = nextEp },
            enabled = !nextLoading,
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) {
            if (nextLoading) androidx.compose.material3.CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            else Icon(Icons.Rounded.SkipNext, null)
            Spacer(Modifier.width(10.dp))
            Text(stringResource(if (nextLoading) R.string.next_episode_loading else R.string.next_episode))
        }
        // Direct : pas de sous-titres à régler ; à la place, de quoi zapper. Le mode de lecture des listes HLS
        // (flux continu ou non) se règle seulement dans les Réglages.
        if (channel) ZapSection(media) else { AudioCard(state); SubtitlesCard(state) }
        Spacer(Modifier.height(8.dp + LocalBottomInset.current))
    }
    }
    }
    }
}

/** En-tête « film » : grande image, logo du titre (ou titre), état de la lecture en surimpression. */
@Composable
private fun FilmHeader(state: CastState) {
    val media = state.media ?: return
    var logoOk by remember(media.logoUrl) { mutableStateOf(false) }
    val desc = media.title + ", " + phaseLabel(state)
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .clip(RoundedCornerShape(28.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .semantics(mergeDescendants = true) { contentDescription = desc; liveRegion = LiveRegionMode.Polite },
    ) {
        RemoteImage(media.backdropUrl, Modifier.matchParentSize())
        Box(
            Modifier.matchParentSize().background(
                Brush.verticalGradient(0f to Color.Transparent, 0.45f to Color.Black.copy(alpha = 0.15f), 1f to Color.Black.copy(alpha = 0.85f))
            )
        )
        Column(Modifier.align(Alignment.BottomStart).padding(18.dp)) {
            RemoteImage(
                media.logoUrl, maxSide = 600, contentScale = ContentScale.Fit, alignment = Alignment.CenterStart,
                modifier = Modifier.heightIn(max = 64.dp).widthIn(max = 230.dp), onState = { logoOk = it },
            )
            if (!logoOk) {
                Text(media.title, style = MaterialTheme.typography.headlineSmall, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
            } else if (media.season != null && media.episode != null) {
                // Le logo remplace le titre : on garde l'épisode visible.
                Text(
                    stringResource(R.string.episode_label, media.season, media.episode),
                    style = MaterialTheme.typography.titleMedium, color = Color.White, modifier = Modifier.padding(top = 6.dp),
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (state.active) EqualizerBars(active = state.phase == Phase.PLAYING, color = MaterialTheme.colorScheme.primary)
                else LiveDot(active = false)
                Spacer(Modifier.width(8.dp))
                Text(
                    phaseLabel(state) + (state.renderer?.let { " · " + it.name } ?: ""),
                    style = MaterialTheme.typography.labelLarge, color = Color.White.copy(alpha = 0.9f),
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (state.phase == Phase.LOADING || state.phase == Phase.SENDING || state.phase == Phase.BUFFERING) {
            LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.BottomCenter))
        }
    }
}

/** Petit point « à l'antenne » qui pulse pendant la lecture. */
@Composable
private fun LiveDot(active: Boolean) {
    val t = rememberInfiniteTransition(label = "direct")
    val a by t.animateFloat(1f, 0.35f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "alpha")
    Box(
        Modifier.size(9.dp).clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary.copy(alpha = if (active) a else 0.6f))
    )
}

@Composable
private fun phaseLabel(s: CastState): String = stringResource(
    when (s.phase) {
        Phase.LOADING -> R.string.phase_loading
        Phase.SENDING -> R.string.phase_sending
        Phase.BUFFERING -> R.string.phase_buffering
        Phase.PLAYING -> R.string.phase_playing
        Phase.PAUSED -> R.string.phase_paused
        Phase.STOPPED -> if (s.idle) R.string.phase_tv_waiting else R.string.phase_stopped
        Phase.IDLE -> R.string.phase_ready
    }
)

@Composable
internal fun Progress(s: CastState) {
    if (s.live) { LiveBar(s); return }
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }
    val hasDuration = s.duration > 0
    val shown = if (dragging) dragValue.toDouble() else s.position
    Column {
        Slider(
            value = if (hasDuration) shown.toFloat().coerceIn(0f, s.duration.toFloat()) else 0f,
            onValueChange = { dragging = true; dragValue = it },
            onValueChangeFinished = { dragging = false; TvCast.seekTo(dragValue.toDouble()) },
            valueRange = 0f..(if (hasDuration) s.duration.toFloat() else 1f),
            enabled = hasDuration && s.active,
            modifier = Modifier.semantics { stateDescription = "${clock(shown)} / ${clock(s.duration)}" },
        )
        Row(Modifier.fillMaxWidth()) {
            Text(clock(shown), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.weight(1f))
            Text(
                if (hasDuration) clock(s.duration) else if (s.active) stringResource(R.string.player_live) else "",
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** En-tête d'une chaîne en direct : à sa couleur, son nom en grand, l'état de la lecture. */
@Composable
private fun ChannelHeader(s: CastState) {
    val m = s.media ?: return
    Box(
        Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(28.dp))
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
    ) {
        // Le logo de la chaîne (ou son nom) sur un fond assorti, comme sur l'accueil.
        ChannelArt(
            name = m.channel ?: m.title, logo = m.posterUrl, streamUrl = m.url, pad = 56.dp,
            textStyle = MaterialTheme.typography.displaySmall.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.ExtraBold),
            modifier = Modifier.matchParentSize().padding(bottom = 0.dp),
        )
        // En lecture sur la télé : les ondes de diffusion autour du logo (« ça passe »).
        if (s.phase == Phase.PLAYING && !s.onPhone) Box(Modifier.align(Alignment.Center)) {
            CastWaves(Color.White.copy(alpha = 0.45f), size = 230.dp)
        }
        // Voile sombre en bas : l'état de la lecture reste lisible sur n'importe quel fond.
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(64.dp)
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.55f)))))
        Column(Modifier.align(Alignment.BottomStart).padding(18.dp)) {
            Text(
                phaseLabel(s) + (s.renderer?.let { " · " + it.name } ?: ""),
                style = MaterialTheme.typography.labelLarge, color = Color.White.copy(alpha = 0.92f), maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        if (s.phase == Phase.LOADING || s.phase == Phase.SENDING || s.phase == Phase.BUFFERING) {
            LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.BottomCenter))
        }
    }
}

/**
 * Direct : la même barre que pour une vidéo, en rouge. Au direct, elle est pleine ; en pause ou en remontant,
 * le curseur recule dans ce que le téléphone a enregistré, et l'on voit le retard (« −1:24 ») et « Revenir au direct ».
 * Sans rien à parcourir (flux lu directement par la télé), pas de barre : seulement l'état du direct.
 */
@Composable
private fun LiveBar(s: CastState) {
    val c = MaterialTheme.colorScheme
    val red = Color(0xFFE53935)
    val max = TvCast.maxLiveBack(s)
    val seekable = s.active && s.continuous && max > 5
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }
    val behindD = if (dragging) max - dragValue else s.liveBehind
    val behind = behindD.toLong()
    val paused = s.phase == Phase.PAUSED
    // Précis à la seconde : dès 1 s de retard (ou en pause), on l'affiche et on propose de revenir au direct.
    val atLive = behind < 1 && !paused
    val liveLabel = stringResource(R.string.player_live)
    val delayText = "−%d:%02d".format(behind / 60, behind % 60)
    Column {
        if (seekable) Slider(
            value = (max - behindD).toFloat().coerceIn(0f, max.toFloat()),
            onValueChange = { dragging = true; dragValue = it },
            onValueChangeFinished = { dragging = false; TvCast.seekLive(max - dragValue) },
            valueRange = 0f..max.toFloat(),
            colors = androidx.compose.material3.SliderDefaults.colors(thumbColor = red, activeTrackColor = red),
            modifier = Modifier.semantics { stateDescription = if (atLive) liveLabel else delayText },
        )
        Row(Modifier.fillMaxWidth().padding(top = if (seekable) 0.dp else 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier.clip(RoundedCornerShape(50))
                    .background(if (atLive) red else c.surfaceContainerHighest)
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(7.dp).clip(CircleShape).background(if (atLive) Color.White else c.onSurfaceVariant))
                Spacer(Modifier.width(6.dp))
                Text(
                    if (atLive) liveLabel.uppercase() else delayText,
                    style = MaterialTheme.typography.labelMedium, color = if (atLive) Color.White else c.onSurface,
                )
            }
            Spacer(Modifier.weight(1f))
            // Disponible en lecture comme en pause, dès qu'on a du retard.
            if (!atLive && s.active) TextButton(onClick = TvCast::goLive) {
                Text(stringResource(R.string.live_back))
                Spacer(Modifier.width(4.dp))
                Icon(Icons.Rounded.SkipNext, null, Modifier.size(18.dp))
            }
        }
    }
}

@Composable
internal fun Controls(s: CastState) {
    // Pendant le chargement ou l'envoi, lecture, pause et ±10 s ne font rien : un seul bouton, pour annuler.
    // (Aussi quand la télé n'a jamais démarré, en veille par exemple : on doit pouvoir abandonner.)
    if (s.phase == Phase.LOADING || s.phase == Phase.SENDING || s.notice?.res == R.string.notice_tv_not_starting) {
        Box(Modifier.fillMaxWidth().height(80.dp), contentAlignment = Alignment.Center) {
            OutlinedButton(onClick = TvCast::cancel, modifier = Modifier.height(56.dp)) {
                Icon(Icons.Rounded.Close, null, Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.action_cancel), style = MaterialTheme.typography.titleMedium)
            }
        }
        return
    }
    val enabled = (s.renderer != null || s.onPhone) && s.phase != Phase.LOADING
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
        AnimatedSkip(forward = false, onSkip = { TvCast.skip(-10.0) }) { iconMod, click ->
            FilledTonalIconButton(onClick = click, enabled = enabled && s.active && (!s.live || s.continuous), modifier = Modifier.size(56.dp)) {
                Icon(Icons.Rounded.Replay10, stringResource(R.string.cd_rewind), Modifier.size(28.dp).then(iconMod))
            }
        }
        val playing = s.phase == Phase.PLAYING || s.phase == Phase.BUFFERING || s.phase == Phase.SENDING
        FilledIconButton(
            onClick = TvCast::togglePlay, enabled = enabled, modifier = Modifier.size(80.dp),
            colors = IconButtonDefaults.filledIconButtonColors(),
        ) {
            Icon(
                if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                stringResource(if (playing) R.string.action_pause else R.string.action_play), Modifier.size(44.dp),
            )
        }
        AnimatedSkip(forward = true, onSkip = { TvCast.skip(10.0) }) { iconMod, click ->
            FilledTonalIconButton(onClick = click, enabled = enabled && s.active && (!s.live || s.continuous && s.liveBehind >= 1), modifier = Modifier.size(56.dp)) {
                Icon(Icons.Rounded.Forward10, stringResource(R.string.cd_forward10), Modifier.size(28.dp).then(iconMod))
            }
        }
        FilledTonalIconButton(onClick = TvCast::stop, enabled = enabled && s.active, modifier = Modifier.size(56.dp)) {
            Icon(Icons.Rounded.Stop, stringResource(R.string.action_stop), Modifier.size(28.dp))
        }
    }
}


/** Langue (piste son) quand la vidéo en propose plusieurs (Arte : français, VO, allemand, audiodescription…). */
@Composable
private fun AudioCard(s: CastState) {
    if (s.audioTracks.size < 2) return
    var open by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf(false) }
    val current = s.audioTracks.getOrNull(s.audioIndex)?.name.orEmpty()
    // Voix d'un fichier (MKV, MP4, TS) lu sur la télé : c'est elle qui les propose, comme les sous-titres intégrés.
    val fileVoices = s.audioTracks.none { it.uri.startsWith("http") || it.uri.startsWith(com.rollandostudio.universaltvcast.proxy.Dash.AUDIO) }
    val onTv = fileVoices && !s.onPhone
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.animateContentSize(),
    ) {
        Column {
            Box {
                OptionRow(Icons.Rounded.Translate, stringResource(R.string.audio_language),
                    if (onTv) s.audioTracks.joinToString { it.name } else current.replaceFirstChar { it.titlecase() },
                    active = !onTv, onClick = { if (onTv) note = !note else open = true })
                DropdownMenu(open && !onTv, onDismissRequest = { open = false }) {
                    s.audioTracks.forEachIndexed { i, t ->
                        DropdownMenuItem(
                            text = { Text(t.name.replaceFirstChar { it.titlecase() }) },
                            leadingIcon = { if (i == s.audioIndex) Icon(Icons.Rounded.Check, null) },
                            onClick = { open = false; TvCast.selectAudio(i) },
                        )
                    }
                }
            }
            if (onTv && note) Text(stringResource(R.string.audio_on_tv_note), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp))
        }
    }
}

/**
 * Ligne commune des cartes « Langue » et « Sous-titres » : icône, titre, choix en cours ; toute la largeur réagit au
 * toucher (un appui ouvre la liste). [trailing] : un bouton en bout de ligne (décalage des sous-titres).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun OptionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, value: String, active: Boolean,
    onClick: () -> Unit, onLongClick: (() -> Unit)? = null, onLongClickLabel: String? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val c = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .combinedClickable(role = Role.Button, onClick = onClick, onLongClickLabel = onLongClickLabel, onLongClick = onLongClick)
            .padding(start = 16.dp, end = if (trailing != null) 8.dp else 16.dp, top = 6.dp, bottom = 6.dp)
            .heightIn(min = 40.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = if (active) c.primary else c.onSurfaceVariant, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(10.dp))
        Text(title, style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.width(8.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        trailing?.invoke()
    }
}

/**
 * Sous-titres : une ligne « Sous-titres · Français ». Un appui ouvre directement la liste (Aucun, les pistes
 * proposées) ; un appui long, les options (ajouter un fichier). Sous-titre choisi : un petit bouton « Décalage »
 * déplie le réglage.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SubtitlesCard(s: CastState) {
    val context = LocalContext.current
    val media = s.media ?: return
    var list by remember { mutableStateOf(false) }
    var options by remember { mutableStateOf(false) }
    // Réglage du décalage déplié (la vidéo attend en pause pendant le réglage).
    var expanded by rememberSaveable(media.url) { mutableStateOf(false) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        TvCast.addSubtitle(uri, Intake.nameOf(context, uri))
        if (!expanded) TvCast.panelClosed()
    }
    // Sur le téléphone, les sous-titres intégrés à la vidéo se choisissent ici (piste ou aucune).
    val phoneTracks by com.rollandostudio.universaltvcast.cast.PhoneEngine.textTracks.collectAsState()
    val phoneSel by com.rollandostudio.universaltvcast.cast.PhoneEngine.textSelected.collectAsState()
    val phoneEmbedded = s.onPhone && media.subs.isEmpty() && phoneTracks.isNotEmpty()
    fun trackName(i: Int) = phoneTracks.getOrNull(i)?.let { t ->
        listOfNotNull(t.lang?.let { com.rollandostudio.universaltvcast.subs.SubtitleFinder.label(it) }, t.label?.takeIf { l -> l.isNotBlank() })
            .distinct().joinToString(" · ").ifEmpty { "#${i + 1}" }
    }
    // Sous-titres déjà dans la vidéo (MKV) : sur la télé, on le dit simplement, ils se choisissent sur la télé.
    val inVideo = s.embeddedSubs.isNotEmpty() && !phoneEmbedded
    val none = stringResource(if (inVideo) R.string.subs_in_video else R.string.subs_none)
    val inVideoLangs = s.embeddedSubs.distinct().joinToString { com.rollandostudio.universaltvcast.subs.SubtitleFinder.label(it) }
    val current = if (phoneEmbedded) trackName(phoneSel) ?: stringResource(R.string.subs_none)
        else media.subs.getOrNull(s.subIndex)?.name ?: none
    androidx.compose.runtime.LaunchedEffect(s.subIndex) { if (s.subIndex < 0 && !inVideo) expanded = false }
    // Changer les sous-titres met la vidéo en pause ; refermer le réglage la relance.
    androidx.compose.runtime.DisposableEffect(expanded) {
        val open = expanded // valeur figée : à la fermeture, `expanded` vaut déjà false
        onDispose { if (open) TvCast.panelClosed() }
    }
    fun choose(i: Int) {
        list = false
        TvCast.selectSubtitle(i)
        if (!expanded) TvCast.panelClosed()
    }
    val addLabel = stringResource(R.string.subs_add)
    val c = MaterialTheme.colorScheme
    Card(
        colors = CardDefaults.cardColors(containerColor = c.surfaceContainerLow),
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.animateContentSize(),
    ) {
        Column {
            Box {
                OptionRow(
                    Icons.Rounded.Subtitles, stringResource(R.string.subs_title), current,
                    active = s.subIndex >= 0 || (phoneEmbedded && phoneSel >= 0),
                    onClick = { if (inVideo) expanded = !expanded else list = true },
                    onLongClick = if (inVideo) null else ({ options = true }),
                    onLongClickLabel = if (inVideo) null else addLabel,
                    trailing = if (s.subIndex >= 0 && !inVideo) ({
                        // Le décalage, à portée d'un appui une fois un sous-titre choisi.
                        TextButton(onClick = { expanded = !expanded }, contentPadding = PaddingValues(horizontal = 10.dp)) {
                            Text(
                                if (s.subDelay != 0.0) String.format(java.util.Locale.getDefault(), "%+.1f s", s.subDelay)
                                else stringResource(R.string.subs_delay),
                                style = MaterialTheme.typography.labelLarge,
                            )
                        }
                    }) else null,
                )
                // Vidéo lue sur le téléphone avec ses propres sous-titres : on choisit la piste, ou aucune.
                if (phoneEmbedded) DropdownMenu(expanded = list, onDismissRequest = { list = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.subs_none)) },
                        leadingIcon = { if (phoneSel < 0) Icon(Icons.Rounded.Check, null) },
                        onClick = { list = false; com.rollandostudio.universaltvcast.cast.PhoneEngine.selectText(-1) },
                    )
                    phoneTracks.indices.forEach { i ->
                        DropdownMenuItem(
                            text = { Text(trackName(i) ?: "") },
                            leadingIcon = { if (i == phoneSel) Icon(Icons.Rounded.Check, null) },
                            onClick = { list = false; com.rollandostudio.universaltvcast.cast.PhoneEngine.selectText(i) },
                        )
                    }
                }
                // La liste, en un appui.
                else DropdownMenu(expanded = list, onDismissRequest = { list = false }) {
                    DropdownMenuItem(
                        text = { Text(none) },
                        leadingIcon = { if (s.subIndex < 0) Icon(Icons.Rounded.Check, null) },
                        onClick = { choose(-1) },
                    )
                    media.subs.forEachIndexed { i, sub ->
                        DropdownMenuItem(
                            text = { Text(sub.name) },
                            leadingIcon = { if (i == s.subIndex) Icon(Icons.Rounded.Check, null) },
                            onClick = { choose(i) },
                        )
                    }
                    // Rien à choisir : l'ajout d'un fichier est proposé ici aussi (sinon, appui long).
                    if (media.subs.isEmpty()) DropdownMenuItem(
                        text = { Text(addLabel) },
                        leadingIcon = { Icon(Icons.Rounded.Add, null) },
                        onClick = { list = false; pick.launch(arrayOf("*/*")) },
                    )
                }
                // Les options, à l'appui long.
                DropdownMenu(expanded = options, onDismissRequest = { options = false }) {
                    DropdownMenuItem(
                        text = { Text(addLabel) },
                        leadingIcon = { Icon(Icons.Rounded.Add, null) },
                        onClick = { options = false; pick.launch(arrayOf("*/*")) },
                    )
                }
            }
            if (expanded) Column(Modifier.padding(horizontal = 16.dp)) {
                if (inVideo) {
                    // Les langues en entier (la ligne repliée ne dit que « Déjà dans la vidéo »).
                    Text(inVideoLangs, style = MaterialTheme.typography.titleSmall)
                    Text(stringResource(R.string.subs_in_video_note), style = MaterialTheme.typography.bodyMedium,
                        color = c.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                } else {
                    SubtitleDelay(s.subDelay)
                    Spacer(Modifier.height(6.dp))
                    Text(stringResource(R.string.subs_note), style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
                }
                Spacer(Modifier.height(14.dp))
            }
        }
    }
}

/** Décalage des sous-titres : − les avance, + les retarde, par pas d'une demi-seconde. */
@Composable
private fun SubtitleDelay(delay: Double) {
    val c = MaterialTheme.colorScheme
    val value = String.format(java.util.Locale.getDefault(), "%+.1f s", delay).replace("+0,0", "0,0").replace("+0.0", "0.0")
    val label = stringResource(R.string.subs_delay)
    Spacer(Modifier.height(12.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        FilledTonalIconButton(onClick = { TvCast.shiftSubtitles(-0.5) }, modifier = Modifier.size(44.dp)) {
            Icon(Icons.Rounded.Remove, stringResource(R.string.cd_subs_earlier))
        }
        Text(
            value, style = MaterialTheme.typography.titleMedium, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.width(76.dp).semantics { contentDescription = "$label : $value"; liveRegion = LiveRegionMode.Polite },
        )
        FilledTonalIconButton(onClick = { TvCast.shiftSubtitles(0.5) }, modifier = Modifier.size(44.dp)) {
            Icon(Icons.Rounded.Add, stringResource(R.string.cd_subs_later))
        }
    }
    Text(stringResource(R.string.subs_delay_hint), style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant,
        modifier = Modifier.padding(top = 6.dp))
    if (delay != 0.0) {
        TextButton(onClick = TvCast::resetSubtitleDelay, contentPadding = PaddingValues(horizontal = 0.dp)) {
            Text(stringResource(R.string.subs_delay_reset))
        }
    }
}


@Composable
private fun Empty() {
    Column(
        Modifier.fillMaxSize().padding(bottom = LocalBottomInset.current).padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier.size(112.dp).clip(RoundedCornerShape(36.dp)).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Rounded.Cast, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary) }
        Spacer(Modifier.height(20.dp))
        Text(stringResource(R.string.player_empty_title), style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(6.dp))
        Text(stringResource(R.string.player_empty_body), style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

private fun clock(sec: Double): String {
    val s = sec.coerceAtLeast(0.0).toLong()
    return if (s >= 3600) String.format(Locale.ROOT, "%d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60)
    else String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60)
}
