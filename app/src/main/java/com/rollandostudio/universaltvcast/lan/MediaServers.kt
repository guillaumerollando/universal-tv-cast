package com.rollandostudio.universaltvcast.lan

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import com.rollandostudio.universaltvcast.dlna.Dlna
import com.rollandostudio.universaltvcast.net.Http
import com.rollandostudio.universaltvcast.net.Net
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Serveurs multimédias du réseau (DLNA / UPnP « MediaServer ») : NAS, box, Plex, Jellyfin, Kodi, Windows…
 * On les trouve sans rien régler, on parcourt leurs dossiers, et la télé lit leurs vidéos directement chez eux.
 */
object MediaServers {
    private const val SSDP_ADDR = "239.255.255.250"
    private val TARGETS = listOf(
        "urn:schemas-upnp-org:device:MediaServer:1",
        "urn:schemas-upnp-org:service:ContentDirectory:1",
    )

    /** Cherche les serveurs pendant [timeoutMs] ; [onFound] est appelé pour chacun dès qu'il est décrit. */
    fun discover(ctx: Context, timeoutMs: Long = 4000, onFound: (LanDevice) -> Unit) {
        val wifi = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val lock = wifi.createMulticastLock("tvcast-servers").apply { setReferenceCounted(false); acquire() }
        val pool = Executors.newFixedThreadPool(8)
        val seen = ConcurrentHashMap.newKeySet<String>()
        val found = ConcurrentHashMap.newKeySet<String>()
        try {
            DatagramSocket(null).use { sock ->
                sock.reuseAddress = true
                sock.bind(InetSocketAddress(Net.localIp()?.let { InetAddress.getByName(it) }, 0))
                sock.soTimeout = 300
                val group = InetAddress.getByName(SSDP_ADDR)
                val start = System.currentTimeMillis()
                var rounds = 0
                val buf = ByteArray(8192)
                while (System.currentTimeMillis() - start < timeoutMs) {
                    if (rounds < 3 && System.currentTimeMillis() - start >= rounds * 1200L) {
                        for (st in TARGETS) {
                            val msg = ("M-SEARCH * HTTP/1.1\r\nHOST: $SSDP_ADDR:1900\r\nMAN: \"ssdp:discover\"\r\n" +
                                "MX: 2\r\nST: $st\r\nUSER-AGENT: Android UPnP/1.0 UniversalTVCast/1.0\r\n\r\n").toByteArray()
                            runCatching { sock.send(DatagramPacket(msg, msg.size, group, 1900)) }
                        }
                        rounds++
                    }
                    val p = DatagramPacket(buf, buf.size)
                    try { sock.receive(p) } catch (_: SocketTimeoutException) { continue }
                    val loc = Regex("(?im)^location:\\s*(\\S+)").find(String(p.data, 0, p.length))?.groupValues?.get(1) ?: continue
                    if (!seen.add(loc)) continue
                    pool.execute {
                        runCatching { describe(loc) }.onFailure { Log.i(TAG, "serveur illisible $loc : ${it.message}") }
                            .getOrNull()?.let { if (found.add(it.id)) onFound(it) }
                    }
                }
            }
        } finally {
            lock.release()
            pool.shutdown()
            pool.awaitTermination(5, TimeUnit.SECONDS)
        }
    }

    /** Lit la description d'un appareil ; null s'il ne partage pas de contenu. */
    fun describe(location: String): LanDevice? {
        val f = Http.fetch(location, emptyMap(), connectTimeout = 2500)
        val doc = xml(f.bytes)
        val root = doc.documentElement
        val base = text(root, "URLBase")?.trim()?.takeIf { it.isNotEmpty() } ?: f.finalUrl
        val services = doc.getElementsByTagName("service")
        for (i in 0 until services.length) {
            val s = services.item(i) as? Element ?: continue
            val type = text(s, "serviceType")?.trim() ?: continue
            if (!type.contains(":ContentDirectory:")) continue
            val ctrl = text(s, "controlURL")?.trim() ?: continue
            val host = URL(f.finalUrl).host
            val name = text(root, "friendlyName")?.trim()?.takeIf { it.isNotEmpty() } ?: host
            return LanDevice(
                kind = LanDevice.Kind.MEDIA_SERVER, name = name, host = host, port = URL(f.finalUrl).port,
                id = text(root, "UDN")?.trim() ?: location,
                controlUrl = URL(URL(base), ctrl).toString(), controlType = type,
                model = listOfNotNull(text(root, "manufacturer")?.trim(), text(root, "modelName")?.trim())
                    .distinct().joinToString(" ").ifEmpty { null },
                location = f.finalUrl,
            )
        }
        return null
    }

    /** Ports et chemins habituels, pour un serveur ajouté à la main (ou qui ne répond pas à la recherche). */
    private val PORTS = listOf(8200, 9000, 50001, 32469, 8096, 1400, 49152, 49153, 2869, 5001, 8080)
    private val PATHS = listOf("/rootDesc.xml", "/description.xml", "/DeviceDescription.xml", "/dms/device.xml",
        "/dlna/description.xml", "/desc.xml", "/upnp/desc.xml", "/MediaServer/desc.xml")

    fun probe(host: String, port: Int? = null): LanDevice? {
        for (p in port?.let { listOf(it) } ?: PORTS) {
            if (!Lan.portOpen(host, p, 600)) continue
            for (path in PATHS) runCatching { describe("http://$host:$p$path") }.getOrNull()?.let { return it }
        }
        return null
    }

    /** Contenu d'un dossier (« 0 » : la racine) ; dossiers d'abord, puis vidéos, musiques et photos. */
    fun browse(d: LanDevice, objectId: String): List<LanEntry> {
        val out = mutableListOf<LanEntry>()
        var start = 0
        while (out.size < 5000) {
            val resp = soap(d.controlUrl!!, d.controlType!!, "Browse", listOf(
                "ObjectID" to objectId, "BrowseFlag" to "BrowseDirectChildren", "Filter" to "*",
                "StartingIndex" to start.toString(), "RequestedCount" to "500", "SortCriteria" to "",
            ))
            val result = Regex("<Result[^>]*>(.*?)</Result>", RegexOption.DOT_MATCHES_ALL).find(resp)?.groupValues?.get(1) ?: break
            val didl = unescape(result)
            val page = parseDidl(didl, d)
            out += page
            val returned = Regex("<NumberReturned>(\\d+)</NumberReturned>").find(resp)?.groupValues?.get(1)?.toIntOrNull() ?: page.size
            val total = Regex("<TotalMatches>(\\d+)</TotalMatches>").find(resp)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            start += returned
            if (returned == 0 || start >= total) break
        }
        return out.sortedWith(Lan.ORDER)
    }

    /**
     * Recherche par titre chez le serveur (action « Search » de l'UPnP, que minidlna, Rygel, Jellyfin ou Plex savent
     * faire) : les vidéos dont le titre contient [query]. Null si le serveur ne sait pas chercher.
     */
    fun search(d: LanDevice, query: String): List<LanEntry>? = runCatching {
        val q = query.replace("\\", "").replace("\"", "")
        val resp = soap(d.controlUrl!!, d.controlType!!, "Search", listOf(
            "ContainerID" to "0",
            "SearchCriteria" to "upnp:class derivedfrom \"object.item.videoItem\" and dc:title contains \"$q\"",
            "Filter" to "*", "StartingIndex" to "0", "RequestedCount" to "60", "SortCriteria" to "",
        ))
        val result = Regex("<Result[^>]*>(.*?)</Result>", RegexOption.DOT_MATCHES_ALL).find(resp)?.groupValues?.get(1) ?: return@runCatching null
        val videos = parseDidl(unescape(result), d).filter { it.kind == LanEntry.Kind.VIDEO }
        // Affiches posées à côté (« Film.jpg ») : une seconde recherche, parmi les images, au même nom.
        val images = runCatching {
            val r2 = soap(d.controlUrl!!, d.controlType!!, "Search", listOf(
                "ContainerID" to "0",
                "SearchCriteria" to "upnp:class derivedfrom \"object.item.imageItem\" and dc:title contains \"$q\"",
                "Filter" to "*", "StartingIndex" to "0", "RequestedCount" to "60", "SortCriteria" to "",
            ))
            Regex("<Result[^>]*>(.*?)</Result>", RegexOption.DOT_MATCHES_ALL).find(r2)?.groupValues?.get(1)
                ?.let { parseDidl(unescape(it), d) }.orEmpty().associateBy { it.name.substringBeforeLast('.').lowercase() }
        }.getOrDefault(emptyMap())
        videos.map { v -> if (v.thumb != null) v else v.copy(thumb = images[v.name.substringBeforeLast('.').lowercase()]?.ref) }
    }.getOrNull()

    internal fun parseDidl(didl: String, d: LanDevice): List<LanEntry> {
        val doc = runCatching { xml(didl.toByteArray()) }.getOrNull() ?: return emptyList()
        val out = mutableListOf<LanEntry>()
        val containers = doc.getElementsByTagName("container")
        for (i in 0 until containers.length) {
            val c = containers.item(i) as Element
            val title = text(c, "dc:title") ?: continue
            out += LanEntry(name = title, folder = true, ref = c.getAttribute("id"),
                thumb = text(c, "upnp:albumArtURI"), device = d)
        }
        val items = doc.getElementsByTagName("item")
        for (i in 0 until items.length) {
            val it = items.item(i) as Element
            val title = text(it, "dc:title") ?: continue
            val res = it.getElementsByTagName("res")
            var best: Element? = null
            var mime: String? = null
            for (j in 0 until res.length) {
                val r = res.item(j) as Element
                val m = r.getAttribute("protocolInfo").split(':').getOrNull(2)?.lowercase() ?: continue
                if (!r.textContent.trim().startsWith("http")) continue
                // La version d'origine d'abord (la première) ; une miniature d'image ne remplace pas une vidéo.
                if (best == null || (mime?.startsWith("image/") == true && !m.startsWith("image/"))) { best = r; mime = m }
            }
            val url = best?.textContent?.trim() ?: continue
            val kind = Lan.kindOf(mime, title).takeIf { it != LanEntry.Kind.OTHER }
                ?: when { text(it, "upnp:class")?.contains("videoItem") == true -> LanEntry.Kind.VIDEO
                    text(it, "upnp:class")?.contains("audioItem") == true -> LanEntry.Kind.AUDIO
                    text(it, "upnp:class")?.contains("imageItem") == true -> LanEntry.Kind.PHOTO
                    else -> LanEntry.Kind.OTHER }
            if (kind == LanEntry.Kind.OTHER) continue
            val thumb = text(it, "upnp:albumArtURI") ?: (0 until res.length).map { res.item(it) as Element }
                .firstOrNull { r -> r !== best && r.getAttribute("protocolInfo").contains("image/") }?.textContent?.trim()
            out += LanEntry(name = title, folder = false, ref = url, kind = kind, mime = mime,
                size = best.getAttribute("size").toLongOrNull(), duration = hms(best.getAttribute("duration")),
                thumb = thumb, device = d, date = text(it, "dc:date")?.let(::isoDate))
        }
        return out
    }

    private fun isoDate(s: String): Long? = runCatching {
        java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ROOT).parse(s.take(10))?.time
    }.getOrNull()

    private fun hms(s: String?): Double? {
        val p = s?.takeIf { it.isNotBlank() }?.split(':') ?: return null
        return runCatching { p.fold(0.0) { acc, v -> acc * 60 + v.toDouble() } }.getOrNull()?.takeIf { it > 0 }
    }

    private fun soap(url: String, type: String, action: String, args: List<Pair<String, String>>): String {
        val body = buildString {
            append("<?xml version=\"1.0\" encoding=\"utf-8\"?>")
            append("<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" ")
            append("s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body>")
            append("<u:$action xmlns:u=\"$type\">")
            for ((k, v) in args) append("<$k>").append(Dlna.esc(v)).append("</$k>")
            append("</u:$action></s:Body></s:Envelope>")
        }.toByteArray()
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.doOutput = true
            c.connectTimeout = 5000
            c.readTimeout = 20000
            c.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
            c.setRequestProperty("SOAPACTION", "\"$type#$action\"")
            c.setFixedLengthStreamingMode(body.size)
            c.outputStream.use { it.write(body) }
            val code = c.responseCode
            val text = (if (code >= 400) c.errorStream else c.inputStream)?.use { String(it.readBytes(), Charsets.UTF_8) } ?: ""
            if (code >= 400) throw java.io.IOException("Browse $code")
            return text
        } finally {
            c.disconnect()
        }
    }

    private fun xml(bytes: ByteArray) = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = false
        isExpandEntityReferences = false
    }.newDocumentBuilder().parse(ByteArrayInputStream(bytes))

    private fun unescape(s: String) = s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
        .replace("&apos;", "'").replace("&amp;", "&")

    private fun text(e: Element, tag: String): String? {
        val n = e.getElementsByTagName(tag)
        return if (n.length > 0) n.item(0).textContent?.trim()?.takeIf { it.isNotEmpty() } else null
    }

    private const val TAG = "TvCastLan"
}
