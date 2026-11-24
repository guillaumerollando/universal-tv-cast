package studio.rollando.castor.proxy

import studio.rollando.castor.net.Http
import java.net.URL

/** Lecture des listes de lecture HLS (.m3u8). */
object Hls {
    class Key(val method: String, val uri: String?, val iv: ByteArray?)
    class Seg(val url: String, val duration: Double, val seq: Long, val key: Key?, val map: String?)

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
        String(bytes, 0, minOf(bytes.size, 64), Charsets.UTF_8).trimStart('﻿', ' ', '\n', '\r', '\t')
            .startsWith("#EXTM3U")

    /** Charge une liste ; si c'est une liste « maître », choisit la meilleure qualité avec le son. */
    fun load(url: String, headers: Map<String, String>): Plan {
        val f = Http.fetch(url, headers)
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
                    map = attrs(line.substringAfter(':'))["URI"]?.let { resolve(base, it) }
                    fmp4 = true
                }
                line.startsWith("#EXT-X-ENDLIST") -> end = true
                line.startsWith("#") -> Unit
                else -> {
                    segs += Seg(resolve(base, line), dur, seq, key, map)
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
