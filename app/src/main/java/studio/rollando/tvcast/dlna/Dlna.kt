package studio.rollando.tvcast.dlna

import android.content.Context
import android.net.wifi.WifiManager
import org.w3c.dom.Element
import studio.rollando.tvcast.net.Http
import studio.rollando.tvcast.net.Net
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URL
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.concurrent.thread

/** Une télé (ou tout lecteur DLNA / UPnP « MediaRenderer ») trouvée sur le réseau. */
data class Renderer(
    val name: String,
    val udn: String,
    val avUrl: String,
    val avType: String,
    val rcUrl: String?,
    val rcType: String?,
    val manufacturer: String?,
    val model: String?,
    val location: String = "",
    val cmUrl: String? = null,
    val cmType: String? = null,
    /** Appareil Google Cast (Chromecast, Google TV, télé avec Cast intégré) plutôt que DLNA. */
    val cast: Boolean = false,
    val routeId: String? = null,
    /** Pour une télé qui a Cast et DLNA : l'entrée DLNA de secours si le lecteur Cast refuse la vidéo. */
    val fallback: Renderer? = null,
    /**
     * La télé a répondu à la recherche du réseau (SSDP). Une télé en veille répond encore aux requêtes directes, mais
     * plus à la recherche : elle est listée sans être choisie d'office.
     */
    val awake: Boolean = true,
) {
    val host: String
        get() = if (location.startsWith("cast://")) location.removePrefix("cast://")
        else runCatching { URL(location.ifEmpty { avUrl }).host }.getOrDefault("")
}

/** La télé a répondu par une erreur UPnP. */
class TvError(val detail: String) : IOException(detail)

/** La télé ne répond pas (occupée, en train de redémarrer ou éteinte). */
class TvBusy(cause: Throwable) : IOException(cause)

object Dlna {
    private const val SSDP_ADDR = "239.255.255.250"
    private val TARGETS = listOf(
        "urn:schemas-upnp-org:device:MediaRenderer:1",
        "urn:schemas-upnp-org:service:AVTransport:1",
        "ssdp:all",
    )

    /** Message SSDP (annonce ou réponse) qui parle de lecture vidéo : récepteur, transport, volume. */
    internal fun isRendererMessage(text: String): Boolean = text.lines().any { l ->
        val h = l.substringBefore(':').trim().uppercase()
        (h == "NT" || h == "ST" || h == "USN") &&
            Regex("(?i)MediaRenderer|AVTransport|RenderingControl").containsMatchIn(l.substringAfter(':'))
    }

    /** Ports et chemins habituels des récepteurs DLNA, pour les télés qui ne répondent pas à la recherche SSDP. */
    private val PROBE_PORTS = listOf(49152, 49153, 49154, 49155, 9197, 2869, 8080)
    private val DESC_PATHS = listOf(
        "/description.xml", "/dmr.xml", "/DeviceDescription.xml", "/rootDesc.xml", "/dmr/description.xml",
        "/MediaRenderer/desc.xml", "/upnp/desc/aios_device/aios_device.xml", "/dd.xml",
    )

    /**
     * Cherche les télés pendant environ [timeoutMs] ; [onFound] est appelé dès qu'une télé est décrite.
     * Trois méthodes en parallèle : recherche SSDP, écoute des annonces, et sondage direct du réseau local
     * (certaines télés, dont des Philips Titan OS, ne répondent jamais à la recherche SSDP).
     * [known] : adresses de description déjà connues, vérifiées tout de suite.
     */
    fun discover(
        ctx: Context, known: Collection<String> = emptyList(), timeoutMs: Long = 5000,
        /** Adresse d'un appareil qui a répondu à la recherche du réseau (il est allumé). */
        onAwake: (String) -> Unit = {},
        onFound: (Renderer) -> Unit,
    ) {
        val wifi = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val lock = wifi.createMulticastLock("tvcast-ssdp").apply { setReferenceCounted(false); acquire() }
        val pool = Executors.newFixedThreadPool(64)
        val seenLocations = ConcurrentHashMap.newKeySet<String>()
        val probedHosts = ConcurrentHashMap.newKeySet<String>()
        val found = ConcurrentHashMap.newKeySet<String>()
        val myIp = Net.localIp()

        fun tryLocation(loc: String) {
            if (!seenLocations.add(loc)) return
            pool.execute {
                runCatching { describe(loc) }.getOrNull()?.let { r -> if (found.add(r.udn)) onFound(r) }
            }
        }

        fun probeHost(host: String, ports: List<Int> = PROBE_PORTS) {
            if (host == myIp) return
            for (port in ports) pool.execute {
                if (!portOpen(host, port)) return@execute
                for (path in DESC_PATHS) {
                    val loc = "http://$host:$port$path"
                    if (!seenLocations.add(loc)) continue
                    val r = runCatching { describe(loc) }.getOrNull() ?: continue
                    if (found.add(r.udn)) onFound(r)
                    break
                }
            }
        }

        try {
            known.forEach(::tryLocation)

            // Sondage direct du /24 du téléphone.
            if (myIp != null) {
                val prefix = myIp.substringBeforeLast('.')
                for (i in 1..254) probeHost("$prefix.$i")
            }

            // Écoute des annonces NOTIFY (port 1900).
            val listener = thread(isDaemon = true, name = "tvcast-notify") {
                runCatching {
                    MulticastSocket(null).use { m ->
                        m.reuseAddress = true
                        m.bind(InetSocketAddress(1900))
                        @Suppress("DEPRECATION")
                        m.joinGroup(InetAddress.getByName(SSDP_ADDR))
                        m.soTimeout = 300
                        val buf = ByteArray(8192)
                        val end = System.currentTimeMillis() + timeoutMs
                        while (System.currentTimeMillis() < end) {
                            val p = DatagramPacket(buf, buf.size)
                            try { m.receive(p) } catch (_: SocketTimeoutException) { continue }
                            val text = String(p.data, 0, p.length)
                            if (!text.startsWith("NOTIFY")) continue
                            // Allumée seulement si l'annonce concerne la lecture : en veille, la Philips continue
                            // d'annoncer d'autres services (« PhotoFrame » de TPV) sans répondre à la recherche.
                            if (text.contains("ssdp:alive", true) && isRendererMessage(text)) p.address?.hostAddress?.let(onAwake)
                            location(text)?.let(::tryLocation)
                            p.address?.hostAddress?.let { if (probedHosts.add(it)) probeHost(it) }
                        }
                    }
                }
            }

            // Recherche SSDP classique.
            DatagramSocket(null).use { sock ->
                sock.reuseAddress = true
                sock.bind(InetSocketAddress(myIp?.let { InetAddress.getByName(it) }, 0))
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
                    try {
                        sock.receive(p)
                    } catch (_: SocketTimeoutException) {
                        continue
                    }
                    val reply = String(p.data, 0, p.length)
                    // Réponse à « ssdp:all » pour un autre service (veille réseau) : pas une preuve que la télé est allumée.
                    if (isRendererMessage(reply)) p.address?.hostAddress?.let(onAwake)
                    location(reply)?.let(::tryLocation)
                }
            }
            listener.join(1000)
        } finally {
            lock.release()
            pool.shutdown()
            pool.awaitTermination(8, TimeUnit.SECONDS)
            pool.shutdownNow()
        }
    }

    /** Cherche une télé à une adresse IP précise (ajout manuel). */
    fun probe(host: String): Renderer? {
        for (port in PROBE_PORTS) {
            if (!portOpen(host, port, 800)) continue
            for (path in DESC_PATHS) runCatching { describe("http://$host:$port$path") }.getOrNull()?.let { return it }
        }
        return null
    }

    private fun location(text: String): String? =
        Regex("(?im)^location:\\s*(\\S+)").find(text)?.groupValues?.get(1)?.trim()

    private fun portOpen(host: String, port: Int, timeout: Int = 250): Boolean = try {
        Socket().use { it.connect(InetSocketAddress(host, port), timeout); true }
    } catch (_: Exception) {
        false
    }

    fun describe(location: String): Renderer? {
        val f = Http.fetch(location, emptyMap(), connectTimeout = 2000)
        if (!String(f.bytes, 0, minOf(f.bytes.size, 512)).contains("<root", ignoreCase = true) &&
            !String(f.bytes).contains("AVTransport")
        ) return null
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            isExpandEntityReferences = false
        }
        val doc = factory.newDocumentBuilder().parse(ByteArrayInputStream(f.bytes))
        val root = doc.documentElement
        val base = text(root, "URLBase")?.trim()?.takeIf { it.isNotEmpty() } ?: f.finalUrl
        var av: Pair<String, String>? = null
        var rc: Pair<String, String>? = null
        var cm: Pair<String, String>? = null
        val services = doc.getElementsByTagName("service")
        for (i in 0 until services.length) {
            val s = services.item(i) as? Element ?: continue
            val type = text(s, "serviceType")?.trim() ?: continue
            val ctrl = text(s, "controlURL")?.trim() ?: continue
            val abs = URL(URL(base), ctrl).toString()
            if (type.contains(":AVTransport:") && av == null) av = abs to type
            if (type.contains(":RenderingControl:") && rc == null) rc = abs to type
            if (type.contains(":ConnectionManager:") && cm == null) cm = abs to type
        }
        val avp = av ?: return null
        val host = URL(f.finalUrl).host
        // « 55OLED760/12(192.168.1.20) » → « 55OLED760/12 »
        val name = text(root, "friendlyName")?.trim()?.replace(Regex("\\s*\\(${Regex.escape(host)}\\)$"), "")
            ?.takeIf { it.isNotEmpty() } ?: host
        return Renderer(
            name = name,
            udn = text(root, "UDN")?.trim() ?: location,
            avUrl = avp.first,
            avType = avp.second,
            rcUrl = rc?.first,
            rcType = rc?.second,
            manufacturer = text(root, "manufacturer")?.trim(),
            model = text(root, "modelName")?.trim(),
            location = f.finalUrl,
            cmUrl = cm?.first,
            cmType = cm?.second,
        )
    }

    /** Types MIME que la télé déclare savoir lire (ConnectionManager / GetProtocolInfo). */
    fun sinkMimes(r: Renderer): List<String> {
        val url = r.cmUrl ?: return emptyList()
        val x = soap(url, r.cmType!!, "GetProtocolInfo", emptyList())
        val sink = Regex("<Sink>(.*?)</Sink>", RegexOption.DOT_MATCHES_ALL).find(x)?.groupValues?.get(1) ?: return emptyList()
        return unescape(sink).split(',').mapNotNull { it.split(':').getOrNull(2)?.trim() }.filter { it.isNotEmpty() && it != "*" }.distinct()
    }

    private val ALIASES = listOf(
        listOf("video/mp2t", "video/mpeg", "video/MP2T", "video/x-mpegts", "video/vnd.dlna.mpeg-tts"),
        listOf("video/x-matroska", "video/x-mkv", "video/mkv"),
        listOf("video/x-msvideo", "video/avi", "video/x-avi"),
        listOf("audio/mp4", "audio/x-m4a", "audio/m4a"),
        listOf("audio/wav", "audio/x-wav", "audio/wave"),
        listOf("application/vnd.apple.mpegurl", "application/x-mpegURL", "application/x-mpegurl", "audio/mpegurl", "audio/x-mpegurl"),
    )

    /** Choisit, parmi les noms équivalents d'un format, celui que la télé déclare accepter. */
    fun adaptMime(mime: String, sinks: List<String>): String {
        if (sinks.isEmpty() || sinks.any { it.equals(mime, true) }) return mime
        val group = ALIASES.firstOrNull { g -> g.any { it.equals(mime, true) } } ?: return mime
        return group.firstOrNull { a -> sinks.any { it.equals(a, true) } } ?: mime
    }

    fun accepts(sinks: List<String>, mime: String): Boolean =
        sinks.isEmpty() || adaptMime(mime, sinks).let { m -> sinks.any { it.equals(m, true) } }

    private fun unescape(s: String) = s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
        .replace("&apos;", "'").replace("&amp;", "&")

    private fun text(e: Element, tag: String): String? {
        val n = e.getElementsByTagName(tag)
        return if (n.length > 0) n.item(0).textContent else null
    }

    fun esc(s: String) = buildString(s.length + 16) {
        for (c in s) when (c) {
            '&' -> append("&amp;"); '<' -> append("&lt;"); '>' -> append("&gt;")
            '"' -> append("&quot;"); '\'' -> append("&apos;"); else -> append(c)
        }
    }

    /** Une seule commande à la fois : le serveur UPnP des télés (libupnp) sature vite. */
    private val soapLock = Any()

    private fun soap(url: String, type: String, action: String, args: List<Pair<String, String>>, readTimeout: Int = 8000): String =
        synchronized(soapLock) {
            try {
                soapNow(url, type, action, args, readTimeout)
            } catch (e: java.net.SocketTimeoutException) {
                throw TvBusy(e)
            } catch (e: java.net.ConnectException) {
                throw TvBusy(e)
            }
        }

    private fun soapNow(url: String, type: String, action: String, args: List<Pair<String, String>>, readTimeout: Int): String {
        val body = buildString {
            append("<?xml version=\"1.0\" encoding=\"utf-8\"?>")
            append("<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" ")
            append("s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body>")
            append("<u:$action xmlns:u=\"$type\">")
            for ((k, v) in args) append("<$k>").append(esc(v)).append("</$k>")
            append("</u:$action></s:Body></s:Envelope>")
        }.toByteArray()
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.doOutput = true
            c.connectTimeout = 5000
            c.readTimeout = readTimeout
            c.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
            c.setRequestProperty("SOAPACTION", "\"$type#$action\"")
            c.setFixedLengthStreamingMode(body.size)
            c.outputStream.use { it.write(body) }
            val code = c.responseCode
            val text = (if (code >= 400) c.errorStream else c.inputStream)?.use { String(it.readBytes()) } ?: ""
            if (code >= 400) {
                val desc = tag(text, "errorDescription")
                val ec = tag(text, "errorCode")
                throw TvError(listOfNotNull(ec ?: code.toString(), desc).joinToString(" · "))
            }
            return text
        } finally {
            c.disconnect()
        }
    }

    private fun tag(xml: String, name: String): String? =
        Regex("<(?:\\w+:)?$name(?:\\s[^>]*)?>(.*?)</(?:\\w+:)?$name>", RegexOption.DOT_MATCHES_ALL)
            .find(xml)?.groupValues?.get(1)?.trim()

    private fun av(r: Renderer, action: String, vararg args: Pair<String, String>) =
        soap(r.avUrl, r.avType, action, listOf("InstanceID" to "0") + args,
            // La télé interroge la vidéo (HEAD) avant de répondre à ces commandes.
            readTimeout = if (action == "SetAVTransportURI" || action == "Play" || action == "Seek") 25_000 else 6000)

    private fun avQuick(r: Renderer, action: String, vararg args: Pair<String, String>) =
        soap(r.avUrl, r.avType, action, listOf("InstanceID" to "0") + args, readTimeout = 7000)

    private fun rc(r: Renderer, action: String, vararg args: Pair<String, String>): String {
        val url = r.rcUrl ?: throw TvError("no volume control")
        return soap(url, r.rcType!!, action, listOf("InstanceID" to "0") + args)
    }

    fun setUri(r: Renderer, url: String, meta: String) {
        av(r, "SetAVTransportURI", "CurrentURI" to url, "CurrentURIMetaData" to meta)
    }

    /** Variante pour une photo (servie par le téléphone, instantanée) : on n'attend pas la télé 25 s. */
    fun setUriQuick(r: Renderer, url: String, meta: String) {
        avQuick(r, "SetAVTransportURI", "CurrentURI" to url, "CurrentURIMetaData" to meta)
    }

    fun playQuick(r: Renderer) { avQuick(r, "Play", "Speed" to "1") }

    fun play(r: Renderer) { av(r, "Play", "Speed" to "1") }
    fun pause(r: Renderer) { av(r, "Pause") }
    fun stop(r: Renderer) { av(r, "Stop") }
    fun seek(r: Renderer, seconds: Double) { av(r, "Seek", "Unit" to "REL_TIME", "Target" to hms(seconds)) }

    /** Position et durée en secondes (0 si la télé ne sait pas). */
    fun position(r: Renderer): Pair<Double, Double> {
        val x = av(r, "GetPositionInfo")
        return parseHms(tag(x, "RelTime")) to parseHms(tag(x, "TrackDuration"))
    }

    fun transportState(r: Renderer): String? = tag(av(r, "GetTransportInfo"), "CurrentTransportState")

    fun getVolume(r: Renderer): Int =
        tag(rc(r, "GetVolume", "Channel" to "Master"), "CurrentVolume")?.toIntOrNull() ?: 0

    fun setVolume(r: Renderer, v: Int) {
        rc(r, "SetVolume", "Channel" to "Master", "DesiredVolume" to v.toString())
    }

    fun hms(sec: Double): String {
        val s = sec.coerceAtLeast(0.0).toLong()
        return String.format(Locale.US, "%d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60)
    }

    fun parseHms(t: String?): Double {
        if (t.isNullOrBlank()) return 0.0
        val p = t.trim().split(':')
        if (p.size != 3) return 0.0
        val h = p[0].toDoubleOrNull() ?: return 0.0
        val m = p[1].toDoubleOrNull() ?: return 0.0
        val s = p[2].toDoubleOrNull() ?: return 0.0
        return h * 3600 + m * 60 + s
    }

    /** Métadonnées DIDL-Lite (titre, type, sous-titres au format reconnu par la plupart des télés). */
    fun didl(
        title: String, url: String, mime: String, subtitleUrl: String?, seekable: Boolean,
        artUrl: String? = null, durationSec: Double = 0.0,
    ): String {
        val op = if (seekable) "01" else "00"
        val info = if (mime.startsWith("image/")) "http-get:*:image/jpeg:DLNA.ORG_PN=JPEG_LRG;DLNA.ORG_OP=00;DLNA.ORG_FLAGS=00900000000000000000000000000000"
            else "http-get:*:$mime:DLNA.ORG_OP=$op;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000"
        val cls = when {
            mime.startsWith("audio/") -> "object.item.audioItem.musicTrack"
            mime.startsWith("image/") -> "object.item.imageItem.photo"
            else -> "object.item.videoItem"
        }
        return buildString {
            append("<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" ")
            append("xmlns:dc=\"http://purl.org/dc/elements/1.1/\" ")
            append("xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\" ")
            append("xmlns:dlna=\"urn:schemas-dlna-org:metadata-1-0/\" ")
            append("xmlns:sec=\"http://www.sec.co.kr/\">")
            append("<item id=\"tvcast\" parentID=\"0\" restricted=\"1\">")
            append("<dc:title>").append(esc(title)).append("</dc:title>")
            append("<upnp:class>$cls</upnp:class>")
            append("<dc:creator>Universal TV Cast</dc:creator>")
            if (artUrl != null) {
                append("<upnp:albumArtURI dlna:profileID=\"JPEG_SM\">").append(esc(artUrl)).append("</upnp:albumArtURI>")
            }
            append("<res protocolInfo=\"").append(esc(info)).append("\"")
            if (durationSec > 0) append(" duration=\"").append(hms(durationSec)).append(".000\"")
            append(">").append(esc(url)).append("</res>")

            if (subtitleUrl != null) {
                val s = esc(subtitleUrl)
                append("<res protocolInfo=\"http-get:*:text/srt:*\">$s</res>")
                append("<sec:CaptionInfoEx sec:type=\"srt\">$s</sec:CaptionInfoEx>")
                append("<sec:CaptionInfo sec:type=\"srt\">$s</sec:CaptionInfo>")
            }
            append("</item></DIDL-Lite>")
        }
    }
}
