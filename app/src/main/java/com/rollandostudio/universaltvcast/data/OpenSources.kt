package com.rollandostudio.universaltvcast.data

import android.content.Context
import com.rollandostudio.universaltvcast.cast.Media
import com.rollandostudio.universaltvcast.lan.Catalog
import com.rollandostudio.universaltvcast.lan.LanDevice
import com.rollandostudio.universaltvcast.lan.LanEntry
import com.rollandostudio.universaltvcast.lan.LanStore
import com.rollandostudio.universaltvcast.lan.MediaServers
import com.rollandostudio.universaltvcast.lan.Smb
import com.rollandostudio.universaltvcast.net.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/**
 * Sources ouvertes qu'on ajoute d'un toucher (écran « Sources ») : des catalogues publics, faits pour être réutilisés,
 * sans clé ni compte, et légaux :
 * - **PeerTube** : vidéos libres des instances PeerTube, par le moteur Sepia Search (tenu par Framasoft, qui ne
 *   référence que des instances modérées) ;
 * - **Internet Archive** : seulement ses collections tenues (longs et courts métrages, archives Prelinger, films muets,
 *   dessins animés classiques) sous licence domaine public ou Creative Commons.
 * Plus « Films libres » (catalogue rangé dans l'appli, voir [Xtream.FREE]).
 */
object OpenSources {
    enum class Source { PEERTUBE, ARCHIVE }

    private var prefs: android.content.SharedPreferences? = null
    private val _on = MutableStateFlow<Set<Source>>(emptySet())
    val on: StateFlow<Set<Source>> = _on

    fun init(ctx: Context) {
        prefs = ctx.getSharedPreferences("sources", Context.MODE_PRIVATE)
        _on.value = prefs!!.getStringSet("on", emptySet())!!.mapNotNull { runCatching { Source.valueOf(it) }.getOrNull() }.toSet()
    }

    fun set(s: Source, enabled: Boolean) {
        _on.value = if (enabled) _on.value + s else _on.value - s
        prefs?.edit()?.putStringSet("on", _on.value.map { it.name }.toSet())?.apply()
    }

    /** Un résultat de recherche, d'où qu'il vienne : de quoi l'afficher, et de quoi le lancer (adresse trouvée au toucher). */
    data class Hit(val key: String, val title: String, val image: String?, val sub: String?, val wide: Boolean, val open: suspend () -> Media?)

    private val UNSAFE = Regex("(?i)\\b(sex|nude|nudi|naked|erotic|porn|xxx|nazi)")
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    private fun json(url: String) = JSONObject(String(Http.fetch(url, emptyMap(), connectTimeout = 10_000).bytes))

    // ---- PeerTube ----
    suspend fun peertube(query: String): List<Hit> = withContext(Dispatchers.IO) {
        val url = "https://sepiasearch.org/api/v1/search/videos?search=${enc(query)}&count=30&nsfw=false&isLive=false"
        val data = json(url).optJSONArray("data") ?: JSONArray()
        (0 until data.length()).mapNotNull { i ->
            val v = data.getJSONObject(i)
            val name = v.optString("name").trim()
            val uuid = v.optString("uuid")
            val host = v.optJSONObject("account")?.optString("host")?.ifEmpty { null } ?: return@mapNotNull null
            if (name.isEmpty() || uuid.isEmpty() || UNSAFE.containsMatchIn(name)) return@mapNotNull null
            val channel = v.optJSONObject("channel")?.optString("displayName")?.ifEmpty { null }
            val dur = v.optInt("duration").takeIf { it > 0 }?.let { "%d:%02d".format(it / 60, it % 60) }
            Hit("pt:$uuid", name, v.optString("thumbnailUrl").ifEmpty { null }, listOfNotNull(channel, dur).joinToString(" · "), wide = true) {
                peertubeMedia(host, uuid, name, v.optString("thumbnailUrl").ifEmpty { null }, v.optString("url"))
            }
        }
    }

    /** L'adresse de la vidéo chez son instance : la liste HLS (toutes qualités), sinon le meilleur fichier. */
    private suspend fun peertubeMedia(host: String, uuid: String, name: String, thumb: String?, page: String): Media? = withContext(Dispatchers.IO) {
        val v = json("https://$host/api/v1/videos/$uuid")
        val hls = v.optJSONArray("streamingPlaylists")?.optJSONObject(0)?.optString("playlistUrl")?.ifEmpty { null }
        val file = v.optJSONArray("files")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
            ?.maxByOrNull { it.optJSONObject("resolution")?.optInt("id") ?: 0 }?.optString("fileUrl")?.ifEmpty { null }
        val src = hls ?: file ?: return@withContext null
        Media(url = src, title = name, mime = if (hls != null) "application/vnd.apple.mpegurl" else null,
            posterUrl = thumb, backdropUrl = thumb, pageUrl = page.ifEmpty { null })
    }

    // ---- Internet Archive ----
    private const val ARCHIVE_SCOPE = "mediatype:movies AND collection:(feature_films OR short_films OR prelinger OR silent_films OR " +
        "classic_cartoons OR animationandcartoons) AND (licenseurl:*publicdomain* OR licenseurl:*creativecommons*)"

    suspend fun archive(query: String): List<Hit> = withContext(Dispatchers.IO) {
        val q = "title:(${query.replace(Regex("[():\"]"), " ")}) AND $ARCHIVE_SCOPE"
        val url = "https://archive.org/advancedsearch.php?q=${enc(q)}&fl[]=identifier&fl[]=title&fl[]=year&sort[]=downloads+desc&rows=30&output=json"
        val docs = json(url).optJSONObject("response")?.optJSONArray("docs") ?: JSONArray()
        (0 until docs.length()).mapNotNull { i ->
            val d = docs.getJSONObject(i)
            val id = d.optString("identifier").ifEmpty { return@mapNotNull null }
            val title = d.opt("title").let { if (it is JSONArray) it.optString(0) else it?.toString() }?.trim().orEmpty()
            if (title.isEmpty() || UNSAFE.containsMatchIn(title)) return@mapNotNull null
            val year = d.opt("year")?.toString()?.take(4)?.takeIf { it.all(Char::isDigit) }
            Hit("ia:$id", title, "https://archive.org/services/img/$id", year, wide = false) { archiveMedia(id, title + (year?.let { " ($it)" } ?: "")) }
        }
    }

    /** La vidéo MP4 la plus légère qui reste correcte (h.264, sinon 512 kb/s). */
    private suspend fun archiveMedia(id: String, title: String): Media? = withContext(Dispatchers.IO) {
        val files = json("https://archive.org/metadata/$id").optJSONArray("files") ?: return@withContext null
        val mp4 = (0 until files.length()).map { files.getJSONObject(it) }.filter { it.optString("name").endsWith(".mp4", true) }
        val best = mp4.minByOrNull { f ->
            val fmt = f.optString("format").lowercase()
            when { "h.264" in fmt && "hd" !in fmt -> 0; "512kb" in fmt -> 1; else -> 2 }
        } ?: return@withContext null
        val name = best.optString("name").split('/').joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
        Media(url = "https://archive.org/download/$id/$name", title = title, posterUrl = "https://archive.org/services/img/$id",
            pageUrl = "https://archive.org/details/$id")
    }

    // ---- Réseau de la maison ----
    /** Index des dossiers partagés suivis (★), refait au plus toutes les 30 minutes : vidéos trouvées en profondeur. */
    @Volatile private var lanIndex: List<LanEntry> = emptyList()
    @Volatile private var lanIndexAt = 0L

    private fun indexShares(): List<LanEntry> {
        if (System.currentTimeMillis() - lanIndexAt < 30 * 60_000L) return lanIndex
        val out = ArrayList<LanEntry>()
        for (f in LanStore.folders.value.filter { it.kind == LanDevice.Kind.SHARE }) {
            val d = f.device()
            fun walk(ref: String, depth: Int) {
                if (depth > 4 || out.size > 5000) return
                val list = runCatching { Smb.list(d, ref) }.getOrDefault(emptyList())
                list.filter { it.kind == LanEntry.Kind.VIDEO }.forEach { out += it }
                list.filter { it.folder }.forEach { walk(it.ref, depth + 1) }
            }
            walk(f.ref, 0)
        }
        lanIndex = out; lanIndexAt = System.currentTimeMillis()
        return out
    }

    private fun fold(s: String) = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "").lowercase().replace(Regex("[._]+"), " ")

    /** Les vidéos du réseau dont le nom contient [query] : serveurs multimédias (ils cherchent eux-mêmes), dossiers suivis. */
    suspend fun lan(query: String): List<Hit> = withContext(Dispatchers.IO) {
        val q = fold(query.trim())
        val found = ArrayList<LanEntry>()
        for (s in LanStore.all().filter { it.kind == LanDevice.Kind.MEDIA_SERVER }) {
            val d = s.location?.let { runCatching { MediaServers.describe(it) }.getOrNull() }
                ?: runCatching { MediaServers.probe(s.host, s.port.takeIf { it > 0 }) }.getOrNull() ?: continue
            MediaServers.search(d, query)?.let { found += it }
        }
        found += indexShares().filter { fold(it.name).contains(q) }
        found.distinctBy { it.ref }.take(60).map { e ->
            val info = Catalog.parse(e.name)
            val title = if (info.isEpisode) "${info.title} S%02dE%02d".format(info.season, info.episode) else info.title
            Hit("lan:${e.ref}", title, e.thumb, e.device?.name, wide = false) {
                val d = e.device!!
                val url = if (d.kind == LanDevice.Kind.SHARE) Smb.contentUri(d, e.ref).toString() else e.ref
                Media(url = url, title = title, mime = e.mime, fileName = e.name, posterUrl = e.thumb,
                    season = info.season, episode = info.episode).also { LanStore.remember(it, d) }
            }
        }
    }

    /** Les appareils du réseau enregistrés (pour montrer l'onglet Rechercher à qui a un NAS). */
    fun hasLan(): Boolean = LanStore.all().isNotEmpty() || LanStore.folders.value.isNotEmpty()
}
