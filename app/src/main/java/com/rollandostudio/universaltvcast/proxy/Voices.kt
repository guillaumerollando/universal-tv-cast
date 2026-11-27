package com.rollandostudio.universaltvcast.proxy

import java.io.OutputStream
import java.util.Locale

/**
 * Les voix (pistes son) des fichiers MP4 / MOV et MPEG-TS, et ce qu'il faut changer au passage pour qu'une télé
 * DLNA, qui lit toujours la première voix, lise celle qu'on a choisie (le MKV est traité par [MkvProbe]).
 * Rien n'est recopié ni réencodé : quelques octets changent, aux mêmes places, et les plages restent justes.
 */
object Voices {
    /** Une voix : identifiant pour le relais (« mp4:<n> », « ts:<pid> »), nom, langue, par défaut. */
    data class Voice(val id: String, val name: String, val lang: String?, val default: Boolean)

    fun langName(code: String?): String? {
        val c = code?.takeIf { it.isNotBlank() && it != "und" } ?: return null
        val n = Locale.forLanguageTag(c).getDisplayLanguage(Locale.getDefault())
        return n.ifBlank { c }.replaceFirstChar { it.titlecase(Locale.getDefault()) }
    }

    /** Noms lisibles et distincts (« Français », « Anglais », « Anglais 2 »). */
    private fun named(raw: List<Triple<String, String?, String?>>): List<Voice> {
        val counts = mutableMapOf<String, Int>()
        return raw.mapIndexed { i, (id, lang, label) ->
            val base = label?.takeIf { it.isNotBlank() && !it.endsWith("Handler", true) } ?: langName(lang) ?: "#${i + 1}"
            val k = (counts[base] ?: 0) + 1
            counts[base] = k
            Voice(id, if (k > 1) "$base $k" else base, lang, i == 0)
        }
    }

    // ------------------------------------------------------------------ MP4 / MOV

    /** Une piste son d'un MP4 : sa boîte « trak » (position absolue dans le fichier). */
    class Mp4Track(val trakAt: Long, val lang: String?, val label: String?)

    /**
     * Pistes son d'un MP4, en lisant ses boîtes de premier niveau (le « moov » peut être au début ou à la fin).
     * [read] lit une plage du fichier, [size] sa taille.
     */
    fun mp4Tracks(size: Long, read: (Long, Int) -> ByteArray): List<Mp4Track> {
        var p = 0L
        var guard = 0
        while (p + 8 <= size && guard++ < 64) {
            val h = read(p, 16)
            if (h.size < 8) return emptyList()
            var sz = u32(h, 0)
            val type = String(h, 4, 4, Charsets.ISO_8859_1)
            var header = 8
            if (sz == 1L && h.size >= 16) { sz = u64(h, 8); header = 16 }
            if (sz == 0L) sz = size - p
            if (sz < header) return emptyList()
            if (type == "moov") {
                if (sz > 64L * 1024 * 1024) return emptyList()
                val moov = read(p, sz.toInt())
                return tracksInMoov(moov, p)
            }
            p += sz
        }
        return emptyList()
    }

    private fun tracksInMoov(m: ByteArray, at: Long): List<Mp4Track> {
        val out = mutableListOf<Mp4Track>()
        children(m, 8, m.size) { type, s, e ->
            if (type != "trak") return@children
            var handler: String? = null; var lang: String? = null; var label: String? = null
            children(m, s + 8, e) { t2, s2, e2 ->
                if (t2 != "mdia") return@children
                children(m, s2 + 8, e2) { t3, s3, e3 ->
                    when (t3) {
                        "hdlr" -> if (e3 - s3 >= 24) {
                            handler = String(m, s3 + 16, 4, Charsets.ISO_8859_1)
                            label = String(m, s3 + 32, maxOf(0, e3 - s3 - 32), Charsets.UTF_8).trim('\u0000', ' ').takeIf { it.isNotBlank() }
                        }
                        "mdhd" -> {
                            val v = m[s3 + 8].toInt()
                            val lp = s3 + 8 + 4 + if (v == 1) 28 else 16
                            if (lp + 2 <= e3) {
                                val c = ((m[lp].toInt() and 0xFF) shl 8) or (m[lp + 1].toInt() and 0xFF)
                                lang = String(charArrayOf((((c shr 10) and 31) + 0x60).toChar(), (((c shr 5) and 31) + 0x60).toChar(), ((c and 31) + 0x60).toChar()))
                                    .takeIf { it.all { ch -> ch in 'a'..'z' } }
                            }
                        }
                    }
                }
            }
            if (handler == "soun") out += Mp4Track(at + s, lang, label)
        }
        return out
    }

    fun mp4Voices(tracks: List<Mp4Track>): List<Voice> = named(tracks.mapIndexed { i, t -> Triple("mp4:$i", t.lang, t.label) })

    /**
     * Pour faire lire la voix n° [keep] : les boîtes « trak » des autres voix deviennent des boîtes « free », que les
     * lecteurs sautent (4 octets chacune). Rien si c'est déjà la première.
     */
    fun mp4Patches(tracks: List<Mp4Track>, keep: Int): List<Pair<Long, ByteArray>> {
        if (tracks.size < 2 || keep <= 0 || keep >= tracks.size) return emptyList()
        return tracks.filterIndexed { i, _ -> i != keep }.map { it.trakAt + 4 to "free".toByteArray(Charsets.ISO_8859_1) }
    }

    private inline fun children(b: ByteArray, from: Int, to: Int, f: (String, Int, Int) -> Unit) {
        var p = from
        while (p + 8 <= to) {
            var sz = u32(b, p)
            if (sz == 1L && p + 16 <= to) sz = u64(b, p + 8)
            if (sz == 0L) sz = (to - p).toLong()
            if (sz < 8 || p + sz > to) return
            f(String(b, p + 4, 4, Charsets.ISO_8859_1), p, (p + sz).toInt())
            p += sz.toInt()
        }
    }

    private fun u32(b: ByteArray, p: Int): Long =
        ((b[p].toLong() and 0xFF) shl 24) or ((b[p + 1].toLong() and 0xFF) shl 16) or ((b[p + 2].toLong() and 0xFF) shl 8) or (b[p + 3].toLong() and 0xFF)
    private fun u64(b: ByteArray, p: Int): Long = (u32(b, p) shl 32) or u32(b, p + 4)

    // ------------------------------------------------------------------ MPEG-TS

    /** Pistes d'un TS (d'après sa PMT) : la PMT et ses voix (PID, langue). */
    class TsInfo(val pmtPid: Int, val audio: List<Pair<Int, String?>>)

    private val AUDIO_TYPES = setOf(0x03, 0x04, 0x0F, 0x11, 0x81, 0x87)

    fun tsInfo(head: ByteArray): TsInfo? {
        if (head.size < 188 * 4 || head[0] != 0x47.toByte()) return null
        var pmt = -1
        var p = 0
        while (p + 188 <= head.size) {
            if (head[p] != 0x47.toByte()) return null
            val pid = pid(head, p)
            val sec = section(head, p)
            if (sec != null) {
                val (o, sl) = sec
                if (pid == 0 && pmt < 0) {
                    var q = o + 8
                    while (q + 4 <= o + 3 + sl - 4) {
                        val prog = ((head[q].toInt() and 0xFF) shl 8) or (head[q + 1].toInt() and 0xFF)
                        if (prog != 0) { pmt = ((head[q + 2].toInt() and 0x1F) shl 8) or (head[q + 3].toInt() and 0xFF); break }
                        q += 4
                    }
                } else if (pid == pmt && pmt >= 0) {
                    return TsInfo(pmt, streams(head, o, sl).filter { (it.type in AUDIO_TYPES) || (it.type == 0x06 && it.ac3) }.map { it.pid to it.lang })
                }
            }
            p += 188
        }
        return null
    }

    fun tsVoices(info: TsInfo): List<Voice> = named(info.audio.map { (pid, lang) -> Triple("ts:$pid", lang, null) })

    private class Es(val at: Int, val len: Int, val type: Int, val pid: Int, val lang: String?, val ac3: Boolean)

    private fun pid(b: ByteArray, p: Int) = ((b[p + 1].toInt() and 0x1F) shl 8) or (b[p + 2].toInt() and 0xFF)

    /** Début (position de table_id) et longueur d'une section qui commence dans ce paquet, ou null. */
    private fun section(b: ByteArray, p: Int): Pair<Int, Int>? {
        if (b[p + 1].toInt() and 0x40 == 0) return null
        var o = p + 4
        if (b[p + 3].toInt() and 0x20 != 0) o += 1 + (b[o].toInt() and 0xFF)
        if (o >= p + 188) return null
        o += 1 + (b[o].toInt() and 0xFF)
        if (o + 3 > p + 188) return null
        val sl = ((b[o + 1].toInt() and 0x0F) shl 8) or (b[o + 2].toInt() and 0xFF)
        if (o + 3 + sl > p + 188) return null // section sur plusieurs paquets : pas prise en charge
        return o to sl
    }

    private fun streams(b: ByteArray, o: Int, sl: Int): List<Es> {
        val pil = ((b[o + 10].toInt() and 0x0F) shl 8) or (b[o + 11].toInt() and 0xFF)
        var q = o + 12 + pil
        val end = o + 3 + sl - 4
        val out = mutableListOf<Es>()
        while (q + 5 <= end) {
            val type = b[q].toInt() and 0xFF
            val epid = ((b[q + 1].toInt() and 0x1F) shl 8) or (b[q + 2].toInt() and 0xFF)
            val il = ((b[q + 3].toInt() and 0x0F) shl 8) or (b[q + 4].toInt() and 0xFF)
            var k = q + 5
            var lang: String? = null; var ac3 = false
            while (k + 2 <= q + 5 + il && k + 2 <= end) {
                val tag = b[k].toInt() and 0xFF; val l = b[k + 1].toInt() and 0xFF
                if (tag == 0x0A && l >= 3) lang = String(b, k + 2, 3, Charsets.ISO_8859_1)
                if (tag == 0x6A || tag == 0x7A || tag == 0x81) ac3 = true
                k += 2 + l
            }
            out += Es(q, 5 + il, type, epid, lang, ac3)
            q += 5 + il
        }
        return out
    }

    private fun crc32(b: ByteArray, from: Int, to: Int): Int {
        var crc = -1
        for (i in from until to) {
            crc = crc xor ((b[i].toInt() and 0xFF) shl 24)
            repeat(8) { crc = if (crc and Int.MIN_VALUE != 0) (crc shl 1) xor 0x04C11DB7 else crc shl 1 }
        }
        return crc
    }

    /**
     * Le TS envoyé à la télé, corrigé au passage pour ne garder que la voix [keepPid] : la PMT (répétée tout au long
     * du fichier) ne la déclare plus seule, et les paquets des autres voix deviennent des paquets vides (PID 0x1FFF)
     * que tout lecteur jette. Les paquets gardent leur taille et leur place. [pos] : position du premier octet écrit.
     */
    class TsFilter(private val out: OutputStream, private var pos: Long, private val info: TsInfo, private val keepPid: Int) : OutputStream() {
        private val drop = info.audio.map { it.first }.filter { it != keepPid }.toSet()
        private val pkt = ByteArray(188)
        private var fill = 0

        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

        override fun write(b: ByteArray, off: Int, len: Int) {
            var i = off
            val end = off + len
            while (i < end) {
                // Début non aligné (avance rapide au milieu d'un paquet) : ces octets passent tels quels.
                if (fill == 0 && pos % 188 != 0L) {
                    val n = minOf((188 - pos % 188).toInt(), end - i)
                    out.write(b, i, n); i += n; pos += n
                    continue
                }
                val n = minOf(188 - fill, end - i)
                System.arraycopy(b, i, pkt, fill, n)
                fill += n; i += n; pos += n
                if (fill == 188) { fix(); out.write(pkt); fill = 0 }
            }
        }

        private fun fix() {
            if (pkt[0] != 0x47.toByte()) return
            val pid = pid(pkt, 0)
            if (pid in drop) { // paquet vide : PID 0x1FFF, contenu effacé
                pkt[1] = 0x1F; pkt[2] = 0xFF.toByte(); pkt[3] = 0x10
                java.util.Arrays.fill(pkt, 4, 188, 0xFF.toByte())
                return
            }
            if (pid != info.pmtPid) return
            val (o, sl) = section(pkt, 0) ?: return
            val es = streams(pkt, o, sl)
            val gone = es.filter { it.pid in drop }
            if (gone.isEmpty()) return
            val pil = ((pkt[o + 10].toInt() and 0x0F) shl 8) or (pkt[o + 11].toInt() and 0xFF)
            val head = pkt.copyOfRange(o, o + 12 + pil)
            val body = java.io.ByteArrayOutputStream()
            es.filter { it.pid !in drop }.forEach { body.write(pkt, it.at, it.len) }
            val newSl = sl - gone.sumOf { it.len }
            val sec = head + body.toByteArray()
            sec[1] = ((sec[1].toInt() and 0xF0) or ((newSl shr 8) and 0x0F)).toByte()
            sec[2] = (newSl and 0xFF).toByte()
            val crc = crc32(sec, 0, sec.size)
            val full = sec + byteArrayOf((crc ushr 24).toByte(), (crc ushr 16).toByte(), (crc ushr 8).toByte(), crc.toByte())
            System.arraycopy(full, 0, pkt, o, full.size)
            java.util.Arrays.fill(pkt, o + full.size, 188, 0xFF.toByte())
        }

        override fun flush() {
            if (fill > 0) { out.write(pkt, 0, fill); fill = 0 }
            out.flush()
        }

        override fun close() = flush()
    }
}
