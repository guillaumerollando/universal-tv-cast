package com.rollandostudio.universaltvcast.ui

import android.widget.Toast
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.rollandostudio.universaltvcast.R
import com.rollandostudio.universaltvcast.cast.Ambiance
import com.rollandostudio.universaltvcast.cast.CastState
import com.rollandostudio.universaltvcast.cast.Phase
import com.rollandostudio.universaltvcast.cast.TvCast
import com.rollandostudio.universaltvcast.data.Look
import com.rollandostudio.universaltvcast.data.SupportStore
import com.rollandostudio.universaltvcast.data.Supporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Couleur du mode Ambiance (tuile, fenêtre) : un bleu-vert profond, à part des actions. */
internal const val AMBIANCE_COLOR = 0xFF16A3B8

/**
 * Mode Ambiance (pack soutien) : photos (les miennes ou un thème), musique (une radio par style), heure, durée de
 * chaque photo, puis « Lancer sur la télé ». Sans le pack : tout se voit, et l'invitation à soutenir remplace le bouton.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AmbianceSheet(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val activity = LocalActivity.current
    val scope = rememberCoroutineScope()
    val unlocked by Supporter.unlocked.collectAsState()
    val price by Supporter.price.collectAsState()
    val theme by Ambiance.theme.collectAsState()
    val mood by Ambiance.mood.collectAsState()
    val clock by Ambiance.clock.collectAsState()
    val seconds by Ambiance.seconds.collectAsState()
    val photos by Look.photos.collectAsState()
    val tv by TvCast.state.collectAsState()
    var busy by remember { mutableStateOf(false) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(Look.MAX_PHOTOS)) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        busy = true
        scope.launch { withContext(Dispatchers.IO) { Look.setPhotos(uris) }; Ambiance.setTheme(Ambiance.Theme.MINE); busy = false }
    }
    val c = MaterialTheme.colorScheme
    ModalBottomSheet(onDismissRequest = onDismiss, modifier = Modifier.statusBarsPadding()) {
        LazyColumn(contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item {
                // En-tête à la couleur de l'ambiance.
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(favBrush(Color(AMBIANCE_COLOR))).padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.AutoAwesome, null, tint = Color.White)
                        Spacer(Modifier.width(10.dp))
                        Text(stringResource(R.string.amb_title), style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold), color = Color.White)
                    }
                    Text(stringResource(R.string.amb_sub), style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.9f))
                }
            }
            item { SectionTitle(stringResource(R.string.amb_photos)) }
            item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Ambiance.Theme.entries.forEach { t ->
                        FilterChip(selected = theme == t, onClick = {
                            if (t == Ambiance.Theme.MINE && photos.isEmpty()) pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                            else Ambiance.setTheme(t)
                        }, label = { Text(stringResource(t.label)) })
                    }
                }
            }
            if (theme == Ambiance.Theme.MINE) item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(enabled = !busy, onClick = { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
                        Icon(Icons.Rounded.PhotoLibrary, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.style_photos))
                    }
                    Spacer(Modifier.width(12.dp))
                    if (photos.isNotEmpty()) Text(pluralStringResource(R.plurals.style_photos_count, photos.size, photos.size),
                        style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant)
                }
            }
            item { SectionTitle(stringResource(R.string.amb_music)) }
            item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Ambiance.Mood.entries.forEach { m ->
                        FilterChip(selected = mood == m, onClick = { Ambiance.setMood(m) }, label = { Text(stringResource(m.label)) })
                    }
                }
            }
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.amb_clock), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    Switch(clock, { Ambiance.setClock(it) })
                }
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.amb_each), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    listOf(15 to "15 s", 30 to "30 s", 60 to "1 min").forEach { (s, label) ->
                        FilterChip(selected = seconds == s, onClick = { Ambiance.setSeconds(s) }, label = { Text(label) })
                    }
                }
            }
            item {
                if (unlocked) {
                    Button(
                        onClick = { Ambiance.start(); Nav.tab.value = Tab.PLAYER; onDismiss() },
                        enabled = !busy, modifier = Modifier.fillMaxWidth().height(52.dp),
                    ) {
                        Icon(Icons.Rounded.Tv, null)
                        Spacer(Modifier.width(10.dp))
                        Text(stringResource(R.string.amb_start))
                    }
                } else {
                    // Fonction du pack soutien : l'invitation à la place du bouton.
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.primaryContainer.copy(alpha = 0.55f)).padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Favorite, null, tint = c.primary)
                            Spacer(Modifier.width(10.dp))
                            Text(stringResource(R.string.pack_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        }
                        Text(stringResource(R.string.pack_body), style = MaterialTheme.typography.bodyMedium)
                        Button(onClick = {
                            val ok = activity?.let { SupportStore.buy(it) } ?: false
                            if (!ok) Toast.makeText(ctx, R.string.pack_unavailable, Toast.LENGTH_LONG).show()
                        }) { Text(price?.let { stringResource(R.string.pack_buy, it) } ?: stringResource(R.string.pack_buy_noprice)) }
                    }
                }
            }
            if (unlocked && tv.renderer == null) item {
                Text(stringResource(R.string.notice_pick_tv), style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
            }
            item {
                Text(stringResource(R.string.amb_credits_note), style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
            }
        }
    }
}

/**
 * Écran de lecture pendant l'ambiance : la photo affichée sur la télé (avec le halo qui respire), le thème et la
 * radio, « Photo suivante » et « Arrêter ».
 */
@Composable
fun AmbiancePlayer(state: CastState) {
    val now = state.ambiance ?: return
    val c = MaterialTheme.colorScheme
    val preview by Ambiance.preview.collectAsState()
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(28.dp)).background(favBrush(Color(AMBIANCE_COLOR))),
            contentAlignment = Alignment.Center) {
            val bmp = preview
            if (bmp != null) Image(bmp.asImageBitmap(), null, Modifier.fillMaxWidth().aspectRatio(16f / 9f), contentScale = ContentScale.Crop)
            else Box(Modifier.size(88.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.AutoAwesome, null, tint = Color.White, modifier = Modifier.size(40.dp))
            }
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Brush.verticalGradient(0.55f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.7f))))
            Column(Modifier.align(Alignment.BottomStart).padding(18.dp)) {
                Text(stringResource(R.string.amb_title) + " · " + stringResource(now.theme.label),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), color = Color.White)
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    EqualizerBars(active = state.phase == Phase.PLAYING, color = c.primary)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        (now.station?.let { stringResource(R.string.amb_radio, it) }
                            ?: if (now.mood == Ambiance.Mood.NONE) stringResource(R.string.amb_mood_none) else stringResource(R.string.amb_no_radio)) +
                            (state.renderer?.let { " · " + it.name } ?: ""),
                        style = MaterialTheme.typography.labelLarge, color = Color.White.copy(alpha = 0.92f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (state.phase == Phase.SENDING) androidx.compose.material3.LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.BottomCenter))
        }
        now.credit?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FilledTonalButton(onClick = { Ambiance.skip() }, modifier = Modifier.weight(1f).height(52.dp)) {
                Icon(Icons.Rounded.SkipNext, null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.amb_next), maxLines = 1)
            }
            OutlinedButton(onClick = { TvCast.stop() }, modifier = Modifier.weight(1f).height(52.dp)) {
                Icon(Icons.Rounded.Stop, null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.action_stop), maxLines = 1)
            }
        }
        var settings by remember { mutableStateOf(false) }
        if (settings) AmbianceSheet(onDismiss = { settings = false })
        androidx.compose.material3.TextButton(onClick = { settings = true }) { Text(stringResource(R.string.amb_change)) }
    }
}
