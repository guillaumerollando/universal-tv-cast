package studio.rollando.castor.ui

import android.provider.Settings
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Vrai si l'utilisateur a coupé les animations dans Android (accessibilité). */
@Composable
private fun animationsOff(): Boolean {
    val ctx = LocalContext.current
    return remember {
        runCatching { Settings.Global.getFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE) == 0f }
            .getOrDefault(false)
    }
}

/** Ondes qui partent de la télé pendant la lecture : le signal envoyé par le téléphone. */
@Composable
fun CastWaves(color: Color, modifier: Modifier = Modifier, size: Dp = 170.dp) {
    val still = animationsOff()
    val t = rememberInfiniteTransition(label = "ondes")
    val p by t.animateFloat(0f, 1f, infiniteRepeatable(tween(2400, easing = LinearEasing)), label = "phase")
    Canvas(modifier.size(size)) {
        val maxR = this.size.minDimension / 2
        for (i in 0 until 3) {
            val f = if (still) (i + 1) / 3.5f else (p + i / 3f) % 1f
            drawCircle(
                color = color.copy(alpha = (1f - f) * 0.5f),
                radius = maxR * (0.38f + 0.62f * f),
                style = Stroke(width = 2.5.dp.toPx()),
            )
        }
    }
}

/** Arcs de diffusion décoratifs, dans un coin de la carte d'accueil. */
fun Modifier.castArcs(color: Color): Modifier = drawBehind {
    val origin = Offset(size.width * 0.98f, size.height * 1.02f)
    for (i in 1..4) {
        val r = size.height * 0.28f * i
        drawArc(
            color = color.copy(alpha = 0.16f - i * 0.025f),
            startAngle = 180f, sweepAngle = 90f, useCenter = false,
            topLeft = Offset(origin.x - r, origin.y - r), size = Size(r * 2, r * 2),
            style = Stroke(width = 6.dp.toPx(), cap = StrokeCap.Round),
        )
    }
}
