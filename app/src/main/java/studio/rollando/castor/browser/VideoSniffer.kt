package studio.rollando.castor.browser

import android.net.Uri
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import studio.rollando.castor.net.Mime

/** Une vidéo repérée dans la page. */
data class Found(
    val url: String, val hls: Boolean, val headers: Map<String, String>, val pageTitle: String, val pageUrl: String,
    /** Pour un flux HLS « principal » : nombre de qualités et la meilleure (ex. 5 et 1080). */
    val qualities: Int = 0, val maxHeight: Int = 0,
    /** Durée en secondes si on la connaît (null : inconnue ; 0 : direct). */
    val duration: Double? = null,
) {
    /** Très courte : presque toujours une pub ou une bande-annonce. */
    val short: Boolean get() = duration != null && duration in 0.5..60.0
    /** Nom du fichier ; pour une adresse sans nom de fichier (« get », « play »…), le titre de la page. */
    val label: String
        get() {
            val name = Uri.decode(url.substringBefore('?').substringAfterLast('/'))
            return (if (name.contains('.') || pageTitle.isBlank()) name else pageTitle).take(60).ifEmpty { url.take(60) }
        }
    val host: String get() = runCatching { Uri.parse(url).host?.removePrefix("www.") }.getOrNull() ?: ""
}

/** Repère les vidéos que la page charge (réseau et balises <video>). */
class VideoSniffer {
    private val _found = MutableStateFlow<List<Found>>(emptyList())
    val found: StateFlow<List<Found>> = _found

    @Volatile var pageUrl: String = ""
    @Volatile var pageTitle: String = ""
    @Volatile var userAgent: String = ""

    private val fileExt = setOf("mp4", "webm", "mkv", "m4v", "mov", "avi", "mp3", "m4a")

    fun reset() { _found.value = emptyList(); hidden.clear(); probed.clear(); misses.clear() }

    /** Extensions qui ne sont jamais des vidéos : inutile de les vérifier. */
    private val notMedia = setOf("js", "css", "png", "jpg", "jpeg", "gif", "webp", "svg", "ico", "woff", "woff2", "ttf",
        "json", "html", "htm", "php", "xml", "txt", "vtt", "srt", "ts", "m4s", "aac", "key", "wasm", "map")
    /** Mots qui trahissent une vidéo servie sans extension (lecteurs de streaming). */
    private val hint = Regex("(?i)(m3u8|/hls|playlist|master|manifest|/video|/stream|/play|/get_?file|/mp4|/media)")
    private val probed = java.util.Collections.synchronizedSet(HashSet<String>())
    /** Adresse « jeton » opaque (ex. /v/kPZ3OslEkQ5-qlc_…) : beaucoup de lecteurs servent ainsi leurs listes HLS. */
    private val token = Regex("^/[A-Za-z0-9_-]{1,12}/[A-Za-z0-9_-]{24,}$|^/[A-Za-z0-9_-]{32,}$")
    /** Préfixes déjà vérifiés : réponses non vidéo (morceaux, API…) et vidéo trouvée. Au-delà, on ne vérifie plus. */
    private val misses = java.util.concurrent.ConcurrentHashMap<String, Int>()
    private fun prefixOf(u: Uri) = (u.host ?: "") + "/" + (u.pathSegments.firstOrNull() ?: "")

    fun inspect(req: WebResourceRequest) {
        val url = req.url.toString()
        if (!url.startsWith("http")) return
        val ext = Mime.ext(url)
        val hls = ext == "m3u8" || url.contains(".m3u8")
        if (AdBlock.isAd(req.url)) return
        if (hls || ext in fileExt) { add(url, hls, req.requestHeaders); return }
        // Adresse sans extension connue : un lecteur vidéo qui demande « à partir de l'octet 0 », ou une adresse
        // qui y ressemble. On vérifie ce que c'est (quelques octets) avant de la proposer.
        if (ext in notMedia) return
        val range = req.requestHeaders.entries.firstOrNull { it.key.equals("range", true) }?.value
        val path = req.url.path ?: ""
        if (path.contains("/api/")) return
        if (range?.startsWith("bytes=0-") == true || hint.containsMatchIn(path) || token.matches(path)) {
            val prefix = prefixOf(req.url)
            if ((misses[prefix] ?: 0) >= 4) return
            probe(url, req.requestHeaders, prefix)
        }
    }

    /** Regarde les premiers octets : liste HLS (#EXTM3U), MP4 (ftyp), WebM/MKV, ou type annoncé vidéo. */
    private fun probe(url: String, headers: Map<String, String>, prefix: String) {
        if (probed.size > 80 || !probed.add(url) || _found.value.any { it.url == url }) return
        kotlin.concurrent.thread(isDaemon = true) {
            runCatching {
                val clean = headers.filterKeys { !it.equals("range", true) && !it.equals("accept-encoding", true) }
                val c = studio.rollando.castor.net.Http.open(url, clean, "bytes=0-1023", connectTimeout = 6000, readTimeout = 6000)
                try {
                    val type = c.contentType.orEmpty().lowercase()
                    val head = c.inputStream.use { ins -> ByteArray(1024).let { b -> val n = ins.read(b); if (n > 0) b.copyOf(n) else ByteArray(0) } }
                    val text = String(head, Charsets.ISO_8859_1)
                    val isHls = type.contains("mpegurl") || text.trimStart().startsWith("#EXTM3U")
                    val isFile = type.startsWith("video/") || (head.size > 8 && text.substring(4, 8) == "ftyp") ||
                        (head.size > 4 && head[0] == 0x1A.toByte() && head[1] == 0x45.toByte() && head[2] == 0xDF.toByte())
                    // Morceaux d'un flux déjà trouvé (listes de variantes, fragments vidéo), ou fichier trop petit pour
                    // être un épisode : ce n'est pas une vidéo à proposer. Au bout de 4, on laisse ce préfixe.
                    val sameAsHls = _found.value.any { it.hls && prefixOf(Uri.parse(it.url)) == prefix }
                    val total = c.getHeaderField("Content-Range")?.substringAfterLast('/')?.toLongOrNull()
                    val tiny = total != null && total < 8_000_000
                    val keep = (isHls && !(sameAsHls && text.contains("#EXTINF"))) || (isFile && !sameAsHls && !tiny)
                    if (keep) add(url, isHls, headers)
                    else misses.merge(prefix, 1, Int::plus)
                } finally { c.disconnect() }
            }
        }
    }

    fun add(url: String, hls: Boolean, reqHeaders: Map<String, String> = emptyMap()) {
        if (_found.value.any { it.url == url }) return
        val headers = LinkedHashMap<String, String>()
        for ((k, v) in reqHeaders) {
            if (k.equals("range", true) || k.equals("accept-encoding", true)) continue
            headers[k] = v
        }
        if (headers.keys.none { it.equals("user-agent", true) } && userAgent.isNotEmpty()) headers["User-Agent"] = userAgent
        if (headers.keys.none { it.equals("referer", true) } && pageUrl.isNotEmpty()) headers["Referer"] = pageUrl
        if (headers.keys.none { it.equals("origin", true) }) {
            val ref = headers.entries.firstOrNull { it.key.equals("referer", true) }?.value
            runCatching { Uri.parse(ref) }.getOrNull()?.let { u -> if (u.scheme != null && u.host != null) headers["Origin"] = "${u.scheme}://${u.host}" }
        }
        CookieManager.getInstance().getCookie(url)?.takeIf { it.isNotBlank() }?.let { headers["Cookie"] = it }
        if (url in hidden) return
        val f = Found(url, hls, headers, pageTitle, pageUrl)
        // Une liste HLS « maître » passe avant le reste ; les fichiers après.
        _found.value = sorted(_found.value + f)
        if (hls) inspectHls(f) else inspectFile(f)
    }

    /** Les plus longues d'abord (le film avant les pubs de 12 s) ; durée inconnue : les HLS d'abord. */
    private fun sorted(l: List<Found>) = l.sortedWith(compareByDescending<Found> { it.duration ?: if (it.hls) 1.0 else 0.5 })

    private fun update(url: String, change: (Found) -> Found) {
        synchronized(this) { _found.value = sorted(_found.value.map { if (it.url == url) change(it) else it }) }
    }

    /** Durée d'un MP4 : lue dans l'en-tête (« mvhd ») s'il est au début du fichier. */
    private fun inspectFile(f: Found) {
        kotlin.concurrent.thread(isDaemon = true) {
            runCatching {
                val c = studio.rollando.castor.net.Http.open(f.url, f.headers, "bytes=0-262143", connectTimeout = 6000, readTimeout = 8000)
                val b = try { c.inputStream.use { it.readBytes() } } finally { c.disconnect() }
                Mp4Duration.of(b)?.let { d -> update(f.url) { it.copy(duration = d) } }
            }
        }
    }

    /** Appelé par le script de la page : durée connue du lecteur. */
    fun durationFromPage(url: String, d: Double) {
        if (d.isFinite() && d > 0) update(url) { if (it.duration == null) it.copy(duration = d) else it }
    }

    /** Variantes de qualité déjà couvertes par une liste principale : on ne les montre pas en double. */
    private val hidden = java.util.Collections.synchronizedSet(HashSet<String>())

    /** Lit la liste HLS : si c'est une liste principale, on masque ses variantes et on note les qualités. */
    private fun inspectHls(f: Found) {
        kotlin.concurrent.thread(isDaemon = true) {
            val text = runCatching { String(studio.rollando.castor.net.Http.fetch(f.url, f.headers, connectTimeout = 8000).bytes) }.getOrNull()
                ?: return@thread
            if (!text.contains("#EXT-X-STREAM-INF")) {
                update(f.url) { it.copy(duration = hlsDuration(text)) }
                return@thread
            }
            val lines = text.lines().map { it.trim() }
            val variants = mutableListOf<String>()
            var maxH = 0
            for ((i, l) in lines.withIndex()) {
                if (!l.startsWith("#EXT-X-STREAM-INF")) continue
                Regex("RESOLUTION=\\d+x(\\d+)").find(l)?.groupValues?.get(1)?.toIntOrNull()?.let { maxH = maxOf(maxH, it) }
                lines.drop(i + 1).firstOrNull { it.isNotEmpty() && !it.startsWith("#") }?.let { u ->
                    runCatching { java.net.URL(java.net.URL(f.url), u).toString() }.getOrNull()?.let(variants::add)
                }
            }
            hidden += variants
            // Durée : celle de la première variante.
            val d = variants.firstOrNull()?.let { v ->
                runCatching { hlsDuration(String(studio.rollando.castor.net.Http.fetch(v, f.headers, connectTimeout = 8000).bytes)) }.getOrNull()
            }
            synchronized(this) {
                _found.value = sorted(_found.value
                    .filter { it.url !in variants }
                    .map { if (it.url == f.url) it.copy(qualities = variants.size, maxHeight = maxH, duration = d ?: it.duration) else it })
            }
        }
    }

    /** Somme des segments d'une liste HLS ; 0 pour un direct (pas de fin). */
    private fun hlsDuration(text: String): Double? {
        if (!text.contains("#EXTINF")) return null
        if (!text.contains("#EXT-X-ENDLIST")) return 0.0
        return Regex("#EXTINF:([0-9.]+)").findAll(text).sumOf { it.groupValues[1].toDoubleOrNull() ?: 0.0 }
    }

    /** Appelée par le script injecté dans la page. */
    inner class Bridge {
        /** Le script tourne aussi dans les cadres des lecteurs intégrés (autre site que la page). */
        @JavascriptInterface
        fun found(url: String?, duration: Double) {
            if (url.isNullOrBlank() || !url.startsWith("http")) return
            if (AdBlock.isAd(Uri.parse(url))) return
            add(url, url.contains(".m3u8"))
            durationFromPage(url, duration)
        }
    }

    companion object {
        const val SCRIPT = """
(function(){
  if (window.__castor) return; window.__castor = true;
  if (typeof CastorBridge === 'undefined') return;
  function report(v){ try { var s = v.currentSrc || v.src; if (s && s.indexOf('http') === 0) CastorBridge.found(s, isFinite(v.duration) ? v.duration : 0); } catch(e){} }
  function scan(){ document.querySelectorAll('video, audio, video source').forEach(report); }
  document.addEventListener('play', function(e){ report(e.target); }, true);
  document.addEventListener('loadedmetadata', function(e){ report(e.target); }, true);
  document.addEventListener('durationchange', function(e){ report(e.target); }, true);
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', scan); else scan();
  setInterval(scan, 3000);
})();"""
    }
}

/** Liste courte de régies publicitaires et de fenêtres surgissantes connues. */
object AdBlock {
    private val hosts = setOf(
        "doubleclick.net", "googlesyndication.com", "googleadservices.com", "adservice.google.com",
        "google-analytics.com", "googletagmanager.com", "adnxs.com", "adsrvr.org", "amazon-adsystem.com",
        "popads.net", "popcash.net", "propellerads.com", "adsterra.com", "exoclick.com", "juicyads.com",
        "trafficjunky.net", "hilltopads.net", "adcash.com", "clickadu.com", "onclickads.net", "onclkds.com",
        "a-ads.com", "mgid.com", "taboola.com", "outbrain.com", "revcontent.com", "zedo.com", "yllix.com",
        "admaven.com", "ad-maven.com", "galaksion.com", "monetag.com", "richpush.co", "pushame.com",
        "pushwhy.com", "ad.plus", "adskeeper.com", "bidvertiser.com", "infolinks.com", "criteo.com",
        "pubmatic.com", "rubiconproject.com", "openx.net", "smartadserver.com", "scorecardresearch.com",
        "hotjar.com", "mc.yandex.ru", "histats.com", "dtscout.com", "disqusads.com",
        // Régies de publicité vidéo (les pubs avant ou pendant les vidéos)
        "imasdk.googleapis.com", "pubads.g.doubleclick.net", "securepubads.g.doubleclick.net", "ad.doubleclick.net",
        "static.doubleclick.net", "tpc.googlesyndication.com", "pagead2.googlesyndication.com", "s0.2mdn.net",
        "fwmrm.net", "freewheel.tv", "spotxchange.com", "spotx.tv", "springserve.com", "teads.tv", "aniview.com",
        "vidoomy.com", "connatix.com", "primis.tech", "sekindo.com", "adform.net", "adsafeprotected.com",
        "moatads.com", "doubleverify.com", "innovid.com", "tremorhub.com", "lkqd.net", "videohub.tv",
        "unrulymedia.com", "smartclip.net", "stickyadstv.com", "adition.com", "yieldmo.com", "ads.yahoo.com",
        "adtech.com", "serving-sys.com", "flashtalking.com", "jwpltx.com", "adskeeper.co.uk", "exdynsrv.com",
        "realsrv.com", "magsrv.com", "tsyndicate.com", "trafficfactory.biz",
    )

    /** Motifs d'adresses typiques des pubs, même sur un site par ailleurs autorisé. */
    private val paths = listOf("/pagead/", "/pubads", "/adview", "/ads/vast", "/vast.xml", "/vmap", "/ad_break", "/api/stats/ads", "/get_midroll", "/adserver")

    /** Vraie si l'adresse ressemble à une pub (régie connue ou motif typique). */
    fun isAd(url: Uri): Boolean {
        if (blocked(url)) return true
        val p = (url.path ?: "") + "?" + (url.query ?: "")
        return paths.any { p.contains(it, ignoreCase = true) }
    }

    fun blocked(url: Uri): Boolean {
        var h = url.host?.lowercase() ?: return false
        while (true) {
            if (h in hosts) return true
            val i = h.indexOf('.')
            if (i < 0 || h.indexOf('.', i + 1) < 0) return false
            h = h.substring(i + 1)
        }
    }
}

/** Durée d'un MP4 d'après sa boîte « mvhd » (si elle est dans les premiers octets lus). */
object Mp4Duration {
    fun of(b: ByteArray): Double? {
        val i = indexOf(b, "mvhd".toByteArray()) ?: return null
        val p = i + 4
        if (p + 32 > b.size) return null
        val version = b[p].toInt()
        fun u32(o: Int) = ((b[o].toLong() and 0xFF) shl 24) or ((b[o + 1].toLong() and 0xFF) shl 16) or
            ((b[o + 2].toLong() and 0xFF) shl 8) or (b[o + 3].toLong() and 0xFF)
        return if (version == 1) {
            val ts = u32(p + 20); val d = (u32(p + 24) shl 32) or u32(p + 28)
            if (ts > 0) d.toDouble() / ts else null
        } else {
            val ts = u32(p + 12); val d = u32(p + 16)
            if (ts > 0) d.toDouble() / ts else null
        }
    }

    private fun indexOf(b: ByteArray, pat: ByteArray): Int? {
        outer@ for (i in 0..b.size - pat.size) {
            for (j in pat.indices) if (b[i + j] != pat[j]) continue@outer
            return i
        }
        return null
    }
}
