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
) {
    fun toMedia() = Media(url = url, title = title, mime = mime, headers = headers, pageUrl = pageUrl, posterUrl = poster, fileName = fileName)
}

data class Bookmark(val title: String, val url: String)

/** Dernière page d'un site où l'on a lancé une vidéo (l'épisode où l'on en est). */
data class SiteVisit(val url: String, val title: String, val episode: Int?, val time: Long)

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
        _sites.value = runCatching {
            val o = JSONObject(prefs.getString("sites", "{}") ?: "{}")
            o.keys().asSequence().associateWith { h ->
                val v = o.getJSONObject(h)
                SiteVisit(v.getString("url"), v.optString("title"), v.optInt("ep", -1).takeIf { it >= 0 }, v.optLong("time"))
            }
        }.getOrDefault(emptyMap())
        _adBlock.value = prefs.getBoolean("adblock", true)
        _continuous.value = prefs.getBoolean("continuous", true)
        _shakeHelp.value = prefs.getBoolean("shake_help", true)
        _embedSubs.value = prefs.getBoolean("embed_subs", true)
        _smoothSlides.value = prefs.getBoolean("smooth_slides_v2", false)
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

    private val _smoothSlides = MutableStateFlow(false)
    /** Diaporama envoyé comme une seule vidéo (pas d'écran noir entre les photos). */
    val smoothSlides: StateFlow<Boolean> = _smoothSlides
    fun setSmoothSlides(on: Boolean) { _smoothSlides.value = on; prefs.edit().putBoolean("smooth_slides_v2", on).apply() }

    private val _shakeHelp = MutableStateFlow(true)
    /** Secouer le téléphone ouvre l'aide. */
    val shakeHelp: StateFlow<Boolean> = _shakeHelp
    fun setShakeHelp(on: Boolean) { _shakeHelp.value = on; prefs.edit().putBoolean("shake_help", on).apply() }

    private val _dismissedTips = MutableStateFlow<Set<String>>(emptySet())
    /** Conseils que l'utilisateur a choisi de ne plus voir. */
    val dismissedTips: StateFlow<Set<String>> = _dismissedTips

    fun dismissTip(key: String) {
        _dismissedTips.value = _dismissedTips.value + key
        prefs.edit().putStringSet("tips_dismissed", _dismissedTips.value).apply()
    }

    fun setContinuous(on: Boolean) { _continuous.value = on; prefs.edit().putBoolean("continuous", on).apply() }

    fun addRecent(m: Media) {
        if (m.isLocal) return
        val r = Recent(m.title, m.url, m.mime, m.headers, m.pageUrl, System.currentTimeMillis(), m.posterUrl, m.fileName)
        _recents.value = (listOf(r) + _recents.value.filter { it.url != m.url }).take(30)
        saveRecents()
    }

    fun removeRecent(r: Recent) { _recents.value = _recents.value - r; saveRecents() }
    fun clearRecents() { _recents.value = emptyList(); saveRecents() }

    fun isBookmarked(url: String) = _bookmarks.value.any { it.url == url }

    private val _sites = MutableStateFlow<Map<String, SiteVisit>>(emptyMap())
    /** Par site (domaine) : la dernière page d'où l'on a casté, pour reprendre la série là où on l'a laissée. */
    val sites: StateFlow<Map<String, SiteVisit>> = _sites

    fun hostOf(url: String): String = runCatching { android.net.Uri.parse(url).host?.removePrefix("www.") }.getOrNull().orEmpty()

    fun rememberSiteVisit(url: String, title: String) {
        val host = hostOf(url).ifEmpty { return }
        val ep = studio.rollando.castor.browser.Episodes.find(url)?.number
        _sites.value = (_sites.value + (host to SiteVisit(url, title, ep, System.currentTimeMillis())))
            .entries.sortedByDescending { it.value.time }.take(30).associate { it.key to it.value }
        prefs.edit().putString("sites", JSONObject().apply {
            _sites.value.forEach { (h, v) ->
                put(h, JSONObject().put("url", v.url).put("title", v.title).put("ep", v.episode ?: -1).put("time", v.time))
            }
        }.toString()).apply()
    }

    fun toggleBookmark(title: String, url: String) {
        _bookmarks.value = if (isBookmarked(url)) _bookmarks.value.filter { it.url != url }
        else _bookmarks.value + Bookmark(title.ifBlank { url }, url)
        prefs.edit().putString("bookmarks", JSONArray().apply {
            _bookmarks.value.forEach { put(JSONObject().put("title", it.title).put("url", it.url)) }
        }.toString()).apply()
    }

    private fun saveRecents() {
        val arr = JSONArray()
        for (r in _recents.value) arr.put(JSONObject().apply {
            put("title", r.title); put("url", r.url); put("mime", r.mime ?: ""); put("page", r.pageUrl ?: ""); put("poster", r.poster ?: ""); put("file", r.fileName ?: "")
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
        )
    }

    private fun readBookmarks(a: JSONArray) = (0 until a.length()).map { i ->
        val o = a.getJSONObject(i)
        Bookmark(o.getString("title"), o.getString("url"))
    }
}
