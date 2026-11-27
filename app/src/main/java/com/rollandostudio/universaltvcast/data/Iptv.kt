package com.rollandostudio.universaltvcast.data

import java.util.Locale

/**
 * Listes de chaînes IPTV (fichiers M3U « #EXTM3U / #EXTINF », comme celles d'iptv-org ou de Free-TV) :
 * reconnaître une liste, la lire, nettoyer le nom des chaînes. Rien ici ne touche au réseau ni au disque.
 */
object Iptv {
    data class Channel(
        val name: String,
        val url: String,
        val logo: String? = null,
        val group: String? = null,
        val headers: Map<String, String> = emptyMap(),
        /** Identifiant de la chaîne dans le guide des programmes (« ARTEFrench.fr »), et son pays (« fr »). */
        val tvgId: String? = null,
        val country: String? = null,
    )

    /**
     * Une liste de chaînes, et pas une liste HLS (celle d'une vidéo ou d'un direct) : des #EXTINF, mais aucune
     * des balises propres au HLS (durée des morceaux, variantes de qualité).
     */
    fun isChannelList(text: String): Boolean {
        val head = text.trimStart('\uFEFF', ' ', '\n', '\r', '\t')
        if (!head.startsWith("#EXTM3U")) return false
        if (!head.contains("#EXTINF")) return false
        return !head.contains("#EXT-X-TARGETDURATION") && !head.contains("#EXT-X-STREAM-INF") &&
            !head.contains("#EXT-X-MEDIA-SEQUENCE")
    }

    private val ATTR = Regex("""([A-Za-z0-9_-]+)="([^"]*)"""")

    /** Sites qui donnent une page et pas un flux : on ne saurait pas les envoyer à la télé. */
    private val PAGE_HOSTS = listOf("youtube.com", "youtu.be", "twitch.tv", "dailymotion.com", "facebook.com", "tiktok.com")

    fun parse(text: String): List<Channel> {
        val out = ArrayList<Channel>()
        val seen = HashSet<String>()
        var info: String? = null
        val opts = HashMap<String, String>()
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            when {
                line.isEmpty() -> Unit
                line.startsWith("#EXTINF", true) -> { info = line; opts.clear() }
                line.startsWith("#EXTVLCOPT:", true) -> {
                    val kv = line.substringAfter(':')
                    opts[kv.substringBefore('=').trim().lowercase()] = kv.substringAfter('=', "").trim()
                }
                line.startsWith("#") -> Unit
                else -> {
                    val inf = info ?: continue
                    info = null
                    val url = line
                    // HTTP, et aussi UDP / RTP multicast (box), RTSP / RTMP (lus sur le téléphone).
                    if (!com.rollandostudio.universaltvcast.net.Streams.supported(url)) continue
                    val host = runCatching { java.net.URI(url).host?.lowercase() }.getOrNull().orEmpty()
                    if (PAGE_HOSTS.any { host == it || host.endsWith(".$it") }) continue
                    if (!seen.add(url)) continue
                    val cut = nameStart(inf)
                    val attrs = ATTR.findAll(if (cut > 0) inf.substring(0, cut) else inf)
                        .associate { it.groupValues[1].lowercase() to it.groupValues[2] }
                    val title = if (cut > 0) inf.substring(cut).trim() else ""
                    val name = cleanName(title.ifBlank { attrs["tvg-name"].orEmpty() }).ifBlank { continue }
                    val headers = buildMap {
                        (attrs["http-user-agent"] ?: opts["http-user-agent"])?.takeIf { it.isNotBlank() }?.let { put("User-Agent", it) }
                        (attrs["http-referrer"] ?: attrs["http-referer"] ?: opts["http-referrer"] ?: opts["http-referer"])
                            ?.takeIf { it.isNotBlank() }?.let { put("Referer", it) }
                        opts["http-origin"]?.takeIf { it.isNotBlank() }?.let { put("Origin", it) }
                    }
                    out += Channel(
                        name = name,
                        url = url,
                        logo = attrs["tvg-logo"]?.takeIf { it.startsWith("http", true) },
                        group = attrs["group-title"]?.split(';')?.firstOrNull()?.trim()?.takeIf { it.isNotBlank() && !it.equals("undefined", true) },
                        headers = headers,
                        tvgId = attrs["tvg-id"]?.substringBefore('@')?.takeIf { it.isNotBlank() },
                        country = (attrs["tvg-country"]?.split(';', ',')?.firstOrNull()
                            ?: attrs["tvg-id"]?.substringBefore('@')?.substringAfterLast('.', ""))
                            ?.trim()?.lowercase()?.takeIf { it.length == 2 },
                    )
                    opts.clear()
                }
            }
        }
        return out
    }

    /** Début du nom, après la virgule de #EXTINF, en sautant les virgules des attributs entre guillemets ; -1 sans virgule. */
    private fun nameStart(inf: String): Int {
        var quoted = false
        for (i in inf.indices) {
            val ch = inf[i]
            if (ch == '"') quoted = !quoted
            else if (ch == ',' && !quoted) return i + 1
        }
        return -1
    }

    private val QUALITY = Regex("""\s*\((?:\d{3,4}p|\d+k|4k|hd|sd|fhd|uhd)\)""", RegexOption.IGNORE_CASE)
    private val BRACKETS = Regex("""\s*\[[^\]]*]""")
    private val MARKS = Regex("""[Ⓐ-ⓩ①-⓿]""") // Ⓢ Ⓖ Ⓨ… (notes de Free-TV)

    /**
     * Guides des programmes déclarés en tête de la liste (« #EXTM3U x-tvg-url="…, …" ») : c'est la liste importée
     * par l'utilisateur qui les fournit.
     */
    fun guideUrls(text: String): List<String> {
        val head = text.lineSequence().firstOrNull { it.trimStart().startsWith("#EXTM3U") } ?: return emptyList()
        val attrs = ATTR.findAll(head).associate { it.groupValues[1].lowercase() to it.groupValues[2] }
        return (attrs["x-tvg-url"] ?: attrs["url-tvg"]).orEmpty().split(',').map { it.trim() }.filter { it.startsWith("http") }
    }

    /**
     * Serveur IPTV « Xtream » (la plupart des abonnements) : la liste est à « …/get.php?username=…&password=… »,
     * son guide toujours à « …/xmltv.php?username=…&password=… ».
     */
    fun xtreamGuide(source: String): String? {
        val u = runCatching { java.net.URI(source) }.getOrNull() ?: return null
        if (!u.path.orEmpty().endsWith("/get.php")) return null
        val q = u.rawQuery.orEmpty().split('&').associate { it.substringBefore('=') to it.substringAfter('=', "") }
        val user = q["username"] ?: return null
        val pass = q["password"] ?: return null
        val base = source.substringBefore("get.php")
        return "${base}xmltv.php?username=$user&password=$pass"
    }

    /** « France 24 (1080p) [Geo-blocked] Ⓖ » → « France 24 ». */
    fun cleanName(raw: String): String = raw
        .replace(QUALITY, "")
        .replace(BRACKETS, "")
        .replace(MARKS, "")
        .replace(Regex("\\s+"), " ")
        .trim()

    /** « fra » (code à trois lettres) comme « fr ». */
    private fun languageLocale(code: String): Locale =
        if (code.length == 2) Locale.forLanguageTag(code)
        else Locale.getISOLanguages().firstOrNull { runCatching { Locale.forLanguageTag(it).isO3Language }.getOrNull() == code }
            ?.let { Locale.forLanguageTag(it) } ?: Locale.forLanguageTag(code)

    /** Un nom lisible pour la liste, d'après son adresse (« Free-TV », « iptv-org · France »…). */
    fun nameFor(url: String, locale: Locale = Locale.getDefault()): String {
        val uri = runCatching { java.net.URI(url) }.getOrNull()
        val host = uri?.host?.removePrefix("www.").orEmpty()
        val parts = uri?.path.orEmpty().split('/').filter { it.isNotBlank() }
        val file = parts.lastOrNull()?.substringBeforeLast('.').orEmpty()
        val owner = when {
            host == "raw.githubusercontent.com" || host == "github.com" -> parts.firstOrNull()
            host.endsWith(".github.io") -> host.removeSuffix(".github.io")
            else -> host.split('.').let { if (it.size >= 2) it[it.size - 2] else host }
        }.orEmpty()
        val dir = parts.getOrNull(parts.size - 2)
        val detail = when {
            dir == "countries" && file.length == 2 -> Locale.Builder().setRegion(file.uppercase()).build().getDisplayCountry(locale)
            dir == "languages" && file.length in 2..3 -> languageLocale(file).getDisplayLanguage(locale).replaceFirstChar { it.titlecase(locale) }
            dir == "categories" -> file.replaceFirstChar { it.titlecase(locale) }
            file.isBlank() || file.lowercase() in setOf("playlist", "index", "list", "channels", "tv", "get", "iptv") -> ""
            else -> file
        }
        return listOf(owner, detail).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { host.ifBlank { url } }
    }
}
