package studio.rollando.castor.ui

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.Smartphone
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
import androidx.compose.animation.animateContentSize
import androidx.compose.material.icons.automirrored.rounded.VolumeDown
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material.icons.rounded.Close
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
import studio.rollando.castor.R
import studio.rollando.castor.cast.CastState
import studio.rollando.castor.cast.Castor
import studio.rollando.castor.cast.Intake
import studio.rollando.castor.cast.Phase
import java.util.Locale

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
    Box(Modifier.fillMaxSize()) {
    // L'ambiance (image du film floutée) est dessinée derrière tout l'écran, en-tête compris : voir Ambient().
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (film) FilmHeader(state)
        else if (state.live) ChannelHeader(state)
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

        if (!film && !state.live) Column {
            Text(media.title, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            media.host?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant) }
        }

        BackgroundTip(state)

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
                    IconButton(onClick = Castor::dismissNotice) { Icon(Icons.Rounded.Close, stringResource(R.string.cd_dismiss)) }
                }
                if (n.error) {
                    TextButton(onClick = { Nav.help.value = true }, modifier = Modifier.padding(start = 8.dp)) {
                        Text(stringResource(R.string.action_help))
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

        if (!state.active) {
            Button(
                onClick = { if (state.renderer == null) onChooseTv() else Castor.cast() },
                enabled = state.phase != Phase.LOADING,
                modifier = Modifier.fillMaxWidth().height(56.dp),
            ) {
                Icon(Icons.Rounded.Cast, null)
                Spacer(Modifier.width(10.dp))
                Text(state.renderer?.let { stringResource(R.string.action_cast_to, it.name) } ?: stringResource(R.string.action_cast_pick),
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }

        Progress(state)
        Controls(state)
        // Finir l'épisode dans son lit : même vidéo, même endroit, sur le téléphone (la télé se met en pause).
        val ctx = LocalContext.current
        OutlinedButton(
            onClick = {
                Castor.toPhone()?.let {
                    studio.rollando.castor.cast.PhonePlayback.pending = it
                    ctx.startActivity(Intent(ctx, studio.rollando.castor.cast.PhonePlayerActivity::class.java))
                }
            },
            enabled = state.phase != Phase.LOADING,
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) {
            Icon(Icons.Rounded.Smartphone, null)
            Spacer(Modifier.width(10.dp))
            Text(stringResource(R.string.phone_watch))
        }
        SubtitlesCard(state)
        if (state.isHls) ModeCard(state)
        Spacer(Modifier.height(8.dp + LocalBottomInset.current))
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
private fun Progress(s: CastState) {
    if (s.live) { LiveBar(s); return }
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }
    val hasDuration = s.duration > 0
    val shown = if (dragging) dragValue.toDouble() else s.position
    Column {
        Slider(
            value = if (hasDuration) shown.toFloat().coerceIn(0f, s.duration.toFloat()) else 0f,
            onValueChange = { dragging = true; dragValue = it },
            onValueChangeFinished = { dragging = false; Castor.seekTo(dragValue.toDouble()) },
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
 * le curseur recule dans ce que garde le flux, et l'on voit le retard (« −1:24 ») et « Revenir au direct ».
 */
@Composable
private fun LiveBar(s: CastState) {
    val c = MaterialTheme.colorScheme
    val red = Color(0xFFE53935)
    val paused = s.phase == Phase.PAUSED
    // Le retard grandit pendant la pause : on le recalcule chaque seconde.
    val pausedSince = remember(paused) { if (paused) System.currentTimeMillis() else 0L }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(paused) { while (paused) { now = System.currentTimeMillis(); kotlinx.coroutines.delay(1000) } }
    val max = Castor.maxLiveBack(s)
    val seekable = s.active && s.continuous && max > 0
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }
    val running = (s.liveBehind + if (paused) (now - pausedSince) / 1000.0 else 0.0)
    val behindD = if (dragging) max - dragValue else if (seekable) running.coerceAtMost(max) else running
    val behind = behindD.toLong()
    val atLive = behind < 5 && !paused
    val liveLabel = stringResource(R.string.player_live)
    val delayText = "−%d:%02d".format(behind / 60, behind % 60)
    Column {
        Slider(
            value = if (seekable) (max - behindD).toFloat().coerceIn(0f, max.toFloat()) else 1f,
            onValueChange = { dragging = true; dragValue = it },
            onValueChangeFinished = { dragging = false; Castor.seekLive(max - dragValue) },
            valueRange = 0f..(if (seekable) max.toFloat() else 1f),
            enabled = seekable,
            colors = androidx.compose.material3.SliderDefaults.colors(
                thumbColor = red, activeTrackColor = red, disabledThumbColor = red, disabledActiveTrackColor = red,
            ),
            modifier = Modifier.semantics { stateDescription = if (atLive) liveLabel else delayText },
        )
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier.clip(RoundedCornerShape(50))
                    .background(if (atLive) red else c.surfaceContainerHighest)
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(7.dp).clip(CircleShape).background(if (atLive) Color.White else c.onSurfaceVariant))
                Spacer(Modifier.width(6.dp))
                Text(
                    if (atLive || behind < 5) liveLabel.uppercase() else delayText,
                    style = MaterialTheme.typography.labelMedium, color = if (atLive) Color.White else c.onSurface,
                )
            }
            Spacer(Modifier.weight(1f))
            if (!atLive && s.active) TextButton(onClick = Castor::goLive) {
                Text(stringResource(R.string.live_back))
                Spacer(Modifier.width(4.dp))
                Icon(Icons.Rounded.SkipNext, null, Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun Controls(s: CastState) {
    val enabled = s.renderer != null && s.phase != Phase.LOADING
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
        FilledTonalIconButton(onClick = { Castor.skip(-10.0) }, enabled = enabled && s.active && (!s.live || s.continuous), modifier = Modifier.size(56.dp)) {
            Icon(Icons.Rounded.Replay10, stringResource(R.string.cd_rewind), Modifier.size(28.dp))
        }
        val playing = s.phase == Phase.PLAYING || s.phase == Phase.BUFFERING || s.phase == Phase.SENDING
        FilledIconButton(
            onClick = Castor::togglePlay, enabled = enabled, modifier = Modifier.size(80.dp),
            colors = IconButtonDefaults.filledIconButtonColors(),
        ) {
            Icon(
                if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                stringResource(if (playing) R.string.action_pause else R.string.action_play), Modifier.size(44.dp),
            )
        }
        FilledTonalIconButton(onClick = { Castor.skip(10.0) }, enabled = enabled && s.active && (!s.live || s.continuous && s.liveBehind > 0), modifier = Modifier.size(56.dp)) {
            Icon(Icons.Rounded.Forward10, stringResource(R.string.cd_forward10), Modifier.size(28.dp))
        }
        FilledTonalIconButton(onClick = Castor::stop, enabled = enabled && s.active, modifier = Modifier.size(56.dp)) {
            Icon(Icons.Rounded.Stop, stringResource(R.string.action_stop), Modifier.size(28.dp))
        }
    }
}

@Composable
private fun Volume(s: CastState) {
    val enabled = s.renderer?.rcUrl != null
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer), shape = RoundedCornerShape(20.dp)) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { Castor.changeVolume(-3) }, enabled = enabled, modifier = Modifier.size(52.dp)) {
                Icon(Icons.AutoMirrored.Rounded.VolumeDown, stringResource(R.string.cd_vol_down))
            }
            Text(
                s.volume?.let { stringResource(R.string.volume_label, it) } ?: stringResource(R.string.volume_unknown),
                style = MaterialTheme.typography.titleSmall, textAlign = TextAlign.Center, modifier = Modifier.weight(1f)
                    .semantics { liveRegion = LiveRegionMode.Polite },
            )
            IconButton(onClick = { Castor.changeVolume(3) }, enabled = enabled, modifier = Modifier.size(52.dp)) {
                Icon(Icons.AutoMirrored.Rounded.VolumeUp, stringResource(R.string.cd_vol_up))
            }
        }
    }
}

@Composable
private fun SubtitlesCard(s: CastState) {
    val context = LocalContext.current
    val media = s.media ?: return
    var open by remember { mutableStateOf(false) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        Castor.addSubtitle(uri, Intake.nameOf(context, uri))
    }
    // Sous-titres déjà dans la vidéo (MKV) : on le dit simplement, ils se choisissent sur la télé.
    val inVideo = s.embeddedSubs.isNotEmpty()
    val none = stringResource(if (inVideo) R.string.subs_in_video else R.string.subs_none)
    val inVideoLangs = s.embeddedSubs.distinct().joinToString { studio.rollando.castor.subs.SubtitleFinder.label(it) }
    val current = media.subs.getOrNull(s.subIndex)?.name ?: none
    // Repliée par défaut : une ligne discrète « Sous-titres · Français », dépliée au toucher.
    var expanded by rememberSaveable(media.url) { mutableStateOf(false) }
    // Changer les sous-titres met la vidéo en pause ; refermer le réglage la relance.
    androidx.compose.runtime.DisposableEffect(expanded) {
        val open = expanded // valeur figée : à la fermeture, `expanded` vaut déjà false
        onDispose { if (open) Castor.panelClosed() }
    }
    val stateText = stringResource(if (expanded) R.string.state_expanded else R.string.state_collapsed)
    val c = MaterialTheme.colorScheme
    Card(
        colors = CardDefaults.cardColors(containerColor = c.surfaceContainerLow),
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.animateContentSize(),
    ) {
        Column {
            // Toute la largeur de la carte réagit au toucher (la surbrillance aussi).
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(role = Role.Button) { expanded = !expanded }
                    .semantics { stateDescription = stateText }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.Subtitles, null, tint = if (s.subIndex >= 0) c.primary else c.onSurfaceVariant, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(10.dp))
                Text(stringResource(R.string.subs_title), style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.width(8.dp))
                Text(
                    current + if (s.subIndex >= 0 && s.subDelay != 0.0)
                        String.format(java.util.Locale.getDefault(), " · %+.1f s", s.subDelay) else "",
                    style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                )
                Icon(if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null, tint = c.onSurfaceVariant)
            }
            if (expanded) Column(Modifier.padding(horizontal = 16.dp)) {
            // Sous-titres déjà dans la vidéo : les nôtres ne pourraient pas s'afficher par-dessus, on n'en propose pas.
            if (!inVideo) Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                        Text(current, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                        DropdownMenuItem(text = { Text(none) }, onClick = { open = false; Castor.selectSubtitle(-1) })
                        media.subs.forEachIndexed { i, sub ->
                            DropdownMenuItem(text = { Text(sub.name) }, onClick = { open = false; Castor.selectSubtitle(i) })
                        }
                    }
                }
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = { pick.launch(arrayOf("*/*")) }, modifier = Modifier.height(48.dp)) {
                    Text(stringResource(R.string.subs_add))
                }
            }
            if (inVideo) {
                // Les langues en entier (la ligne repliée ne dit que « Déjà dans la vidéo »).
                Text(inVideoLangs, style = MaterialTheme.typography.titleSmall)
                Text(stringResource(R.string.subs_in_video_note), style = MaterialTheme.typography.bodyMedium,
                    color = c.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
            }
            if (s.subIndex >= 0 && !inVideo) SubtitleDelay(s.subDelay)
            Spacer(Modifier.height(6.dp))
            if (!inVideo) Text(stringResource(R.string.subs_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
        FilledTonalIconButton(onClick = { Castor.shiftSubtitles(-0.5) }, modifier = Modifier.size(44.dp)) {
            Icon(Icons.Rounded.Remove, stringResource(R.string.cd_subs_earlier))
        }
        Text(
            value, style = MaterialTheme.typography.titleMedium, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.width(76.dp).semantics { contentDescription = "$label : $value"; liveRegion = LiveRegionMode.Polite },
        )
        FilledTonalIconButton(onClick = { Castor.shiftSubtitles(0.5) }, modifier = Modifier.size(44.dp)) {
            Icon(Icons.Rounded.Add, stringResource(R.string.cd_subs_later))
        }
    }
    Text(stringResource(R.string.subs_delay_hint), style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant,
        modifier = Modifier.padding(top = 6.dp))
    if (delay != 0.0) {
        TextButton(onClick = Castor::resetSubtitleDelay, contentPadding = PaddingValues(horizontal = 0.dp)) {
            Text(stringResource(R.string.subs_delay_reset))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModeCard(s: CastState) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text(stringResource(R.string.mode_title), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(10.dp))
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = s.continuous, onClick = { Castor.setContinuous(true) },
                    shape = SegmentedButtonDefaults.itemShape(0, 2),
                ) { Text(stringResource(R.string.mode_continuous)) }
                SegmentedButton(
                    selected = !s.continuous, onClick = { Castor.setContinuous(false) },
                    shape = SegmentedButtonDefaults.itemShape(1, 2),
                ) { Text(stringResource(R.string.mode_direct)) }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(
                    when {
                        s.splitAudio && !s.continuous -> R.string.mode_split_audio
                        s.continuous -> R.string.mode_continuous_help
                        else -> R.string.mode_direct_help
                    }
                ),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
