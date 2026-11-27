package com.rollandostudio.universaltvcast.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material.icons.rounded.CastConnected
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.rollandostudio.universaltvcast.R
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect

/** Hauteur réservée par la barre d'onglets : les écrans défilent dessous et finissent au-dessus. */
val LocalBottomInset = androidx.compose.runtime.compositionLocalOf { 0.dp }

private data class TabSpec(val tab: Tab, val icon: ImageVector, val label: String, val dot: Boolean = false)

/**
 * Barre d'onglets flottante de largeur fixe, en « verre fumé » : trois emplacements égaux, icône et nom toujours visibles,
 * et un indicateur ambré qui glisse d'un onglet à l'autre (≈ 0,2 s, sans rebond). Rien ne change de taille.
 */
@Composable
fun PillTabBar(tab: Tab, casting: Boolean, haze: dev.chrisbanes.haze.HazeState, onSelect: (Tab) -> Unit) {
    val c = MaterialTheme.colorScheme
    val tabs = listOf(
        TabSpec(Tab.HOME, Icons.Rounded.Home, stringResource(R.string.tab_home)),
        TabSpec(Tab.BROWSER, Icons.Rounded.Public, stringResource(R.string.tab_browser)),
        TabSpec(Tab.PLAYER, if (casting) Icons.Rounded.CastConnected else Icons.Rounded.Cast, stringResource(R.string.tab_player), dot = casting),
    )
    val index = tabs.indexOfFirst { it.tab == tab }.coerceAtLeast(0)
    // Économie d'énergie : pas de flou (recalculé à chaque image), un fond plein à la place.
    val saving by com.rollandostudio.universaltvcast.data.Power.saving.collectAsState()

    // « Verre fumé » : pilule translucide avec liseré et reflet clair ; le contenu défile dessous.
    Box(
        Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        val shape = RoundedCornerShape(50)
        Surface(
            shape = shape,
            // Verre fumé vert : ce qui défile dessous est flouté puis teinté, pour que les onglets restent lisibles.
            color = androidx.compose.ui.graphics.Color.Transparent,
            border = BorderStroke(1.dp, androidx.compose.ui.graphics.Color.White.copy(alpha = 0.12f)),
            modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth()
                .clip(shape)
                .then(if (saving) Modifier.background(c.surfaceContainerHigh.copy(alpha = 0.97f)) else Modifier.hazeEffect(haze, HazeStyle(
                    backgroundColor = c.background,
                    tint = HazeTint(androidx.compose.ui.graphics.Color(0xFF12241F).copy(alpha = 0.72f)),
                    blurRadius = 28.dp,
                    noiseFactor = 0.04f,
                    fallbackTint = HazeTint(androidx.compose.ui.graphics.Color(0xFF12241F).copy(alpha = 0.95f)),
                )))
                .background(androidx.compose.ui.graphics.Brush.verticalGradient(
                    listOf(androidx.compose.ui.graphics.Color.White.copy(alpha = 0.07f), androidx.compose.ui.graphics.Color.Transparent)), shape),
        ) {
            BoxWithConstraints(Modifier.padding(5.dp).height(58.dp)) {
                val slot = maxWidth / tabs.size
                val x by animateDpAsState(slot * index, tween(220, easing = FastOutSlowInEasing), label = "indicateur")
                // Indicateur qui glisse sous l'onglet choisi.
                Box(
                    Modifier
                        .offset { androidx.compose.ui.unit.IntOffset(x.roundToPx(), 0) }
                        .width(slot)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(50))
                        .background(c.primary)
                )
                Row(Modifier.fillMaxWidth().fillMaxHeight().selectableGroup()) {
                    tabs.forEach { t ->
                        val selected = t.tab == tab
                        val fg by animateColorAsState(
                            if (selected) c.onPrimary else c.onSurfaceVariant, tween(220), label = "couleur",
                        )
                        Column(
                            Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(50))
                                .selectable(selected = selected, role = Role.Tab) { onSelect(t.tab) },
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Box {
                                Icon(t.icon, contentDescription = null, tint = fg, modifier = Modifier.size(22.dp))
                                if (t.dot && !selected) {
                                    Box(Modifier.size(7.dp).offset(x = 17.dp, y = (-1).dp).clip(CircleShape).background(c.primary))
                                }
                            }
                            Spacer(Modifier.height(2.dp))
                            Text(
                                t.label, color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.padding(horizontal = 6.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}
