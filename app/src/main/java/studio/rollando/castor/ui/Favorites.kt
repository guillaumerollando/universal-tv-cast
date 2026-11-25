package studio.rollando.castor.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import studio.rollando.castor.R
import studio.rollando.castor.data.Bookmark
import java.io.File

/** Logos des sites (favicons), donnés par le navigateur lui-même et gardés sur le téléphone. */
object Favicons {
    private fun file(ctx: Context, host: String) = File(File(ctx.filesDir, "favicons").apply { mkdirs() }, host.replace('/', '_') + ".png")

    fun save(ctx: Context, host: String, icon: Bitmap) {
        if (host.isBlank()) return
        runCatching { file(ctx, host).outputStream().use { icon.compress(Bitmap.CompressFormat.PNG, 100, it) } }
    }

    suspend fun load(ctx: Context, host: String): Bitmap? = withContext(Dispatchers.IO) {
        runCatching { BitmapFactory.decodeFile(file(ctx, host).path) }.getOrNull()
    }
}

/** Logo du site, ou sa première lettre sur une pastille. */
@Composable
fun SiteIcon(host: String, size: Int = 28) {
    val ctx = LocalContext.current
    val icon by produceState<Bitmap?>(null, host) { value = Favicons.load(ctx, host) }
    val c = MaterialTheme.colorScheme
    Box(
        Modifier.size(size.dp).clip(RoundedCornerShape((size / 3.5).dp)).background(c.surfaceContainerHighest),
        contentAlignment = Alignment.Center,
    ) {
        val bmp = icon
        if (bmp != null) Image(bmp.asImageBitmap(), null, Modifier.size((size * 0.75).dp))
        else Text(host.firstOrNull()?.uppercase() ?: "?", style = MaterialTheme.typography.labelLarge, color = c.primary)
    }
}

/**
 * Carte d'un favori (marque-page d'une série ou d'un site) : logo, titre, site et épisode où l'on en est.
 * Un toucher reprend là où on en était.
 */
@Composable
fun FavoriteCard(b: Bookmark, resumeUrl: String, episode: Int?, onOpen: (String) -> Unit, onRemove: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val removeCd = stringResource(R.string.cd_delete)
    val badge = if (episode != null) stringResource(R.string.bookmark_episode, episode)
        else if (resumeUrl != b.url) stringResource(R.string.bookmark_resume) else null
    Box(
        Modifier.width(176.dp).clip(RoundedCornerShape(20.dp)).background(c.surfaceContainerHigh)
            .clickable(role = Role.Button) { onOpen(resumeUrl) }
            .semantics { contentDescription = b.title + (badge?.let { ", $it" } ?: "") + ", " + b.host },
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SiteIcon(b.host)
                Spacer(Modifier.width(8.dp))
                Text(b.host, style = MaterialTheme.typography.labelMedium, color = c.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(end = 18.dp))
            }
            Text(b.title, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.height(44.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(26.dp).clip(CircleShape).background(c.primary), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.PlayArrow, null, tint = c.onPrimary, modifier = Modifier.size(18.dp))
                }
                Spacer(Modifier.width(8.dp))
                Text(badge ?: stringResource(R.string.bookmark_open), style = MaterialTheme.typography.labelLarge, color = c.primary)
            }
        }
        Box(
            Modifier.align(Alignment.TopEnd).padding(6.dp).size(28.dp).clip(CircleShape)
                .clickable(role = Role.Button) { onRemove() }.semantics { contentDescription = removeCd },
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Rounded.Close, null, tint = c.onSurfaceVariant, modifier = Modifier.size(16.dp)) }
    }
}
