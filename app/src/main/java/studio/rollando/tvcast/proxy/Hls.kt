package studio.rollando.tvcast.proxy

import studio.rollando.tvcast.net.Http
import java.net.URL

/** Lecture des listes de lecture HLS (.m3u8). */
object Hls {
    class Key(val method: String, val uri: String?, val iv: ByteArray?)
    /**
     * Un morceau de la vidéo. [range] : plage d'octets dans un fichier commun (« bytes=1394-243885 », balise
     * EXT-X-BYTERANGE : PeerTube découpe ainsi un seul MP4) ; [mapRange] : de même pour l'en-tête (EXT-X-MAP).
     */
    class Seg(
        val url: String, val duration: Double, val seq: Long, val key: Key?, val map: String?,
        val range: String? = null, val mapRange: String? = null,
    ) {
        /** Identifie l'en-tête (fichier et plage) : on ne le renvoie que s'il change. */
        val mapId: String? get() = map?.let { it + (mapRange?.let { r -> "#$r" } ?: "") }
    }

    /** Contenu d'un fichier, ou d'une plage d'octets de ce fichier. */
    fun bytes(url: String, range: String?, headers: Map<String, String>): ByteArray {
        if (range == null) return Http.fetch(url, headers).bytes
        val c = Http.open(url, headers, range, connectTimeout = 15_000, readTimeout = 30_000)
        try {
            if (c.responseCode >= 400) throw studio.rollando.tvcast.net.HttpError(c.responseCode)
            return c.inputStream.use { it.readBytes() }
        } finally { c.disconnect() }
    }

    /** « 242492@1394 » (longueur@début ; début absent : à la suite du morceau précédent) → « bytes=1394-243885 ». */
    private fun byteRange(spec: String, next: Long): Pair<String, Long>? {
        val len = spec.substringBefore('@').trim().toLongOrNull() ?: return null
        val start = if (spec.contains('@')) spec.substringAfter('@').trim().toLongOrNull() ?: return null else next
        return "bytes=$start-${start + len - 1}" to start + len
    }

    class Plan(
        val url: String,
        val segs: List<Seg>,
        val endList: Boolean,
        val targetDuration: Double,
        val fmp4: Boolean,
        /** Le son est dans une piste à part : le flux continu n'aurait pas de son. */
        val splitAudio: Boolean,
        val encryptedSample: Boolean,
    ) {
        val duration: Double get() = segs.sumOf { it.duration }
    }

    private val ATTR = Regex("""([A-Z0-9-]+)=("[^"]*"|[^,]*)""")

    fun attrs(s: String): Map<String, String> =
        ATTR.findAll(s).associate { it.groupValues[1] to it.groupValues[2].trim().trim('"') }

    fun resolve(base: String, ref: String): String = URL(URL(base), ref.trim()).toString()

    fun isPlaylist(bytes: ByteArray): Boolean =
        String(bytes, 0, minOf(bytes.size, 64), Charsets.UTF_8).trimStart('\uFEFF', ' ', '\n', '\r', '\t')
            .startsWith("#EXTM3U")

    /** Charge une liste ; si c'est une liste « maître », choisit la meilleure qualité avec le son. */
    fun load(url: String, headers: Map<String, String>): Plan {
        // Une liste répond en une ou deux secondes : un serveur muet est vite déclaré absent (pas 15 s d'attente).
        val f = Http.fetch(url, headers, connectTimeout = 6000)
        val text = String(f.bytes, Charsets.UTF_8)
        if (!text.contains("#EXT-X-STREAM-INF")) return parseMedia(text, f.finalUrl, false)

        val lines = text.lines().map { it.trim() }
        val audioGroupsWithUri = lines.filter { it.startsWith("#EXT-X-MEDIA:") }
            .map { attrs(it.substringAfter(':')) }
            .filter { it["TYPE"] == "AUDIO" && !it["URI"].isNullOrBlank() }
            .mapNotNull { it["GROUP-ID"] }
            .toSet()

        class Variant(val bandwidth: Long, val audio: String?, val uri: String)
        val variants = mutableListOf<Variant>()
        for ((i, line) in lines.withIndex()) {
            if (!line.startsWith("#EXT-X-STREAM-INF:")) continue
            val a = attrs(line.substringAfter(':'))
            val uri = lines.drop(i + 1).firstOrNull { it.isNotEmpty() && !it.startsWith("#") } ?: continue
            variants += Variant(a["BANDWIDTH"]?.toLongOrNull() ?: 0, a["AUDIO"], uri)
        }
        if (variants.isEmpty()) return parseMedia(text, f.finalUrl, false)
        val muxed = variants.filter { it.audio == null || it.audio !in audioGroupsWithUri }
        val chosen = muxed.ifEmpty { variants }.maxBy { it.bandwidth }
        val split = muxed.isEmpty()
        val v = Http.fetch(resolve(f.finalUrl, chosen.uri), headers)
        return parseMedia(String(v.bytes, Charsets.UTF_8), v.finalUrl, split)
    }

    /** Piste son ou sous-titres proposée par une liste maîtresse (« français », « plurilingue (VO) »…). */
    data class Track(val name: String, val lang: String?, val uri: String, val default: Boolean)

    /**
     * Pistes son et sous-titres d'une liste maîtresse (#EXT-X-MEDIA). Les sous-titres ne sont gardés que s'ils tiennent
     * en un seul fichier WebVTT (cas d'Arte) : l'appli sait les envoyer à la télé et au lecteur du téléphone.
     */
    fun tracks(url: String, headers: Map<String, String>): Pair<List<Track>, List<Track>> {
        val f = Http.fetch(url, headers)
        val text = String(f.bytes, Charsets.UTF_8)
        if (!text.contains("#EXT-X-STREAM-INF")) return emptyList<Track>() to emptyList()
        val media = text.lines().map { it.trim() }.filter { it.startsWith("#EXT-X-MEDIA:") }.map { attrs(it.substringAfter(':')) }
        fun track(a: Map<String, String>) = a["URI"]?.takeIf { it.isNotBlank() }?.let { u ->
            Track(a["NAME"] ?: a["LANGUAGE"] ?: "?", a["LANGUAGE"], resolve(f.finalUrl, u), a["DEFAULT"] == "YES")
        }
        val audio = media.filter { it["TYPE"] == "AUDIO" }.mapNotNull(::track).distinctBy { it.uri }
        val subs = media.filter { it["TYPE"] == "SUBTITLES" && it["FORCED"] != "YES" }.mapNotNull(::track).mapNotNull { t ->
            runCatching {
                val p = Http.fetch(t.uri, headers)
                val segs = String(p.bytes, Charsets.UTF_8).lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
                segs.singleOrNull()?.let { resolve(p.finalUrl, it) }?.takeIf { it.substringBefore('?').let { s -> s.endsWith(".vtt") || s.endsWith(".webvtt") } }
                    ?.let { t.copy(uri = it) }
            }.getOrNull()
        }
        return audio to subs
    }

    fun reload(plan: Plan, headers: Map<String, String>): Plan {
        val f = Http.fetch(plan.url, headers)
        return parseMedia(String(f.bytes, Charsets.UTF_8), f.finalUrl, plan.splitAudio)
    }

    private fun parseMedia(text: String, base: String, splitAudio: Boolean): Plan {
        var seq = 0L
        var target = 6.0
        var dur = 0.0
        var key: Key? = null
        var map: String? = null
        var end = false
        var fmp4 = false
        var sample = false
        var mapRange: String? = null
        var range: String? = null
        val nextByte = HashMap<String, Long>()
        var pendingRange: String? = null
        val segs = mutableListOf<Seg>()
        for (raw in text.lines()) {
            val line = raw.trim()
            when {
                line.isEmpty() -> Unit
                line.startsWith("#EXT-X-MEDIA-SEQUENCE:") -> seq = line.substringAfter(':').trim().toLongOrNull() ?: 0
                line.startsWith("#EXT-X-TARGETDURATION:") -> target = line.substringAfter(':').trim().toDoubleOrNull() ?: 6.0
                line.startsWith("#EXTINF:") -> dur = line.substringAfter(':').substringBefore(',').trim().toDoubleOrNull() ?: 0.0
                line.startsWith("#EXT-X-KEY:") -> {
                    val a = attrs(line.substringAfter(':'))
                    val method = a["METHOD"] ?: "NONE"
                    if (method.startsWith("SAMPLE-AES")) sample = true
                    key = if (method == "NONE") null else Key(method, a["URI"]?.let { resolve(base, it) }, a["IV"]?.let(::hex))
                }
                line.startsWith("#EXT-X-MAP:") -> {
                    val a = attrs(line.substringAfter(':'))
                    map = a["URI"]?.let { resolve(base, it) }
                    mapRange = a["BYTERANGE"]?.let { byteRange(it, 0)?.first }
                    fmp4 = true
                }
                line.startsWith("#EXT-X-BYTERANGE:") -> pendingRange = line.substringAfter(':').trim()
                line.startsWith("#EXT-X-ENDLIST") -> end = true
                line.startsWith("#") -> Unit
                else -> {
                    val u = resolve(base, line)
                    range = pendingRange?.let { spec -> byteRange(spec, nextByte[u] ?: 0)?.also { nextByte[u] = it.second }?.first }
                    pendingRange = null
                    segs += Seg(u, dur, seq, key, map, range, mapRange)
                    seq++
                    dur = 0.0
                }
            }
        }
        return Plan(base, segs, end, target, fmp4, splitAudio, sample)
    }

    private fun hex(s: String): ByteArray? {
        val h = s.removePrefix("0x").removePrefix("0X").padStart(32, '0')
        if (h.length != 32) return null
        return ByteArray(16) { i -> h.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }

    /** Certains serveurs ajoutent des octets avant le flux MPEG-TS : on se recale sur l'octet de synchro 0x47. */
    fun syncTs(data: ByteArray): ByteArray {
        if (data.isEmpty() || data[0] == 0x47.toByte()) return data
        val limit = minOf(data.size - 189, 64 * 1024)
        for (i in 0 until limit.coerceAtLeast(0)) {
            if (data[i] == 0x47.toByte() && data[i + 188] == 0x47.toByte() &&
                (i + 376 >= data.size || data[i + 376] == 0x47.toByte())
            ) return data.copyOfRange(i, data.size)
        }
        return data
    }
}
