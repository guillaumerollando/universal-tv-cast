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
    /** [caption] : l'image n'est pas une affiche (vignette, image du film) : la carte y écrit le titre. */
    data class Hit(val key: String, val title: String, val image: String?, val sub: String?, val wide: Boolean,
                   val caption: Boolean = true, val open: suspend () -> Media?)

    private val UNSAFE = Regex("(?i)\\b(sex|nude|nudi|naked|erotic|porn|xxx|nazi)")
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    private fun json(url: String) = JSONObject(String(Http.fetch(url, emptyMap(), connectTimeout = 10_000).bytes))

    // ---- PeerTube ----
    suspend fun peertube(query: String): List<Hit> = withContext(Dispatchers.IO) {
        // Licence libre obligatoire (Creative Commons ou domaine public), et pas la catégorie « Films » : c'est là
        // que se glissent les films mis en ligne sans droits (souvent sans licence, parfois avec une fausse).
        val licences = (1..7).joinToString("") { "&licenceOneOf=$it" }
        val base = "https://sepiasearch.org/api/v1/search/videos?search=${enc(query)}&count=40&nsfw=false&isLive=false$licences"
        // Dans la langue du téléphone d'abord (et les vidéos sans langue indiquée) ; trop peu : toutes les langues.
        val lang = java.util.Locale.getDefault().language
        val local = runCatching { json("$base&languageOneOf=$lang&languageOneOf=_unknown").optJSONArray("data") }.getOrNull()
        val data = local?.takeIf { it.length() >= 6 } ?: json(base).optJSONArray("data") ?: JSONArray()
        (0 until data.length()).mapNotNull { i ->
            val v = data.getJSONObject(i)
            if (v.optJSONObject("category")?.optInt("id") == 2) return@mapNotNull null
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

    /**
     * Vidéos récentes d'une chaîne PeerTube choisie et sûre (son instance officielle) : de quoi montrer PeerTube sur
     * l'accueil sans passer par une recherche par thème, où se mêlent vidéos douteuses et films sans droits.
     */
    private suspend fun peertubeChannel(host: String, path: String): List<Hit> = withContext(Dispatchers.IO) {
        val data = json("https://$host$path").optJSONArray("data") ?: JSONArray()
        (0 until data.length()).mapNotNull { i ->
            val v = data.getJSONObject(i)
            val uuid = v.optString("uuid").ifEmpty { return@mapNotNull null }
            val name = v.optString("name").trim()
            val thumb = v.optString("thumbnailPath").takeIf { it.startsWith("/") }?.let { "https://$host$it" }
            Hit("pt:$uuid", name, thumb, null, wide = true) { peertubeMedia(host, uuid, name, thumb, "https://$host/w/$uuid") }
        }
    }

    // ---- Internet Archive ----
    // Licence libre déclarée, ou collection du domaine public par nature (archives Prelinger, films muets, dessins
    // animés anciens) : beaucoup de ces films n'ont pas le champ « licence » rempli.
    private const val ARCHIVE_SCOPE = "mediatype:movies AND collection:(feature_films OR short_films OR prelinger OR silent_films OR " +
        "classic_cartoons OR animationandcartoons) AND (licenseurl:*publicdomain* OR licenseurl:*creativecommons* OR " +
        "collection:(prelinger OR silent_films OR classic_cartoons))"

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

    /** Les plus regardés d'une collection tenue par Internet Archive (domaine public ou Creative Commons). */
    private suspend fun archiveCollection(collection: String): List<Hit> = withContext(Dispatchers.IO) {
        val q = "collection:$collection AND mediatype:movies AND (licenseurl:*publicdomain* OR licenseurl:*creativecommons*)"
        val url = "https://archive.org/advancedsearch.php?q=${enc(q)}&fl[]=identifier&fl[]=title&fl[]=year&sort[]=downloads+desc&rows=24&output=json"
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

    /** Une rangée de l'accueil venue d'une source ouverte : son titre (ressource), ses vidéos. */
    data class Row(val title: Int, val hits: List<Hit>)
    private val rowsCache = HashMap<String, Pair<Long, List<Hit>>>()

    /** Rangées de l'accueil pour les sources ouvertes ajoutées (gardées 6 h ; une rangée injoignable est sautée). */
    suspend fun homeRows(): List<Row> {
        val on = _on.value
        val wanted = buildList {
            if (Source.PEERTUBE in on) add(Triple("pt-blender", com.rollandostudio.universaltvcast.R.string.open_peertube,
                suspend { peertubeChannel("video.blender.org", "/api/v1/videos?sort=-publishedAt&count=24&isLocal=true") }))
            if (Source.ARCHIVE in on) {
                add(Triple("ia-feature", com.rollandostudio.universaltvcast.R.string.open_ia_feature, suspend { archiveCollection("feature_films") }))
                add(Triple("ia-cartoons", com.rollandostudio.universaltvcast.R.string.open_ia_cartoons, suspend { archiveCollection("classic_cartoons") }))
                add(Triple("ia-silent", com.rollandostudio.universaltvcast.R.string.open_ia_silent, suspend { archiveCollection("silent_films") }))
                add(Triple("ia-prelinger", com.rollandostudio.universaltvcast.R.string.open_ia_prelinger, suspend { archiveCollection("prelinger") }))
            }
        }
        return wanted.mapNotNull { (key, title, load) ->
            val cached = synchronized(rowsCache) { rowsCache[key] }?.takeIf { System.currentTimeMillis() - it.first < 6 * 3600_000L }?.second
            val hits = cached ?: runCatching { load() }.getOrNull()?.also { synchronized(rowsCache) { rowsCache[key] = System.currentTimeMillis() to it } }
            hits?.takeIf { it.isNotEmpty() }?.let { Row(title, it) }
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
            Hit("lan:${e.ref}", title, e.thumb, e.device?.name, wide = false, caption = false) {
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
