package studio.rollando.castor.ui

import android.net.Uri
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import studio.rollando.castor.R
import studio.rollando.castor.cast.Castor
import studio.rollando.castor.data.Library
import studio.rollando.castor.data.Recent

@Composable
fun HomeScreen(
    onBrowse: (String?) -> Unit,
    onPickFile: () -> Unit,
    onPickVideo: () -> Unit,
    onPickPhotos: () -> Unit,
    onPasteLink: () -> Unit,
) {
    val recents by Library.recents.collectAsState()
    val bookmarks by Library.bookmarks.collectAsState()
    val c = MaterialTheme.colorScheme

    LazyColumn(
        contentPadding = PaddingValues(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Box(
                Modifier
                    .padding(horizontal = 16.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(28.dp))
                    .background(Brush.linearGradient(listOf(c.primaryContainer, c.secondaryContainer)))
                    .castArcs(c.onPrimaryContainer)
                    .padding(22.dp)
            ) {
                Column {
                    Icon(painterResource(R.drawable.ic_logo), null, Modifier.size(36.dp), tint = c.onPrimaryContainer)
                    Spacer(Modifier.height(10.dp))
                    Text(stringResource(R.string.home_title), style = MaterialTheme.typography.headlineSmall,
                        color = c.onPrimaryContainer, modifier = Modifier.semantics { heading() })
                    Spacer(Modifier.height(4.dp))
                    Text(stringResource(R.string.home_subtitle), style = MaterialTheme.typography.bodyLarge,
                        color = c.onPrimaryContainer.copy(alpha = 0.85f))
                }
            }
        }

        // Les quatre façons de caster, en grille.
        item {
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Tile(Icons.Rounded.Public, stringResource(R.string.home_browse_tile), stringResource(R.string.home_browse_short), Modifier.weight(1f)) { onBrowse(null) }
                    Tile(Icons.Rounded.VideoLibrary, stringResource(R.string.home_videos), stringResource(R.string.home_videos_short), Modifier.weight(1f), onPickVideo)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Tile(Icons.Rounded.PhotoLibrary, stringResource(R.string.home_photos), stringResource(R.string.home_photos_short), Modifier.weight(1f), onPickPhotos)
                    Tile(Icons.Rounded.Link, stringResource(R.string.home_link), stringResource(R.string.home_link_short), Modifier.weight(1f), onPasteLink)
                }
                TextButton(onClick = onPickFile, modifier = Modifier.align(Alignment.End)) {
                    Icon(Icons.Rounded.FolderOpen, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.home_files_other))
                }
            }
        }

        if (recents.isNotEmpty()) {
            item { SectionTitle(stringResource(R.string.home_resume), Modifier.padding(horizontal = 16.dp).semantics { heading() }) }
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(recents, key = { "r" + it.url }) { r -> RecentCard(r) }
                }
            }
        }

        item { SectionTitle(stringResource(R.string.home_bookmarks), Modifier.padding(horizontal = 16.dp).semantics { heading() }) }
        if (bookmarks.isEmpty()) {
            item {
                Text(stringResource(R.string.home_bookmarks_empty), style = MaterialTheme.typography.bodyMedium,
                    color = c.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp))
            }
        } else item {
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(bookmarks, key = { "b" + it.url }) { b ->
                    AssistChip(
                        onClick = { onBrowse(b.url) },
                        label = { Text(b.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        leadingIcon = { Icon(Icons.Rounded.Star, null, Modifier.size(18.dp), tint = c.primary) },
                        colors = AssistChipDefaults.assistChipColors(containerColor = c.surfaceContainerHigh),
                        border = null,
                        modifier = Modifier.height(40.dp),
                    )
                }
            }
        }

        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = c.surfaceContainer), shape = RoundedCornerShape(20.dp),
                modifier = Modifier.padding(horizontal = 16.dp),
            ) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
                    Icon(Icons.AutoMirrored.Rounded.OpenInNew, null, tint = c.secondary)
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(stringResource(R.string.home_other_title), style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(2.dp))
                        Text(stringResource(R.string.home_other_body), style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant)
                    }
                }
            }
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
private fun Tile(icon: ImageVector, title: String, sub: String, modifier: Modifier, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    Surface(onClick = onClick, shape = RoundedCornerShape(22.dp), color = c.surfaceContainerHigh, modifier = modifier.height(118.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Box(Modifier.size(42.dp).clip(CircleShape).background(c.primary.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = c.primary, modifier = Modifier.size(24.dp))
            }
            Column {
                Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(sub, style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Carte « Reprendre » : l'affiche du film (ou une icône), le titre, et une croix pour retirer. */
@Composable
private fun RecentCard(r: Recent) {
    val c = MaterialTheme.colorScheme
    val removeCd = stringResource(R.string.cd_delete)
    Column(Modifier.width(118.dp)) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(16.dp)).background(c.surfaceContainerHigh)
                .clickable(role = Role.Button) {
                    Castor.open(r.toMedia())
                    Nav.tab.value = Tab.PLAYER
                }
                .semantics { contentDescription = r.title },
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.Movie, null, Modifier.size(36.dp), tint = c.onSurfaceVariant)
            if (r.poster != null) RemoteImage(r.poster, Modifier.fillMaxSize(), maxSide = 400)
            IconButton(
                onClick = { Library.removeRecent(r) },
                modifier = Modifier.align(Alignment.TopEnd).size(36.dp),
            ) {
                Box(Modifier.size(24.dp).clip(CircleShape).background(c.surface.copy(alpha = 0.75f)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Close, removeCd, Modifier.size(16.dp))
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(r.title, style = MaterialTheme.typography.labelLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(Uri.parse(r.pageUrl ?: r.url).host?.removePrefix("www.")?.takeIf { it != "127.0.0.1" } ?: "",
            style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant, maxLines = 1)
    }
}
