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
        _embedSubs.value = prefs.getBoolean("embed_subs", true)
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
