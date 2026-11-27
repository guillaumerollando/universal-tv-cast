package com.rollandostudio.universaltvcast.ui

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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.rollandostudio.universaltvcast.net.Http

/**
 * Chargement d'images (logos, affiches, fonds) : réduites à la taille utile, gardées en mémoire (24 Mo au plus) et
 * sur le téléphone (dossier cache, 30 Mo au plus) : un logo déjà vu s'affiche aussitôt, même après un redémarrage.
 */
object Images {
    private val cache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val missing = HashSet<String>()
    @Volatile private var dir: java.io.File? = null
    private const val DISK_MAX = 30L * 1024 * 1024

    fun init(ctx: android.content.Context) {
        if (dir != null) return
        dir = java.io.File(ctx.cacheDir, "images").apply { mkdirs() }
        // Ménage au démarrage : on garde les plus récemment utilisées.
        Thread { trim() }.start()
    }

    private fun trim() {
        val files = dir?.listFiles()?.sortedByDescending { it.lastModified() } ?: return
        var total = 0L
        files.forEach { f -> total += f.length(); if (total > DISK_MAX) f.delete() }
    }

    private fun fileFor(url: String, maxSide: Int): java.io.File? = dir?.let { d ->
        val h = java.security.MessageDigest.getInstance("SHA-1").digest("$maxSide|$url".toByteArray())
        java.io.File(d, h.joinToString("") { "%02x".format(it) } + ".webp")
    }

    fun peek(url: String?): Bitmap? = url?.let { cache.get(it) }

    suspend fun load(url: String, maxSide: Int = 1280): Bitmap? = withContext(Dispatchers.IO) {
        cache.get(url)?.let { return@withContext it }
        if (synchronized(missing) { url in missing }) return@withContext null
        val file = fileFor(url, maxSide)
        if (file != null && file.exists()) {
            BitmapFactory.decodeFile(file.path)?.let { b ->
                file.setLastModified(System.currentTimeMillis())
                cache.put(url, b)
                return@withContext b
            }
        }
        val bmp = runCatching {
            val f = Http.fetch(url, emptyMap(), connectTimeout = 8000)
            // Image supprimée chez Imgur : il renvoie une image « n'existe plus » à la place ; on la traite comme absente.
            if (f.finalUrl.contains("imgur.com/removed")) return@runCatching null
            val bytes = f.bytes
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
            val raw = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            // Réduite à la taille utile (un logo de 2000 px n'occupe pas 16 Mo pour une tuile de 96 dp).
            val big = maxOf(raw.width, raw.height)
            if (big > maxSide) Bitmap.createScaledBitmap(raw, raw.width * maxSide / big, raw.height * maxSide / big, true) else raw
        }.getOrNull()
        if (bmp == null) synchronized(missing) { missing += url } else {
            cache.put(url, bmp)
            if (file != null) runCatching {
                @Suppress("DEPRECATION")
                // Logos : sans perte (traits nets) ; affiches et fonds : compressées.
                val fmt = when {
                    android.os.Build.VERSION.SDK_INT < 30 -> Bitmap.CompressFormat.WEBP
                    maxSide <= 400 -> Bitmap.CompressFormat.WEBP_LOSSLESS
                    else -> Bitmap.CompressFormat.WEBP_LOSSY
                }
                val tmp = java.io.File(file.path + ".tmp")
                tmp.outputStream().use { bmp.compress(fmt, if (maxSide <= 400) 100 else 85, it) }
                tmp.renameTo(file)
            }
        }
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
    // Déjà en mémoire : affichée tout de suite, sans fondu.
    val img: ImageBitmap? by produceState(remember(url) { Images.peek(url)?.asImageBitmap() }, url) {
        value = url?.let { Images.load(it, maxSide)?.asImageBitmap() }
        onState?.invoke(value != null)
    }
    AnimatedVisibility(img != null, modifier = modifier, enter = fadeIn(tween(350))) {
        img?.let { Image(it, contentDescription = null, contentScale = contentScale, alignment = alignment) }
    }
}
