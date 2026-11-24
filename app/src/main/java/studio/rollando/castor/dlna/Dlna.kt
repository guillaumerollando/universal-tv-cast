package studio.rollando.castor.dlna

import android.content.Context
import android.net.wifi.WifiManager
import org.w3c.dom.Element
import studio.rollando.castor.net.Http
import studio.rollando.castor.net.Net
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.net.URL
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.xml.parsers.DocumentBuilderFactory

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
)

/** La télé a répondu par une erreur UPnP. */
class TvError(val detail: String) : IOException(detail)

object Dlna {
    private const val SSDP_ADDR = "239.255.255.250"
    private val TARGETS = listOf(
        "urn:schemas-upnp-org:device:MediaRenderer:1",
        "urn:schemas-upnp-org:service:AVTransport:1",
        "ssdp:all",
    )

    /** Cherche les télés pendant [timeoutMs] ; [onFound] est appelé dès qu'une télé est décrite. */
    fun discover(ctx: Context, timeoutMs: Long = 5000, onFound: (Renderer) -> Unit) {
        val wifi = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val lock = wifi.createMulticastLock("castor-ssdp").apply { setReferenceCounted(false); acquire() }
        val pool = Executors.newFixedThreadPool(6)
        val seen = HashSet<String>()
        try {
            DatagramSocket(null).use { sock ->
                sock.reuseAddress = true
                val ip = Net.localIp()
                sock.bind(InetSocketAddress(ip?.let { InetAddress.getByName(it) }, 0))
                sock.soTimeout = 300
                val group = InetAddress.getByName(SSDP_ADDR)
                val start = System.currentTimeMillis()
                var rounds = 0
                val buf = ByteArray(8192)
                while (System.currentTimeMillis() - start < timeoutMs) {
                    if (rounds < 3 && System.currentTimeMillis() - start >= rounds * 1200L) {
                        for (st in TARGETS) {
                            val msg = ("M-SEARCH * HTTP/1.1\r\nHOST: $SSDP_ADDR:1900\r\nMAN: \"ssdp:discover\"\r\n" +
                                "MX: 2\r\nST: $st\r\nUSER-AGENT: Android UPnP/1.0 Castor/1.0\r\n\r\n").toByteArray()
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
                    val text = String(p.data, 0, p.length)
                    val loc = Regex("(?im)^location:\\s*(\\S+)").find(text)?.groupValues?.get(1)?.trim() ?: continue
                    if (seen.add(loc)) pool.execute { runCatching { describe(loc) }.getOrNull()?.let(onFound) }
                }
            }
        } finally {
            lock.release()
            pool.shutdown()
            pool.awaitTermination(6, TimeUnit.SECONDS)
        }
    }

    fun describe(location: String): Renderer? {
        val f = Http.fetch(location, emptyMap(), connectTimeout = 3000)
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            isExpandEntityReferences = false
        }
        val doc = factory.newDocumentBuilder().parse(ByteArrayInputStream(f.bytes))
        val root = doc.documentElement
        val base = text(root, "URLBase")?.trim()?.takeIf { it.isNotEmpty() } ?: f.finalUrl
        var av: Pair<String, String>? = null
        var rc: Pair<String, String>? = null
        val services = doc.getElementsByTagName("service")
        for (i in 0 until services.length) {
            val s = services.item(i) as? Element ?: continue
            val type = text(s, "serviceType")?.trim() ?: continue
            val ctrl = text(s, "controlURL")?.trim() ?: continue
            val abs = URL(URL(base), ctrl).toString()
            if (type.contains(":AVTransport:") && av == null) av = abs to type
            if (type.contains(":RenderingControl:") && rc == null) rc = abs to type
        }
        val avp = av ?: return null
        val name = text(root, "friendlyName")?.trim()?.takeIf { it.isNotEmpty() } ?: URL(location).host
        return Renderer(
            name = name,
            udn = text(root, "UDN")?.trim() ?: location,
            avUrl = avp.first,
            avType = avp.second,
            rcUrl = rc?.first,
            rcType = rc?.second,
            manufacturer = text(root, "manufacturer")?.trim(),
            model = text(root, "modelName")?.trim(),
        )
    }

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

    private fun soap(url: String, type: String, action: String, args: List<Pair<String, String>>): String {
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
            c.readTimeout = 8000
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
        soap(r.avUrl, r.avType, action, listOf("InstanceID" to "0") + args)

    private fun rc(r: Renderer, action: String, vararg args: Pair<String, String>): String {
        val url = r.rcUrl ?: throw TvError("no volume control")
        return soap(url, r.rcType!!, action, listOf("InstanceID" to "0") + args)
    }

    fun setUri(r: Renderer, url: String, meta: String) {
        av(r, "SetAVTransportURI", "CurrentURI" to url, "CurrentURIMetaData" to meta)
    }

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
    fun didl(title: String, url: String, mime: String, subtitleUrl: String?, seekable: Boolean): String {
        val op = if (seekable) "01" else "00"
        val info = "http-get:*:$mime:DLNA.ORG_OP=$op;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000"
        val cls = if (mime.startsWith("audio/")) "object.item.audioItem.musicTrack" else "object.item.videoItem"
        return buildString {
            append("<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" ")
            append("xmlns:dc=\"http://purl.org/dc/elements/1.1/\" ")
            append("xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\" ")
            append("xmlns:dlna=\"urn:schemas-dlna-org:metadata-1-0/\" ")
            append("xmlns:sec=\"http://www.sec.co.kr/\">")
            append("<item id=\"castor\" parentID=\"0\" restricted=\"1\">")
            append("<dc:title>").append(esc(title)).append("</dc:title>")
            append("<upnp:class>$cls</upnp:class>")
            append("<res protocolInfo=\"").append(esc(info)).append("\">").append(esc(url)).append("</res>")
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
