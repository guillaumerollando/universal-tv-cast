package com.rollandostudio.universaltvcast.proxy

import com.rollandostudio.universaltvcast.net.Http
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
        /** Le morceau suit une coupure (#EXT-X-DISCONTINUITY : publicité, pré-roll) : ses horodatages repartent ailleurs. */
        val discontinuity: Boolean = false,
    ) {
        /** Identifie l'en-tête (fichier et plage) : on ne le renvoie que s'il change. */
        val mapId: String? get() = map?.let { it + (mapRange?.let { r -> "#$r" } ?: "") }
    }

    /** Contenu d'un fichier, ou d'une plage d'octets de ce fichier. */
    fun bytes(url: String, range: String?, headers: Map<String, String>): ByteArray {
        if (range == null) return Http.fetch(url, headers).bytes
        val c = Http.open(url, headers, range, connectTimeout = 15_000, readTimeout = 30_000)
        try {
            if (c.responseCode >= 400) throw com.rollandostudio.universaltvcast.net.HttpError(c.responseCode)
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
        /** Traduit d'un manifeste DASH. */
        val dash: Boolean = false,
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
        // Manifeste DASH (.mpd) : traduit en liste équivalente.
        if (Dash.isDash(url)) return Dash.plan(url, headers)
        // Une liste répond en une ou deux secondes : un serveur muet est vite déclaré absent (pas 15 s d'attente).
        val f = Http.fetch(url, headers, connectTimeout = 6000)
        if (Dash.isDash("", f.bytes)) return Dash.plan(f.finalUrl, headers)
        val text = String(f.bytes, Charsets.UTF_8)
        if (!text.contains("#EXT-X-STREAM-INF")) return parseMedia(text, f.finalUrl, false)

        val lines = text.lines().map { it.trim() }
        val audioGroupsWithUri = lines.filter { it.startsWith("#EXT-X-MEDIA:") }
            .map { attrs(it.substringAfter(':')) }
            .filter { it["TYPE"] == "AUDIO" && !it["URI"].isNullOrBlank() }
            .mapNotNull { it["GROUP-ID"] }
            .toSet()

        class Variant(val bandwidth: Long, val audio: String?, val uri: String, val hevc: Boolean)
        val variants = mutableListOf<Variant>()
        for ((i, line) in lines.withIndex()) {
            if (!line.startsWith("#EXT-X-STREAM-INF:")) continue
            val a = attrs(line.substringAfter(':'))
            val uri = lines.drop(i + 1).firstOrNull { it.isNotEmpty() && !it.startsWith("#") } ?: continue
            val codecs = a["CODECS"].orEmpty().lowercase()
            variants += Variant(a["BANDWIDTH"]?.toLongOrNull() ?: 0, a["AUDIO"], uri,
                hevc = codecs.contains("hvc1") || codecs.contains("hev1") || codecs.contains("dvh"))
        }
        if (variants.isEmpty()) return parseMedia(text, f.finalUrl, false)
        // H.264 d'abord : toutes les télés le lisent ; le HEVC (H.265) en flux continu en fige certaines (Philips :
        // quelques secondes d'image puis plus rien). Le HEVC seulement s'il n'y a rien d'autre.
        val compatible = variants.filter { !it.hevc }.ifEmpty { variants }
        val muxed = compatible.filter { it.audio == null || it.audio !in audioGroupsWithUri }
        val chosen = muxed.ifEmpty { compatible }.maxBy { it.bandwidth }
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
        if (Dash.isDash(url)) return Dash.tracks(url, headers) to runCatching { Dash.subtitles(url, headers) }.getOrDefault(emptyList())
        val f = Http.fetch(url, headers)
        if (Dash.isDash("", f.bytes)) return Dash.tracks(f.finalUrl, headers) to runCatching { Dash.subtitles(f.finalUrl, headers) }.getOrDefault(emptyList())
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
                val vtt = { u: String -> u.substringBefore('?').let { s -> s.endsWith(".vtt") || s.endsWith(".webvtt") } }
                when {
                    segs.size == 1 -> segs.single().let { resolve(p.finalUrl, it) }.takeIf(vtt)?.let { t.copy(uri = it) }
                    // WebVTT découpé en morceaux (beaucoup de diffuseurs) : recollé au chargement ([vttJoin]).
                    segs.isNotEmpty() && segs.all(vtt) -> t.copy(uri = VTT_JOIN + p.finalUrl)
                    else -> null
                }
            }.getOrNull()
        }
        return audio to subs
    }

    /** Adresse d'une piste de sous-titres WebVTT découpée (« hlsvtt:<liste> »). */
    const val VTT_JOIN = "hlsvtt:"

    /**
     * Recolle les morceaux d'une piste WebVTT en un seul fichier. Chaque morceau peut porter son propre calage
     * (X-TIMESTAMP-MAP) : les répliques sont ramenées sur la même ligne de temps que le premier.
     */
    fun vttJoin(listUrl: String, headers: Map<String, String>): ByteArray {
        val p = Http.fetch(listUrl, headers)
        val urls = String(p.bytes, Charsets.UTF_8).lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
            .map { resolve(p.finalUrl, it) }.distinct()
        val pool = java.util.concurrent.Executors.newFixedThreadPool(6)
        val parts = try {
            urls.map { u -> pool.submit<String> { runCatching { String(Http.fetch(u, headers).bytes, Charsets.UTF_8) }.getOrDefault("") } }.map { it.get() }
        } finally { pool.shutdown() }
        val map = Regex("X-TIMESTAMP-MAP=.*?MPEGTS:(\\d+).*?LOCAL:([\\d:.]+)|X-TIMESTAMP-MAP=.*?LOCAL:([\\d:.]+).*?MPEGTS:(\\d+)")
        val cueTime = Regex("((?:\\d+:)?\\d{2}:\\d{2}\\.\\d{3})")
        fun secs(t: String): Double { val f = t.split(':').map { it.toDouble() }; return f.fold(0.0) { acc, v -> acc * 60 + v } }
        fun fmt(s: Double): String { val ms = (s.coerceAtLeast(0.0) * 1000).toLong()
            return String.format(java.util.Locale.ROOT, "%02d:%02d:%02d.%03d", ms / 3_600_000, ms / 60_000 % 60, ms / 1000 % 60, ms % 1000) }
        var base: Double? = null
        val out = StringBuilder("WEBVTT\n\n")
        val seen = HashSet<String>()
        for (text in parts) {
            val m = map.find(text)
            val mpegts = (m?.groupValues?.get(1)?.ifEmpty { m.groupValues[4] })?.toDoubleOrNull()
            val local = (m?.groupValues?.get(2)?.ifEmpty { m.groupValues[3] })?.let(::secs) ?: 0.0
            val origin = (mpegts?.div(90_000.0) ?: 0.0) - local
            if (base == null) base = origin
            val shift = origin - base!!
            for (block in text.replace("\r\n", "\n").split(Regex("\n\\s*\n"))) {
                val lines = block.trim('\n').lines()
                val i = lines.indexOfFirst { it.contains("-->") }
                if (i < 0) continue
                val times = cueTime.findAll(lines[i]).map { it.value }.toList()
                if (times.size < 2) continue
                val cue = fmt(secs(times[0]) + shift) + " --> " + fmt(secs(times[1]) + shift) + "\n" + lines.drop(i + 1).joinToString("\n")
                // Une réplique à cheval sur deux morceaux y est souvent répétée.
                if (seen.add(cue)) out.append(cue).append("\n\n")
            }
        }
        return out.toString().toByteArray(Charsets.UTF_8)
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
        var cut = false
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
                line == "#EXT-X-DISCONTINUITY" -> cut = true
                line.startsWith("#") -> Unit
                else -> {
                    val u = resolve(base, line)
                    range = pendingRange?.let { spec -> byteRange(spec, nextByte[u] ?: 0)?.also { nextByte[u] = it.second }?.first }
                    pendingRange = null
                    segs += Seg(u, dur, seq, key, map, range, mapRange, discontinuity = cut)
                    cut = false
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
