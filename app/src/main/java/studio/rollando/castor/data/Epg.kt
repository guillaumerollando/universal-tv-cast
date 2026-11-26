package studio.rollando.castor.data

import android.content.Context
import android.util.Log
import android.util.Xml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import studio.rollando.castor.net.Http
import java.io.File
import java.text.Normalizer
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.zip.GZIPInputStream

/**
 * Programme en cours et suivant d'une chaîne, d'après les guides (XMLTV) que déclarent les listes importées.
 * On ne télécharge que le guide du pays de la chaîne (quelques Mo), une fois toutes les 12 h, et on n'en garde
 * qu'un résumé : les programmes d'hier soir à demain, titre et horaires.
 */
object Epg {
    data class Prog(val title: String, val start: Long, val stop: Long)

    private lateinit var dir: File
    private val mem = HashMap<String, Map<String, List<Prog>>>()
    private val lock = Mutex()
    private const val FRESH = 12 * 3600 * 1000L

    fun init(ctx: Context) {
        if (::dir.isInitialized) return
        dir = File(ctx.cacheDir, "epg").apply { mkdirs() }
    }

    fun key(s: String): String = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "").replace("+", "plus").replace(Regex("[^a-z0-9]+"), "")

    /** Guide découpé par pays (« …_FR1.xml.gz ») : seuls ceux du pays de la chaîne servent. */
    private val BY_COUNTRY = Regex("_([a-z]{2})\\d*\\.xml", RegexOption.IGNORE_CASE)
    private val SKIP = listOf("all_sources", "dummy")

    /**
     * (en ce moment, ensuite) pour cette chaîne ; null si aucun guide ne la connaît. Cherchée par son identifiant
     * de guide (celui de la liste, quand la liste et le guide viennent du même fournisseur), sinon par son nom.
     */
    suspend fun nowNext(name: String, country: String?, tvgId: String? = null): Pair<Prog, Prog?>? = withContext(Dispatchers.IO) {
        val cc = country?.lowercase()?.takeIf { it.length == 2 }
        val guides = IptvStore.guideUrls().filter { g ->
            val f = g.url.substringAfterLast('/').substringBefore('?').lowercase()
            if (SKIP.any { it in f }) return@filter false
            // Collection découpée par pays : seulement le guide de ce pays. Sinon (guide unique) : toujours.
            !g.byCountry || BY_COUNTRY.find(f)?.groupValues?.get(1)?.lowercase() == cc
        }.map { it.url }.let { declared ->
            // Aucun guide déclaré ne connaît peut-être la chaîne : en dernier recours, le guide gratuit du pays que
            // déclare la liste Free-TV (open source), sans avoir à importer Free-TV.
            val fallback = cc?.let { fallbackGuide(it) }
            if (fallback != null && fallback !in declared) declared + fallback else declared
        }
        if (guides.isEmpty()) return@withContext null
        val keys = listOfNotNull(tvgId?.lowercase()?.let { "id:$it" }, key(name).takeIf { it.isNotEmpty() })
        val now = System.currentTimeMillis()
        for (g in guides) {
            val idx = load(g) ?: continue
            val progs = keys.firstNotNullOfOrNull { idx[it] } ?: continue
            val i = progs.indexOfFirst { now >= it.start && now < it.stop }
            if (i >= 0) return@withContext progs[i] to progs.getOrNull(i + 1)
        }
        null
    }

    /** Guide gratuit d'un pays, celui que déclare la liste Free-TV (« …_FR1.xml.gz »). Un pays sans guide : refus mis en cache. */
    fun fallbackGuide(cc: String): String {
        val code = if (cc == "gb") "UK" else cc.uppercase()
        return "https://epgshare01.online/epgshare01/epg_ripper_${code}1.xml.gz"
    }

    /** Guides injoignables : on ne réessaie qu'au bout d'une heure. */
    private val failed = HashMap<String, Long>()

    private suspend fun load(url: String): Map<String, List<Prog>>? = lock.withLock {
        mem[url]?.let { return it }
        failed[url]?.let { if (System.currentTimeMillis() - it < 3600_000) return null }
        val name = url.hashCode().toUInt().toString(16)
        val cache = File(dir, "$name.tsv")
        val idx = if (cache.exists() && System.currentTimeMillis() - cache.lastModified() < FRESH) readCache(cache)
            else runCatching { download(url, cache) }.onFailure { Log.w("CastorEpg", "guide $url : ${it.message}") }.getOrNull()
                ?: if (cache.exists()) readCache(cache) else null
        if (idx == null) failed[url] = System.currentTimeMillis()
        idx?.also { mem[url] = it }
    }

    private fun download(url: String, cache: File): Map<String, List<Prog>> {
        val t0 = System.currentTimeMillis()
        val raw = File(dir, "dl.tmp")
        Http.open(url, emptyMap(), connectTimeout = 10_000, readTimeout = 60_000).let { c ->
            try { c.inputStream.use { inp -> raw.outputStream().use { inp.copyTo(it) } } } finally { c.disconnect() }
        }
        val idx = raw.inputStream().buffered().use { b ->
            b.mark(2)
            val gz = b.read() == 0x1f && b.read() == 0x8b
            b.reset()
            parse(if (gz) GZIPInputStream(b) else b)
        }
        raw.delete()
        cache.bufferedWriter().use { w ->
            idx.forEach { (k, list) -> list.forEach { p -> w.write("$k\t${p.start}\t${p.stop}\t${p.title.replace('\t', ' ').replace('\n', ' ')}\n") } }
        }
        Log.i("CastorEpg", "guide ${url.substringAfterLast('/')} : ${idx.size} chaînes en ${System.currentTimeMillis() - t0} ms")
        return idx
    }

    private fun readCache(f: File): Map<String, List<Prog>> {
        val out = HashMap<String, MutableList<Prog>>()
        f.forEachLine { l ->
            val p = l.split('\t', limit = 4)
            if (p.size == 4) out.getOrPut(p[0]) { ArrayList() } += Prog(p[3], p[1].toLong(), p[2].toLong())
        }
        return out
    }

    private val TIME = SimpleDateFormat("yyyyMMddHHmmss Z", Locale.US)
    private fun time(s: String?): Long? = s?.let { runCatching { TIME.parse(if (it.length > 14 && it[14] != ' ') it.substring(0, 14) + " " + it.substring(14) else it)?.time }.getOrNull() }

    /** Lecture en flux (un guide pèse des dizaines de Mo une fois décompressé) ; seuls les programmes autour de maintenant. */
    private fun parse(input: java.io.InputStream): Map<String, List<Prog>> {
        val now = System.currentTimeMillis()
        val from = now - 6 * 3600 * 1000L
        val to = now + 30 * 3600 * 1000L
        val names = HashMap<String, MutableSet<String>>() // id de chaîne → clés de nom
        val progs = HashMap<String, MutableList<Prog>>()   // id de chaîne → programmes
        val x = Xml.newPullParser()
        x.setInput(input, null)
        var chan: String? = null
        var pChan: String? = null
        var pStart = 0L
        var pStop = 0L
        var title: String? = null
        var ev = x.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.START_TAG) when (x.name) {
                "channel" -> {
                    chan = x.getAttributeValue(null, "id")
                    chan?.let { id -> names.getOrPut(id) { HashSet() } += key(id.substringBeforeLast('.')) }
                }
                "display-name" -> chan?.let { id -> names.getOrPut(id) { HashSet() } += key(x.nextText()) }
                "programme" -> {
                    pChan = x.getAttributeValue(null, "channel")
                    pStart = time(x.getAttributeValue(null, "start")) ?: 0L
                    pStop = time(x.getAttributeValue(null, "stop")) ?: 0L
                    title = null
                }
                "title" -> if (pChan != null && title == null) title = x.nextText().trim()
            } else if (ev == XmlPullParser.END_TAG) when (x.name) {
                "channel" -> chan = null
                "programme" -> {
                    val c = pChan
                    val t = title
                    if (c != null && t != null && pStop > from && pStart < to && pStop > pStart) progs.getOrPut(c) { ArrayList() } += Prog(t, pStart, pStop)
                    pChan = null
                }
            }
            ev = x.next()
        }
        // Par nom de chaîne : la source qui a le plus de programmes l'emporte (un guide en propose parfois plusieurs).
        val out = HashMap<String, List<Prog>>()
        for ((id, list) in progs) {
            val sorted = list.sortedBy { it.start }
            for (k in names[id].orEmpty() + key(id.substringBeforeLast('.')) + ("id:" + id.lowercase())) {
                if (k.isEmpty()) continue
                if ((out[k]?.size ?: 0) < sorted.size) out[k] = sorted
            }
        }
        return out
    }
}
