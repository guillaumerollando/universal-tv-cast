package studio.rollando.castor.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.automirrored.rounded.VolumeDown
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Forward10
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
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
    val media = state.media
    if (media == null) {
        Empty()
        return
    }
    val c = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // « Affiche » : grand cadre qui dit ce qui se passe sur la télé.
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(28.dp))
                .background(Brush.linearGradient(listOf(c.primaryContainer, c.surfaceContainerHighest, c.secondaryContainer))),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(if (state.active) Icons.Rounded.Tv else Icons.Rounded.Movie, null, Modifier.size(56.dp), tint = c.onPrimaryContainer)
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

        Column {
            Text(media.title, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            media.host?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant) }
        }

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
        Volume(state)
        SubtitlesCard(state)
        if (state.isHls) ModeCard(state)
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun phaseLabel(s: CastState): String = stringResource(
    when (s.phase) {
        Phase.LOADING -> R.string.phase_loading
        Phase.SENDING -> R.string.phase_sending
        Phase.BUFFERING -> R.string.phase_buffering
        Phase.PLAYING -> R.string.phase_playing
        Phase.PAUSED -> R.string.phase_paused
        Phase.STOPPED -> R.string.phase_stopped
        Phase.IDLE -> R.string.phase_ready
    }
)

@Composable
private fun Progress(s: CastState) {
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

@Composable
private fun Controls(s: CastState) {
    val enabled = s.renderer != null && s.phase != Phase.LOADING
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
        FilledTonalIconButton(onClick = { Castor.skip(-10.0) }, enabled = enabled && s.active, modifier = Modifier.size(56.dp)) {
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
        FilledTonalIconButton(onClick = { Castor.skip(10.0) }, enabled = enabled && s.active, modifier = Modifier.size(56.dp)) {
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
    val none = stringResource(R.string.subs_none)
    val current = media.subs.getOrNull(s.subIndex)?.name ?: none
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Subtitles, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(10.dp))
                Text(stringResource(R.string.subs_title), style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
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
            Spacer(Modifier.height(6.dp))
            Text(stringResource(R.string.subs_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
        Modifier.fillMaxSize().padding(32.dp),
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
