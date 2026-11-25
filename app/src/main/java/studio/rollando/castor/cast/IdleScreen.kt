package studio.rollando.castor.cast

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import androidx.core.content.res.ResourcesCompat
import studio.rollando.castor.R
import java.io.ByteArrayOutputStream

/**
 * Écran d'attente affiché sur la télé entre deux vidéos, à la fin d'une vidéo ou quand on arrête :
 * la télé reste sur le lecteur au lieu de repartir sur sa dernière source (HDMI…), et la vidéo suivante
 * démarre plus vite. Image 1920 × 1080 dessinée sur le téléphone, aux couleurs de l'appli.
 */
object IdleScreen {
    private var cached: Pair<String, ByteArray>? = null // (texte affiché, image)

    fun jpeg(ctx: Context): ByteArray {
        val line = ctx.getString(R.string.tv_idle_line)
        cached?.let { (l, b) -> if (l == line) return b }
        val w = 1920
        val h = 1080
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(0xFF0F1115.toInt())
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        // Lueurs ambrée (haut gauche) et turquoise (bas droite), comme la carte d'accueil.
        p.shader = RadialGradient(260f, 120f, 1100f, intArrayOf(0x66FFB86B, 0x00FFB86B), null, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        p.shader = RadialGradient(1700f, 1000f, 1000f, intArrayOf(0x5539A89A, 0x0039A89A), null, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        p.shader = null
        // Ondes de diffusion discrètes en bas à droite.
        p.style = Paint.Style.STROKE
        p.strokeWidth = 14f
        p.color = 0x14FFFFFF
        for (i in 1..4) {
            val r = 260f * i
            c.drawArc(RectF(w + 60f - r, h + 60f - r, w + 60f + r, h + 60f + r), 180f, 90f, false, p)
        }
        p.style = Paint.Style.FILL

        // Logo.
        val logo = ResourcesCompat.getDrawable(ctx.resources, R.drawable.ic_logo, null)?.mutate()
        val cx = w / 2f
        logo?.let {
            it.setTint(0xFFFFB86B.toInt())
            it.setBounds((cx - 90).toInt(), 250, (cx + 90).toInt(), 430)
            it.draw(c)
        }
        val display = runCatching { ResourcesCompat.getFont(ctx, R.font.inter_display_extrabold) }.getOrNull() ?: Typeface.DEFAULT_BOLD
        val regular = runCatching { ResourcesCompat.getFont(ctx, R.font.inter_regular) }.getOrNull() ?: Typeface.DEFAULT
        p.textAlign = Paint.Align.CENTER
        p.typeface = Typeface.create(display, Typeface.BOLD)
        p.textSize = 40f
        p.letterSpacing = 0.35f
        p.color = 0xFFFFB86B.toInt()
        c.drawText("UNIVERSAL", cx + 7f, 520f, p)
        p.letterSpacing = -0.02f
        p.textSize = 150f
        p.shader = LinearGradient(0f, 560f, 0f, 700f, 0xFFFFFFFF.toInt(), 0xFFECE7E1.toInt(), Shader.TileMode.CLAMP)
        c.drawText("TV Cast", cx, 680f, p)
        p.shader = null
        p.typeface = regular
        p.textSize = 44f
        p.letterSpacing = 0.01f
        p.color = 0xFFBDB6AD.toInt()
        c.drawText(line, cx, 800f, p)
        p.textSize = 30f
        p.color = Color.argb(110, 255, 255, 255)
        c.drawText("by Rollando Studio", cx, h - 70f, p)

        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 92, out)
        bmp.recycle()
        return out.toByteArray().also { cached = line to it }
    }
}
