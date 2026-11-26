package studio.rollando.castor.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import studio.rollando.castor.cast.Media

data class Recent(
    val title: String, val url: String, val mime: String?, val headers: Map<String, String>, val pageUrl: String?, val time: Long,
    val poster: String? = null, val fileName: String? = null,
    /** Saison et épisode (fichier reconnu, ex. Stremio) ; pour un site, l'épisode est lu dans l'adresse de la page. */
    val season: Int? = null, val episode: Int? = null,
    /** Où l'on s'est arrêté (secondes) et durée totale ; [done] : la lecture est allée jusqu'au bout. */
    val pos: Double = 0.0, val dur: Double = 0.0, val done: Boolean = false,
    /** Page de l'épisode suivant, repérée sur le site (quand l'adresse ne dit pas le numéro). */
    val nextUrl: String? = null,
    /** Chaîne en direct (rangée « Chaînes », pas « Reprendre ») et le nom que l'utilisateur lui a donné. */
    val live: Boolean = false, val channel: String? = null,
) {
    /** Nom de la chaîne : celui choisi, sinon une chaîne connue (Arte…), sinon le titre, sinon le site. */
    val channelName: String get() = channel
        ?: Channels.find("$url $pageUrl $title")?.name
        ?: title.takeIf { t -> t.length > 2 && !t.contains(".m3u8") && t.lowercase() !in GENERIC_LIVE_TITLES }
        ?: Library.hostOf(pageUrl ?: url).substringBefore('.').replaceFirstChar { it.uppercase() }

    /** Fini, ou presque (le générique) : 5 minutes avant la fin d'un film, environ 8 % pour un épisode. */
    val finished: Boolean get() = done || (dur > 60 && pos >= dur - minOf(300.0, dur * 0.08))

    /** Page d'un site (pas une vidéo d'une autre appli). */
    val isWeb: Boolean get() = web

    fun toMedia() = Media(url = url, title = title, mime = mime, headers = headers, pageUrl = pageUrl, posterUrl = poster, backdropUrl = if (live) null else poster, fileName = fileName, channel = channel, nextUrl = nextUrl,
        startSec = if (finished || pos < 30) 0.0 else pos - 5)

    /** Avancement (0 à 1) si la vidéo est commencée et pas finie. */
    val progress: Float? get() = if (dur > 0 && pos >= 30 && !finished) (pos / dur).toFloat() else null

    private val web: Boolean get() = pageUrl?.startsWith("http") == true

    /** Une seule entrée par série : l'adresse de la page sans le numéro d'épisode, ou le nom de la série et sa saison. */
    val key: String get() = when {
        live -> "live:" + (pageUrl?.takeIf { web } ?: url.substringBefore('?'))
        // L'adresse ne dit pas l'épisode (site qui donne un identifiant par vidéo) : la série, d'après le titre.
        web && urlEpisode == null && titleEpisode != null ->
            "t:" + Library.hostOf(pageUrl!!) + "|" + studio.rollando.castor.browser.PageTitle.series(title).lowercase()
        web -> studio.rollando.castor.browser.Episodes.seriesKey(pageUrl!!)
        season != null && fileName != null ->
            "f:" + studio.rollando.castor.subs.SubtitleFinder.parse(fileName).title.lowercase() + "#" + season
        else -> url
    }

    /** Épisode où l'on en est (pour la pastille), s'il est connu. */
    val episodeNumber: Int? get() = episode ?: urlEpisode ?: titleEpisode

    private val urlEpisode: Int? get() = pageUrl?.takeIf { web }?.let { studio.rollando.castor.browser.Episodes.find(it)?.number }
    private val titleEpisode: Int? get() = if (web) studio.rollando.castor.browser.PageTitle.episode(title) else null

    /** Page d'accueil d'un site (une vidéo d'aperçu, pas quelque chose qu'on reprend). */
    val isHomePage: Boolean get() = web && runCatching {
        android.net.Uri.parse(pageUrl).path.orEmpty().trim('/').lowercase() in HOME_PATHS
    }.getOrDefault(false)

    /** Titre de la série seule (« Jujutsu Kaisen » plutôt que « Jujutsu Kaisen – Épisode 5 »). */
    val seriesTitle: String get() = when {
        season != null && fileName != null -> studio.rollando.castor.subs.SubtitleFinder.parse(fileName).title
        episodeNumber != null -> studio.rollando.castor.browser.PageTitle.series(title)
        else -> title
    }
}

/**
 * Favori = marque-page : [url] est la page où l'on en est (l'épisode en cours) ; il avance tout seul quand on
 * caste un autre épisode de la même série. [episode] : numéro lu dans l'adresse, si elle en a un.
 */
data class Bookmark(
    val title: String, val url: String, val episode: Int? = null, val time: Long = 0,
    /** Image de la page (affiche, vignette d'épisode), pour une carte plus parlante. */
    val image: String? = null,
) {
    val host: String get() = runCatching { android.net.Uri.parse(url).host?.removePrefix("www.") }.getOrNull().orEmpty()
    val key: String get() = studio.rollando.castor.browser.Episodes.seriesKey(url)
    /** Favori d'un site (sa page d'accueil) : il ne bouge jamais. Sinon, une série ou un film. */
    val isSite: Boolean get() =
        key == (runCatching { android.net.Uri.parse(url).authority }.getOrNull() ?: host).removePrefix("www.").lowercase()
    /** Série suivie (une page d'épisode) ; sinon c'est un site ou une page de site, rangé dans « Mes sites ». */
    val isSeries: Boolean get() = !isSite && (episode != null || titleEpisode != null || !isHomePage)

    /** Épisode lu dans le titre, pour les sites dont l'adresse n'a pas de numéro (« /watch/a8Kz93 »). */
    val titleEpisode: Int? get() = studio.rollando.castor.browser.PageTitle.episode(title)
    /** Nom de la série seule (« Frieren – Épisode 8 » → « Frieren »). */
    val seriesTitle: String get() = studio.rollando.castor.browser.PageTitle.series(title)

    /** Accueil d'un site (« /home », « /accueil »…) : rangé avec les sites. */
    private val isHomePage: Boolean get() = runCatching {
        android.net.Uri.parse(url).path.orEmpty().trim('/').lowercase() in HOME_PATHS
    }.getOrDefault(false)

    /** Même série qu'une entrée de « Reprendre » (même adresse de série, même page, ou même nom sur le même site). */
    fun sameSeries(r: Recent): Boolean = key == r.key || url == r.pageUrl ||
        (r.isWeb && host == Library.hostOf(r.pageUrl!!) && seriesTitle.equals(r.seriesTitle, ignoreCase = true))
}

private val GENERIC_LIVE_TITLES = setOf("live", "direct", "en direct", "en directo", "diretta", "ao vivo", "video", "vidéo", "vídeo", "index", "stream")

private val HOME_PATHS = setOf("", "home", "accueil", "index", "index.html", "index.php", "fr", "en")

/** Ce que l'appli a fait toute seule avec un favori, pour le dire à l'utilisateur. */
sealed class BookmarkEvent {
    data class Followed(val title: String, val series: Boolean) : BookmarkEvent()
    data class Advanced(val title: String, val episode: Int?) : BookmarkEvent()
}



/** Favoris, historique et réglages, gardés sur le téléphone uniquement. */
object Library {
    private lateinit var prefs: SharedPreferences
    private val _recents = MutableStateFlow<List<Recent>>(emptyList())
    private val _bookmarks = MutableStateFlow<List<Bookmark>>(emptyList())
    private val _adBlock = MutableStateFlow(true)
    private val _continuous = MutableStateFlow(true)
    val recents: StateFlow<List<Recent>> = _recents
    val bookmarks: StateFlow<List<Bookmark>> = _bookmarks
    val adBlock: StateFlow<Boolean> = _adBlock
    val continuousHls: StateFlow<Boolean> = _continuous

    fun init(ctx: Context) {
        if (::prefs.isInitialized) return
        prefs = ctx.getSharedPreferences("library", Context.MODE_PRIVATE)
        _recents.value = runCatching { readRecents(JSONArray(prefs.getString("recents", "[]"))) }.getOrDefault(emptyList())
        _bookmarks.value = runCatching { readBookmarks(JSONArray(prefs.getString("bookmarks", "[]"))) }.getOrDefault(emptyList())
        _adBlock.value = prefs.getBoolean("adblock", true)
        _continuous.value = prefs.getBoolean("continuous", true)
        _shakeHelp.value = prefs.getBoolean("shake_help", true)
        _dismissedStarters.value = prefs.getStringSet("starters_dismissed", emptySet()).orEmpty().toSet()
        _embedSubs.value = prefs.getBoolean("embed_subs", true)
        _smoothSlides.value = prefs.getBoolean("smooth_slides_v2", false)
        _powerSaver.value = prefs.getBoolean("power_saver", false)
        _hiddenSites.value = prefs.getStringSet("hidden_sites", emptySet()).orEmpty().toSet()
        _dismissedTips.value = prefs.getStringSet("tips_dismissed", emptySet()).orEmpty().toSet()
    }

    var lastTv: String?
        get() = prefs.getString("tv", null)
        set(v) { prefs.edit().putString("tv", v).apply() }

    /** Adresses de description des télés déjà vues : revérifiées en premier à chaque recherche. */
    val knownTvs: Set<String> get() = prefs.getStringSet("tvs", emptySet()).orEmpty()

    fun rememberTv(location: String) {
        if (location.isBlank() || location in knownTvs) return
        prefs.edit().putStringSet("tvs", (knownTvs + location).toList().takeLast(10).toSet()).apply()
    }

    fun setAdBlock(on: Boolean) { _adBlock.value = on; prefs.edit().putBoolean("adblock", on).apply() }
    private val _embedSubs = MutableStateFlow(true)
    /** Intégrer les sous-titres au MP4 pour les télés DLNA (qui ignorent souvent les sous-titres externes). */
    val embedSubs: StateFlow<Boolean> = _embedSubs
    fun setEmbedSubs(on: Boolean) { _embedSubs.value = on; prefs.edit().putBoolean("embed_subs", on).apply() }

    private val _powerSaver = MutableStateFlow(false)
    /** Économie d'énergie choisie dans les Réglages (voir [Power]). */
    val powerSaver: StateFlow<Boolean> = _powerSaver
    fun setPowerSaver(on: Boolean) { _powerSaver.value = on; prefs.edit().putBoolean("power_saver", on).apply(); Power.refresh() }

    private val _smoothSlides = MutableStateFlow(false)
    /** Diaporama envoyé comme une seule vidéo (pas d'écran noir entre les photos). */
    val smoothSlides: StateFlow<Boolean> = _smoothSlides
    fun setSmoothSlides(on: Boolean) { _smoothSlides.value = on; prefs.edit().putBoolean("smooth_slides_v2", on).apply() }

    private val _shakeHelp = MutableStateFlow(true)
    /** Secouer le téléphone ouvre l'aide. */
    val shakeHelp: StateFlow<Boolean> = _shakeHelp
    fun setShakeHelp(on: Boolean) { _shakeHelp.value = on; prefs.edit().putBoolean("shake_help", on).apply() }

    /** Suggestions gratuites de « Pour commencer » que l'utilisateur ne veut plus voir (site, ou « free-tv »). */
    private val _dismissedStarters = MutableStateFlow<Set<String>>(emptySet())
    val dismissedStarters: StateFlow<Set<String>> = _dismissedStarters
    fun dismissStarter(id: String) {
        _dismissedStarters.value = _dismissedStarters.value + id
        prefs.edit().putStringSet("starters_dismissed", _dismissedStarters.value).apply()
    }

    private val _dismissedTips = MutableStateFlow<Set<String>>(emptySet())
    /** Conseils que l'utilisateur a choisi de ne plus voir. */
    val dismissedTips: StateFlow<Set<String>> = _dismissedTips

    fun dismissTip(key: String) {
        _dismissedTips.value = _dismissedTips.value + key
        prefs.edit().putStringSet("tips_dismissed", _dismissedTips.value).apply()
    }

    fun setContinuous(on: Boolean) { _continuous.value = on; prefs.edit().putBoolean("continuous", on).apply() }

    fun addRecent(m: Media, live: Boolean = false) {
        if (m.isLocal) return
        val r00 = Recent(m.title, m.url, m.mime, m.headers, m.pageUrl, System.currentTimeMillis(),
            m.backdropUrl ?: m.posterUrl, m.fileName, m.season, m.episode, live = live, nextUrl = m.nextUrl) // image large : elle remplit la carte
        // Une chaîne garde le nom qu'on lui a donné.
        val r0 = r00.copy(channel = _recents.value.firstOrNull { it.key == r00.key || it.url == r00.url }?.channel ?: m.channel)
        // Même série : le nouvel épisode remplace l'ancien (et garde son image si celui-ci n'en a pas).
        val old = _recents.value.filter { it.key == r0.key || it.url == r0.url }
        val r = if (r0.poster == null) r0.copy(poster = old.firstNotNullOfOrNull { it.poster } ?: bookmarkFor(m.pageUrl ?: "")?.image) else r0
        _recents.value = (listOf(r) + (_recents.value - old.toSet())).take(60)
        saveRecents()
    }

    fun renameChannel(key: String, name: String) {
        _recents.value = _recents.value.map { if (it.key == key) it.copy(channel = name.trim().ifBlank { null }) else it }
        saveRecents()
    }

    /** Retire de « Reprendre » toute la série (et son favori, s'il y en a un, sinon elle y reviendrait). */
    fun removeRecent(key: String) {
        val gone = _recents.value.filter { it.key == key }
        _recents.value = _recents.value - gone.toSet()
        saveRecents()
        _bookmarks.value.filter { b -> b.isSeries && (b.key == key || gone.any { b.sameSeries(it) }) }.forEach { removeBookmark(it) }
    }
    fun removeRecent(r: Recent) = removeRecent(r.key)

    /** Avancement de la lecture en cours (noté régulièrement), pour ne pas proposer de reprendre ce qui est fini. */
    fun setProgress(url: String, pos: Double, dur: Double) {
        if (_recents.value.none { it.url == url }) return
        _recents.value = _recents.value.map { if (it.url == url) it.copy(pos = pos, dur = dur) else it }
        saveRecents()
    }

    /** Position où reprendre la vidéo de cette page (quelques secondes avant l'arrêt), ou 0. */
    fun resumeAt(pageUrl: String): Double {
        val r = _recents.value.firstOrNull { it.pageUrl == pageUrl } ?: return 0.0
        return if (r.finished || r.pos < 30) 0.0 else r.pos - 5
    }

    fun markFinished(url: String) {
        if (_recents.value.none { it.url == url }) return
        _recents.value = _recents.value.map { if (it.url == url) it.copy(done = true) else it }
        saveRecents()
    }

    private val _hiddenSites = MutableStateFlow<Set<String>>(emptySet())
    /** Sites que l'utilisateur ne veut plus voir proposés dans « Mes sites ». */
    val hiddenSites: StateFlow<Set<String>> = _hiddenSites
    fun hideSite(host: String) {
        _hiddenSites.value = _hiddenSites.value + host
        prefs.edit().putStringSet("hidden_sites", _hiddenSites.value).apply()
        _bookmarks.value.filter { it.isSite && it.host == host }.forEach { removeBookmark(it) }
    }
    fun clearRecents() { _recents.value = emptyList(); saveRecents() }

    /** La page appartient-elle à une série (ou un site) déjà en favori ? */
    fun bookmarkFor(url: String): Bookmark? {
        val k = studio.rollando.castor.browser.Episodes.seriesKey(url)
        return _bookmarks.value.firstOrNull { it.url == url || it.key == k }
    }

    fun isBookmarked(url: String) = bookmarkFor(url) != null

    fun removeBookmark(b: Bookmark) { _bookmarks.value = _bookmarks.value - b; saveBookmarks() }

    /**
     * Le marque-page avance : on caste (ou on ouvre un épisode plus loin de) une série suivie. Le favori pointe
     * alors sur cette page et remonte en tête de liste. [onlyForward] : seulement si l'épisode est plus loin.
     */
    fun advanceBookmark(url: String, onlyForward: Boolean = false, image: String? = null) {
        val b = bookmarkFor(url) ?: return
        // Un favori de site ne bouge jamais ; une série n'avance que vers un épisode de la même série.
        if (b.isSite || b.key != studio.rollando.castor.browser.Episodes.seriesKey(url)) return
        val ep = studio.rollando.castor.browser.Episodes.find(url)?.number ?: return
        if (b.url == url) { if (!onlyForward) touchBookmark(b); return }
        if (onlyForward && (b.episode == null || ep <= b.episode)) return
        _bookmarks.value = listOf(b.copy(url = url, episode = ep, time = System.currentTimeMillis(), image = image ?: b.image)) +
            (_bookmarks.value - b)
        saveBookmarks()
        _events.tryEmit(BookmarkEvent.Advanced(b.title, ep))
    }

    fun setRecentImage(page: String, image: String) {
        if (_recents.value.none { it.pageUrl == page && it.poster == null }) return
        _recents.value = _recents.value.map { if (it.pageUrl == page && it.poster == null) it.copy(poster = image) else it }
        saveRecents()
    }

    /** L'image de la page arrive après coup : on illustre le favori qui pointe sur cette page, s'il n'en a pas. */
    fun setBookmarkImage(url: String, image: String) {
        // La carte « Reprendre » de cette page en profite aussi.
        if (_recents.value.any { it.pageUrl == url && it.poster == null }) {
            _recents.value = _recents.value.map { if (it.pageUrl == url && it.poster == null) it.copy(poster = image) else it }
            saveRecents()
        }
        val b = _bookmarks.value.firstOrNull { it.url == url && it.image == null } ?: return
        _bookmarks.value = _bookmarks.value.map { if (it === b) it.copy(image = image) else it }
        saveBookmarks()
    }

    private fun touchBookmark(b: Bookmark) {
        _bookmarks.value = listOf(b.copy(time = System.currentTimeMillis())) + (_bookmarks.value - b)
        saveBookmarks()
    }

    private fun saveBookmarks() {
        prefs.edit().putString("bookmarks", JSONArray().apply {
            _bookmarks.value.forEach {
                put(JSONObject().put("title", it.title).put("url", it.url).put("ep", it.episode ?: -1).put("time", it.time).put("img", it.image ?: ""))
            }
        }.toString()).apply()
    }

    fun hostOf(url: String): String = runCatching { android.net.Uri.parse(url).host?.removePrefix("www.") }.getOrNull().orEmpty()

    private val _events = kotlinx.coroutines.flow.MutableSharedFlow<BookmarkEvent>(extraBufferCapacity = 4)
    val events: kotlinx.coroutines.flow.SharedFlow<BookmarkEvent> = _events

    fun toggleBookmark(title: String, url: String, image: String? = null) {
        val existing = bookmarkFor(url)
        if (existing != null) {
            _bookmarks.value = _bookmarks.value - existing
        } else {
            val ep = studio.rollando.castor.browser.Episodes.find(url)
            hostOf(url).let { h -> if (h in _hiddenSites.value) {
                _hiddenSites.value = _hiddenSites.value - h
                prefs.edit().putStringSet("hidden_sites", _hiddenSites.value).apply()
            } }
            val b = Bookmark(title.ifBlank { url }, url, ep?.number ?: studio.rollando.castor.browser.PageTitle.episode(title),
                System.currentTimeMillis(), image)
            _bookmarks.value = listOf(b) + _bookmarks.value
            _events.tryEmit(BookmarkEvent.Followed(b.title, series = !b.isSite && b.episode != null))
        }
        saveBookmarks()
    }

    private fun saveRecents() {
        val arr = JSONArray()
        for (r in _recents.value) arr.put(JSONObject().apply {
            put("title", r.title); put("url", r.url); put("mime", r.mime ?: ""); put("page", r.pageUrl ?: ""); put("poster", r.poster ?: ""); put("file", r.fileName ?: "")
            put("season", r.season ?: -1); put("ep", r.episode ?: -1)
            put("pos", r.pos); put("dur", r.dur); put("done", r.done)
            put("live", r.live); put("channel", r.channel ?: ""); put("next", r.nextUrl ?: "")
            put("time", r.time); put("headers", JSONObject(r.headers as Map<*, *>))
        })
        prefs.edit().putString("recents", arr.toString()).apply()
    }

    private fun readRecents(a: JSONArray) = (0 until a.length()).map { i ->
        val o = a.getJSONObject(i)
        val h = o.optJSONObject("headers")
        Recent(
            title = o.getString("title"),
            url = o.getString("url"),
            mime = o.optString("mime").ifEmpty { null },
            headers = h?.keys()?.asSequence()?.associateWith { h.getString(it) } ?: emptyMap(),
            pageUrl = o.optString("page").ifEmpty { null },
            poster = o.optString("poster").ifEmpty { null },
            fileName = o.optString("file").ifEmpty { null },
            time = o.optLong("time"),
            season = o.optInt("season", -1).takeIf { it >= 0 },
            episode = o.optInt("ep", -1).takeIf { it >= 0 },
            pos = o.optDouble("pos", 0.0), dur = o.optDouble("dur", 0.0), done = o.optBoolean("done", false),
            live = o.optBoolean("live", false), channel = o.optString("channel").ifEmpty { null },
            nextUrl = o.optString("next").ifEmpty { null },
        )
    }.distinctBy { it.key } // anciennes listes : un épisode par entrée

    private fun readBookmarks(a: JSONArray) = (0 until a.length()).map { i ->
        val o = a.getJSONObject(i)
        Bookmark(o.getString("title"), o.getString("url"), o.optInt("ep", -1).takeIf { it >= 0 }, o.optLong("time"),
            o.optString("img").ifEmpty { null })
    }
}
