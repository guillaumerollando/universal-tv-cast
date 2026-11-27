package com.rollandostudio.universaltvcast.cast

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

/** Prépare une photo pour la télé : JPEG d'au plus 1920 px, bien orientée (HEIC et PNG convertis). */
object Photos {
    private const val MAX = 1920

    fun prepare(ctx: Context, uri: Uri): ByteArray = jpeg(decode(ctx, uri))

    /** La photo prête à afficher (1920 px au plus, bien orientée), en 16 bits pour ménager la mémoire. */
    fun decode(ctx: Context, uri: Uri): Bitmap =
        if (Build.VERSION.SDK_INT >= 28) decodeModern(ctx, uri) else decodeLegacy(ctx, uri)

    fun jpeg(bmp: Bitmap): ByteArray {
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 90, out)
        return out.toByteArray()
    }

    @androidx.annotation.RequiresApi(28)
    private fun decodeModern(ctx: Context, uri: Uri): Bitmap {
        val src = ImageDecoder.createSource(ctx.contentResolver, uri)
        // ImageDecoder applique l'orientation EXIF et lit le HEIC.
        return ImageDecoder.decodeBitmap(src) { dec, info, _ ->
            val w = info.size.width
            val h = info.size.height
            val k = MAX.toFloat() / max(w, h)
            if (k < 1f) dec.setTargetSize((w * k).roundToInt(), (h * k).roundToInt())
            dec.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            dec.memorySizePolicy = ImageDecoder.MEMORY_POLICY_LOW_RAM
        }
    }

    private fun decodeLegacy(ctx: Context, uri: Uri): Bitmap {
        val cr = ctx.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX) sample *= 2
        val bmp = cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.RGB_565 }) }
            ?: throw IllegalArgumentException("photo illisible")
        val rot: Int = runCatching {
            cr.openInputStream(uri)?.use { ins ->
                when (ExifInterface(ins).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270
                    else -> 0
                }
            }
        }.getOrNull() ?: 0
        if (rot == 0) return bmp
        val m = Matrix().apply { postRotate(rot.toFloat()) }
        return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true).also { if (it !== bmp) bmp.recycle() }
    }
}
