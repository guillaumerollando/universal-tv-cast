package studio.rollando.tvcast.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Les quatre mouvements d'un saut (flèche qui pivote, rebond, onde, « +10 » qui monte), relancés à chaque appui. */
private class SkipFx {
    val spin = Animatable(0f)
    val press = Animatable(1f)
    val ring = Animatable(1f)
    val label = Animatable(1f)

    fun fire(scope: CoroutineScope, sign: Float) {
        scope.launch {
            spin.snapTo(0f)
            spin.animateTo(sign, tween(140, easing = FastOutSlowInEasing))
            spin.animateTo(0f, spring(dampingRatio = 0.4f, stiffness = Spring.StiffnessMediumLow))
        }
        scope.launch { press.snapTo(0.84f); press.animateTo(1f, spring(dampingRatio = 0.35f, stiffness = Spring.StiffnessMedium)) }
        scope.launch { ring.snapTo(0f); ring.animateTo(1f, tween(480, easing = LinearOutSlowInEasing)) }
        scope.launch { label.snapTo(0f); label.animateTo(1f, tween(950, easing = LinearOutSlowInEasing)) }
    }
}

/** Le bouton (ou l'icône) avec son rebond et son onde, puis le « +10 » qui en sort. */
@Composable
private fun SkipFxBox(fx: SkipFx, forward: Boolean, total: Int, accent: Color, content: @Composable (iconModifier: Modifier) -> Unit) {
    val sign = if (forward) 1f else -1f
    val density = LocalDensity.current
    Box(contentAlignment = Alignment.Center) {
        // L'onde, derrière le bouton (dessinée hors de ses bords).
        Box(
            Modifier
                .graphicsLayer { scaleX = fx.press.value; scaleY = fx.press.value }
                .drawBehind {
                    val p = fx.ring.value
                    if (p < 1f) {
                        val r = size.minDimension / 2f
                        drawCircle(accent.copy(alpha = 0.55f * (1f - p)), radius = r * (1f + 0.75f * p), center = center,
                            style = Stroke(width = (3.dp.toPx() * (1f - p)).coerceAtLeast(0.5f)))
                        drawCircle(accent.copy(alpha = 0.18f * (1f - p)), radius = r * (1f + 0.35f * p), center = center)
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            content(Modifier.graphicsLayer { rotationZ = 38f * fx.spin.value })
        }
        // « +10 » qui sort du bouton et monte en s'effaçant.
        val p = fx.label.value
        if (p < 1f && total > 0) {
            Text(
                (if (forward) "+" else "−") + total,
                color = accent,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.ExtraBold,
                    shadow = Shadow(Color.Black.copy(alpha = 0.5f), Offset(0f, 2f), 6f),
                ),
                modifier = Modifier
                    .wrapContentSize(unbounded = true)
                    .graphicsLayer {
                        val rise = with(density) { (34.dp + 30.dp * p).toPx() }
                        translationY = -rise
                        translationX = with(density) { (10.dp * sign * p).toPx() }
                        val pop = if (p < 0.15f) 0.6f + p / 0.15f * 0.5f else 1.1f - (p - 0.15f) * 0.2f
                        scaleX = pop; scaleY = pop
                        alpha = when {
                            p < 0.1f -> p / 0.1f
                            p > 0.55f -> 1f - (p - 0.55f) / 0.45f
                            else -> 1f
                        }
                    },
            )
        }
    }
}

/**
 * Bouton ±10 s animé, comme sur YouTube : le bouton s'enfonce et rebondit, sa flèche pivote dans le sens du saut,
 * une onde part du bouton et « +10 » sort vers le haut en s'effaçant. Les appuis rapprochés s'additionnent (« +20 »…).
 * [button] dessine le bouton lui-même (avec [iconModifier] sur son icône) et appelle le clic reçu.
 */
@Composable
fun AnimatedSkip(
    forward: Boolean,
    onSkip: () -> Unit,
    accent: Color = MaterialTheme.colorScheme.primary,
    button: @Composable (iconModifier: Modifier, onClick: () -> Unit) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val fx = remember { SkipFx() }
    var total by remember { mutableIntStateOf(0) }
    var lastTap by remember { mutableLongStateOf(0L) }
    val click: () -> Unit = {
        onSkip()
        val now = System.currentTimeMillis()
        total = if (now - lastTap < 900) total + 10 else 10
        lastTap = now
        fx.fire(scope, if (forward) 1f else -1f)
    }
    SkipFxBox(fx, forward, total, accent) { iconMod -> button(iconMod, click) }
}

/**
 * Même animation, sans bouton à toucher : pour le double appui sur la vidéo. Rejouée à chaque nouveau [tap]
 * (le geste compte lui-même le [total]).
 */
@Composable
fun SkipBurst(forward: Boolean, tap: Int, total: Int, accent: Color = Color.White, icon: @Composable (iconModifier: Modifier) -> Unit) {
    val scope = rememberCoroutineScope()
    val fx = remember { SkipFx() }
    LaunchedEffect(tap) { fx.fire(scope, if (forward) 1f else -1f) }
    SkipFxBox(fx, forward, total, accent, icon)
}
