package studio.rollando.castor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Slideshow
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import studio.rollando.castor.R
import studio.rollando.castor.cast.CastState
import studio.rollando.castor.cast.Castor
import studio.rollando.castor.cast.Phase

/** Album photo sur la télé : photo affichée, précédente / suivante, diaporama, vignettes. */
@Composable
fun SlideshowScreen(state: CastState) {
    val sl = state.slides ?: return
    val c = MaterialTheme.colorScheme
    val strip = rememberLazyListState()
    LaunchedEffect(sl.index) { strip.animateScrollToItem((sl.index - 2).coerceAtLeast(0)) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(4f / 3f).clip(RoundedCornerShape(28.dp)).background(c.surfaceContainerHighest),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.PhotoLibrary, null, Modifier.size(56.dp), tint = c.onSurfaceVariant)
            LocalImage(sl.uris[sl.index], Modifier.fillMaxSize(), side = 1200, contentScale = ContentScale.Fit)
            if (state.phase == Phase.SENDING || state.phase == Phase.LOADING) {
                LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.BottomCenter))
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.photos_counter, sl.index + 1, sl.uris.size),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
            )
            state.renderer?.let {
                Text(stringResource(R.string.player_on_tv, it.name), style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            FilledTonalIconButton(onClick = { Castor.nextPhoto(-1) }, modifier = Modifier.size(56.dp)) {
                Icon(Icons.Rounded.SkipPrevious, stringResource(R.string.cd_prev_photo), Modifier.size(28.dp))
            }
            FilledIconButton(onClick = Castor::toggleSlideshow, modifier = Modifier.size(80.dp)) {
                Icon(
                    if (sl.auto) Icons.Rounded.Pause else Icons.Rounded.Slideshow,
                    stringResource(if (sl.auto) R.string.cd_slideshow_stop else R.string.cd_slideshow_start), Modifier.size(40.dp),
                )
            }
            FilledTonalIconButton(onClick = { Castor.nextPhoto(1) }, modifier = Modifier.size(56.dp)) {
                Icon(Icons.Rounded.SkipNext, stringResource(R.string.cd_next_photo), Modifier.size(28.dp))
            }
            FilledTonalIconButton(onClick = Castor::stop, modifier = Modifier.size(56.dp)) {
                Icon(Icons.Rounded.Stop, stringResource(R.string.action_stop), Modifier.size(28.dp))
            }
        }
        if (sl.uris.size > 1) {
            LazyRow(state = strip, contentPadding = PaddingValues(horizontal = 2.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                itemsIndexed(sl.uris) { i, u ->
                    val sel = i == sl.index
                    val label = stringResource(R.string.photos_counter, i + 1, sl.uris.size)
                    Box(
                        Modifier.size(72.dp).clip(RoundedCornerShape(14.dp)).background(c.surfaceContainerHigh)
                            .then(if (sel) Modifier.border(3.dp, c.primary, RoundedCornerShape(14.dp)) else Modifier)
                            .clickable(role = Role.Button) { Castor.photoAt(i) }
                            .semantics { contentDescription = label },
                    ) { LocalImage(u, Modifier.fillMaxSize(), side = 200) }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}
