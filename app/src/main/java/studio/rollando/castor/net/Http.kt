package studio.rollando.castor.net

import java.io.IOException
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URL

class HttpError(val code: Int) : IOException("HTTP $code")

/** Petites aides HTTP : redirections suivies à la main (y compris http ↔ https). */
object Http {
    const val DEFAULT_UA =
        "Mozilla/5.0 (Linux; Android 14; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

    private val SKIPPED = setOf("range", "host", "accept-encoding", "connection", "content-length")

    fun open(
        url: String,
        headers: Map<String, String>,
        range: String? = null,
        connectTimeout: Int = 15_000,
        readTimeout: Int = 30_000,
    ): HttpURLConnection {
        var current = url
        repeat(8) {
            val c = URL(current).openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = connectTimeout
            c.readTimeout = readTimeout
            var hasUa = false
            for ((k, v) in headers) {
                if (k.lowercase() in SKIPPED) continue
                c.setRequestProperty(k, v)
                if (k.equals("user-agent", true)) hasUa = true
            }
            if (!hasUa) c.setRequestProperty("User-Agent", DEFAULT_UA)
            if (range != null) c.setRequestProperty("Range", range)
            c.setRequestProperty("Accept-Encoding", "identity")
            val code = c.responseCode
            if (code in 300..399 && code != 304) {
                val loc = c.getHeaderField("Location")
                c.disconnect()
                if (loc.isNullOrBlank()) throw IOException("redirect without location")
                current = URL(URL(current), loc).toString()
                return@repeat
            }
            return c
        }
        throw IOException("too many redirects")
    }

    class Fetched(val bytes: ByteArray, val finalUrl: String, val contentType: String?)

    fun fetch(url: String, headers: Map<String, String>, connectTimeout: Int = 15_000): Fetched {
        val c = open(url, headers, connectTimeout = connectTimeout)
        try {
            if (c.responseCode >= 400) throw HttpError(c.responseCode)
            val bytes = c.inputStream.use { it.readBytes() }
            return Fetched(bytes, c.url.toString(), c.contentType)
        } finally {
            c.disconnect()
        }
    }
}

object Net {
    /** Adresse IPv4 du téléphone sur le réseau local (Wi-Fi en priorité). */
    fun localIp(): String? {
        val ifs = runCatching { NetworkInterface.getNetworkInterfaces()?.toList().orEmpty() }.getOrDefault(emptyList())
            .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
            .sortedBy {
                when {
                    it.name.startsWith("wlan") -> 0
                    it.name.startsWith("eth") -> 1
                    it.name.startsWith("ap") || it.name.startsWith("swlan") -> 2
                    else -> 3
                }
            }
        for (i in ifs) for (a in i.inetAddresses) {
            if (a is Inet4Address && a.isSiteLocalAddress) return a.hostAddress
        }
        return null
    }
}

object Mime {
    fun ext(url: String): String =
        url.substringBefore('?').substringBefore('#').substringAfterLast('/').substringAfterLast('.', "").lowercase()

    fun guess(url: String): String = when (ext(url)) {
        "mp4", "m4v" -> "video/mp4"
        "mkv" -> "video/x-matroska"
        "webm" -> "video/webm"
        "ts", "m2ts", "mts" -> "video/mp2t"
        "avi" -> "video/x-msvideo"
        "mov" -> "video/quicktime"
        "m3u8" -> "application/vnd.apple.mpegurl"
        "mp3" -> "audio/mpeg"
        "m4a", "aac" -> "audio/mp4"
        "flac" -> "audio/flac"
        "ogg", "opus" -> "audio/ogg"
        "wav" -> "audio/wav"
        else -> "video/mp4"
    }

    fun isMedia(ct: String?) = ct != null && (ct.startsWith("video/") || ct.startsWith("audio/"))
}
