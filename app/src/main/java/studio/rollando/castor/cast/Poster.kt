package studio.rollando.castor.cast

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import androidx.core.content.ContextCompat
import studio.rollando.castor.R
import java.io.ByteArrayOutputStream

/** Petite affiche 640×360 envoyée à la télé : titre de la vidéo sur le fond aux couleurs de Castor. */
object Poster {
    fun jpeg(ctx: Context, title: String): ByteArray {
        val w = 640
        val h = 360
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), Paint().apply {
            shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(),
                intArrayOf(0xFF5B3A12.toInt(), 0xFF1A1D23.toInt(), 0xFF1E4A44.toInt()), null, Shader.TileMode.CLAMP)
        })
        ContextCompat.getDrawable(ctx, R.drawable.ic_logo)?.mutate()?.apply {
            setTint(0xFFFFB86B.toInt())
            setBounds(48, 48, 48 + 72, 48 + 72)
            draw(c)
        }
        val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFDDBA.toInt()
            textSize = 40f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val layout = StaticLayout.Builder.obtain(title, 0, title.length, tp, w - 96)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setMaxLines(3)
            .setEllipsize(TextUtils.TruncateAt.END)
            .build()
        c.save()
        c.translate(48f, h - 56f - layout.height)
        layout.draw(c)
        c.restore()
        c.drawText("Castor", 136f, 96f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFECE7E1.toInt(); textSize = 30f; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        })
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 88, out)
        bmp.recycle()
        return out.toByteArray()
    }
}
