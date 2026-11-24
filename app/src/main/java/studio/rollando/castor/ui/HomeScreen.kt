package studio.rollando.castor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.clickable
import android.net.Uri
import studio.rollando.castor.R
import studio.rollando.castor.cast.Castor
import studio.rollando.castor.data.Library

@Composable
fun HomeScreen(onBrowse: (String?) -> Unit, onPickFile: () -> Unit, onPasteLink: () -> Unit) {
    val recents by Library.recents.collectAsState()
    val bookmarks by Library.bookmarks.collectAsState()
    val c = MaterialTheme.colorScheme

    LazyColumn(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(28.dp))
                    .background(Brush.linearGradient(listOf(c.primaryContainer, c.secondaryContainer)))
                    .padding(24.dp)
            ) {
                Column {
                    Icon(painterResource(R.drawable.ic_logo), null, Modifier.size(44.dp), tint = c.onPrimaryContainer)
                    Spacer(Modifier.height(12.dp))
                    Text(stringResource(R.string.home_title), style = MaterialTheme.typography.headlineSmall,
                        color = c.onPrimaryContainer, modifier = Modifier.semantics { heading() })
                    Spacer(Modifier.height(6.dp))
                    Text(stringResource(R.string.home_subtitle), style = MaterialTheme.typography.bodyLarge,
                        color = c.onPrimaryContainer.copy(alpha = 0.85f))
                }
            }
        }
        item { ActionCard(Icons.Rounded.Public, stringResource(R.string.home_browse), stringResource(R.string.home_browse_sub)) { onBrowse(null) } }
        item { ActionCard(Icons.Rounded.VideoLibrary, stringResource(R.string.home_files), stringResource(R.string.home_files_sub), onPickFile) }
        item { ActionCard(Icons.Rounded.Link, stringResource(R.string.home_link), stringResource(R.string.home_link_sub), onPasteLink) }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = c.surfaceContainer), shape = RoundedCornerShape(20.dp)) {
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

        if (bookmarks.isNotEmpty()) {
            item { SectionTitle(stringResource(R.string.home_bookmarks), Modifier.semantics { heading() }) }
            items(bookmarks, key = { "b" + it.url }) { b ->
                ListItem(
                    headlineContent = { Text(b.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = { Text(Uri.parse(b.url).host?.removePrefix("www.") ?: b.url, maxLines = 1) },
                    leadingContent = { Icon(Icons.Rounded.Star, null, tint = c.primary) },
                    colors = ListItemDefaults.colors(containerColor = c.surfaceContainerLow),
                    modifier = Modifier.clip(RoundedCornerShape(16.dp)).clickable(role = Role.Button) { onBrowse(b.url) },
                )
            }
        }

        if (recents.isNotEmpty()) {
            item { SectionTitle(stringResource(R.string.home_recents), Modifier.semantics { heading() }) }
            items(recents, key = { "r" + it.url }) { r ->
                ListItem(
                    headlineContent = { Text(r.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = {
                        Text(Uri.parse(r.pageUrl ?: r.url).host?.removePrefix("www.") ?: "", maxLines = 1)
                    },
                    leadingContent = { Icon(Icons.Rounded.History, null) },
                    trailingContent = {
                        IconButton(onClick = { Library.removeRecent(r) }) {
                            Icon(Icons.Rounded.Close, stringResource(R.string.cd_delete))
                        }
                    },
                    colors = ListItemDefaults.colors(containerColor = c.surfaceContainerLow),
                    modifier = Modifier.clip(RoundedCornerShape(16.dp)).clickable(role = Role.Button) {
                        Castor.open(r.toMedia())
                        Nav.tab.value = Tab.PLAYER
                    },
                )
            }
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
private fun ActionCard(icon: ImageVector, title: String, sub: String, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    Surface(onClick = onClick, shape = RoundedCornerShape(20.dp), color = c.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(52.dp).clip(CircleShape).background(c.primary.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center,
            ) { Icon(icon, null, tint = c.primary, modifier = Modifier.size(28.dp)) }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(sub, style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant)
            }
        }
    }
}
