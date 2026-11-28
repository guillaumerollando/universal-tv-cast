package com.rollandostudio.universaltvcast.ui

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
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.dp

/** Vrai si les animations décoratives doivent s'arrêter : coupées dans Android (accessibilité), ou économie d'énergie. */
@Composable
fun animationsOff(): Boolean {
    val ctx = LocalContext.current
    val system = remember {
        runCatching { Settings.Global.getFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE) == 0f }
            .getOrDefault(false)
    }
    val saving by com.rollandostudio.universaltvcast.data.Power.saving.collectAsState()
    return system || saving
}

/**
 * Ondes qui partent de la télé pendant la lecture : le signal envoyé par le téléphone. Elles débordent librement
 * de leur place (taille imposée, quel que soit le cadre) : c'est le cadre autour qui les coupe à ses bords.
 */
@Composable
fun CastWaves(color: Color, modifier: Modifier = Modifier, size: Dp = 170.dp, inner: Dp = 40.dp) {
    val still = animationsOff()
    val t = rememberInfiniteTransition(label = "ondes")
    val p by t.animateFloat(0f, 1f, infiniteRepeatable(tween(2600, easing = LinearEasing)), label = "phase")
    Canvas(modifier.requiredSize(size)) {
        val maxR = this.size.minDimension / 2
        val minR = inner.toPx() // les ondes partent du bord de ce qu'elles entourent, jamais de l'intérieur
        // Un halo doux au centre, puis trois anneaux qui s'éloignent en s'effaçant.
        drawCircle(
            Brush.radialGradient(listOf(color.copy(alpha = color.alpha * 0.35f), Color.Transparent), center, minR * 1.9f),
            radius = minR * 1.9f,
        )
        for (i in 0 until 3) {
            val f = if (still) (i + 1) / 3.5f else (p + i / 3f) % 1f
            val fade = (1f - f) * (1f - f)
            drawCircle(
                color = color.copy(alpha = color.alpha * fade),
                radius = minR + (maxR - minR) * f,
                style = Stroke(width = (3.dp.toPx() * (1f - f * 0.5f))),
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


/** Petites barres d'égaliseur qui dansent pendant la lecture (immobiles en pause ou sans animations). */
@Composable
fun EqualizerBars(active: Boolean, color: Color, modifier: Modifier = Modifier) {
    val still = animationsOff() || !active
    val t = rememberInfiniteTransition(label = "egaliseur")
    val periods = listOf(520, 680, 450, 610)
    val heights = periods.mapIndexed { i, ms ->
        t.animateFloat(0.25f, 1f, infiniteRepeatable(tween(ms, delayMillis = i * 90), androidx.compose.animation.core.RepeatMode.Reverse), label = "barre$i")
    }
    Canvas(modifier.size(width = 18.dp, height = 14.dp)) {
        val n = heights.size
        val gap = 2.dp.toPx()
        val w = (this.size.width - gap * (n - 1)) / n
        heights.forEachIndexed { i, h ->
            val v = if (still) listOf(0.5f, 0.8f, 0.35f, 0.65f)[i] else h.value
            val bh = this.size.height * v
            drawRoundRect(
                color = color,
                topLeft = Offset(i * (w + gap), this.size.height - bh),
                size = Size(w, bh),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(w / 2, w / 2),
            )
        }
    }
}
