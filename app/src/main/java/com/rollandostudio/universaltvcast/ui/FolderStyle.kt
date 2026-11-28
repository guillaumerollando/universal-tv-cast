package com.rollandostudio.universaltvcast.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChildCare
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.LiveTv
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Photo
import androidx.compose.material.icons.rounded.SportsSoccer
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.rollandostudio.universaltvcast.R
import com.rollandostudio.universaltvcast.lan.LanDevice
import com.rollandostudio.universaltvcast.lan.LanStore

/** Icônes proposées pour un favori du réseau (clé enregistrée, image, nom pour l'accessibilité). */
internal object FolderStyle {
    val icons: List<Triple<String, ImageVector, Int>> = listOf(
        Triple("folder", Icons.Rounded.Folder, R.string.fav_icon_folder),
        Triple("movie", Icons.Rounded.Movie, R.string.fav_icon_movie),
        Triple("series", Icons.Rounded.LiveTv, R.string.fav_icon_series),
        Triple("videos", Icons.Rounded.VideoLibrary, R.string.fav_icon_videos),
        Triple("kids", Icons.Rounded.ChildCare, R.string.fav_icon_kids),
        Triple("music", Icons.Rounded.MusicNote, R.string.fav_icon_music),
        Triple("photo", Icons.Rounded.Photo, R.string.fav_icon_photo),
        Triple("sport", Icons.Rounded.SportsSoccer, R.string.fav_icon_sport),
        Triple("star", Icons.Rounded.Star, R.string.fav_icon_star),
    )

    /** Couleurs proposées (la première, nulle, est celle de l'appli). */
    // Couleurs franches, de la même famille que celles des actions de l'accueil (pas pastel).
    val colors: List<Long?> = listOf(null, 0xFFE53950, 0xFFF08A24, 0xFFF2C230, 0xFF13A86F, 0xFF16A3B8, 0xFF2F7BF5, 0xFF7C4DFF, 0xFFE0457B, 0xFF4E7396)

    /** Icône d'origine : un dossier, ou l'appareil entier (serveur multimédia, ordinateur). */
    fun defaultIcon(f: LanStore.Folder): ImageVector = when {
        f.ref.isNotEmpty() -> Icons.Rounded.Folder
        f.kind == LanDevice.Kind.MEDIA_SERVER -> Icons.Rounded.Dns
        else -> Icons.Rounded.Computer
    }

    fun icon(f: LanStore.Folder): ImageVector = icons.firstOrNull { it.first == f.icon }?.second ?: defaultIcon(f)
}

/**
 * Personnaliser un favori du réseau : son nom (celui du dossier par défaut), une icône (film, série, enfants…) et une
 * couleur. Proposé à l'ajout ([justAdded]) et à l'appui long sur le favori ; tout reste facultatif.
 */
@Composable
internal fun FolderStyleDialog(f: LanStore.Folder, justAdded: Boolean, onDismiss: () -> Unit) {
    val c = MaterialTheme.colorScheme
    var name by remember { mutableStateOf(f.name) }
    var icon by remember { mutableStateOf(f.icon) }
    var color by remember { mutableStateOf(f.color) }
    val tint = color?.let { Color(it) } ?: c.primary
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (justAdded) R.string.fav_added_title else R.string.fav_customize_title)) },
        text = {
            // Défilable : avec le clavier ouvert, les boutons restent atteignables.
            Column(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                // Aperçu de la tuile, comme sur l'accueil.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(52.dp).clip(RoundedCornerShape(15.dp)).background(tint.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
                        Icon(FolderStyle.icons.firstOrNull { it.first == icon }?.second ?: FolderStyle.defaultIcon(f), null,
                            tint = tint, modifier = Modifier.size(28.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(name.ifBlank { f.title }, style = MaterialTheme.typography.titleMedium, maxLines = 2)
                }
                val focus = androidx.compose.ui.platform.LocalFocusManager.current
                // Champ au style des tuiles de l'appli : fond plein arrondi, sans contour.
                androidx.compose.material3.TextField(name, { name = it.take(40) }, Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text(stringResource(R.string.fav_name)) },
                    shape = RoundedCornerShape(14.dp),
                    colors = androidx.compose.material3.TextFieldDefaults.colors(
                        focusedContainerColor = c.surfaceContainerHighest, unfocusedContainerColor = c.surfaceContainerHighest,
                        focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.Sentences,
                        imeAction = androidx.compose.ui.text.input.ImeAction.Done),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { focus.clearFocus() }))
                Text(stringResource(R.string.fav_icon), style = MaterialTheme.typography.labelLarge, color = c.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    FolderStyle.icons.forEach { (key, img, label) ->
                        val on = icon == key || (icon == null && key == "folder" && f.ref.isNotEmpty())
                        val desc = stringResource(label)
                        Box(
                            Modifier.size(44.dp).clip(RoundedCornerShape(12.dp))
                                .background(if (on) tint.copy(alpha = 0.22f) else c.surfaceContainerHighest)
                                .clickable(role = Role.RadioButton) { icon = key }
                                .semantics { contentDescription = desc; selected = on },
                            contentAlignment = Alignment.Center,
                        ) { Icon(img, null, tint = if (on) tint else c.onSurfaceVariant, modifier = Modifier.size(24.dp)) }
                    }
                }
                Text(stringResource(R.string.style_color), style = MaterialTheme.typography.labelLarge, color = c.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    FolderStyle.colors.forEach { col ->
                        val on = col == color
                        val shown = col?.let { Color(it) } ?: c.primary
                        Box(
                            Modifier.size(34.dp).clip(CircleShape).background(shown)
                                .then(if (on) Modifier.border(3.dp, c.onSurface, CircleShape) else Modifier)
                                .clickable(role = Role.RadioButton) { color = col }
                                .semantics { selected = on },
                            contentAlignment = Alignment.Center,
                        ) { if (on) Icon(Icons.Rounded.Check, null, tint = Color(0xFF1A1A1A), modifier = Modifier.size(18.dp)) }
                    }
                }
                Spacer(Modifier.height(2.dp))
            }
        },
        confirmButton = {
            TextButton(onClick = { LanStore.customizeFolder(f.key, name, icon, color); onDismiss() }) {
                Text(stringResource(R.string.action_save))
            }
        },
        // À l'ajout, le favori est déjà enregistré tel quel : seul « Enregistrer » a un sens. Ensuite, on peut annuler.
        dismissButton = if (justAdded) null else ({ TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } }),
    )
}

/** Renommer un favori (site, chaîne) : le nom en cours, modifiable ; vide = celui d'origine. */
@Composable
internal fun RenameDialog(current: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    val c = MaterialTheme.colorScheme
    var name by remember { mutableStateOf(current) }
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.fav_rename)) },
        text = {
            androidx.compose.material3.TextField(name, { name = it.take(40) }, Modifier.fillMaxWidth(), singleLine = true,
                label = { Text(stringResource(R.string.fav_name)) },
                shape = RoundedCornerShape(14.dp),
                colors = androidx.compose.material3.TextFieldDefaults.colors(
                    focusedContainerColor = c.surfaceContainerHighest, unfocusedContainerColor = c.surfaceContainerHighest,
                    focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.Sentences,
                    imeAction = androidx.compose.ui.text.input.ImeAction.Done),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { focus.clearFocus() }))
        },
        confirmButton = { TextButton(onClick = { onSave(name); onDismiss() }) { Text(stringResource(R.string.action_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
