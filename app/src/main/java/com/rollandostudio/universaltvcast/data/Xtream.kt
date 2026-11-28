package com.rollandostudio.universaltvcast.data

import android.content.Context
import android.util.JsonReader
import android.util.JsonToken
import com.rollandostudio.universaltvcast.cast.Media
import com.rollandostudio.universaltvcast.net.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.InputStreamReader

/**
 * Films et séries d'un abonnement IPTV « Xtream » (la plupart des fournisseurs), lus par son interface standard
 * (`player_api.php`), comme le font les lecteurs IPTV du Store. L'appli ne fournit aucun contenu : elle présente ce
 * que le fournisseur de l'utilisateur lui sert avec ses propres identifiants (ceux de la liste qu'il a importée).
 *
 * Les catalogues sont gros (des dizaines de milliers de films) : lus au fil de l'eau, gardés 12 h en cache.
 */
object Xtream {
    /** Un compte : l'adresse du serveur et les identifiants pris dans l'adresse de la liste « …/get.php?username=…&password=… ». */
    data class Account(val base: String, val user: String, val pass: String) {
        val key: String get() = Integer.toHexString("$base|$user".hashCode())
        fun api(action: String, extra: String = "") = "${base}player_api.php?username=${enc(user)}&password=${enc(pass)}&action=$action$extra"
    }

    data class Category(val id: String, val name: String)
    /** [url] : adresse complète (catalogue libre) ; sinon elle se déduit du compte. */
    data class Movie(val id: String, val name: String, val poster: String?, val rating: Double?, val category: String?, val ext: String, val added: Long,
                     val url: String? = null, val year: Int? = null)
    data class Series(val id: String, val name: String, val cover: String?, val rating: Double?, val category: String?, val plot: String?, val year: String?)
    data class Episode(val id: String, val season: Int, val number: Int, val title: String, val ext: String, val image: String?, val plot: String?)
    data class SeriesInfo(val plot: String?, val backdrop: String?, val episodes: List<Episode>)

    enum class Kind { MOVIES, SERIES }

    private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8")

    fun accountOf(source: String): Account? {
        val u = runCatching { java.net.URI(source) }.getOrNull() ?: return null
        val path = u.path.orEmpty()
        if (!path.endsWith("/get.php") && !path.endsWith("/player_api.php")) return null
        val q = u.rawQuery.orEmpty().split('&').associate {
            it.substringBefore('=') to java.net.URLDecoder.decode(it.substringAfter('=', ""), "UTF-8")
        }
        val user = q["username"]?.takeIf { it.isNotEmpty() } ?: return null
        val pass = q["password"] ?: return null
        return Account(source.substringBefore(path.substringAfterLast('/')), user, pass)
    }

    /**
     * Catalogue « Films libres », rangé dans l'appli (`assets/free-catalog.json`, fait par `tools/free_catalog.py`) :
     * films ouverts de la Blender Foundation et longs métrages du domaine public d'Internet Archive. Présenté comme un
     * abonnement ; on l'ajoute d'un toucher (« Pour commencer », écran Chaînes) et on le retire comme une liste.
     */
    val FREE = Account("libre:", "", "")
    private val _freeOn = kotlinx.coroutines.flow.MutableStateFlow(false)
    val freeOn: kotlinx.coroutines.flow.StateFlow<Boolean> = _freeOn
    fun setFree(on: Boolean) { _freeOn.value = on; prefs?.edit()?.putBoolean("free", on)?.apply() }
    /** Genres du catalogue libre → leur nom dans la langue du téléphone. */
    private val FREE_GENRES = linkedMapOf(
        "blender" to com.rollandostudio.universaltvcast.R.string.free_cat_blender,
        "classic" to com.rollandostudio.universaltvcast.R.string.free_cat_classic,
        "comedy" to com.rollandostudio.universaltvcast.R.string.free_cat_comedy,
        "noir" to com.rollandostudio.universaltvcast.R.string.free_cat_noir,
        "horror" to com.rollandostudio.universaltvcast.R.string.free_cat_horror,
        "scifi" to com.rollandostudio.universaltvcast.R.string.free_cat_scifi,
        "western" to com.rollandostudio.universaltvcast.R.string.free_cat_western,
        "adventure" to com.rollandostudio.universaltvcast.R.string.free_cat_adventure,
        "drama" to com.rollandostudio.universaltvcast.R.string.free_cat_drama,
        "animation" to com.rollandostudio.universaltvcast.R.string.free_cat_animation,
    )
    private var app: Context? = null

    /** Les comptes Xtream parmi les listes importées (un seul par serveur et identifiant), plus le catalogue libre s'il est ajouté. */
    fun accounts(lists: List<IptvStore.Playlist>, free: Boolean = _freeOn.value): List<Account> =
        lists.mapNotNull { accountOf(it.source) }.distinctBy { it.key } + listOfNotNull(FREE.takeIf { free })

    fun movieUrl(a: Account, m: Movie) = m.url ?: "${a.base}movie/${a.user}/${a.pass}/${m.id}.${m.ext}"
    fun episodeUrl(a: Account, e: Episode) = "${a.base}series/${a.user}/${a.pass}/${e.id}.${e.ext}"

    // ---- Cache ----
    private var dir: File? = null
    private const val TTL = 12 * 3600_000L
    private val memory = HashMap<String, Any>()

    private var prefs: android.content.SharedPreferences? = null

    fun init(ctx: Context) {
        app = ctx.applicationContext
        dir = File(ctx.cacheDir, "xtream").apply { mkdirs() }
        prefs = ctx.getSharedPreferences("xtream", Context.MODE_PRIVATE)
        _freeOn.value = prefs!!.getBoolean("free", false)
        // Films et épisodes déjà ouverts : de quoi les rouvrir après un redémarrage (« Reprendre », épisode suivant).
        runCatching {
            val o = JSONObject(prefs!!.getString("known", "{}")!!)
            o.keys().forEach { url ->
                val m = o.getJSONObject(url)
                known[url] = Media(url = url, title = m.optString("t"), posterUrl = m.optString("p").ifEmpty { null },
                    backdropUrl = m.optString("b").ifEmpty { null }, fileName = m.optString("f").ifEmpty { null },
                    season = m.optInt("s", -1).takeIf { it >= 0 }, episode = m.optInt("e", -1).takeIf { it >= 0 },
                    nextUrl = m.optString("n").ifEmpty { null })
            }
        }
    }

    /** Garde les 400 derniers films et épisodes connus. */
    private fun save() {
        val p = prefs ?: return
        val o = JSONObject()
        known.values.toList().takeLast(400).forEach { m ->
            o.put(m.url, JSONObject().put("t", m.title).put("p", m.posterUrl ?: "").put("b", m.backdropUrl ?: "")
                .put("f", m.fileName ?: "").put("s", m.season ?: -1).put("e", m.episode ?: -1).put("n", m.nextUrl ?: ""))
        }
        p.edit().putString("known", o.toString()).apply()
    }

    /** Le corps de la réponse, depuis le cache s'il a moins de 12 h, sinon depuis le serveur (et gardé). */
    private fun cached(name: String, url: String, fresh: Boolean): File {
        val f = File(dir ?: error("Xtream.init"), name)
        if (!fresh && f.exists() && System.currentTimeMillis() - f.lastModified() < TTL) return f
        val c = Http.open(url, emptyMap(), null, connectTimeout = 15_000, readTimeout = 60_000)
        try {
            if (c.responseCode !in 200..299) throw java.io.IOException("HTTP ${c.responseCode}")
            val tmp = File(f.path + ".part")
            c.inputStream.use { ins -> tmp.outputStream().use { ins.copyTo(it) } }
            tmp.renameTo(f)
        } finally { c.disconnect() }
        return f
    }

    @Suppress("UNCHECKED_CAST")
    suspend fun categories(a: Account, kind: Kind, fresh: Boolean = false): List<Category> = withContext(Dispatchers.IO) {
        val key = "${a.key}-${kind}-cat"
        if (a == FREE) return@withContext if (kind == Kind.SERIES) emptyList()
            else FREE_GENRES.map { (k, r) -> Category(k, app?.getString(r) ?: k) }
        if (!fresh) (memory[key] as? List<Category>)?.let { return@withContext it }
        val action = if (kind == Kind.MOVIES) "get_vod_categories" else "get_series_categories"
        val out = ArrayList<Category>()
        readArray(cached("$key.json", a.api(action), fresh)) { o ->
            val id = o["category_id"] ?: return@readArray
            out += Category(id, o["category_name"]?.trim().orEmpty().ifEmpty { id })
        }
        memory[key] = out
        out
    }

    @Suppress("UNCHECKED_CAST")
    suspend fun movies(a: Account, fresh: Boolean = false): List<Movie> = withContext(Dispatchers.IO) {
        val key = "${a.key}-movies"
        if (!fresh) (memory[key] as? List<Movie>)?.let { return@withContext it }
        if (a == FREE) return@withContext freeMovies().also { memory[key] = it }
        val out = ArrayList<Movie>()
        readArray(cached("$key.json", a.api("get_vod_streams"), fresh)) { o ->
            val id = o["stream_id"] ?: return@readArray
            out += Movie(id, o["name"]?.trim().orEmpty(), o["stream_icon"]?.takeIf { it.startsWith("http") },
                o["rating"]?.toDoubleOrNull()?.takeIf { it > 0 }, o["category_id"], o["container_extension"]?.ifEmpty { null } ?: "mp4",
                o["added"]?.toLongOrNull() ?: 0L)
        }
        memory[key] = out
        out
    }

    @Suppress("UNCHECKED_CAST")
    suspend fun series(a: Account, fresh: Boolean = false): List<Series> = withContext(Dispatchers.IO) {
        val key = "${a.key}-series"
        if (a == FREE) return@withContext emptyList()
        if (!fresh) (memory[key] as? List<Series>)?.let { return@withContext it }
        val out = ArrayList<Series>()
        readArray(cached("$key.json", a.api("get_series"), fresh)) { o ->
            val id = o["series_id"] ?: return@readArray
            out += Series(id, o["name"]?.trim().orEmpty(), o["cover"]?.takeIf { it.startsWith("http") },
                o["rating"]?.toDoubleOrNull()?.takeIf { it > 0 }, o["category_id"], o["plot"]?.takeIf { it.isNotBlank() },
                (o["releaseDate"] ?: o["release_date"] ?: o["year"])?.take(4)?.takeIf { it.all(Char::isDigit) && it.length == 4 })
        }
        memory[key] = out
        out
    }

    /** Les films du catalogue libre, dans l'ordre du fichier (Blender d'abord, puis les plus regardés). */
    private fun freeMovies(): List<Movie> = runCatching {
        val root = JSONObject(app!!.assets.open("free-catalog.json").bufferedReader().readText())
        val a = root.getJSONArray("movies")
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            val y = o.optInt("year").takeIf { it > 0 }
            Movie(o.getString("id"), o.getString("name") + (y?.let { " ($it)" } ?: ""), o.optString("poster").ifEmpty { null }, null,
                o.optString("category"), o.getString("url").substringAfterLast('.'), (a.length() - i).toLong(), o.getString("url"), y)
        }
    }.getOrElse { android.util.Log.w("TvCastIptv", "catalogue libre illisible", it); emptyList() }

    /** Saisons et épisodes d'une série (petit : lu d'un bloc). */
    suspend fun seriesInfo(a: Account, s: Series): SeriesInfo = withContext(Dispatchers.IO) {
        val f = cached("${a.key}-s${s.id}.json", a.api("get_series_info", "&series_id=${s.id}"), false)
        val root = JSONObject(f.readText())
        val info = root.optJSONObject("info")
        val backdrop = info?.optJSONArray("backdrop_path")?.optString(0)?.takeIf { it.startsWith("http") }
        val eps = ArrayList<Episode>()
        // « episodes » : { "1": [ … ], "2": [ … ] } (parfois une liste de listes).
        val byKey = root.opt("episodes")
        fun take(arr: org.json.JSONArray?, seasonHint: Int) {
            arr ?: return
            for (i in 0 until arr.length()) {
                val e = arr.optJSONObject(i) ?: continue
                val ei = e.optJSONObject("info")
                val id = e.optString("id")
                if (id.isEmpty()) continue
                eps += Episode(
                    id = id,
                    season = e.optString("season").toIntOrNull() ?: seasonHint,
                    number = e.optString("episode_num").toIntOrNull() ?: (i + 1),
                    title = e.optString("title").trim(),
                    ext = e.optString("container_extension").ifEmpty { "mp4" },
                    image = ei?.optString("movie_image")?.takeIf { it.startsWith("http") },
                    plot = ei?.optString("plot")?.takeIf { it.isNotBlank() },
                )
            }
        }
        when (byKey) {
            is JSONObject -> byKey.keys().forEach { k -> take(byKey.optJSONArray(k), k.toIntOrNull() ?: 1) }
            is org.json.JSONArray -> for (i in 0 until byKey.length()) take(byKey.optJSONArray(i), i + 1)
        }
        SeriesInfo(info?.optString("plot")?.takeIf { it.isNotBlank() }, backdrop,
            eps.sortedWith(compareBy({ it.season }, { it.number })))
    }

    /** Lit un tableau JSON d'objets au fil de l'eau ; chaque objet arrive en champs simples (texte), sans les imbriqués. */
    private fun readArray(f: File, each: (Map<String, String?>) -> Unit) {
        JsonReader(InputStreamReader(f.inputStream().buffered(), Charsets.UTF_8)).use { r ->
            r.isLenient = true
            if (r.peek() != JsonToken.BEGIN_ARRAY) return // erreur du serveur (objet) : rien
            r.beginArray()
            while (r.hasNext()) {
                if (r.peek() != JsonToken.BEGIN_OBJECT) { r.skipValue(); continue }
                val o = HashMap<String, String?>()
                r.beginObject()
                while (r.hasNext()) {
                    val name = r.nextName()
                    when (r.peek()) {
                        JsonToken.STRING, JsonToken.NUMBER -> o[name] = r.nextString()
                        JsonToken.BOOLEAN -> o[name] = r.nextBoolean().toString()
                        JsonToken.NULL -> { r.nextNull(); o[name] = null }
                        else -> r.skipValue()
                    }
                }
                r.endObject()
                each(o)
            }
            r.endArray()
        }
    }

    // ---- Épisode suivant, « Reprendre » ----
    /** Ce qu'il faut pour rouvrir un film ou un épisode d'après son adresse (épisode suivant, reprise). */
    private val known = java.util.Collections.synchronizedMap(LinkedHashMap<String, Media>())
    private fun remember(m: Media) { known.remove(m.url); known[m.url] = m }
    fun mediaFor(url: String): Media? = known[url]

    fun movieMedia(a: Account, m: Movie): Media =
        Media(url = movieUrl(a, m), title = m.name, posterUrl = m.poster, fileName = "${m.name}.${m.ext}").also { remember(it); save() }

    /** Les épisodes d'une série, chacun avec l'adresse du suivant (« Épisode suivant » à la fin). */
    fun episodeMedias(a: Account, s: Series, info: SeriesInfo): List<Media> {
        val eps = info.episodes
        return eps.mapIndexed { i, e ->
            val title = e.title.takeIf { it.isNotEmpty() && !it.contains(s.name, ignoreCase = true) }
                ?.let { "${s.name} – $it" } ?: "${s.name} S%02dE%02d".format(e.season, e.number)
            Media(url = episodeUrl(a, e), title = title, posterUrl = s.cover, backdropUrl = info.backdrop ?: e.image,
                season = e.season, episode = e.number, fileName = "${s.name}.S%02dE%02d.${e.ext}".format(e.season, e.number),
                nextUrl = eps.getOrNull(i + 1)?.let { episodeUrl(a, it) })
        }.onEach(::remember).also { save() }
    }
}
