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
    /** Image sans son (le site sert le son à part) et son pas encore trouvé : proposée, mais signalée. */
    val silent: Boolean = false,
    /** Liste HLS du son servi à part, recollé à l'image par le relais. */
    val audio: String? = null,
    /** Durée en secondes si on la connaît (null : inconnue ; 0 : direct). */
    val duration: Double? = null,
) {
    /** Très courte : presque toujours une pub ou une bande-annonce. */
    val short: Boolean get() = duration != null && duration in 0.5..60.0
    /**
     * Nom à montrer : le titre de la page (« Prisonnière 951 (1/4) ») quand le nom du fichier est technique
     * (« 130430-001-A.m3u8 », « index.m3u8 », identifiant…) ; sinon le nom du fichier (« Vacances.mp4 »).
     */
    val label: String
        get() {
            val name = Uri.decode(url.substringBefore('?').substringAfterLast('/'))
            val base = name.substringBeforeLast('.').lowercase()
            // Technique aussi : un nom en un seul bloc avec des tirets bas (« charlie_chaplin_film_fest_512k »).
            // Un nom qui reprend l'identifiant de la page (« xjfs17.m3u8 » de …/video/xjfs17) est technique aussi.
            val pageId = base.length >= 4 && Uri.parse(pageUrl).pathSegments.any { it.equals(base, ignoreCase = true) }
            val technical = !name.contains('.') || base in TECHNICAL || name.count { it.isDigit() } >= 5 || pageId ||
                (base.contains('_') && !base.contains(' ')) ||
                Regex("[a-z0-9]{20,}").containsMatchIn(base)
            // Sans les fins de titre du genre « – Regarder le film complet » / « – Watch now ».
            val title = PageTitle.display(pageTitle, Uri.parse(pageUrl).host.orEmpty())
            return (if (technical && title.isNotBlank()) title else name).take(60).ifEmpty { url.take(60) }
        }

    private companion object {
        val TECHNICAL = setOf("index", "master", "playlist", "manifest", "stream", "video", "chunklist", "main", "live", "prog_index", "media", "play", "get")
    }
    val host: String get() = runCatching { Uri.parse(url).host?.removePrefix("www.") }.getOrNull() ?: ""
}

/** Repère les vidéos que la page charge (réseau et balises <video>). */
class VideoSniffer {
    private val _found = MutableStateFlow<List<Found>>(emptyList())
    val found: StateFlow<List<Found>> = _found

    @Volatile var pageUrl: String = ""
    /** Titre de la page, sans le nom du site quand la page le déclare (og:site_name, ex. « Maternelle Éducatif »). */
    @Volatile var pageTitle: String = ""
        set(v) {
            field = PageTitle.withoutSite(v, siteName)
            // Titre arrivé (ou changé) après la vidéo (sites qui chargent leur titre ensuite : Dailymotion…).
            val page = pageUrl
            synchronized(this) {
                if (_found.value.any { it.pageUrl == page && it.pageTitle != field })
                    _found.value = _found.value.map { if (it.pageUrl == page) it.copy(pageTitle = field) else it }
            }
        }
    /** Nom du site déclaré par la page (og:site_name). */
    @Volatile private var siteName: String? = null
    /** Image de la page (og:image, affiche de la vidéo) : illustre le favori. */
    @Volatile var pageImage: String? = null
    /** Lien « épisode suivant » trouvé dans la page (lien marqué suivant, ou « Épisode suivant », « Next »…). */
    @Volatile var nextEpisode: String? = null
    @Volatile var userAgent: String = ""
    /** L'utilisateur vient de lancer une vidéo dans la page (lecteur du site, avec le son) : passer au lecteur de l'appli. */
    @Volatile var onUserPlay: (() -> Unit)? = null

    private val fileExt = setOf("mp4", "webm", "mkv", "m4v", "mov", "avi", "mp3", "m4a")

    fun reset() {
        _found.value = emptyList(); hidden.clear(); probed.clear(); misses.clear(); audioList = null; pageImage = null; nextEpisode = null; lastLogged = -1
        siteName = null
        _tracks.clear()
    }

    /** Sous-titres déclarés par le lecteur de la page (balises <track> : Wikimedia Commons, PeerTube…). */
    private val _tracks = java.util.concurrent.CopyOnWriteArrayList<studio.rollando.castor.cast.SubSource>()
    /** Langue du téléphone d'abord (« français (fr) » avant les 40 autres de Wikimedia). */
    val tracks: List<studio.rollando.castor.cast.SubSource>
        get() {
            val lang = java.util.Locale.getDefault().language
            return _tracks.sortedByDescending { trackLang[it.uri] == lang }
        }
    private val trackLang = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** Dernière piste son seule repérée sur la page (pour la recoller à l'image). */
    @Volatile private var audioList: String? = null

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
        if (!url.startsWith("http") || req.isForMainFrame) return
        val ext = Mime.ext(url)
        // Une liste HLS se termine par .m3u8 dans son chemin ; pas « m3u8 » au milieu des paramètres (mesures
        // d'audience d'Arte, journaux de lecteurs…), qui faisaient apparaître des dizaines de fausses vidéos.
        val hls = ext == "m3u8" || req.url.path.orEmpty().endsWith(".m3u8", ignoreCase = true)
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


    fun add(url0: String, hls: Boolean, reqHeaders: Map<String, String> = emptyMap()) {
        // Le « #… » final (« …_h264_aac.m3u8#cell=cf3 ») n'est jamais envoyé au serveur : même adresse sans lui.
        val url = url0.substringBefore('#')
        // Manifestes DASH / Smooth Streaming (Wikimedia, certains lecteurs) : ni le lecteur du téléphone ni les télés
        // DLNA ne les lisent ; la même vidéo est presque toujours proposée aussi en fichier ou en HLS.
        if (Mime.ext(url) in setOf("mpd", "ism", "f4m")) return
        // Ogg Theora (vieux fichiers de Wikimedia) : ni les télés ni le lecteur du téléphone ne le lisent ; le site
        // en propose presque toujours une version WebM ou MP4.
        if (Mime.ext(url) in setOf("ogv", "ogg")) return
        // Ancienne version archivée d'un fichier (Wikimedia : « …/archive/…/20120806143806!Film.ogv »).
        if (Regex("/\\d{14}(!|%21)").containsMatchIn(url)) return
        // Morceaux d'un flux découpé (DASH, MSE : « init-1.3x1080p.vp9.webm », « chunk-0-00001… », « segment_12.mp4 ») :
        // pas une vidéo à part entière.
        if (SEGMENT.containsMatchIn(url.substringBefore('?').substringAfterLast('/'))) return
        // La page elle-même (« …/wiki/File:Film.webm ») n'est pas la vidéo.
        if (url.substringBefore('#') == pageUrl.substringBefore('#')) return
        // Plusieurs fils peuvent trouver la même vidéo en même temps : une seule fois dans la liste, et pas plus de 15.
        synchronized(this) {
            if (_found.value.any { it.url == url } || _found.value.size >= 15) return
            // La même liste HLS demandée deux fois avec d'autres paramètres (jetons, mesures : Dailymotion) : une seule.
            // Y compris depuis un autre serveur du même site (cdndirector.dailymotion.com / dmxleo.dailymotion.com).
            if (hls && _found.value.any { it.hls && sameStream(it.url, url) }) return
            // Déjà couvert par une liste maîtresse du même dossier (morceau, piste, variante) : rien de plus à proposer.
            val dir = url.substringBefore('?').substringBeforeLast('/')
            if (_found.value.any { it.qualities > 0 && it.url.substringBefore('?').substringBeforeLast('/') == dir }) return
            // Piste ou variante d'une liste déjà repérée (« 130430-001-A_aud_VF… » de « 130430-001-A.m3u8 »), même si
            // cette liste n'est pas encore examinée : on ne la montre jamais seule.
            if (_found.value.any { it.hls && baseOf(url).startsWith(baseOf(it.url) + "_") }) return
            // Morceaux d'une liste HLS servis en un seul fichier (PeerTube : « …-720-fragmented.mp4 », parfois depuis
            // un miroir) : la liste les contient déjà.
            val fragment = !hls && FRAGMENT.containsMatchIn(url.substringBefore('?'))
            if (fragment && _found.value.any { it.hls }) return
            // La liste qui regroupe arrive après ses pistes : elle les remplace tout de suite.
            if (hls) _found.value = _found.value.filter { !baseOf(it.url).startsWith(baseOf(url) + "_") && !FRAGMENT.containsMatchIn(it.url.substringBefore('?')) }
            // La même vidéo en plusieurs qualités (Wikimedia : 240p, 480p, 1080p, original ; PeerTube : -360, -720…) :
            // une seule proposition, la meilleure pour la télé.
            if (!hls) {
                val stem = stemOf(url)
                val twin = _found.value.firstOrNull { !it.hls && it.host == Uri.parse(url).host?.removePrefix("www.") && stemOf(it.url) == stem }
                if (twin != null && stem.isNotEmpty()) {
                    if (fileScore(url) <= fileScore(twin.url)) { hidden += url; return }
                    hidden += twin.url
                    _found.value = _found.value.filter { it.url != twin.url }
                }
            }
            addLocked(url, hls, reqHeaders)
        }
    }

    private fun addLocked(url: String, hls: Boolean, reqHeaders: Map<String, String>) {
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
    /** Nom de fichier sans extension (« 127960-000-A »). */
    private fun baseOf(u: String) = Uri.decode(u.substringBefore('?').substringAfterLast('/')).substringBeforeLast('.')

    /** Même chemin sur le même site (domaine principal), quels que soient le serveur et les paramètres. */
    private fun sameStream(a: String, b: String): Boolean {
        val ua = Uri.parse(a); val ub = Uri.parse(b)
        fun site(h: String?) = h.orEmpty().split('.').takeLast(2).joinToString(".")
        return ua.path == ub.path && site(ua.host) == site(ub.host)
    }

    private val FRAGMENT = Regex("(?i)-fragmented\\.mp4$")
    private val SEGMENT = Regex("(?i)^(init|chunk|segment|seg|frag|fragment)[-_.]?\\d*([-_.]\\d+)*[-_.]")

    /** Repères de qualité ou de format dans un nom de fichier (« .480p.vp9.webm », « -720.mp4 », « _1080p_h264 »). */
    private val QUALITY_TOKEN = Regex("^((144|240|360|400|480|540|576|720|1080|1440|2160)p?|\\d{3,5}k|[248]k|uhd|fhd|hd|sd|low|lo|high|hi|medium|mid|vp8|vp9|av1|h264|h265|hevc|x264|x265|mpeg4|fragmented|webm|ogv|ogg|mp4|m4v|mov|mkv)$")

    /** Nom de la vidéo sans ses repères de qualité : deux qualités d'une même vidéo ont le même. */
    internal fun stemOf(u: String): String = runCatching { java.net.URLDecoder.decode(u.substringBefore('?').substringAfterLast('/').replace("+", "%2B"), "UTF-8") }
        .getOrDefault(u.substringBefore('?').substringAfterLast('/')).lowercase()
        .split('.', '_', '-', ' ').filter { it.isNotEmpty() && !QUALITY_TOKEN.matches(it) }.joinToString(" ")

    /**
     * Préférence entre deux qualités d'une même vidéo : la plus haute jusqu'à 1080 (au-delà, trop lourde pour le Wi-Fi
     * et bien des télés), MP4 de préférence (lu par toutes les télés).
     */
    internal fun fileScore(u: String): Int {
        val name = u.substringBefore('?').substringAfterLast('/').lowercase()
        val h = Regex("(?<![0-9])(2160|1440|1080|720|576|480|360|240|144)p?(?![0-9])").findAll(name).lastOrNull()?.groupValues?.get(1)?.toInt()
            ?: if (Regex("(?<![a-z0-9])4k(?![a-z0-9])").containsMatchIn(name)) 2160 else 700
        val size = if (h <= 1080) h else 1080 - (h - 1080) / 4
        val mp4 = if (Mime.ext(u) in setOf("mp4", "m4v")) 100 else 0
        return size + mp4
    }

    /** Morceau, piste ou variante d'une vidéo déjà proposée par sa liste maîtresse (« 127960-000-A_v1080… »). */
    private fun partOf(it: Found, master: Found): Boolean =
        it.url != master.url && baseOf(it.url).startsWith(baseOf(master.url) + "_")

    /** La vidéo complète (liste maîtresse, plusieurs qualités) d'abord, puis la plus longue. */
    private fun sorted(l: List<Found>) = l.sortedWith(compareByDescending<Found> { it.qualities > 0 }.thenByDescending { it.duration ?: if (it.hls) 1.0 else 0.5 })
        .also { r -> if (r.size != lastLogged) { lastLogged = r.size; android.util.Log.i("CastorWeb", "vidéos proposées : ${r.size} ${r.joinToString { it.label.take(30) + " [" + it.host + "…" + it.url.substringBefore('?').takeLast(40) + "]" }}") } }

    @Volatile private var lastLogged = -1

    private fun update(url: String, change: (Found) -> Found) {
        synchronized(this) { _found.value = sorted(_found.value.map { if (it.url == url) change(it) else it }) }
    }

    /** Durée d'un MP4 : lue dans l'en-tête (« mvhd ») s'il est au début du fichier. */
    private fun inspectFile(f0: Found) {
        kotlin.concurrent.thread(isDaemon = true) {
            val f = betterQuality(f0)
            runCatching {
                val c = studio.rollando.castor.net.Http.open(f.url, f.headers, "bytes=0-262143", connectTimeout = 6000, readTimeout = 8000)
                val b = try { c.inputStream.use { it.readBytes() } } finally { c.disconnect() }
                Mp4Duration.of(b)?.let { d -> update(f.url) { it.copy(duration = d) } }
            }
        }
    }

    /**
     * Le lecteur du site démarre souvent en petite qualité (« ….480p.vp9.webm », « film_360p.mp4 ») : si le même nom
     * existe en 1080p ou 720p, c'est celle-là qu'on propose (deux petites vérifications, sans rien télécharger).
     */
    private fun betterQuality(f: Found): Found {
        val path = f.url.substringBefore('?')
        val m = HEIGHT_TOKEN.findAll(path.substringAfterLast('/')).lastOrNull() ?: return f
        val h = m.groupValues[2].toInt()
        val dir = path.substringBeforeLast('/') + "/"
        val name = path.substringAfterLast('/')
        for (target in listOf(1080, 720)) {
            if (target <= h) break
            val url = dir + name.replaceRange(m.groups[2]!!.range, target.toString()) + f.url.removePrefix(path)
            val ok = runCatching {
                val c = studio.rollando.castor.net.Http.open(url, f.headers, "bytes=0-0", connectTimeout = 5000, readTimeout = 5000)
                try { c.responseCode in 200..299 && !c.contentType.orEmpty().startsWith("text/") } finally { c.disconnect() }
            }.getOrDefault(false)
            if (ok) {
                android.util.Log.i("CastorWeb", "même vidéo en ${target}p : proposée à la place de ${h}p")
                val better = f.copy(url = url)
                synchronized(this) {
                    hidden += f.url
                    _found.value = sorted(_found.value.map { if (it.url == f.url) better else it })
                }
                return better
            }
        }
        return f
    }

    /** Hauteur d'image écrite dans un nom de fichier : « .480p. », « _720p », ou « -480.mp4 » (PeerTube). */
    private val HEIGHT_TOKEN = Regex("(?i)(^|[._-])(240|360|480|540|576|720)(?:p(?=[._-]|$)|(?=\\.(mp4|m4v|webm)$))")

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
                // Liste « média » directe : on regarde ce que contient son premier morceau. Une piste son seule
                // (image et son séparés par le lecteur du site) ne se caste pas : on ne la propose pas.
                val kind = runCatching { HlsKind.of(text, f.url, f.headers) }.getOrNull()
                android.util.Log.i("CastorWeb", "liste HLS ${f.url.takeLast(24)} : $kind")
                if (kind == HlsKind.Kind.AUDIO) {
                    // Le son à part : pas proposé seul, mais recollé aux images sans son de la page.
                    hidden += f.url
                    audioList = f.url
                    synchronized(this) {
                        _found.value = sorted(_found.value.filter { it.url != f.url }
                            .map { if (it.silent) it.copy(silent = false, audio = f.url) else it })
                    }
                    return@thread
                }
                val sound = if (kind == HlsKind.Kind.VIDEO) audioList else null
                update(f.url) { it.copy(duration = hlsDuration(text), silent = kind == HlsKind.Kind.VIDEO && sound == null, audio = sound) }
                return@thread
            }
            val lines = text.lines().map { it.trim() }
            val variants = mutableListOf<String>()
            var maxH = 0
            for ((i, l) in lines.withIndex()) {
                // Pistes son et sous-titres de la liste maîtresse (« aud_VF », « aud_VO »…) : comprises dans la vidéo.
                if (l.startsWith("#EXT-X-MEDIA")) {
                    Regex("URI=\"([^\"]+)\"").find(l)?.groupValues?.get(1)?.let { u ->
                        runCatching { java.net.URL(java.net.URL(f.url), u).toString().substringBefore('#') }.getOrNull()?.let(variants::add)
                    }
                    continue
                }
                if (!l.startsWith("#EXT-X-STREAM-INF")) continue
                Regex("RESOLUTION=\\d+x(\\d+)").find(l)?.groupValues?.get(1)?.toIntOrNull()?.let { maxH = maxOf(maxH, it) }
                lines.drop(i + 1).firstOrNull { it.isNotEmpty() && !it.startsWith("#") }?.let { u ->
                    runCatching { java.net.URL(java.net.URL(f.url), u).toString().substringBefore('#') }.getOrNull()?.let(variants::add)
                }
            }
            hidden += variants
            // Durée : celle de la première variante.
            val d = variants.firstOrNull()?.let { v ->
                runCatching { hlsDuration(String(studio.rollando.castor.net.Http.fetch(v, f.headers, connectTimeout = 8000).bytes)) }.getOrNull()
            }
            // Tout ce qui vient du même dossier que la liste maîtresse (pistes, morceaux, variantes) est déjà dedans :
            // une seule ligne « Auto · N qualités » pour cette vidéo.
            val dir = f.url.substringBefore('?').substringBeforeLast('/')
            synchronized(this) {
                _found.value = sorted(_found.value
                    .filter { it.url !in variants && (it.url == f.url || (it.url.substringBefore('?').substringBeforeLast('/') != dir && !partOf(it, f))) }
                    .map { if (it.url == f.url) it.copy(qualities = variants.count { v -> !v.contains("aud", true) }.coerceAtLeast(1), maxHeight = maxH, duration = d ?: it.duration) else it })
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
        /** Lien vers l'épisode suivant, repéré dans la page principale. */
        @JavascriptInterface
        fun nextEpisode(url: String?) {
            if (url.isNullOrBlank() || !url.startsWith("http") || url == pageUrl) return
            nextEpisode = url
        }

        /** Piste de sous-titres du lecteur (<track kind="subtitles|captions">) : proposée avec la vidéo. */
        @JavascriptInterface
        fun track(url: String?, label: String?, lang: String?) {
            if (url.isNullOrBlank() || !url.startsWith("http") || _tracks.size >= 40 || _tracks.any { it.uri == url }) return
            lang?.substringBefore('-')?.lowercase()?.takeIf { it.isNotBlank() }?.let { trackLang[url] = it }
            // Sans les marques de sens d'écriture invisibles (« ‪(ar)‬ »).
            val name = label?.replace(Regex("[\\u200e\\u200f\\u202a-\\u202e\\u2066-\\u2069]"), "")?.trim()?.takeIf { it.isNotEmpty() }
                ?: lang?.let { l -> java.util.Locale.forLanguageTag(l).let { it.getDisplayLanguage(it) }.replaceFirstChar { c -> c.titlecase() } }
                ?: "?"
            _tracks += studio.rollando.castor.cast.SubSource(url, name)
            if (_tracks.size == 1) android.util.Log.i("CastorWeb", "sous-titres proposés par la page : $name…")
        }

        /**
         * Lecture lancée dans la page par l'utilisateur (le site a reçu un vrai geste, son non coupé) : pas un aperçu
         * muet qui démarre tout seul. Les vidéos d'une minute ou moins (pubs) ne comptent pas.
         */
        @JavascriptInterface
        fun played(duration: Double) {
            if (duration > 0.5 && duration <= 60.0) return
            onUserPlay?.invoke()
        }

        /** Nom du site (og:site_name) : retiré des titres (« Le loup (documentaire) - Maternelle Éducatif »). */
        @JavascriptInterface
        fun siteName(name: String?) {
            val n = name?.trim()?.takeIf { it.length in 2..60 } ?: return
            siteName = n
            pageTitle = pageTitle
            synchronized(this@VideoSniffer) {
                _found.value = _found.value.map { it.copy(pageTitle = PageTitle.withoutSite(it.pageTitle, n)) }
            }
        }

        /** Image de la page principale (balise og:image ou affiche du lecteur). */
        @JavascriptInterface
        fun pageImage(url: String?) {
            if (url.isNullOrBlank() || !url.startsWith("http")) return
            if (pageImage == null) {
                pageImage = url
                studio.rollando.castor.data.Library.setBookmarkImage(pageUrl, url)
                studio.rollando.castor.cast.Castor.setPageImage(pageUrl, url)
            }
        }

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
  function tracks(){ try { if (!CastorBridge.track) return; document.querySelectorAll('track').forEach(function(tr){
    var k = (tr.kind || 'subtitles').toLowerCase(); if (k !== 'subtitles' && k !== 'captions') return;
    var s = tr.src; if (s && s.indexOf('http') === 0) CastorBridge.track(s, tr.label || '', tr.srclang || ''); }); } catch(e){} }
  function scan(){ document.querySelectorAll('video, audio, video source').forEach(report); tracks(); }
  document.addEventListener('play', function(e){ report(e.target); tracks();
    try { var v = e.target, act = navigator.userActivation ? navigator.userActivation.hasBeenActive : true;
      if (act && !v.muted && CastorBridge.played) CastorBridge.played(isFinite(v.duration) ? v.duration : 0); } catch(x){} }, true);
  document.addEventListener('loadedmetadata', function(e){ report(e.target); }, true);
  document.addEventListener('durationchange', function(e){ report(e.target); }, true);
  function image(){ try { if (window !== window.top) return;
    var m = document.querySelector('meta[property="og:image"],meta[name="og:image"],meta[name="twitter:image"],meta[property="twitter:image"]');
    var u = m && m.content; if (!u) { var v = document.querySelector('video[poster]'); u = v && v.poster; }
    if (u) CastorBridge.pageImage(new URL(u, location.href).href);
    var ov = document.querySelectorAll('meta[property="og:video"],meta[property="og:video:url"],meta[property="og:video:secure_url"],meta[property="twitter:player:stream"],meta[name="twitter:player:stream"]');
    for (var k = 0; k < ov.length; k++) { var vu = ov[k].content;
      if (vu && /\.(mp4|m4v|webm|m3u8)(\?|$)/i.test(vu.split('#')[0])) CastorBridge.found(new URL(vu, location.href).href, 0); }
    var sn = document.querySelector('meta[property="og:site_name"],meta[name="og:site_name"],meta[name="application-name"]');
    if (sn && sn.content && CastorBridge.siteName) CastorBridge.siteName(sn.content); } catch(e){} }
  function nextEp(){ try { if (window !== window.top || !CastorBridge.nextEpisode) return;
    var u = null, l = document.querySelector('a[rel~="next"][href],link[rel~="next"][href]'); if (l) u = l.href;
    var re = /((é|e)pisode|ep\.?)\s*(suivant|next)|next\s*(episode|ep)|prochain\s*(é|e)pisode|^\s*(suivant|next)\s*[›»>→]?\s*$/i;
    if (!u) { var as = document.querySelectorAll('a[href]'); for (var i = 0; i < as.length; i++) { var a = as[i];
      var t = ((a.textContent || '') + ' ' + (a.getAttribute('aria-label') || '') + ' ' + (a.title || '')).trim();
      if (t.length < 60 && (re.test(a.textContent || '') || re.test(a.getAttribute('aria-label') || '') || re.test(a.title || ''))
          && a.href.indexOf('http') === 0 && a.host === location.host) { u = a.href; break; } } }
    if (u) CastorBridge.nextEpisode(u); } catch(e){} }
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', function(){ scan(); image(); nextEp(); }); else { scan(); image(); nextEp(); }
  setTimeout(image, 2500); setTimeout(nextEp, 2500); setInterval(nextEp, 6000);
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

/**
 * Ce que contient une liste HLS : image et son, image seule ou son seul. On lit le début du premier morceau
 * (déchiffré s'il est en AES-128) et on regarde les types de pistes annoncés (PMT du MPEG-TS).
 */
object HlsKind {
    enum class Kind { BOTH, VIDEO, AUDIO }

    fun of(playlist: String, base: String, headers: Map<String, String>): Kind? {
        val lines = playlist.lines().map { it.trim() }
        val segLine = lines.firstOrNull { it.isNotEmpty() && !it.startsWith("#") } ?: return null
        val keyLine = lines.takeWhile { it != segLine }.lastOrNull { it.startsWith("#EXT-X-KEY") }
        val seg = java.net.URL(java.net.URL(base), segLine).toString()
        val c = studio.rollando.castor.net.Http.open(seg, headers, null, connectTimeout = 6000, readTimeout = 8000)
        var data = try {
            c.inputStream.use { ins -> val b = ByteArray(32 * 1024); var n = 0
                while (n < b.size) { val r = ins.read(b, n, b.size - n); if (r < 0) break; n += r }
                b.copyOf(n) }
        } finally { c.disconnect() }
        if (keyLine != null && keyLine.contains("AES-128")) {
            val keyUri = Regex("URI=\"([^\"]+)\"").find(keyLine)?.groupValues?.get(1) ?: return null
            val key = studio.rollando.castor.net.Http.fetch(java.net.URL(java.net.URL(base), keyUri).toString(), headers, connectTimeout = 6000).bytes
            val ivHex = Regex("IV=0[xX]([0-9a-fA-F]+)").find(keyLine)?.groupValues?.get(1)
            val iv = ivHex?.padStart(32, '0')?.chunked(2)?.map { it.toInt(16).toByte() }?.toByteArray() ?: ByteArray(16)
            val n = data.size / 16 * 16
            val cipher = javax.crypto.Cipher.getInstance("AES/CBC/NoPadding")
            cipher.init(javax.crypto.Cipher.DECRYPT_MODE, javax.crypto.spec.SecretKeySpec(key, "AES"), javax.crypto.spec.IvParameterSpec(iv))
            data = cipher.doFinal(data, 0, n)
        } else if (keyLine != null && !keyLine.contains("NONE")) return null
        return fromTs(data)
    }

    /** Types de pistes de la table PMT d'un début de MPEG-TS. */
    fun fromTs(d: ByteArray): Kind? {
        val start = (0 until minOf(d.size, 188 * 4)).firstOrNull { i ->
            d[i] == 0x47.toByte() && i + 188 < d.size && d[i + 188] == 0x47.toByte()
        } ?: return null
        var pmtPid = -1
        var p = start
        while (p + 188 <= d.size) {
            val pid = ((d[p + 1].toInt() and 0x1F) shl 8) or (d[p + 2].toInt() and 0xFF)
            val pusi = d[p + 1].toInt() and 0x40 != 0
            val afc = (d[p + 3].toInt() ushr 4) and 3
            var q = p + 4
            if (afc == 2 || afc == 3) q += 1 + (d[q].toInt() and 0xFF)
            if (pusi && q < p + 188) {
                q += 1 + (d[q].toInt() and 0xFF) // pointer_field
                if (pid == 0 && pmtPid < 0 && q + 12 < p + 188) {
                    pmtPid = ((d[q + 10].toInt() and 0x1F) shl 8) or (d[q + 11].toInt() and 0xFF)
                } else if (pid == pmtPid && q + 12 < p + 188 && d[q].toInt() == 2) {
                    val sectionEnd = q + 3 + (((d[q + 1].toInt() and 0x0F) shl 8) or (d[q + 2].toInt() and 0xFF)) - 4
                    val infoLen = ((d[q + 10].toInt() and 0x0F) shl 8) or (d[q + 11].toInt() and 0xFF)
                    var e = q + 12 + infoLen
                    var video = false; var audio = false
                    while (e + 5 <= minOf(sectionEnd, p + 188)) {
                        when (d[e].toInt() and 0xFF) {
                            0x01, 0x02, 0x10, 0x1B, 0x24, 0x42, 0xEA -> video = true
                            0x03, 0x04, 0x0F, 0x11, 0x81, 0x87 -> audio = true
                        }
                        e += 5 + (((d[e + 3].toInt() and 0x0F) shl 8) or (d[e + 4].toInt() and 0xFF))
                    }
                    return when { video && audio -> Kind.BOTH; video -> Kind.VIDEO; audio -> Kind.AUDIO; else -> null }
                }
            }
            p += 188
        }
        return null
    }
}
