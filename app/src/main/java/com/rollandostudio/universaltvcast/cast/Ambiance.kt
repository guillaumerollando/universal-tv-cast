package com.rollandostudio.universaltvcast.cast

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.util.Log
import androidx.annotation.StringRes
import com.rollandostudio.universaltvcast.R
import com.rollandostudio.universaltvcast.data.Look
import com.rollandostudio.universaltvcast.net.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * Mode Ambiance (pack soutien) : la télé devient un cadre vivant. De belles photos défilent sans fin, en plein écran,
 * avec un fondu lent et un zoom très doux, l'heure par-dessus, et une radio en fond si on veut. Le tout en une
 * seule vidéo en direct ([SlideStream]) : la télé la lit comme un film (DLNA ou Google Cast).
 *
 * Photos : celles du téléphone (choisies dans la Personnalisation), ou des photos libres de Wikimedia Commons
 * (images de qualité, choisies par la communauté), créditées dans un coin comme leur licence le demande.
 * Radio : l'annuaire libre Radio Browser ([Radio]). Rien n'est envoyé d'autre que le thème ou le style demandé.
 */
object Ambiance {
    private const val TAG = "TvCastAmbiance"
    private const val UA = "UniversalTVCast/1.0 (+https://rollandostudio.com)"

    /** Thème des photos : les miennes, ou une catégorie d'images de qualité de Wikimedia Commons. */
    enum class Theme(val category: String?, @param:StringRes val label: Int) {
        MINE(null, R.string.amb_theme_mine),
        LANDSCAPES("Featured pictures of landscapes", R.string.amb_theme_landscapes),
        MOUNTAINS("Featured pictures of mountains", R.string.amb_theme_mountains),
        LAKES("Featured pictures of lakes", R.string.amb_theme_lakes),
        FORESTS("Featured pictures of forests", R.string.amb_theme_forests),
        CITIES("Featured pictures of cityscapes", R.string.amb_theme_cities),
        SPACE("Featured pictures of astronomy", R.string.amb_theme_space),
    }

    /** Style de musique (étiquettes de l'annuaire de radios), ou pas de musique. */
    enum class Mood(val tags: List<String>, @param:StringRes val label: Int) {
        NONE(emptyList(), R.string.amb_mood_none),
        LOUNGE(listOf("chillout", "lounge"), R.string.amb_mood_lounge),
        JAZZ(listOf("smooth jazz", "jazz"), R.string.amb_mood_jazz),
        CLASSICAL(listOf("classical"), R.string.amb_mood_classical),
        LOFI(listOf("lofi", "lo-fi"), R.string.amb_mood_lofi),
        AMBIENT(listOf("ambient"), R.string.amb_mood_ambient),
        PIANO(listOf("piano"), R.string.amb_mood_piano),
    }

    /** Ce qui passe en ce moment (pour l'écran de lecture). */
    data class Now(val theme: Theme, val mood: Mood, val station: String?, val credit: String?)

    private lateinit var app: Context
    private val prefs get() = app.getSharedPreferences("ambiance", Context.MODE_PRIVATE)

    private val _theme = MutableStateFlow(Theme.LANDSCAPES)
    val theme: StateFlow<Theme> = _theme
    private val _mood = MutableStateFlow(Mood.LOUNGE)
    val mood: StateFlow<Mood> = _mood
    private val _clock = MutableStateFlow(true)
    val clock: StateFlow<Boolean> = _clock
    private val _seconds = MutableStateFlow(30)
    val seconds: StateFlow<Int> = _seconds
    /** La photo affichée sur la télé, réduite, pour l'écran de lecture du téléphone. */
    private val _preview = MutableStateFlow<Bitmap?>(null)
    val preview: StateFlow<Bitmap?> = _preview

    fun init(ctx: Context) {
        app = ctx.applicationContext
        val p = prefs
        _theme.value = runCatching { Theme.valueOf(p.getString("theme", "LANDSCAPES")!!) }.getOrDefault(Theme.LANDSCAPES)
        _mood.value = runCatching { Mood.valueOf(p.getString("mood", "LOUNGE")!!) }.getOrDefault(Mood.LOUNGE)
        _clock.value = p.getBoolean("clock", true)
        _seconds.value = p.getInt("seconds", 30)
    }

    fun setTheme(t: Theme) { _theme.value = t; prefs.edit().putString("theme", t.name).apply() }
    fun setMood(m: Mood) { _mood.value = m; prefs.edit().putString("mood", m.name).apply() }
    fun setClock(on: Boolean) { _clock.value = on; prefs.edit().putBoolean("clock", on).apply() }
    fun setSeconds(s: Int) { _seconds.value = s; prefs.edit().putInt("seconds", s).apply(); current?.slideMs = s * 1000L }

    @Volatile private var current: SlideStream? = null
    @Volatile private var feed: Radio.Feed? = null
    private var loop: Job? = null
    @Volatile private var credit: String? = null

    // ------------------------------------------------------------------ lancement et arrêt

    /** Lance l'ambiance sur la télé choisie (pack soutien). */
    fun start() = TvCast.exec {
        val chosen = TvCast._state.value.renderer
        if (chosen == null) {
            TvCast._state.update { it.copy(notice = Notice(R.string.notice_pick_tv)) }
            TvCast.discover()
            return@exec
        }
        if (!com.rollandostudio.universaltvcast.data.Supporter.unlocked.value) return@exec
        TvCast.pollJob?.cancel(); TvCast.seekJob?.cancel()
        Album.slideJob?.cancel()
        Album.stopStream()
        stopFeed()
        if (TvCast._state.value.onPhone) { PhoneEngine.stop(); TvCast._state.update { it.copy(onPhone = false) } }
        val th = theme.value
        val md = mood.value
        TvCast._state.update {
            it.copy(media = null, slides = null, phase = Phase.SENDING, idle = false, notice = null, position = 0.0, duration = 0.0,
                ambiance = Now(th, md, null, null))
        }
        val st = SlideStream.start() ?: run {
            TvCast._state.update { it.copy(phase = Phase.IDLE, ambiance = null, notice = Notice(R.string.amb_failed, null, true)) }
            return@exec
        }
        st.cover = true
        st.fadeMs = 1800
        st.slideMs = seconds.value * 1000L
        st.overlay = overlay()
        current = st
        val images = Images(th)
        // La radio d'abord : le flux annonce sa piste son dès le départ (la télé ne la découvrirait pas en route).
        val f = if (md == Mood.NONE) null else withContext(Dispatchers.IO) {
            Radio.stations(md.tags).asSequence().take(8).mapNotNull { Radio.Feed.open(it) }.firstOrNull()
        }
        if (f != null) { st.enableAudio(f.streamType); feed = f }
        else if (md != Mood.NONE) Log.i(TAG, "aucune radio « ${md.name} » joignable : sans musique")
        val first = withContext(Dispatchers.IO) { images.next() }
        if (first == null) {
            st.stop(); current = null; stopFeed()
            TvCast._state.update { it.copy(phase = Phase.IDLE, ambiance = null, notice = Notice(if (th == Theme.MINE) R.string.amb_no_photos else R.string.amb_failed, null, true)) }
            return@exec
        }
        showImage(st, first)
        Album.useStream(st)
        try {
            val used = Album.sendStreamToTv(chosen, st, app.getString(R.string.amb_title))
            TvCast.via = if (used !== chosen) used else null
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.i(TAG, "flux refusé : ${e.message}")
            Album.stopStream(); stopFeed(); current = null
            TvCast._state.update { it.copy(phase = Phase.IDLE, ambiance = null, notice = Notice(R.string.amb_failed, null, true)) }
            return@exec
        }
        feed?.let { fd -> Thread({ fd.run(st) }, "ambiance-radio").start() }
        CastService.start(app)
        TvCast._state.update { it.copy(phase = Phase.PLAYING, ambiance = Now(th, md, f?.station?.name, credit)) }
        Log.i(TAG, "ambiance sur ${chosen.name} : ${th.name}, radio ${f?.station?.name ?: "aucune"}")
        loop?.cancel()
        loop = TvCast.scope.launch(Dispatchers.IO) {
            var next = images.next()
            var shown = System.currentTimeMillis()
            while (isActive && st.alive) {
                delay(500)
                if (System.currentTimeMillis() - shown < st.slideMs) continue
                val img = next ?: images.next()
                if (img != null) {
                    showImage(st, img)
                    TvCast._state.update { s -> if (s.ambiance != null) s.copy(ambiance = s.ambiance.copy(credit = credit)) else s }
                }
                shown = System.currentTimeMillis()
                next = images.next()
            }
            // Le flux s'est arrêté (arrêt, autre vidéo, écran d'attente) : l'ambiance est finie.
            if (current === st) {
                current = null
                stopFeed()
                _preview.value = null
                TvCast._state.update { it.copy(ambiance = null) }
            }
        }
    }

    /** Photo suivante tout de suite. */
    fun skip() { current?.let { st -> TvCast.scope.launch(Dispatchers.IO) { Images(theme.value).next()?.let { showImage(st, it) } } } }

    private fun stopFeed() { feed?.stop(); feed = null }

    private fun showImage(st: SlideStream, img: Image) {
        credit = img.credit
        st.show(img.bitmap)
        _preview.value = runCatching { Bitmap.createScaledBitmap(img.bitmap, 480, (480f * img.bitmap.height / img.bitmap.width).toInt().coerceAtLeast(1), true) }.getOrNull()
    }

    // ------------------------------------------------------------------ calque : heure, date, crédit

    private fun overlay(): (Canvas) -> Unit {
        val fonts = runCatching {
            androidx.core.content.res.ResourcesCompat.getFont(app, R.font.inter_display_extrabold) to androidx.core.content.res.ResourcesCompat.getFont(app, R.font.inter_regular)
        }.getOrNull()
        val bold = fonts?.first ?: Typeface.DEFAULT_BOLD
        val regular = fonts?.second ?: Typeface.DEFAULT
        val shade = Paint().apply {
            shader = LinearGradient(0f, SlideStream.H * 0.62f, 0f, SlideStream.H.toFloat(), 0x00000000, 0x99000000.toInt(), Shader.TileMode.CLAMP)
        }
        val time = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt(); textSize = 120f; typeface = bold; setShadowLayer(18f, 0f, 3f, 0x66000000) }
        val date = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xE6FFFFFF.toInt(); textSize = 40f; typeface = regular; setShadowLayer(12f, 0f, 2f, 0x66000000) }
        val small = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xB3FFFFFF.toInt(); textSize = 22f; typeface = regular; textAlign = Paint.Align.RIGHT; setShadowLayer(8f, 0f, 1f, 0x88000000.toInt()) }
        val timeFmt = android.text.format.DateFormat.getTimeFormat(app)
        val dateFmt = java.text.SimpleDateFormat("EEEE d MMMM", java.util.Locale.getDefault())
        return { c ->
            val showClock = clock.value
            if (showClock || credit != null) c.drawRect(0f, SlideStream.H * 0.62f, SlideStream.W.toFloat(), SlideStream.H.toFloat(), shade)
            if (showClock) {
                val now = java.util.Date()
                c.drawText(timeFmt.format(now), 96f, SlideStream.H - 150f, time)
                c.drawText(dateFmt.format(now).replaceFirstChar { it.titlecase() }, 100f, SlideStream.H - 92f, date)
            }
            credit?.let { c.drawText(it, SlideStream.W - 48f, SlideStream.H - 40f, small) }
        }
    }

    // ------------------------------------------------------------------ photos

    private class Image(val bitmap: Bitmap, val credit: String?)

    /** Source de photos d'un thème : tirées au hasard, sans répétition tant qu'il en reste. */
    private class Images(val theme: Theme) {
        private val queue = ArrayDeque<String>()

        fun next(): Image? {
            if (theme == Theme.MINE) {
                val mine = Look.photos.value
                if (mine.isEmpty()) return null
                if (queue.isEmpty()) queue.addAll(mine.map { it.path }.shuffled())
                val path = queue.removeFirst()
                return runCatching { BitmapFactory.decodeFile(path)?.let { Image(it, null) } }.getOrNull()
            }
            repeat(6) {
                if (queue.isEmpty()) queue.addAll(titles(theme.category!!).shuffled())
                if (queue.isEmpty()) return null
                commons(queue.removeFirst())?.let { return it }
            }
            return null
        }
    }

    /** Fichiers d'une catégorie de Commons (gardés une semaine en cache). */
    private fun titles(category: String): List<String> {
        val cache = File(app.cacheDir, "ambiance/" + category.replace(' ', '_') + ".txt")
        if (cache.exists() && System.currentTimeMillis() - cache.lastModified() < 7 * 86_400_000L) {
            return cache.readLines().filter { it.isNotBlank() }
        }
        return runCatching {
            val url = "https://commons.wikimedia.org/w/api.php?action=query&list=categorymembers&cmtype=file&cmlimit=500&format=json&cmtitle=" +
                java.net.URLEncoder.encode("Category:$category", "UTF-8")
            val c = Http.open(url, mapOf("User-Agent" to UA), connectTimeout = 8000, readTimeout = 15_000)
            val o = try { JSONObject(c.inputStream.use { String(it.readBytes()) }) } finally { c.disconnect() }
            val a = o.getJSONObject("query").getJSONArray("categorymembers")
            val list = (0 until a.length()).map { a.getJSONObject(it).getString("title") }
                .filter { t -> listOf(".jpg", ".jpeg", ".png").any { t.lowercase().endsWith(it) } }
            cache.parentFile?.mkdirs(); cache.writeText(list.joinToString("\n"))
            list
        }.onFailure { Log.i(TAG, "catégorie $category : ${it.message}") }.getOrDefault(emptyList())
    }

    /** Une image de Commons en 1920 px de large (paysage seulement : c'est pour une télé), avec son crédit. */
    private fun commons(title: String): Image? = runCatching {
        val url = "https://commons.wikimedia.org/w/api.php?action=query&prop=imageinfo&iiprop=url|size|extmetadata&iiurlwidth=1920&format=json&titles=" +
            java.net.URLEncoder.encode(title, "UTF-8")
        val c = Http.open(url, mapOf("User-Agent" to UA), connectTimeout = 8000, readTimeout = 15_000)
        val o = try { JSONObject(c.inputStream.use { String(it.readBytes()) }) } finally { c.disconnect() }
        val pages = o.getJSONObject("query").getJSONObject("pages")
        val info = pages.getJSONObject(pages.keys().next()).getJSONArray("imageinfo").getJSONObject(0)
        val w = info.optInt("width"); val h = info.optInt("height")
        if (w <= 0 || h <= 0 || w < h * 1.2) return@runCatching null // portrait ou carré : mal sur une télé
        val thumb = info.optString("thumburl").ifBlank { return@runCatching null }
        val meta = info.optJSONObject("extmetadata")
        fun field(k: String) = meta?.optJSONObject(k)?.optString("value").orEmpty()
            .replace(Regex("<[^>]+>"), "").replace(Regex("\\s+"), " ").trim()
        val artist = field("Artist").take(60)
        val license = field("LicenseShortName")
        val ic = Http.open(thumb, mapOf("User-Agent" to UA), connectTimeout = 8000, readTimeout = 20_000)
        val bytes = try { ic.inputStream.use { it.readBytes() } } finally { ic.disconnect() }
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@runCatching null
        Image(bmp, listOf(artist, license, "Wikimedia Commons").filter { it.isNotBlank() }.joinToString(" · "))
    }.onFailure { Log.i(TAG, "image $title : ${it.message}") }.getOrNull()
}
