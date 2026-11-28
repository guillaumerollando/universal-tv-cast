package com.rollandostudio.universaltvcast.ui

import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.rollandostudio.universaltvcast.R
import com.rollandostudio.universaltvcast.cast.IdleScreen
import com.rollandostudio.universaltvcast.cast.TvCast
import com.rollandostudio.universaltvcast.data.Look
import com.rollandostudio.universaltvcast.data.SupportStore
import com.rollandostudio.universaltvcast.data.Supporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Personnalisation (pack soutien) : couleur, noir OLED, écran d'attente de la télé, icône de l'appli.
 * Sans le pack, tout se voit (aperçu compris) mais reste grisé, avec l'invitation à soutenir : rien d'utile n'en dépend.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StyleSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val scope = rememberCoroutineScope()
    val unlocked by Supporter.unlocked.collectAsState()
    val price by Supporter.price.collectAsState()
    val accent by Look.accent.collectAsState()
    val oled by Look.oled.collectAsState()
    val idle by Look.idle.collectAsState()
    val message by Look.message.collectAsState()
    val live by Look.live.collectAsState()
    val icon by Look.icon.collectAsState()
    val photos by Look.photos.collectAsState()
    var busyPhotos by remember { mutableStateOf(false) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(Look.MAX_PHOTOS)) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        busyPhotos = true
        scope.launch {
            withContext(Dispatchers.IO) { Look.setPhotos(uris) }
            if (Look.idle.value != Look.Idle.PHOTO) Look.setIdle(Look.Idle.PHOTO)
            busyPhotos = false
        }
    }
    val enabled = unlocked
    val dim = Modifier.alpha(if (enabled) 1f else 0.45f)

    ModalBottomSheet(onDismissRequest = onDismiss, modifier = Modifier.statusBarsPadding()) {
        LazyColumn(contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item {
                Text(stringResource(R.string.style_title), style = MaterialTheme.typography.titleLarge)
            }
            // Le pack : remerciement s'il est là, sinon l'invitation (achat unique, l'appli reste gratuite).
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f))) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Favorite, null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(10.dp))
                            Text(stringResource(if (unlocked) R.string.pack_thanks else R.string.pack_title),
                                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        }
                        if (!unlocked) {
                            Text(stringResource(R.string.pack_body), style = MaterialTheme.typography.bodyMedium)
                            Button(onClick = {
                                val ok = activity?.let { SupportStore.buy(it) } ?: false
                                if (!ok) Toast.makeText(context, R.string.pack_unavailable, Toast.LENGTH_LONG).show()
                            }) {
                                Text(price?.let { stringResource(R.string.pack_buy, it) } ?: stringResource(R.string.pack_buy_noprice))
                            }
                            Text(stringResource(R.string.pack_once), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }

            // Le mode Ambiance, l'autre grande fonction du pack : on y va d'ici aussi.
            item {
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(favBrush(Color(AMBIANCE_COLOR)))
                        .clickable { Nav.ambiance.value = true }.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.AutoAwesome, null, tint = Color.White)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.amb_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Color.White)
                        Text(stringResource(R.string.amb_sub), style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.9f))
                    }
                }
            }
            // ---------------------------------------------------------------- couleur
            item { Section(stringResource(R.string.style_color), enabled) }
            item {
                FlowRow(dim, horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Look.Accent.entries.forEach { a ->
                        val c = Color(a.argb)
                        val on = a == accent
                        Box(
                            Modifier.size(44.dp).clip(CircleShape).background(c)
                                .border(BorderStroke(if (on) 3.dp else 0.dp, MaterialTheme.colorScheme.onSurface), CircleShape)
                                .clickable(enabled = enabled, role = Role.RadioButton) { Look.setAccent(a) }
                                .semantics { contentDescription = a.name.lowercase() },
                            contentAlignment = Alignment.Center,
                        ) { if (on) Icon(Icons.Rounded.Check, null, tint = Color(0xFF1A1A1A)) }
                    }
                }
            }
            item {
                Row(dim.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.style_oled), style = MaterialTheme.typography.bodyLarge)
                        Text(stringResource(R.string.style_oled_sub), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(oled, { Look.setOled(it) }, enabled = enabled)
                }
            }

            // ---------------------------------------------------------------- écran d'attente
            item { Section(stringResource(R.string.style_idle), enabled) }
            item {
                // Aperçu : la même image que celle envoyée à la télé (refaite à chaque réglage, et chaque minute).
                val tick by produceState(0L) { while (true) { value = System.currentTimeMillis() / 60_000; kotlinx.coroutines.delay(5_000) } }
                val img by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, unlocked, accent, idle, message, live, photos, tick) {
                    value = withContext(Dispatchers.Default) {
                        runCatching { IdleScreen.jpeg(context).let { BitmapFactory.decodeByteArray(it, 0, it.size) }.asImageBitmap() }.getOrNull()
                    }
                }
                Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(16.dp))
                    .background(Color.Black), contentAlignment = Alignment.Center) {
                    img?.let { Image(it, stringResource(R.string.style_idle), Modifier.fillMaxWidth()) }
                }
            }
            item {
                Row(dim, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(Look.Idle.CLASSIC to R.string.style_idle_classic, Look.Idle.PHOTO to R.string.style_idle_photo,
                        Look.Idle.CLOCK to R.string.style_idle_clock).forEach { (i, label) ->
                        FilterChip(selected = idle == i, enabled = enabled, onClick = {
                            Look.setIdle(i)
                            if (i == Look.Idle.PHOTO && photos.isEmpty())
                                pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        }, label = { Text(stringResource(label)) })
                    }
                }
            }
            if (idle == Look.Idle.PHOTO) item {
                Row(dim, verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(enabled = enabled && !busyPhotos,
                        onClick = { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
                        Icon(Icons.Rounded.PhotoLibrary, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.style_photos))
                    }
                    Spacer(Modifier.width(12.dp))
                    if (photos.isNotEmpty()) Text(pluralStringResource(R.plurals.style_photos_count, photos.size, photos.size),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            item {
                // Le texte s'enregistre à la frappe ; l'aperçu suit.
                var text by remember { mutableStateOf(message) }
                LaunchedEffect(text) { kotlinx.coroutines.delay(400); if (text != Look.message.value) Look.setMessage(text) }
                OutlinedTextField(text, { text = it.take(60) }, dim.fillMaxWidth(), enabled = enabled, singleLine = true,
                    label = { Text(stringResource(R.string.style_message)) },
                    placeholder = { Text(stringResource(R.string.style_message_hint)) })
            }
            if (idle != Look.Idle.CLASSIC) item {
                Row(dim.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.style_live), style = MaterialTheme.typography.bodyLarge)
                        Text(stringResource(R.string.style_live_sub), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(live, { Look.setLive(it) }, enabled = enabled)
                }
            }
            item {
                val tv by TvCast.state.collectAsState()
                OutlinedButton(enabled = tv.renderer != null && !tv.active, onClick = { TvCast.previewIdle() }) {
                    Icon(Icons.Rounded.Tv, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.style_show_tv))
                }
            }

            // ---------------------------------------------------------------- icône
            item { Section(stringResource(R.string.style_icon), enabled) }
            item {
                Row(dim, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Look.Icon.entries.forEach { i ->
                        val bg = when (i) {
                            Look.Icon.NIGHT -> Color(0xFF14161F); Look.Icon.OCEAN -> Color(0xFF0B2A3A)
                            Look.Icon.PLUM -> Color(0xFF2A1035); Look.Icon.FOREST -> Color(0xFF0F2A1E); Look.Icon.BLACK -> Color.Black
                        }
                        val on = i == icon
                        Box(
                            Modifier.size(52.dp).clip(RoundedCornerShape(16.dp)).background(bg)
                                .border(BorderStroke(if (on) 3.dp else 1.dp,
                                    if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant), RoundedCornerShape(16.dp))
                                .clickable(enabled = enabled, role = Role.RadioButton) { if (!on) Look.setIcon(i) }
                                .semantics { contentDescription = i.name.lowercase() },
                            contentAlignment = Alignment.Center,
                        ) { Icon(painterResource(R.drawable.ic_logo), null, Modifier.size(30.dp), tint = Color(0xFFFFB86B)) }
                    }
                }
            }
            item {
                Text(stringResource(R.string.style_icon_note), dim, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun Section(title: String, enabled: Boolean) {
    Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        if (!enabled) {
            Spacer(Modifier.width(8.dp))
            Icon(Icons.Rounded.Lock, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
