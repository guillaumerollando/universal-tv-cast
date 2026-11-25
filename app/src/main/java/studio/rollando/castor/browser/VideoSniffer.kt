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
) {
    val label: String
        get() = Uri.decode(url.substringBefore('?').substringAfterLast('/')).take(60).ifEmpty { url.take(60) }
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

    fun reset() { _found.value = emptyList(); hidden.clear() }

    fun inspect(req: WebResourceRequest) {
        val url = req.url.toString()
        if (!url.startsWith("http")) return
        val ext = Mime.ext(url)
        val hls = ext == "m3u8" || url.contains(".m3u8")
        if (!hls && ext !in fileExt) return
        if (AdBlock.isAd(req.url)) return
        add(url, hls, req.requestHeaders)
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
        _found.value = (_found.value + f).sortedBy { if (it.hls) 0 else 1 }
        if (hls) inspectHls(f)
    }

    /** Variantes de qualité déjà couvertes par une liste principale : on ne les montre pas en double. */
    private val hidden = java.util.Collections.synchronizedSet(HashSet<String>())

    /** Lit la liste HLS : si c'est une liste principale, on masque ses variantes et on note les qualités. */
    private fun inspectHls(f: Found) {
        kotlin.concurrent.thread(isDaemon = true) {
            val text = runCatching { String(studio.rollando.castor.net.Http.fetch(f.url, f.headers, connectTimeout = 8000).bytes) }.getOrNull()
                ?: return@thread
            if (!text.contains("#EXT-X-STREAM-INF")) return@thread
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
            _found.value = _found.value
                .filter { it.url !in variants }
                .map { if (it.url == f.url) it.copy(qualities = variants.size, maxHeight = maxH) else it }
        }
    }

    /** Appelée par le script injecté dans la page. */
    inner class Bridge {
        @JavascriptInterface
        fun found(url: String?) {
            if (url.isNullOrBlank() || !url.startsWith("http")) return
            if (AdBlock.isAd(Uri.parse(url))) return
            add(url, url.contains(".m3u8"))
        }
    }

    companion object {
        const val SCRIPT = """
(function(){
  if (window.__castor) return; window.__castor = true;
  function report(v){ try { var s = v.currentSrc || v.src; if (s && s.indexOf('http') === 0) CastorBridge.found(s); } catch(e){} }
  function scan(){ document.querySelectorAll('video, audio, video source').forEach(report); }
  document.addEventListener('play', function(e){ report(e.target); }, true);
  document.addEventListener('loadedmetadata', function(e){ report(e.target); }, true);
  scan(); setInterval(scan, 3000);
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
