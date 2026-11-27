package com.rollandostudio.universaltvcast.data

import android.content.Context
import android.util.Log
import android.util.Xml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import com.rollandostudio.universaltvcast.net.Http
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
    suspend fun nowNext(name: String, country: String?, tvgId: String? = null): Pair<Prog, Prog?>? {
        val now = System.currentTimeMillis()
        return programmes(name, country, tvgId) { pick(it, now) != null }?.let { pick(it, now) }
    }

    /**
     * Grille de la chaîne, d'hier soir à demain, sans les longs blocs « soirée » qui doublent les émissions qui les
     * composent ; null si aucun guide ne la connaît.
     */
    suspend fun schedule(name: String, country: String?, tvgId: String? = null): List<Prog>? {
        val now = System.currentTimeMillis()
        return programmes(name, country, tvgId) { pick(it, now) != null }?.let { withoutBlocks(it) }
    }

    /** Programmes de la chaîne dans le premier guide qui la connaît (et qui satisfait [ok]). */
    private suspend fun programmes(name: String, country: String?, tvgId: String?, ok: (List<Prog>) -> Boolean): List<Prog>? = withContext(Dispatchers.IO) {
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
        for (g in guides) {
            val idx = load(g, cc) ?: continue
            val progs = keys.firstNotNullOfOrNull { idx[it] } ?: continue
            if (ok(progs)) return@withContext progs
        }
        null
    }

    /** Retire les programmes qui en recouvrent entièrement au moins deux autres (un bloc « soirée » et son détail). */
    internal fun withoutBlocks(progs: List<Prog>): List<Prog> = progs.filter { p ->
        progs.count { o -> o !== p && o.start >= p.start && o.stop <= p.stop && (o.start != p.start || o.stop != p.stop) } < 2
    }.distinctBy { it.start to key(it.title) }

    /**
     * En ce moment : le programme commencé le plus récemment (certaines sources se chevauchent : un long bloc
     * « soirée » et les épisodes qui le composent). Ensuite : le premier qui commence après maintenant.
     */
    internal fun pick(progs: List<Prog>, now: Long): Pair<Prog, Prog?>? {
        val cur = progs.filter { now >= it.start && now < it.stop }.maxByOrNull { it.start } ?: return null
        val next = progs.filter { it.start > now && it.start >= cur.start }.minByOrNull { it.start }
        return cur to next
    }

    /** Guide gratuit d'un pays, celui que déclare la liste Free-TV (« …_FR1.xml.gz »). Un pays sans guide : refus mis en cache. */
    fun fallbackGuide(cc: String): String {
        val code = if (cc == "gb") "UK" else cc.uppercase()
        return "https://epgshare01.online/epgshare01/epg_ripper_${code}1.xml.gz"
    }

    /** Guides injoignables : on ne réessaie qu'au bout d'une heure. */
    private val failed = HashMap<String, Long>()

    private suspend fun load(url: String, cc: String?): Map<String, List<Prog>>? = lock.withLock {
        mem[url]?.let { return it }
        failed[url]?.let { if (System.currentTimeMillis() - it < 3600_000) return null }
        val name = url.hashCode().toUInt().toString(16)
        val cache = File(dir, "$name.v4.tsv")
        val idx = if (cache.exists() && System.currentTimeMillis() - cache.lastModified() < FRESH) readCache(cache)
            else runCatching { download(url, cache, cc) }.onFailure { Log.w("TvCastEpg", "guide $url : ${it.message}") }.getOrNull()
                ?: if (cache.exists()) readCache(cache) else null
        if (idx == null) failed[url] = System.currentTimeMillis()
        idx?.also { mem[url] = it }
    }

    private fun download(url: String, cache: File, cc: String?): Map<String, List<Prog>> {
        val t0 = System.currentTimeMillis()
        val raw = File(dir, "dl.tmp")
        Http.open(url, emptyMap(), connectTimeout = 10_000, readTimeout = 60_000).let { c ->
            try { c.inputStream.use { inp -> raw.outputStream().use { inp.copyTo(it) } } } finally { c.disconnect() }
        }
        val idx = raw.inputStream().buffered().use { b ->
            b.mark(2)
            val gz = b.read() == 0x1f && b.read() == 0x8b
            b.reset()
            val zone = zoneOf(cc)
            Log.i("TvCastEpg", "guide ${url.substringAfterLast('/')} : heures des titres lues à l'heure de ${zone.id}")
            parse(if (gz) GZIPInputStream(b) else b, zone)
        }
        dir.listFiles { f -> f.name.endsWith(".tsv") && !f.name.endsWith(".v4.tsv") }?.forEach { it.delete() }
        raw.delete()
        cache.bufferedWriter().use { w ->
            idx.forEach { (k, list) -> list.forEach { p -> w.write("$k\t${p.start}\t${p.stop}\t${p.title.replace('\t', ' ').replace('\n', ' ')}\n") } }
        }
        Log.i("TvCastEpg", "guide ${url.substringAfterLast('/')} : ${idx.size} chaînes en ${System.currentTimeMillis() - t0} ms")
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

    private const val HOUR = 3600 * 1000L

    /** Heure écrite dans un titre : « Le 6h info », « Journal 20h00 », « 13h15, le dimanche ». */
    private val HOUR_IN_TITLE = Regex("""(?<![\d/])([01]?\d|2[0-3]) ?[hH]([0-5]\d)?(?![a-zA-Z])""")

    /**
     * Écart (en heures entières) entre l'heure annoncée par le guide et celle écrite dans le titre, à l'heure du pays
     * de la chaîne ; null si le titre n'en contient pas, ou si l'écart n'est pas proche d'un nombre d'heures entier.
     */
    internal fun hourGap(title: String, start: Long, zone: java.util.TimeZone): Int? {
        if (start <= 0) return null
        val m = HOUR_IN_TITLE.find(title) ?: return null
        val want = m.groupValues[1].toInt() * 60 + (m.groupValues[2].toIntOrNull() ?: 0)
        val cal = java.util.Calendar.getInstance(zone).apply { timeInMillis = start }
        val got = cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + cal.get(java.util.Calendar.MINUTE)
        var d = got - want
        if (d > 12 * 60) d -= 24 * 60
        if (d < -12 * 60) d += 24 * 60
        val h = Math.round(d / 60.0).toInt()
        return h.takeIf { kotlin.math.abs(d - h * 60) <= 20 && kotlin.math.abs(h) <= 3 }
    }

    /**
     * Décalage d'une source : l'écart le plus fréquent, s'il est net (au moins 8 titres, et 2 sur 3 d'accord) ;
     * sinon 0. Mesuré sur le guide France : France Télévisions 1 h trop tôt (64 titres sur 78), SFR et Télérama justes.
     */
    internal fun sourceShift(gaps: List<Int>): Int {
        if (gaps.size < 8) return 0
        val (h, n) = gaps.groupingBy { it }.eachCount().maxBy { it.value }
        return if (h != 0 && n * 3 >= gaps.size * 2) h else 0
    }

    /** Fuseau du pays de la chaîne (celui des heures écrites dans les titres) ; celui du téléphone à défaut. */
    private fun zoneOf(cc: String?): java.util.TimeZone {
        val mine = java.util.TimeZone.getDefault()
        val ids = cc?.let { runCatching { android.icu.util.TimeZone.getAvailableIDs(it.uppercase()).toList() }.getOrNull() }.orEmpty()
        if (ids.isEmpty() || mine.id in ids) return mine
        // Un fuseau inconnu donnerait GMT sans prévenir (vu sur l'émulateur) : seulement un fuseau reconnu.
        val known = java.util.TimeZone.getAvailableIDs().toHashSet()
        return ids.firstOrNull { it in known }?.let { java.util.TimeZone.getTimeZone(it) } ?: mine
    }

    private val TIME = SimpleDateFormat("yyyyMMddHHmmss Z", Locale.US)
    private fun time(s: String?): Long? = s?.let { runCatching { TIME.parse(if (it.length > 14 && it[14] != ' ') it.substring(0, 14) + " " + it.substring(14) else it)?.time }.getOrNull() }

    /** Lecture en flux (un guide pèse des dizaines de Mo une fois décompressé) ; seuls les programmes autour de maintenant. */
    private fun parse(input: java.io.InputStream, zone: java.util.TimeZone): Map<String, List<Prog>> {
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
        val source = HashMap<String, String>()                       // id de chaîne → site d'où vient son programme
        val samples = HashMap<String, MutableList<Int>>()            // id de chaîne → écarts relevés (heures)
        var ev = x.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.START_TAG) when (x.name) {
                "channel" -> {
                    chan = x.getAttributeValue(null, "id")
                    chan?.let { id -> names.getOrPut(id) { HashSet() } += key(id.substringBeforeLast('.')) }
                }
                "display-name" -> chan?.let { id -> names.getOrPut(id) { HashSet() } += key(x.nextText()) }
                "url" -> chan?.let { id -> source[id] = x.nextText().trim().substringAfter("//").substringBefore('/') }
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
                    if (c != null && t != null) hourGap(t, pStart, zone)?.let { samples.getOrPut(c) { ArrayList() } += it }
                    // Fenêtre large (± 3 h de plus) : une source décalée est recalée après coup.
                    if (c != null && t != null && pStop > from - 3 * HOUR && pStart < to + 3 * HOUR && pStop > pStart) progs.getOrPut(c) { ArrayList() } += Prog(t, pStart, pStop)
                    pChan = null
                }
            }
            ev = x.next()
        }
        // Décalage de chaque source (un même guide en mélange plusieurs, et certaines se trompent d'une heure).
        val bySource = HashMap<String, MutableList<Int>>()
        samples.forEach { (id, s) -> bySource.getOrPut(source[id] ?: "") { ArrayList() } += s }
        val shift = bySource.mapValues { (src, s) -> sourceShift(s).also { if (it != 0) Log.i("TvCastEpg", "source $src décalée de $it h : corrigée") } }
        // Par nom de chaîne : la source qui a le plus de programmes l'emporte (un guide en propose parfois plusieurs).
        val out = HashMap<String, List<Prog>>()
        for ((id, raw) in progs) {
            val fix = (shift[source[id] ?: ""] ?: 0) * HOUR
            val sorted = raw.map { if (fix == 0L) it else it.copy(start = it.start - fix, stop = it.stop - fix) }
                .filter { it.stop > from && it.start < to }.sortedBy { it.start }
            if (sorted.isEmpty()) continue
            for (k in names[id].orEmpty() + key(id.substringBeforeLast('.')) + ("id:" + id.lowercase())) {
                if (k.isEmpty()) continue
                if ((out[k]?.size ?: 0) < sorted.size) out[k] = sorted
            }
        }
        return out
    }
}
