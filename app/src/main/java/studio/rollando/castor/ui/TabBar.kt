package studio.rollando.castor.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import studio.rollando.castor.R

/**
 * Barre d'onglets en pilule flottante : l'onglet actif s'ouvre en capsule ambrée avec son nom,
 * les autres ne montrent que leur icône (leur nom reste lu par les lecteurs d'écran).
 */
@Composable
fun PillTabBar(tab: Tab, casting: Boolean, onSelect: (Tab) -> Unit) {
    val c = MaterialTheme.colorScheme
    Box(
        Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = RoundedCornerShape(50),
            color = c.surfaceContainerHigh,
            shadowElevation = 10.dp,
            border = BorderStroke(1.dp, c.outlineVariant.copy(alpha = 0.5f)),
        ) {
            Row(Modifier.padding(6.dp).selectableGroup(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                PillItem(tab == Tab.HOME, Icons.Rounded.Home, stringResource(R.string.tab_home)) { onSelect(Tab.HOME) }
                PillItem(tab == Tab.BROWSER, Icons.Rounded.Public, stringResource(R.string.tab_browser)) { onSelect(Tab.BROWSER) }
                PillItem(
                    tab == Tab.PLAYER, if (casting) Icons.Rounded.CastConnected else Icons.Rounded.Cast,
                    stringResource(R.string.tab_player), dot = casting,
                ) { onSelect(Tab.PLAYER) }
            }
        }
    }
}

@Composable
private fun PillItem(selected: Boolean, icon: ImageVector, label: String, dot: Boolean = false, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val bg by animateColorAsState(if (selected) c.primary else Color.Transparent, label = "fond")
    val fg by animateColorAsState(if (selected) c.onPrimary else c.onSurfaceVariant, label = "texte")
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(bg)
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .height(52.dp)
            .animateContentSize()
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            Icon(icon, contentDescription = if (selected) null else label, tint = fg)
            if (dot && !selected) {
                Box(
                    Modifier.size(8.dp).offset(x = 16.dp, y = (-2).dp).clip(CircleShape).background(c.primary)
                )
            }
        }
        AnimatedVisibility(selected, enter = fadeIn() + expandHorizontally(), exit = fadeOut() + shrinkHorizontally()) {
            Row {
                Spacer(Modifier.width(8.dp))
                Text(label, style = MaterialTheme.typography.labelLarge, color = fg, maxLines = 1)
            }
        }
    }
}
