package studio.rollando.castor.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import studio.rollando.castor.net.Http

/** Chargement d'images (affiches, fonds) : petit cache en mémoire, image réduite à la taille utile. */
object Images {
    private val cache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val missing = HashSet<String>()

    fun peek(url: String?): Bitmap? = url?.let { cache.get(it) }

    suspend fun load(url: String, maxSide: Int = 1280): Bitmap? = withContext(Dispatchers.IO) {
        cache.get(url)?.let { return@withContext it }
        if (synchronized(missing) { url in missing }) return@withContext null
        val bmp = runCatching {
            val bytes = Http.fetch(url, emptyMap(), connectTimeout = 8000).bytes
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
        }.getOrNull()
        if (bmp == null) synchronized(missing) { missing += url } else cache.put(url, bmp)
        bmp
    }
}

/** Vignette d'une photo ou vidéo du téléphone. */
suspend fun loadLocalThumb(ctx: android.content.Context, uri: android.net.Uri, side: Int): Bitmap? = withContext(Dispatchers.IO) {
    runCatching {
        if (android.os.Build.VERSION.SDK_INT >= 29) ctx.contentResolver.loadThumbnail(uri, android.util.Size(side, side), null)
        else {
            val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, b) }
            var k = 1
            while (maxOf(b.outWidth, b.outHeight) / (k * 2) >= side) k *= 2
            ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = k }) }
        }
    }.getOrNull()
}

@Composable
fun LocalImage(uri: String, modifier: Modifier = Modifier, side: Int = 512, contentScale: ContentScale = ContentScale.Crop) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val img: ImageBitmap? by produceState<ImageBitmap?>(null, uri) {
        value = loadLocalThumb(ctx, android.net.Uri.parse(uri), side)?.asImageBitmap()
    }
    AnimatedVisibility(img != null, modifier = modifier, enter = fadeIn(tween(250))) {
        img?.let { Image(it, contentDescription = null, contentScale = contentScale) }
    }
}

/** Image distante qui apparaît en fondu une fois chargée ; rien n'est affiché si elle est introuvable. */
@Composable
fun RemoteImage(
    url: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    alignment: Alignment = Alignment.Center,
    maxSide: Int = 1280,
    onState: ((Boolean) -> Unit)? = null,
) {
    val img: ImageBitmap? by produceState<ImageBitmap?>(null, url) {
        value = url?.let { Images.load(it, maxSide)?.asImageBitmap() }
        onState?.invoke(value != null)
    }
    AnimatedVisibility(img != null, modifier = modifier, enter = fadeIn(tween(350))) {
        img?.let { Image(it, contentDescription = null, contentScale = contentScale, alignment = alignment) }
    }
}
