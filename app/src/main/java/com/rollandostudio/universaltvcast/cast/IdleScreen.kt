package com.rollandostudio.universaltvcast.cast

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
import com.rollandostudio.universaltvcast.R
import java.io.ByteArrayOutputStream

/**
 * Écran d'attente affiché sur la télé entre deux vidéos, à la fin d'une vidéo ou quand on arrête :
 * la télé reste sur le lecteur au lieu de repartir sur sa dernière source (HDMI…), et la vidéo suivante
 * démarre plus vite. Image 1920 × 1080 dessinée sur le téléphone, aux couleurs de l'appli.
 */
object IdleScreen {
    private var cached: Pair<String, ByteArray>? = null // (ce qui est affiché, image)

    /**
     * L'écran d'attente, selon le style choisi (pack soutien) : classique, photo ou horloge, avec le message perso.
     * L'horloge (et les photos, en mode « vivant ») changent chaque minute : [TvCast] renvoie alors l'image.
     */
    fun jpeg(ctx: Context): ByteArray {
        val style = com.rollandostudio.universaltvcast.data.Look.idleNow()
        val accent = com.rollandostudio.universaltvcast.data.Look.accentNow().argb.toInt()
        val custom = com.rollandostudio.universaltvcast.data.Look.messageNow()
        val photos = com.rollandostudio.universaltvcast.data.Look.photos.value
        val live = com.rollandostudio.universaltvcast.data.Look.liveNow()
        val minute = System.currentTimeMillis() / 60_000
        val photo = if (style == com.rollandostudio.universaltvcast.data.Look.Idle.PHOTO && photos.isNotEmpty())
            photos[(if (live) minute else 0L).mod(photos.size.toLong()).toInt()] else null
        val line = custom.ifEmpty { ctx.getString(R.string.tv_idle_line) }
        val key = "$style|$accent|$line|${photo?.name}|${if (style == com.rollandostudio.universaltvcast.data.Look.Idle.CLOCK || live) minute else 0}"
        cached?.let { (k, b) -> if (k == key) return b }
        val bytes = when {
            style == com.rollandostudio.universaltvcast.data.Look.Idle.CLOCK -> clock(ctx, accent, custom)
            photo != null -> photo(ctx, photo, accent, custom, live)
            else -> classic(ctx, line, accent)
        }
        cached = key to bytes
        return bytes
    }

    private fun fonts(ctx: Context): Pair<Typeface, Typeface> =
        (runCatching { ResourcesCompat.getFont(ctx, R.font.inter_display_extrabold) }.getOrNull() ?: Typeface.DEFAULT_BOLD) to
            (runCatching { ResourcesCompat.getFont(ctx, R.font.inter_regular) }.getOrNull() ?: Typeface.DEFAULT)

    private fun encode(bmp: Bitmap): ByteArray {
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 92, out)
        bmp.recycle()
        return out.toByteArray()
    }

    private fun alpha(c: Int, a: Int) = (c and 0x00FFFFFF) or (a shl 24)

    /** Horloge géante (heure du téléphone), la date, et le message ; lueur de la couleur d'accent. */
    private fun clock(ctx: Context, accent: Int, message: String): ByteArray {
        val w = 1920; val h = 1080
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(0xFF0B0C0F.toInt())
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = RadialGradient(w / 2f, h * 0.42f, 1100f, intArrayOf(alpha(accent, 0x55), alpha(accent, 0)), null, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        p.shader = null
        val (display, regular) = fonts(ctx)
        val now = java.util.Date()
        val time = android.text.format.DateFormat.getTimeFormat(ctx).format(now)
        val date = java.text.SimpleDateFormat("EEEE d MMMM", java.util.Locale.getDefault()).format(now)
            .replaceFirstChar { it.titlecase(java.util.Locale.getDefault()) }
        p.textAlign = Paint.Align.CENTER
        p.typeface = Typeface.create(display, Typeface.BOLD)
        p.textSize = 320f
        p.letterSpacing = -0.03f
        p.color = 0xFFFFFFFF.toInt()
        c.drawText(time, w / 2f, h * 0.52f, p)
        p.typeface = regular
        p.letterSpacing = 0.02f
        p.textSize = 56f
        p.color = accent
        c.drawText(date, w / 2f, h * 0.52f + 110f, p)
        if (message.isNotEmpty()) {
            p.textSize = 48f
            p.color = 0xFFBDB6AD.toInt()
            c.drawText(message, w / 2f, h * 0.52f + 210f, p)
        }
        signature(ctx, c, w, h, p)
        return encode(bmp)
    }

    /** Une photo du téléphone en plein écran, assombrie en bas pour le message (et l'heure en mode vivant). */
    private fun photo(ctx: Context, f: java.io.File, accent: Int, message: String, live: Boolean): ByteArray {
        val w = 1920; val h = 1080
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(0xFF000000.toInt())
        val src = android.graphics.BitmapFactory.decodeFile(f.path)
        if (src != null) {
            // Remplit l'écran (recadrage au centre).
            val scale = maxOf(w.toFloat() / src.width, h.toFloat() / src.height)
            val dw = src.width * scale; val dh = src.height * scale
            c.drawBitmap(src, null, RectF((w - dw) / 2, (h - dh) / 2, (w + dw) / 2, (h + dh) / 2), Paint(Paint.FILTER_BITMAP_FLAG))
            src.recycle()
        }
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = LinearGradient(0f, h * 0.55f, 0f, h.toFloat(), 0x00000000, 0xCC000000.toInt(), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        p.shader = null
        val (display, regular) = fonts(ctx)
        var y = h - 90f
        if (message.isNotEmpty()) {
            p.typeface = regular; p.textSize = 52f; p.color = 0xFFFFFFFF.toInt()
            c.drawText(message, 90f, y, p)
            y -= 80f
        }
        if (live) {
            p.typeface = Typeface.create(display, Typeface.BOLD); p.textSize = 150f; p.letterSpacing = -0.02f; p.color = 0xFFFFFFFF.toInt()
            c.drawText(android.text.format.DateFormat.getTimeFormat(ctx).format(java.util.Date()), 84f, y, p)
            p.letterSpacing = 0f
        }
        // Petite marque de l'appli, en bas à droite, à la couleur d'accent.
        val logo = ResourcesCompat.getDrawable(ctx.resources, R.drawable.ic_logo, null)?.mutate()
        logo?.let { it.setTint(accent); it.alpha = 210; it.setBounds(w - 150, h - 150, w - 80, h - 80); it.draw(c) }
        return encode(bmp)
    }

    /** « by Rollando Studio », petit et discret, en bas au centre. */
    private fun signature(ctx: Context, c: Canvas, w: Int, h: Int, p: Paint) {
        val studio = runCatching { android.graphics.BitmapFactory.decodeResource(ctx.resources, R.drawable.rollando_studio) }.getOrNull() ?: return
        val lw = 180f
        val lh = lw * studio.height / studio.width
        val sp = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { alpha = 150 }
        p.textSize = 30f; p.textAlign = Paint.Align.LEFT; p.color = Color.argb(150, 255, 255, 255); p.letterSpacing = 0f
        val byW = p.measureText("by"); val gap = 14f
        val left = w / 2f - (byW + gap + lw) / 2; val top = h - 60f - lh
        c.drawText("by", left, top + lh * 0.6f, p)
        c.drawBitmap(studio, null, RectF(left + byW + gap, top, left + byW + gap + lw, h - 60f), sp)
        studio.recycle()
    }

    /** L'écran d'origine (lueurs, logo, « Universal TV Cast »), à la couleur d'accent. */
    private fun classic(ctx: Context, line: String, accent: Int): ByteArray {
        val w = 1920
        val h = 1080
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(0xFF0F1115.toInt())
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        // Lueurs de la couleur d'accent (haut gauche) et turquoise (bas droite), comme la carte d'accueil.
        p.shader = RadialGradient(260f, 120f, 1100f, intArrayOf(alpha(accent, 0x66), alpha(accent, 0)), null, Shader.TileMode.CLAMP)
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
            it.setTint(accent)
            it.setBounds((cx - 90).toInt(), 250, (cx + 90).toInt(), 430)
            it.draw(c)
        }
        val display = runCatching { ResourcesCompat.getFont(ctx, R.font.inter_display_extrabold) }.getOrNull() ?: Typeface.DEFAULT_BOLD
        val regular = runCatching { ResourcesCompat.getFont(ctx, R.font.inter_regular) }.getOrNull() ?: Typeface.DEFAULT
        p.textAlign = Paint.Align.CENTER
        p.typeface = Typeface.create(display, Typeface.BOLD)
        p.textSize = 40f
        p.letterSpacing = 0.35f
        p.color = accent
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
        // Signature du studio en bas, petite et discrète (le logo, à défaut le nom).
        val studio = runCatching { android.graphics.BitmapFactory.decodeResource(ctx.resources, R.drawable.rollando_studio) }.getOrNull()
        if (studio != null) {
            val lw = 180f
            val lh = lw * studio.height / studio.width
            val sp = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { alpha = 150 }
            // « by » devant le logo, posé sur la ligne de « Rollando. » ; l'ensemble est centré.
            p.textSize = 30f
            p.textAlign = Paint.Align.LEFT
            p.color = Color.argb(150, 255, 255, 255)
            val byW = p.measureText("by")
            val gap = 14f
            val left = cx - (byW + gap + lw) / 2
            val top = h - 60f - lh
            c.drawText("by", left, top + lh * 0.6f, p)
            c.drawBitmap(studio, null, RectF(left + byW + gap, top, left + byW + gap + lw, h - 60f), sp)
            studio.recycle()
        } else {
            p.textSize = 30f
            p.color = Color.argb(110, 255, 255, 255)
            c.drawText("by Rollando Studio", cx, h - 70f, p)
        }

        return encode(bmp)
    }
}
