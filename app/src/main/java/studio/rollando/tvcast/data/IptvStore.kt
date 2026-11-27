package studio.rollando.tvcast.data

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import studio.rollando.tvcast.net.Http
import java.io.File

/**
 * Les listes de chaînes importées : leur nom et leur adresse dans les préférences, leur contenu dans un fichier
 * (une liste mondiale pèse plusieurs Mo). Une liste importée par adresse se met à jour toute seule une fois par jour.
 */
object IptvStore {
    data class Playlist(val id: String, val name: String, val source: String, val count: Int, val updated: Long, val guide: String? = null) {
        val isRemote: Boolean get() = source.startsWith("http")
    }

    class NotAList : Exception()

    private lateinit var prefs: SharedPreferences
    private lateinit var dir: File
    private val _lists = MutableStateFlow<List<Playlist>>(emptyList())
    val lists: StateFlow<List<Playlist>> = _lists
    private val cache = HashMap<String, List<Iptv.Channel>>()

    fun init(ctx: Context) {
        if (::prefs.isInitialized) return
        prefs = ctx.getSharedPreferences("iptv", Context.MODE_PRIVATE)
        dir = File(ctx.filesDir, "iptv").apply { mkdirs() }
        _lists.value = runCatching {
            val a = JSONArray(prefs.getString("lists", "[]"))
            (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                Playlist(o.getString("id"), o.getString("name"), o.getString("source"), o.optInt("count"), o.optLong("updated"),
                    o.optString("guide").takeIf { it.isNotBlank() })
            }
        }.getOrDefault(emptyList())
        _hidden.value = prefs.getStringSet("hidden", emptySet()).orEmpty().toSet()
    }

    private fun save() {
        prefs.edit().putString("lists", JSONArray().apply {
            _lists.value.forEach { p ->
                put(JSONObject().put("id", p.id).put("name", p.name).put("source", p.source).put("count", p.count).put("updated", p.updated)
                    .apply { p.guide?.let { put("guide", it) } })
            }
        }.toString()).apply()
    }

    /** Lit le début d'une adresse pour savoir si c'est une liste de chaînes (sans tout télécharger). */
    suspend fun looksLikeList(url: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val c = Http.open(url, emptyMap(), connectTimeout = 8000)
            try {
                if (c.responseCode >= 400) return@runCatching false
                val buf = ByteArray(64 * 1024)
                var n = 0
                c.inputStream.use { s ->
                    while (n < buf.size) { val r = s.read(buf, n, buf.size - n); if (r < 0) break; n += r }
                }
                Iptv.isChannelList(String(buf, 0, n, Charsets.UTF_8))
            } finally { c.disconnect() }
        }.getOrDefault(false)
    }

    /** Importe une liste depuis une adresse ou un fichier du téléphone ; la remplace si elle est déjà là. */
    suspend fun import(ctx: Context, source: String, name: String? = null): Playlist = withContext(Dispatchers.IO) {
        val text = read(ctx, source)
        if (!Iptv.isChannelList(text)) throw NotAList()
        val channels = Iptv.parse(text)
        if (channels.isEmpty()) throw NotAList()
        val old = _lists.value.firstOrNull { it.source == source }
        val id = old?.id ?: java.util.UUID.randomUUID().toString().take(8)
        File(dir, "$id.m3u").writeText(text)
        synchronized(cache) { cache[id] = channels }
        val p = Playlist(id, old?.name ?: name ?: Iptv.nameFor(source), source, channels.size, System.currentTimeMillis(), old?.guide)
        _lists.value = listOf(p) + _lists.value.filter { it.id != id }
        save()
        logoIndex = null; allCache = null; guidesCache = null
        p
    }

    private fun read(ctx: Context, source: String): String =
        if (source.startsWith("http")) Http.fetch(source, emptyMap()).bytes.toString(Charsets.UTF_8)
        else ctx.contentResolver.openInputStream(Uri.parse(source))?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""

    /** Les chaînes d'une liste (lues une fois, puis gardées en mémoire). */
    suspend fun channels(id: String): List<Iptv.Channel> = withContext(Dispatchers.IO) {
        synchronized(cache) { cache[id] }?.let { return@withContext it }
        val list = runCatching { Iptv.parse(File(dir, "$id.m3u").readText()) }.getOrDefault(emptyList())
        synchronized(cache) { cache[id] = list }
        list
    }

    /** Remet à jour les listes en ligne de plus d'un jour (sans rien dire si le réseau manque). */
    suspend fun refreshStale(ctx: Context) {
        val day = 24 * 3600 * 1000L
        _lists.value.filter { it.isRemote && System.currentTimeMillis() - it.updated > day }.forEach { p ->
            runCatching { import(ctx, p.source) }
        }
    }

    // ---------------------------------------------------------------- logos des chaînes, d'après les listes importées

    @Volatile private var logoIndex: Pair<Map<String, String>, Map<String, String>>? = null // (adresse → logo, nom → logo)

    private fun fold(s: String) = java.text.Normalizer.normalize(s.lowercase(), java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "").replace("+", "plus").replace(Regex("[^a-z0-9]+"), "")

    /**
     * Le logo d'une chaîne, s'il est dans une des listes importées : même adresse de flux, sinon même nom
     * (« Arte » ouverte par son lien retrouve le logo d'« Arte » de la liste). Rien n'est cherché ailleurs.
     */
    suspend fun logoFor(name: String?, url: String?): String? = withContext(Dispatchers.IO) {
        if (_lists.value.isEmpty()) return@withContext null
        val idx = logoIndex ?: run {
            val byUrl = HashMap<String, String>()
            val byName = HashMap<String, String>()
            for (p in _lists.value) for (c in channels(p.id)) {
                val logo = c.logo ?: continue
                byUrl.putIfAbsent(c.url, logo)
                byName.putIfAbsent(fold(c.name), logo)
            }
            (byUrl to byName).also { logoIndex = it }
        }
        url?.let { idx.first[it] } ?: name?.let { n -> fold(n).takeIf { it.isNotEmpty() }?.let { idx.second[it] } }
    }

    // ---------------------------------------------------------------- chaînes masquées (lien mort, pas envie…)

    private val _hidden = MutableStateFlow<Set<String>>(emptySet())
    /** Adresses des chaînes masquées par l'utilisateur, dans toutes les listes. Réversible. */
    val hidden: StateFlow<Set<String>> = _hidden

    fun hide(url: String) { _hidden.value = _hidden.value + url; prefs.edit().putStringSet("hidden", _hidden.value).apply() }
    fun unhide(url: String) { _hidden.value = _hidden.value - url; prefs.edit().putStringSet("hidden", _hidden.value).apply() }
    fun unhideAll(urls: Collection<String>) { _hidden.value = _hidden.value - urls.toSet(); prefs.edit().putStringSet("hidden", _hidden.value).apply() }

    /** Un guide des programmes, et s'il fait partie d'une collection découpée par pays (à ne lire que pour ce pays). */
    data class Guide(val url: String, val byCountry: Boolean)

    @Volatile private var guidesCache: List<Guide>? = null

    /** Tous les guides des programmes fournis par les listes importées. */
    suspend fun guideUrls(): List<Guide> = withContext(Dispatchers.IO) {
        guidesCache?.let { return@withContext it }
        _lists.value.flatMap { p ->
            val declared = runCatching { File(dir, "${p.id}.m3u").bufferedReader().use { r -> Iptv.guideUrls(r.readLine().orEmpty()) } }.getOrDefault(emptyList())
            // Guide indiqué par l'utilisateur, celui d'un serveur « Xtream », celui écrit en tête de la liste.
            // Une liste qui en déclare beaucoup (Free-TV : un par pays) : seulement celui du pays de la chaîne.
            listOfNotNull(p.guide, Iptv.xtreamGuide(p.source)).map { Guide(it, false) } +
                declared.map { Guide(it, declared.size > 3) }
        }.distinctBy { it.url }.also { guidesCache = it }
    }

    @Volatile private var allCache: List<Iptv.Channel>? = null

    /**
     * Toutes les chaînes de toutes les listes, en une seule : peu importe d'où elles viennent. Une chaîne présente
     * dans plusieurs listes (même flux, ou même nom) n'apparaît qu'une fois, de préférence avec son logo.
     */
    suspend fun allChannels(): List<Iptv.Channel> = withContext(Dispatchers.IO) {
        allCache?.let { return@withContext it }
        val out = LinkedHashMap<String, Iptv.Channel>()
        val urls = HashSet<String>()
        for (p in _lists.value) for (c in channels(p.id)) {
            if (!urls.add(c.url)) continue
            val k = fold(c.name)
            val prev = out[k]
            if (prev == null) out[k] = c else if (prev.logo == null && c.logo != null) out[k] = c
        }
        out.values.toList().also { allCache = it }
    }

    /** Toutes les fiches de cette chaîne, dans toutes les listes (même flux ou même nom). */
    suspend fun entries(url: String, name: String?): List<Iptv.Channel> {
        val n = name?.let { fold(it) }
        return _lists.value.flatMap { p -> channels(p.id).filter { it.url == url || (n != null && fold(it.name) == n) } }
    }


    /** Adresse du guide des programmes donnée par l'utilisateur pour cette liste (vide : aucune). */
    fun setGuide(id: String, url: String?) {
        _lists.value = _lists.value.map { if (it.id == id) it.copy(guide = url?.trim()?.takeIf { u -> u.startsWith("http") }) else it }
        save()
        guidesCache = null
    }

    fun rename(id: String, name: String) {
        if (name.isBlank()) return
        _lists.value = _lists.value.map { if (it.id == id) it.copy(name = name.trim()) else it }
        save()
    }

    fun remove(id: String) {
        _lists.value = _lists.value.filter { it.id != id }
        synchronized(cache) { cache.remove(id) }
        runCatching { File(dir, "$id.m3u").delete() }
        save()
        logoIndex = null; allCache = null; guidesCache = null
    }
}
