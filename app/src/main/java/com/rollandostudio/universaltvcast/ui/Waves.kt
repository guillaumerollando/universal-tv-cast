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
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.clipPath
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

/** Réglage des animations de diffusion : immobiles (animations coupées dans Android), lentes (économie d'énergie). */
@Composable
private fun castMotion(): Pair<Boolean, Boolean> {
    val ctx = LocalContext.current
    val still = remember {
        runCatching { Settings.Global.getFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE) == 0f }.getOrDefault(false)
    }
    val saving by com.rollandostudio.universaltvcast.data.Power.saving.collectAsState()
    return still to saving
}

/**
 * Halo qui respire, derrière le logo d'une chaîne ou la télé du cadre par défaut, pendant la lecture sur la télé :
 * une lueur douce qui s'élargit et se resserre lentement. Taille imposée ; le cadre autour la coupe à ses bords.
 */
@Composable
fun CastHalo(color: Color, modifier: Modifier = Modifier, size: Dp = 260.dp) {
    val (still, saving) = castMotion()
    val t = rememberInfiniteTransition(label = "halo")
    val k by t.animateFloat(0f, 1f, infiniteRepeatable(tween(if (saving) 2600 else 1800, easing = androidx.compose.animation.core.FastOutSlowInEasing),
        androidx.compose.animation.core.RepeatMode.Reverse), label = "souffle")
    Canvas(modifier.requiredSize(size)) {
        val b = if (still) 0.6f else k
        val r = this.size.minDimension / 2 * (0.82f + 0.26f * b)
        val a = 0.55f + 0.45f * b
        drawCircle(
            Brush.radialGradient(
                0f to color.copy(alpha = color.alpha * 0.30f * a),
                0.45f to color.copy(alpha = color.alpha * 0.10f * a),
                0.72f to Color.Transparent,
                center = center, radius = r,
            ),
            radius = r,
        )
    }
}

/**
 * Liseré de lumière qui fait le tour du cadre pendant la lecture sur la télé (chaîne, film, vidéo) : un trait fin
 * dont un segment s'allume et tourne lentement. À poser par-dessus le cadre, à sa taille ([corner] : ses coins).
 */
@Composable
fun CastRim(color: Color, corner: Dp, modifier: Modifier = Modifier) {
    val (still, saving) = castMotion()
    val t = rememberInfiniteTransition(label = "liseré")
    val turn by t.animateFloat(0f, 360f, infiniteRepeatable(tween(if (saving) 8500 else 5500, easing = LinearEasing)), label = "tour")
    Canvas(modifier) {
        val w = size.width; val h = size.height
        val sw = 1.5.dp.toPx()
        val r = corner.toPx()
        val ring = androidx.compose.ui.graphics.Path().apply {
            addRoundRect(androidx.compose.ui.geometry.RoundRect(0f, 0f, w, h, androidx.compose.ui.geometry.CornerRadius(r)))
            addRoundRect(androidx.compose.ui.geometry.RoundRect(sw, sw, w - sw, h - sw, androidx.compose.ui.geometry.CornerRadius((r - sw).coerceAtLeast(0f))))
            fillType = androidx.compose.ui.graphics.PathFillType.EvenOdd
        }
        clipPath(ring) {
            rotate(if (still) 300f else turn) {
                drawCircle(
                    Brush.sweepGradient(0f to Color.Transparent, 0.69f to Color.Transparent, 0.89f to color, 1f to Color.Transparent, center = center),
                    radius = kotlin.math.hypot(w, h), center = center,
                )
            }
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
