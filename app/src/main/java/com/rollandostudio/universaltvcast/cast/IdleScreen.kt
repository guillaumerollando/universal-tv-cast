package com.rollandostudio.universaltvcast.cast

import android.content.Context
import com.rollandostudio.universaltvcast.data.Look
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
        val face = com.rollandostudio.universaltvcast.data.Look.clockFace.value
        val bg = com.rollandostudio.universaltvcast.data.Look.clockBg.value
        val key = "$style|$face|$bg|$accent|$line|${photo?.name}|${if (style == com.rollandostudio.universaltvcast.data.Look.Idle.CLOCK || live) minute else 0}"
        cached?.let { (k, b) -> if (k == key) return b }
        val bytes = when {
            style == com.rollandostudio.universaltvcast.data.Look.Idle.CLOCK -> clock(ctx, accent, custom, face, bg)
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

    /**
     * Horloge (pack soutien) : l'heure du téléphone, la date et le message, dans le style [face] et sur le fond [bg]
     * choisis, à la couleur d'accent. Pas de signature du studio : c'est l'écran de ceux qui ont soutenu l'appli.
     */
    private fun clock(ctx: Context, accent: Int, message: String, face: Look.ClockFace, bg: Look.ClockBg): ByteArray {
        val w = 1920; val h = 1080
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        when (bg) {
            Look.ClockBg.BLACK -> c.drawColor(0xFF000000.toInt())
            Look.ClockBg.GRADIENT -> {
                p.shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), intArrayOf(mix(accent, 0xFF0B0C0F.toInt(), 0.55f), 0xFF0B0C0F.toInt(), mix(accent, 0xFF000000.toInt(), 0.8f)),
                    floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP)
                c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
            }
            Look.ClockBg.GLOW -> {
                c.drawColor(0xFF0B0C0F.toInt())
                p.shader = RadialGradient(w / 2f, h * 0.42f, 1100f, intArrayOf(alpha(accent, 0x55), alpha(accent, 0)), null, Shader.TileMode.CLAMP)
                c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
            }
        }
        p.shader = null
        val (display, regular) = fonts(ctx)
        val now = java.util.Date()
        val time = android.text.format.DateFormat.getTimeFormat(ctx).format(now)
        val date = java.text.SimpleDateFormat("EEEE d MMMM", java.util.Locale.getDefault()).format(now)
            .replaceFirstChar { it.titlecase(java.util.Locale.getDefault()) }
        p.textAlign = Paint.Align.CENTER
        var below = h * 0.52f // où écrire la date (et le message dessous)
        when (face) {
            Look.ClockFace.BIG -> {
                p.typeface = Typeface.create(display, Typeface.BOLD); p.textSize = 320f; p.letterSpacing = -0.03f; p.color = 0xFFFFFFFF.toInt()
                c.drawText(time, w / 2f, h * 0.52f, p)
                below = h * 0.52f + 110f
            }
            Look.ClockFace.THIN -> {
                p.typeface = Typeface.create("sans-serif-thin", Typeface.NORMAL); p.textSize = 300f; p.letterSpacing = 0.02f; p.color = 0xFFFFFFFF.toInt()
                c.drawText(time, w / 2f, h * 0.52f, p)
                // Un trait fin de la couleur d'accent entre l'heure et la date.
                p.strokeWidth = 4f; p.color = accent
                c.drawLine(w / 2f - 90f, h * 0.52f + 50f, w / 2f + 90f, h * 0.52f + 50f, p)
                below = h * 0.52f + 130f
            }
            Look.ClockFace.ANALOG -> {
                val cx = w / 2f; val cy = h * 0.40f; val r = 300f
                val cal = java.util.Calendar.getInstance().also { it.time = now }
                val min = cal.get(java.util.Calendar.MINUTE); val hr = cal.get(java.util.Calendar.HOUR) + min / 60f
                p.style = Paint.Style.FILL; p.color = 0x22FFFFFF
                c.drawCircle(cx, cy, r, p)
                p.style = Paint.Style.STROKE; p.strokeWidth = 6f; p.color = alpha(accent, 0xCC)
                c.drawCircle(cx, cy, r, p)
                // Les douze repères, plus marqués aux quarts.
                for (i in 0 until 12) {
                    val a = Math.toRadians(i * 30.0)
                    val inner = if (i % 3 == 0) r - 50f else r - 28f
                    p.strokeWidth = if (i % 3 == 0) 10f else 5f; p.color = 0xCCFFFFFF.toInt(); p.strokeCap = Paint.Cap.ROUND
                    c.drawLine(cx + inner * Math.sin(a).toFloat(), cy - inner * Math.cos(a).toFloat(),
                        cx + (r - 14f) * Math.sin(a).toFloat(), cy - (r - 14f) * Math.cos(a).toFloat(), p)
                }
                fun hand(angleDeg: Float, len: Float, width: Float, color: Int) {
                    val a = Math.toRadians(angleDeg.toDouble())
                    p.strokeWidth = width; p.color = color; p.strokeCap = Paint.Cap.ROUND
                    c.drawLine(cx, cy, cx + len * Math.sin(a).toFloat(), cy - len * Math.cos(a).toFloat(), p)
                }
                hand(hr * 30f, r * 0.55f, 18f, 0xFFFFFFFF.toInt())
                hand(min * 6f, r * 0.82f, 10f, accent)
                p.style = Paint.Style.FILL; p.color = accent
                c.drawCircle(cx, cy, 16f, p)
                below = cy + r + 110f
            }
            Look.ClockFace.FLIP -> {
                // Cartes rétro : un pavé arrondi par chiffre, coupé au milieu.
                val digits = time.filter { it.isDigit() }
                val sep = time.firstOrNull { !it.isDigit() && it != ' ' } ?: ':'
                val cw = 210f; val ch = 300f; val gap = 22f; val sepW = 70f
                val total = digits.length * cw + (digits.length - 1) * gap + sepW
                var x = w / 2f - total / 2
                val top = h * 0.40f - ch / 2
                p.typeface = Typeface.create(display, Typeface.BOLD); p.textSize = 230f; p.letterSpacing = 0f
                digits.forEachIndexed { i, d ->
                    if (i == digits.length - 2) {
                        p.color = accent; c.drawText(sep.toString(), x + sepW / 2 - gap / 2, top + ch * 0.72f, p)
                        x += sepW
                    }
                    p.color = 0xFF1C1F26.toInt(); c.drawRoundRect(RectF(x, top, x + cw, top + ch), 26f, 26f, p)
                    p.color = 0xFFFFFFFF.toInt(); c.drawText(d.toString(), x + cw / 2, top + ch * 0.77f, p)
                    p.color = 0xFF0B0C0F.toInt(); p.strokeWidth = 5f; c.drawLine(x, top + ch / 2, x + cw, top + ch / 2, p)
                    x += cw + gap
                }
                // AM / PM éventuel (format 12 h), discret à droite.
                time.filter { it.isLetter() }.takeIf { it.isNotEmpty() }?.let {
                    p.typeface = regular; p.textSize = 48f; p.color = 0xFFBDB6AD.toInt(); c.drawText(it, x + 50f, top + ch * 0.9f, p)
                }
                below = top + ch + 110f
            }
        }
        p.style = Paint.Style.FILL
        p.typeface = regular
        p.letterSpacing = 0.02f
        p.textSize = 56f
        p.color = accent
        c.drawText(date, w / 2f, below, p)
        if (message.isNotEmpty()) {
            p.textSize = 48f
            p.color = 0xFFBDB6AD.toInt()
            c.drawText(message, w / 2f, below + 100f, p)
        }
        return encode(bmp)
    }

    /** Mélange [a] avec [b] ([t] = part de [b]). */
    private fun mix(a: Int, b: Int, t: Float): Int {
        fun ch(x: Int, sh: Int) = (x shr sh) and 0xFF
        fun m(sh: Int) = (ch(a, sh) * (1 - t) + ch(b, sh) * t).toInt()
        return (0xFF shl 24) or (m(16) shl 16) or (m(8) shl 8) or m(0)
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
