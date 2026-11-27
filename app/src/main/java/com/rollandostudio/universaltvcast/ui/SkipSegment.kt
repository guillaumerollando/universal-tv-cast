package com.rollandostudio.universaltvcast.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.rollandostudio.universaltvcast.R
import com.rollandostudio.universaltvcast.cast.CastState
import com.rollandostudio.universaltvcast.cast.Skips
import com.rollandostudio.universaltvcast.cast.TvCast

/** Épisode suivant connu (lien repéré sur le site, déduit de l'adresse, ou fichier suivant du dossier). */
internal fun nextEpisodeOf(s: CastState): String? {
    val m = s.media ?: return null
    if (s.live || m.channel != null) return null
    return m.nextUrl ?: m.pageUrl?.let { com.rollandostudio.universaltvcast.browser.Episodes.find(it)?.next?.takeIf { n -> n.isNotEmpty() } }
}

/**
 * « Passer l'intro », « Passer le récap », « Passer le générique » (ou « Épisode suivant » pendant le générique de
 * fin, quand on le connaît) : visible seulement pendant le passage, comme sur les plateformes. Sur le téléphone comme
 * pour la télé (on déplace la lecture là où elle a lieu).
 */
@Composable
fun SkipSegmentButton(s: CastState, modifier: Modifier = Modifier) {
    val pos = s.position
    val dur = s.duration
    val seg = s.skips.firstOrNull { g ->
        val end = g.end ?: dur
        pos >= g.start - 0.5 && end > 0 && pos < end - 1.5
    }
    val next = nextEpisodeOf(s)
    // Générique de fin d'un film (rien après) : pas de bouton, il n'y a rien à sauter vers.
    val show = seg != null && !(seg.kind == Skips.Kind.CREDITS && next == null && (seg.end == null || seg.end >= dur - 5))
    AnimatedVisibility(show && !s.live, modifier = modifier, enter = fadeIn() + slideInHorizontally { it / 3 }, exit = fadeOut()) {
        if (seg == null) return@AnimatedVisibility
        val toNext = seg.kind == Skips.Kind.CREDITS && next != null
        val label = when {
            toNext -> R.string.next_episode
            seg.kind == Skips.Kind.INTRO -> R.string.skip_intro
            seg.kind == Skips.Kind.RECAP -> R.string.skip_recap
            else -> R.string.skip_credits
        }
        Button(
            onClick = {
                if (toNext) Nav.nextEpisode.value = next
                else TvCast.seekTo(seg.end ?: dur, debounceMs = 0)
            },
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
        ) {
            Icon(if (toNext) Icons.Rounded.SkipNext else Icons.Rounded.FastForward, null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(label))
        }
    }
}
