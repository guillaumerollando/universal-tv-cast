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
        HIPHOP(listOf("hiphop", "hip-hop", "rap"), R.string.amb_mood_hiphop),
        ELECTRO(listOf("electronic", "electro"), R.string.amb_mood_electro),
        /** La radio choisie par l'utilisateur (« Mes radios »). */
        MY_RADIO(emptyList(), R.string.amb_mood_myradio),
        /** Les morceaux du téléphone (« Ma musique »). */
        MY_MUSIC(emptyList(), R.string.amb_mood_mymusic),
    }

    /** Ce qui passe en ce moment (pour l'écran de lecture). */
    /** [track] : le morceau qui passe (« Artiste - Titre »), quand la radio le dit. */
    data class Now(val theme: Theme, val mood: Mood, val station: String?, val credit: String?, val track: String? = null)

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

    // ---- Mes radios : celle choisie, et les dernières ajoutées (pour en changer d'un toucher).
    private val _myRadio = MutableStateFlow<Radio.Station?>(null)
    val myRadio: StateFlow<Radio.Station?> = _myRadio
    private val _myRadios = MutableStateFlow<List<Radio.Station>>(emptyList())
    val myRadios: StateFlow<List<Radio.Station>> = _myRadios

    /** Une ambiance gardée en favori : photos, musique (et la radio), heure, durée des photos, sous un nom. */
    data class Preset(val name: String, val theme: Theme, val mood: Mood, val radio: Radio.Station?, val clock: Boolean, val seconds: Int)
    private val _presets = MutableStateFlow<List<Preset>>(emptyList())
    val presets: StateFlow<List<Preset>> = _presets

    /** Le nom proposé pour l'ambiance réglée : « Paysages · FIP Reggae ». */
    fun suggestedName(): String = app.getString(theme.value.label) + " · " +
        (if (mood.value == Mood.MY_RADIO) myRadio.value?.name ?: app.getString(mood.value.label) else app.getString(mood.value.label))

    fun savePreset(name: String) {
        val p = Preset(name.trim().ifEmpty { suggestedName() }.take(40), theme.value, mood.value, myRadio.value.takeIf { mood.value == Mood.MY_RADIO },
            clock.value, seconds.value)
        _presets.value = (_presets.value.filter { it.name != p.name } + p).take(12)
        writePresets()
    }

    fun deletePreset(p: Preset) { _presets.value = _presets.value - p; writePresets() }

    /** Remet les réglages de [p] (et lance l'ambiance si [start]). */
    fun applyPreset(p: Preset, start: Boolean) {
        setTheme(p.theme); setClock(p.clock); setSeconds(p.seconds)
        if (p.mood == Mood.MY_RADIO && p.radio != null) useRadio(p.radio) else setMood(p.mood)
        if (start) start()
    }

    private fun writePresets() {
        val a = org.json.JSONArray()
        _presets.value.forEach { p ->
            a.put(org.json.JSONObject().put("n", p.name).put("t", p.theme.name).put("m", p.mood.name).put("c", p.clock).put("s", p.seconds)
                .put("rn", p.radio?.name ?: "").put("ru", p.radio?.url ?: ""))
        }
        prefs.edit().putString("presets", a.toString()).apply()
    }

    /** Styles de musique masqués (« Gérer » : on ne garde que ceux qu'on écoute). */
    private val _hiddenMoods = MutableStateFlow<Set<Mood>>(emptySet())
    val hiddenMoods: StateFlow<Set<Mood>> = _hiddenMoods
    fun setHidden(m: Mood, hidden: Boolean) {
        _hiddenMoods.value = if (hidden) _hiddenMoods.value + m else _hiddenMoods.value - m
        prefs.edit().putStringSet("hidden_moods", _hiddenMoods.value.map { it.name }.toSet()).apply()
    }

    /** Écoute une radio de « Mes radios » d'un toucher (sans la remonter en tête : l'ordre reste celui choisi). */
    fun useRadio(st: Radio.Station) {
        _myRadio.value = st
        prefs.edit().putString("my_radio", st.url).apply()
        setMood(Mood.MY_RADIO)
    }

    /** Ajoute une radio à « Mes radios » (en tête, 20 au plus) et la choisit. */
    fun pickRadio(st: Radio.Station) {
        _myRadio.value = st
        _myRadios.value = (listOf(st) + _myRadios.value.filter { it.url != st.url }).take(20)
        val a = org.json.JSONArray(); _myRadios.value.forEach { a.put(org.json.JSONObject().put("n", it.name).put("u", it.url).put("l", it.logo ?: "")) }
        prefs.edit().putString("my_radios", a.toString()).putString("my_radio", st.url).apply()
        setMood(Mood.MY_RADIO)
    }

    fun forgetRadio(st: Radio.Station) {
        _myRadios.value = _myRadios.value.filter { it.url != st.url }
        if (_myRadio.value?.url == st.url) _myRadio.value = _myRadios.value.firstOrNull()
        val a = org.json.JSONArray(); _myRadios.value.forEach { a.put(org.json.JSONObject().put("n", it.name).put("u", it.url).put("l", it.logo ?: "")) }
        prefs.edit().putString("my_radios", a.toString()).putString("my_radio", _myRadio.value?.url ?: "").apply()
    }

    fun init(ctx: Context) {
        app = ctx.applicationContext
        val p = prefs
        MyMusic.init(app)
        runCatching {
            val a = org.json.JSONArray(p.getString("presets", "[]"))
            _presets.value = (0 until a.length()).mapNotNull { i ->
                val o = a.getJSONObject(i)
                runCatching {
                    Preset(o.getString("n"), Theme.valueOf(o.getString("t")), Mood.valueOf(o.getString("m")),
                        o.optString("ru").takeIf { it.isNotEmpty() }?.let { Radio.Station(o.optString("rn"), it) }, o.optBoolean("c", true), o.optInt("s", 60))
                }.getOrNull()
            }
        }
        _hiddenMoods.value = p.getStringSet("hidden_moods", emptySet()).orEmpty().mapNotNull { runCatching { Mood.valueOf(it) }.getOrNull() }.toSet()
        runCatching {
            val a = org.json.JSONArray(p.getString("my_radios", "[]"))
            _myRadios.value = (0 until a.length()).map { a.getJSONObject(it).let { o -> Radio.Station(o.getString("n"), o.getString("u"), logo = o.optString("l").ifEmpty { null }) } }
            val cur = p.getString("my_radio", "")
            _myRadio.value = _myRadios.value.firstOrNull { it.url == cur } ?: _myRadios.value.firstOrNull()
        }
        _theme.value = runCatching { Theme.valueOf(p.getString("theme", "LANDSCAPES")!!) }.getOrDefault(Theme.LANDSCAPES)
        _mood.value = runCatching { Mood.valueOf(p.getString("mood", "LOUNGE")!!) }.getOrDefault(Mood.LOUNGE)
        _clock.value = p.getBoolean("clock", true)
        _seconds.value = p.getInt("seconds", 60).coerceIn(10, 3600)
    }

    fun setTheme(t: Theme) { _theme.value = t; prefs.edit().putString("theme", t.name).apply() }
    fun setMood(m: Mood) { _mood.value = m; prefs.edit().putString("mood", m.name).apply() }
    fun setClock(on: Boolean) { _clock.value = on; prefs.edit().putBoolean("clock", on).apply() }
    fun setSeconds(s0: Int) { val s = s0.coerceIn(10, 3600); _seconds.value = s; prefs.edit().putInt("seconds", s).apply(); current?.slideMs = s * 1000L }

    @Volatile private var current: SlideStream? = null
    @Volatile private var feed: Radio.Feed? = null
    private var loop: Job? = null
    @Volatile private var credit: String? = null
    @Volatile private var track: String? = null
    private fun fold(s: String) = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "").lowercase().filter { it.isLetterOrDigit() }

    // ------------------------------------------------------------------ lancement et arrêt

    /** Lance l'ambiance sur la télé choisie (pack soutien). */
    fun start() {
        // Tout de suite (avant la file des commandes) : l'écran de lecture montre « Préparation de l'ambiance » au lieu
        // d'un « rien à caster » le temps que tout démarre, et l'image de l'ambiance précédente s'efface.
        if (TvCast._state.value.renderer != null && com.rollandostudio.universaltvcast.data.Supporter.unlocked.value) {
            _preview.value = null
            track = null
            TvCast._state.update { it.copy(media = null, slides = null, phase = Phase.SENDING, idle = false, notice = null,
                ambiance = Now(theme.value, mood.value, null, null)) }
        }
        launch()
    }

    private fun launch() = TvCast.exec {
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
        val f = withContext(Dispatchers.IO) {
            when (md) {
                Mood.NONE -> null
                Mood.MY_RADIO -> myRadio.value?.let { Radio.Feed.open(it) }
                Mood.MY_MUSIC -> MyMusic.feed(app)
                else -> Radio.stations(md.tags).asSequence().take(8).mapNotNull { Radio.Feed.open(it) }.firstOrNull()
            }
        }
        if (f != null) {
            st.enableAudio(f.streamType); feed = f
            // Le morceau qui passe : sur le téléphone et, discrètement, sur la télé (pas le nom de la radio redit).
            f.onTitle = { t ->
                val clean = t.replace(Regex("\\s+"), " ").trim()
                val sameAsStation = fold(clean).let { c -> c == fold(f.station.name) || fold(f.station.name).contains(c) }
                track = clean.takeIf { it.length > 2 && !sameAsStation }
                TvCast._state.update { s -> if (s.ambiance != null) s.copy(ambiance = s.ambiance.copy(track = track)) else s }
            }
        }
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
        TvCast._state.update { it.copy(phase = Phase.PLAYING, ambiance = Now(th, md, f?.station?.name, credit, track)) }
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
        // Crédit de la photo : obligatoire (licence), mais discret.
        val small = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x73FFFFFF; textSize = 18f; typeface = regular; textAlign = Paint.Align.RIGHT; setShadowLayer(6f, 0f, 1f, 0x66000000) }
        // Le morceau, en bas à droite : titre blanc, artiste plus pâle, une note à la couleur de l'appli.
        val songTitle = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xF2FFFFFF.toInt(); textSize = 30f; typeface = Typeface.create(regular, Typeface.BOLD); textAlign = Paint.Align.RIGHT; setShadowLayer(10f, 0f, 2f, 0x88000000.toInt()) }
        val songArtist = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xB3FFFFFF.toInt(); textSize = 23f; typeface = regular; textAlign = Paint.Align.RIGHT; setShadowLayer(8f, 0f, 1f, 0x88000000.toInt()) }
        val note = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 30f; typeface = regular; textAlign = Paint.Align.RIGHT; setShadowLayer(10f, 0f, 2f, 0x88000000.toInt()) }
        val timeFmt = android.text.format.DateFormat.getTimeFormat(app)
        val dateFmt = java.text.SimpleDateFormat("EEEE d MMMM", java.util.Locale.getDefault())
        return { c ->
            val showClock = clock.value
            val song = track
            if (showClock || credit != null || song != null) c.drawRect(0f, SlideStream.H * 0.62f, SlideStream.W.toFloat(), SlideStream.H.toFloat(), shade)
            if (showClock) {
                val now = java.util.Date()
                c.drawText(timeFmt.format(now), 96f, SlideStream.H - 150f, time)
                c.drawText(dateFmt.format(now).replaceFirstChar { it.titlecase() }, 100f, SlideStream.H - 92f, date)
            }
            song?.let {
                fun cut(t: String, n: Int) = if (t.length > n) t.take(n - 1) + "…" else t
                // « Artiste - Titre » : deux lignes ; sinon le texte tel quel.
                val parts = it.split(" - ", limit = 2)
                val (title, artist) = if (parts.size == 2) parts[1].trim() to parts[0].trim() else it to ""
                val right = SlideStream.W - 40f
                val yTitle = if (artist.isEmpty()) SlideStream.H - 62f else SlideStream.H - 92f
                note.color = com.rollandostudio.universaltvcast.data.Look.accentNow().argb.toInt()
                val t = cut(title, 48)
                c.drawText(t, right, yTitle, songTitle)
                c.drawText("♪", right - songTitle.measureText(t) - 14f, yTitle, note)
                if (artist.isNotEmpty()) c.drawText(cut(artist, 56), right, SlideStream.H - 60f, songArtist)
            }
            credit?.let { c.drawText(it, SlideStream.W - 40f, SlideStream.H - 28f, small) }
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
                // Photos seulement (les PNG sont souvent des schémas ou des cartes), pas de panoramas ni de cartes.
                .filter { t -> t.lowercase().let { l -> (l.endsWith(".jpg") || l.endsWith(".jpeg")) && !Regex("map|carte|diagram|panorama|pano").containsMatchIn(l) } }
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
        // Un vrai fond d'écran : large comme une télé (de 4:3 un peu large à 2,2:1, pas de panorama étiré), et grand.
        if (w <= 0 || h <= 0 || w < h * 1.4 || w > h * 2.2 || w < 2400) return@runCatching null
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
