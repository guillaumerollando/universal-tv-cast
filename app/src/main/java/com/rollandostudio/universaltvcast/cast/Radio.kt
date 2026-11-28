package com.rollandostudio.universaltvcast.cast

import android.util.Log
import com.rollandostudio.universaltvcast.net.Http
import org.json.JSONArray
import java.io.InputStream
import java.net.HttpURLConnection

/**
 * Radios en ligne pour l'ambiance, trouvées dans l'annuaire libre Radio Browser (radio-browser.info, sans clé ni
 * compte) par style, et leur son glissé tel quel dans le flux vidéo de l'ambiance ([SlideStream]) : pas de
 * réencodage, les trames MP3 ou AAC sont découpées et calées sur l'horloge de la vidéo.
 */
object Radio {
    private const val TAG = "TvCastRadio"
    private const val UA = "UniversalTVCast/1.0 (+https://rollandostudio.com)"

    /** Une radio : nom, adresse du flux. */
    data class Station(val name: String, val url: String)

    /** Les radios les plus écoutées d'un style (MP3 ou AAC, au moins 96 kb/s), sans les flux signalés en panne. */
    fun stations(tags: List<String>): List<Station> {
        val out = LinkedHashMap<String, Station>()
        for (tag in tags) {
            runCatching {
                val c = Http.open("https://all.api.radio-browser.info/json/stations/bytag/${java.net.URLEncoder.encode(tag, "UTF-8")}" +
                    "?order=clickcount&reverse=true&limit=40&hidebroken=true", mapOf("User-Agent" to UA), connectTimeout = 8000, readTimeout = 10_000)
                val a = try { JSONArray(c.inputStream.use { String(it.readBytes()) }) } finally { c.disconnect() }
                for (i in 0 until a.length()) {
                    val o = a.getJSONObject(i)
                    val codec = o.optString("codec").uppercase()
                    if (codec != "MP3" && codec != "AAC" && codec != "AAC+") continue
                    if (o.optInt("bitrate") in 1..95) continue
                    val url = o.optString("url_resolved").ifBlank { o.optString("url") }
                    if (!url.startsWith("http")) continue
                    val name = o.optString("name").trim().ifBlank { continue }
                    if (name.contains("christmas", true) || name.contains("xmas", true)) continue
                    out.putIfAbsent(name.lowercase(), Station(name, url))
                }
            }.onFailure { Log.i(TAG, "annuaire ($tag) : ${it.message}") }
        }
        return out.values.toList()
    }

    /**
     * Le son d'une radio, en continu vers [stream]. Démarré par [open] (qui dit si c'est du MP3 ou de l'AAC),
     * puis [run] jusqu'à l'arrêt du flux ou de la radio ; se reconnecte seul après une coupure.
     */
    class Feed private constructor(val station: Station, private var conn: HttpURLConnection, private var input: InputStream, val streamType: Int) {
        @Volatile private var stopped = false
        fun stop() { stopped = true; runCatching { input.close() }; runCatching { conn.disconnect() } }

        companion object {
            /** Se connecte et reconnaît le format (MP3 : 0x03, AAC : 0x0F) ; null si la radio ne répond pas. */
            fun open(st: Station): Feed? = runCatching {
                val c = Http.open(st.url, mapOf("User-Agent" to UA, "Icy-MetaData" to "0"), connectTimeout = 8000, readTimeout = 15_000)
                if (c.responseCode !in 200..299) { c.disconnect(); return@runCatching null }
                val ins = java.io.BufferedInputStream(c.inputStream, 64 * 1024)
                ins.mark(16 * 1024)
                val head = ByteArray(8 * 1024)
                var n = 0
                while (n < head.size) { val k = ins.read(head, n, head.size - n); if (k < 0) break; n += k }
                ins.reset()
                val type = Frames.detect(head, n) ?: run { c.disconnect(); return@runCatching null }
                Feed(st, c, ins, type)
            }.onFailure { Log.i(TAG, "${st.name} : ${it.message}") }.getOrNull()
        }

        /** Lit les trames et les envoie au flux, calées sur son horloge (un peu d'avance, jamais trop). */
        fun run(stream: SlideStream) {
            val buf = ByteArray(256 * 1024)
            var len = 0
            var pts = -1L
            var failures = 0
            while (!stopped && stream.alive) {
                try {
                    val k = input.read(buf, len, buf.size - len)
                    if (k < 0) throw java.io.EOFException()
                    len += k
                    var off = 0
                    while (true) {
                        val f = Frames.next(buf, off, len, streamType) ?: break
                        if (f.start + f.length > len) break // trame coupée : on attend la suite
                        val now = stream.ptsNow()
                        if (now < 0) { off = f.start + f.length; continue } // la vidéo n'a pas commencé
                        // Départ (ou retard après une coupure) : on se recale juste devant l'image.
                        if (pts < 0 || pts < now - 27_000) pts = now + 18_000
                        stream.audio(buf.copyOfRange(f.start, f.start + f.length), pts)
                        pts += f.samples * 90_000L / f.rate
                        off = f.start + f.length
                        // Pas plus de 3 s d'avance (la radio envoie souvent une réserve d'un coup au départ).
                        val ahead = pts - stream.ptsNow()
                        if (ahead > 270_000) Thread.sleep((ahead - 180_000) / 90)
                    }
                    if (off > 0) { System.arraycopy(buf, off, buf, 0, len - off); len -= off }
                    if (len == buf.size) len = 0 // pas une seule trame reconnue : on repart de zéro
                    failures = 0
                } catch (e: Exception) {
                    if (stopped || !stream.alive) break
                    if (++failures > 6) { Log.i(TAG, "${station.name} : abandon (${e.message})"); break }
                    Log.i(TAG, "${station.name} : coupure (${e.message}), reconnexion")
                    Thread.sleep(1500L * failures)
                    runCatching {
                        conn.disconnect()
                        conn = Http.open(station.url, mapOf("User-Agent" to UA, "Icy-MetaData" to "0"), connectTimeout = 8000, readTimeout = 15_000)
                        input = java.io.BufferedInputStream(conn.inputStream, 64 * 1024)
                        len = 0
                    }
                }
            }
            stop()
        }
    }

    /** Découpage des trames MP3 (MPEG audio couche III) et AAC (ADTS). */
    internal object Frames {
        class Frame(val start: Int, val length: Int, val samples: Int, val rate: Int)

        private val MP3_RATES = arrayOf(intArrayOf(44100, 48000, 32000), intArrayOf(22050, 24000, 16000), intArrayOf(11025, 12000, 8000))
        private val MP3_KBPS_V1 = intArrayOf(0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320)
        private val MP3_KBPS_V2 = intArrayOf(0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160)
        private val AAC_RATES = intArrayOf(96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350)

        /** MP3 (0x03) ou AAC ADTS (0x0F) : trois trames d'affilée bien formées ; null sinon. */
        fun detect(b: ByteArray, n: Int): Int? {
            for (type in intArrayOf(0x03, 0x0F)) {
                var off = 0
                var ok = 0
                while (ok < 3) {
                    val f = next(b, off, n, type) ?: break
                    if (f.start + f.length > n) break
                    if (ok > 0 && f.start != off) { ok = 0 } // pas collée à la précédente : faux départ
                    ok++
                    off = f.start + f.length
                }
                if (ok >= 3) return type
            }
            return null
        }

        /** Prochaine trame à partir de [from] (cherche l'en-tête), ou null s'il n'y en a pas avant [to]. */
        fun next(b: ByteArray, from: Int, to: Int, type: Int): Frame? {
            var i = from
            while (i + 7 <= to) {
                if (b[i] == 0xFF.toByte() && (b[i + 1].toInt() and 0xE0) == 0xE0) {
                    val f = if (type == 0x0F) adts(b, i) else mp3(b, i)
                    if (f != null) return f
                }
                i++
            }
            return null
        }

        private fun mp3(b: ByteArray, i: Int): Frame? {
            val h1 = b[i + 1].toInt() and 0xFF; val h2 = b[i + 2].toInt() and 0xFF
            val ver = (h1 ushr 3) and 3 // 3 : MPEG-1, 2 : MPEG-2, 0 : MPEG-2.5
            val layer = (h1 ushr 1) and 3
            if (ver == 1 || layer != 1) return null // couche III seulement
            val br = (h2 ushr 4) and 15; val sr = (h2 ushr 2) and 3; val pad = (h2 ushr 1) and 1
            if (br == 0 || br == 15 || sr == 3) return null
            val v1 = ver == 3
            val rate = MP3_RATES[if (v1) 0 else if (ver == 2) 1 else 2][sr]
            val kbps = (if (v1) MP3_KBPS_V1 else MP3_KBPS_V2)[br]
            val len = (if (v1) 144 else 72) * kbps * 1000 / rate + pad
            return if (len < 24) null else Frame(i, len, if (v1) 1152 else 576, rate)
        }

        private fun adts(b: ByteArray, i: Int): Frame? {
            val h1 = b[i + 1].toInt() and 0xFF
            if ((h1 and 0xF6) != 0xF0) return null // synchro 0xFFF, couche 00
            val sr = ((b[i + 2].toInt() and 0xFF) ushr 2) and 15
            if (sr >= AAC_RATES.size) return null
            val len = ((b[i + 3].toInt() and 3) shl 11) or ((b[i + 4].toInt() and 0xFF) shl 3) or ((b[i + 5].toInt() and 0xFF) ushr 5)
            val blocks = (b[i + 6].toInt() and 3) + 1
            return if (len < 7) null else Frame(i, len, 1024 * blocks, AAC_RATES[sr])
        }
    }
}
