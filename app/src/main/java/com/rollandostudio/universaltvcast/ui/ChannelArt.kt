package com.rollandostudio.universaltvcast.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.rollandostudio.universaltvcast.data.Channels
import com.rollandostudio.universaltvcast.data.IptvStore

/** Un seul format de tuile de chaîne dans toute l'appli : accueil, mosaïque, récentes, zapping. */
object ChannelTiles {
    val width = 96.dp
    val gap = 4.dp
    val corner = 12.dp
    val logoPad = 9.dp
    /** Marge sur les côtés d'une rangée ou de la mosaïque. */
    val edge = 10.dp
}

/** Fond choisi pour chaque logo (couleur unie), repris par la notification. */
object ChannelArtColors {
    val background = java.util.concurrent.ConcurrentHashMap<String, Int>()
}

/** Comment poser un logo : sur sa propre couleur de fond (logo « en carré »), ou sur un fond qui le fait ressortir. */
private data class LogoLook(val bitmap: Bitmap, val background: Color?, val ink: Float)

object Logos {
    /** Part du dessin qui est sombre (0 à 1) : sur un fond foncé, ces parties disparaîtraient (le « 5 » de France 5). */
    fun darkShare(b: Bitmap): Float {
        var dark = 0; var n = 0
        val step = maxOf(1, minOf(b.width, b.height) / 30)
        for (y in 0 until b.height step step) for (x in 0 until b.width step step) {
            val p = b.getPixel(x, y)
            if (android.graphics.Color.alpha(p) < 128) continue
            n++
            val l = (0.2126 * android.graphics.Color.red(p) + 0.7152 * android.graphics.Color.green(p) + 0.0722 * android.graphics.Color.blue(p)) / 255
            if (l < 0.22) dark++
        }
        return if (n == 0) 0f else dark.toFloat() / n
    }
}

private object LogoLooks {
    /** Logos prêts à poser, en mémoire (20 Mo au plus) ; les fichiers restent sur le téléphone (voir Images). */
    private val cache = object : android.util.LruCache<String, LogoLook>(20 * 1024 * 1024) {
        override fun sizeOf(key: String, value: LogoLook) = value.bitmap.byteCount
    }

    /** Taille gardée : assez pour une tuile de 96 dp sur un écran très fin, pas plus. */
    private const val SIDE = 256

    fun peek(url: String?): LogoLook? = url?.let { cache.get(it) }

    suspend fun get(url: String): LogoLook? {
        cache.get(url)?.let { return it }
        val bmp = Images.load(url, SIDE) ?: return null
        val look = withContext(Dispatchers.Default) { analyse(bmp) }
        cache.put(url, look)
        return look
    }

    /** Bords opaques et d'une seule couleur : c'est le fond du logo, on le prolonge. Et la clarté moyenne du dessin. */
    /** Retire les marges transparentes autour du dessin (sinon un logo « en carré » flotte au milieu de vide). */
    private fun trim(b: Bitmap): Bitmap {
        if (!b.hasAlpha()) return b
        var l = b.width; var t = b.height; var r = -1; var bo = -1
        for (y in 0 until b.height) for (x in 0 until b.width) {
            if (android.graphics.Color.alpha(b.getPixel(x, y)) > 24) {
                if (x < l) l = x; if (x > r) r = x; if (y < t) t = y; if (y > bo) bo = y
            }
        }
        if (r < l || bo < t) return b
        if (l == 0 && t == 0 && r == b.width - 1 && bo == b.height - 1) return b
        return Bitmap.createBitmap(b, l, t, r - l + 1, bo - t + 1)
    }

    private fun analyse(src: Bitmap): LogoLook {
        val b = trim(src)
        val w = b.width
        val h = b.height
        val edge = ArrayList<Int>()
        val step = maxOf(1, minOf(w, h) / 40)
        for (x in 0 until w step step) { edge += b.getPixel(x, 0); edge += b.getPixel(x, h - 1) }
        for (y in 0 until h step step) { edge += b.getPixel(0, y); edge += b.getPixel(w - 1, y) }
        val opaque = edge.filter { android.graphics.Color.alpha(it) > 220 }
        var bg: Color? = null
        if (opaque.size >= edge.size * 0.9) {
            // La couleur qui domine sur le bord (quelques pixels du dessin peuvent le toucher).
            val groups = opaque.groupBy { (android.graphics.Color.red(it) / 24) * 10000 + (android.graphics.Color.green(it) / 24) * 100 + android.graphics.Color.blue(it) / 24 }
            val top = groups.values.maxByOrNull { it.size }
            if (top != null && top.size >= edge.size * 0.7) bg = Color(
                top.map { android.graphics.Color.red(it) }.average().toInt(),
                top.map { android.graphics.Color.green(it) }.average().toInt(),
                top.map { android.graphics.Color.blue(it) }.average().toInt(),
            )
        }
        // Clarté du dessin (pixels visibles) : pour choisir un fond contrasté quand le logo est détouré.
        var sum = 0.0
        var n = 0
        val s2 = maxOf(1, minOf(w, h) / 30)
        for (y in 0 until h step s2) for (x in 0 until w step s2) {
            val p = b.getPixel(x, y)
            if (android.graphics.Color.alpha(p) > 128) { sum += Color(p).luminance(); n++ }
        }
        return LogoLook(b, bg, if (n == 0) 0.5f else (sum / n).toFloat())
    }
}

/**
 * Visuel d'une chaîne, le même partout (accueil, listes, lecteur) : son logo s'il y en a un (fourni, ou trouvé dans
 * une liste importée), posé sur un fond assorti ; sinon son nom sur sa couleur.
 */
@Composable
fun ChannelArt(
    name: String,
    logo: String?,
    streamUrl: String?,
    modifier: Modifier,
    textStyle: TextStyle,
    pad: Dp = 10.dp,
    label: String = name,
    /** Dessiné entre le fond et le logo (les ondes de diffusion), avec une encre lisible sur ce fond. */
    underlay: (@Composable (ink: Color) -> Unit)? = null,
) {
    // Le logo fourni ; s'il manque ou ne se charge pas, celui de la même chaîne dans une liste importée.
    // Logo déjà prêt : posé tout de suite (pas de nom qui s'affiche puis se remplace à chaque retour sur l'écran).
    val ready = androidx.compose.runtime.remember(logo) { logo?.let { u -> LogoLooks.peek(u)?.let { u to it } } }
    val found by produceState(ready, name, logo, streamUrl) {
        value = logo?.let { u -> LogoLooks.get(u)?.let { u to it } }
            ?: IptvStore.logoFor(name, streamUrl)?.takeIf { it != logo }?.let { u -> LogoLooks.get(u)?.let { u to it } }
    }
    val url = found?.first
    val look = found?.second
    val brand = Color(Channels.find(name)?.color ?: Channels.colorFor(name))
    val l = look
    val brandBrush = Brush.linearGradient(listOf(brand, brand.copy(alpha = 0.55f).compositeOver(Color.Black)))
    val bg: Brush = when {
        l == null -> brandBrush
        l.background != null -> Brush.linearGradient(listOf(l.background, l.background))
        // Logo détouré : la couleur de la chaîne s'il s'en détache, sinon un fond neutre clair ou sombre.
        kotlin.math.abs(l.ink - brand.luminance()) > 0.3f -> brandBrush
        l.ink < 0.35f -> Brush.linearGradient(listOf(Color(0xFFF4F2EF), Color(0xFFE4E1DC)))
        else -> Brush.linearGradient(listOf(Color(0xFF2A2D35), Color(0xFF1B1D22)))
    }
    if (l != null && url != null) {
        val solid = when {
            l.background != null -> l.background
            kotlin.math.abs(l.ink - brand.luminance()) > 0.3f -> brand
            l.ink < 0.35f -> Color(0xFFF4F2EF)
            else -> Color(0xFF23262D)
        }
        ChannelArtColors.background[url] = android.graphics.Color.argb(255, (solid.red * 255).toInt(), (solid.green * 255).toInt(), (solid.blue * 255).toInt())
    }
    Box(modifier.background(bg), contentAlignment = Alignment.Center) {
        if (underlay != null) {
            // Fond clair (logo sombre) : ondes sombres ; sinon claires.
            val lightBg = l != null && l.background == null && kotlin.math.abs(l.ink - brand.luminance()) <= 0.3f && l.ink < 0.35f ||
                l?.background?.let { it.luminance() > 0.55f } == true
            underlay(if (lightBg) Color.Black else Color.White)
        }
        if (l == null) Text(
            label, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
            style = textStyle, modifier = Modifier.padding(horizontal = pad),
        ) else Image(
            l.bitmap.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize().padding(if (l.background != null) pad / 2 else pad),
        )
    }
}
