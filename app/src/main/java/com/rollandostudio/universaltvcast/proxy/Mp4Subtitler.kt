package com.rollandostudio.universaltvcast.proxy

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.IOException

/**
 * Ajoute une piste de sous-titres (tx3g, le format texte standard du MP4) à un MP4 **sans le recopier** :
 * on lit une seule fois l'index (« moov »), on le réécrit avec une piste de plus et des repères décalés,
 * et l'on décrit un fichier « virtuel » fait de morceaux de l'original et de morceaux en mémoire.
 * Aucune image n'est décodée ni réencodée : le coût est celui d'une simple recopie d'octets.
 *
 * Fonctionne pour les MP4 « classiques » (moov au début ou à la fin). Les MP4 fragmentés sont refusés.
 */
class Mp4Subtitler(private val size: Long, private val read: (offset: Long, length: Int) -> ByteArray) {

    data class Cue(val startMs: Long, val endMs: Long, val text: String)

    sealed class Segment(val length: Long) {
        /** Octets de l'original, à partir de [from]. */
        class Src(val from: Long, length: Long) : Segment(length)
        /** Octets produits en mémoire (index réécrit, sous-titres). */
        class Mem(val bytes: ByteArray) : Segment(bytes.size.toLong())
    }

    class Plan(val segments: List<Segment>) {
        val total: Long = segments.sumOf { it.length }
    }

    private class Box(val type: String, val start: Long, val size: Long, val header: Int)

    /** Les boîtes de premier niveau du fichier (ftyp, moov, mdat…). */
    private fun topLevel(): List<Box> {
        val out = mutableListOf<Box>()
        var pos = 0L
        while (pos + 8 <= size) {
            val h = read(pos, 16)
            var sz = u32(h, 0)
            val type = String(h, 4, 4, Charsets.ISO_8859_1)
            var header = 8
            if (sz == 1L) { sz = u64(h, 8); header = 16 } else if (sz == 0L) sz = size - pos
            if (sz < header) throw IOException("MP4 invalide")
            out += Box(type, pos, sz, header)
            pos += sz
            if (out.size > 64) break
        }
        return out
    }

    fun build(cues: List<Cue>, language: String = "und"): Plan {
        val boxes = topLevel()
        val moov = boxes.firstOrNull { it.type == "moov" } ?: throw IOException("pas d'index moov")
        if (boxes.any { it.type == "moof" }) throw IOException("MP4 fragmenté")
        val mdatStart = boxes.firstOrNull { it.type == "mdat" }?.start ?: throw IOException("pas de mdat")
        if (moov.size > 64L * 1024 * 1024) throw IOException("index trop gros")
        val moovBytes = read(moov.start, moov.size.toInt())
        val moovBeforeMdat = moov.start < mdatStart

        // 1. Construire l'index avec des repères provisoires (décalage 0) pour en connaître la taille.
        val probe = rewriteMoov(moovBytes, moov.header, 0, 0, cues, language)
        val delta = if (moovBeforeMdat) probe.size.toLong() - moov.size else 0L
        // Les sous-titres sont placés tout à la fin du fichier virtuel.
        val tailFrom = moov.start + moov.size
        val subOffset = moov.start + probe.size + (size - tailFrom)
        val newMoov = rewriteMoov(moovBytes, moov.header, delta, subOffset, cues, language)
        check(newMoov.size == probe.size)
        val samples = sampleData(cues)
        val segs = mutableListOf<Segment>()
        if (moov.start > 0) segs += Segment.Src(0, moov.start)
        segs += Segment.Mem(newMoov)
        if (size > tailFrom) segs += Segment.Src(tailFrom, size - tailFrom)
        segs += Segment.Mem(samples)
        return Plan(segs)
    }

    // ------------------------------------------------------------------ réécriture de l'index

    private class Info(var timescale: Long = 1000, var duration: Long = 0, var nextTrack: Long = 1, var width: Long = 0, var height: Long = 0)

    private fun rewriteMoov(moov: ByteArray, header: Int, delta: Long, subOffset: Long, cues: List<Cue>, lang: String): ByteArray {
        val info = Info()
        scan(moov, header, moov.size, info)
        val children = ByteArrayOutputStream()
        forEachChild(moov, header, moov.size) { type, s, e ->
            when (type) {
                "mvhd" -> children.write(patchMvhd(moov.copyOfRange(s, e), info.nextTrack + 1))
                "trak" -> children.write(rewriteContainer(moov, s, e, delta))
                "mvex" -> throw IOException("MP4 fragmenté")
                else -> children.write(moov, s, e - s)
            }
        }
        children.write(textTrak(info, cues, lang, subOffset))
        return box("moov", children.toByteArray())
    }

    private val containers = setOf("trak", "mdia", "minf", "stbl", "edts", "dinf")

    /** Recopie une boîte et ses enfants ; les tables d'emplacements (stco/co64) sont décalées et passées en 64 bits. */
    private fun rewriteContainer(b: ByteArray, s: Int, e: Int, delta: Long): ByteArray {
        val type = String(b, s + 4, 4, Charsets.ISO_8859_1)
        val hdr = if (u32(b, s) == 1L) 16 else 8
        val out = ByteArrayOutputStream()
        forEachChild(b, s + hdr, e) { t, cs, ce ->
            when {
                t == "stco" -> out.write(stcoToCo64(b, cs, ce, delta))
                t == "co64" -> out.write(shiftCo64(b, cs, ce, delta))
                t in containers -> out.write(rewriteContainer(b, cs, ce, delta))
                else -> out.write(b, cs, ce - cs)
            }
        }
        return box(type, out.toByteArray())
    }

    private fun stcoToCo64(b: ByteArray, s: Int, e: Int, delta: Long): ByteArray {
        val n = u32(b, s + 12).toInt()
        val out = ByteArrayOutputStream(16 + n * 8)
        val d = DataOutputStream(out)
        d.writeInt(0); d.writeInt(n)
        for (i in 0 until n) d.writeLong(u32(b, s + 16 + i * 4) + delta)
        return box("co64", out.toByteArray())
    }

    private fun shiftCo64(b: ByteArray, s: Int, e: Int, delta: Long): ByteArray {
        if (delta == 0L) return b.copyOfRange(s, e)
        val n = u32(b, s + 12).toInt()
        val out = ByteArrayOutputStream(16 + n * 8)
        val d = DataOutputStream(out)
        d.writeInt(0); d.writeInt(n)
        for (i in 0 until n) d.writeLong(u64(b, s + 16 + i * 8) + delta)
        return box("co64", out.toByteArray())
    }

    private fun patchMvhd(mvhd: ByteArray, next: Long): ByteArray {
        val version = mvhd[8].toInt()
        val off = 8 + 4 + (if (version == 1) 28 else 16) + 4 + 2 + 10 + 36 + 24
        val out = mvhd.copyOf()
        putU32(out, off, next)
        return out
    }

    /** Relève l'échelle de temps, la durée, le prochain numéro de piste et la taille de la vidéo. */
    private fun scan(b: ByteArray, s: Int, e: Int, info: Info) {
        forEachChild(b, s, e) { type, cs, ce ->
            val hdr = if (u32(b, cs) == 1L) 16 else 8
            when (type) {
                "mvhd" -> {
                    val v = b[cs + hdr].toInt()
                    val p = cs + hdr + 4
                    if (v == 1) { info.timescale = u32(b, p + 16); info.duration = u64(b, p + 20) }
                    else { info.timescale = u32(b, p + 8); info.duration = u32(b, p + 12) }
                    val np = cs + hdr + 4 + (if (v == 1) 28 else 16) + 4 + 2 + 10 + 36 + 24
                    info.nextTrack = u32(b, np)
                }
                "tkhd" -> {
                    val w = u32(b, ce - 8) shr 16
                    val h = u32(b, ce - 4) shr 16
                    if (w > info.width) { info.width = w; info.height = h }
                }
                in containers -> scan(b, cs + hdr, ce, info)
            }
        }
    }

    // ------------------------------------------------------------------ piste de sous-titres

    private fun textTrak(info: Info, cues: List<Cue>, lang: String, chunkOffset: Long): ByteArray {
        val trackId = info.nextTrack
        val durMs = maxOf(cues.maxOfOrNull { it.endMs } ?: 0L, info.duration * 1000 / maxOf(1, info.timescale))
        val movieDur = durMs * info.timescale / 1000

        val tkhd = fullBox("tkhd", 0, 3, bytes {
            writeInt(0); writeInt(0); writeInt(trackId.toInt()); writeInt(0); writeInt(movieDur.toInt())
            writeLong(0); writeShort(0); writeShort(0); writeShort(0); writeShort(0)
            for (m in intArrayOf(0x10000, 0, 0, 0, 0x10000, 0, 0, 0, 0x40000000)) writeInt(m)
            writeInt((info.width shl 16).toInt()); writeInt((info.height shl 16).toInt())
        })
        val mdhd = fullBox("mdhd", 0, 0, bytes {
            writeInt(0); writeInt(0); writeInt(1000); writeInt(durMs.toInt()); writeShort(packLang(lang)); writeShort(0)
        })
        val hdlr = fullBox("hdlr", 0, 0, bytes {
            writeInt(0); write("sbtl".toByteArray()); writeInt(0); writeInt(0); writeInt(0); write("SubtitleHandler\u0000".toByteArray())
        })
        val dinf = box("dinf", fullBox("dref", 0, 0, bytes { writeInt(1); write(fullBox("url ", 0, 1, ByteArray(0))) }))

        // Échantillons : un « vide » pour chaque silence, un par réplique.
        val sizes = mutableListOf<Int>()
        val durations = mutableListOf<Long>()
        var t = 0L
        for (c in cues) {
            if (c.startMs > t) { sizes += 2; durations += c.startMs - t }
            sizes += 2 + c.text.toByteArray(Charsets.UTF_8).size
            durations += maxOf(1, c.endMs - maxOf(c.startMs, t))
            t = maxOf(t, c.endMs)
        }
        if (sizes.isEmpty()) { sizes += 2; durations += maxOf(1, durMs) }

        val tx3g = box("tx3g", bytes {
            write(ByteArray(6)); writeShort(1)
            writeInt(0)
            writeByte(1); writeByte(-1)
            writeInt(0)
            writeLong(0)
            writeShort(0); writeShort(0); writeShort(1); writeByte(0); writeByte(18); writeInt(-1)
            write(box("ftab", bytes { writeShort(1); writeShort(1); val n = "Sans-Serif".toByteArray(); writeByte(n.size); write(n) }))
        })
        val stsd = fullBox("stsd", 0, 0, bytes { writeInt(1); write(tx3g) })
        val sttsEntries = mutableListOf<Pair<Int, Long>>()
        for (d in durations) {
            if (sttsEntries.isNotEmpty() && sttsEntries.last().second == d) sttsEntries[sttsEntries.lastIndex] = sttsEntries.last().first + 1 to d
            else sttsEntries += 1 to d
        }
        val stts = fullBox("stts", 0, 0, bytes { writeInt(sttsEntries.size); for ((n, d) in sttsEntries) { writeInt(n); writeInt(d.toInt()) } })
        val stsc = fullBox("stsc", 0, 0, bytes { writeInt(1); writeInt(1); writeInt(sizes.size); writeInt(1) })
        val stsz = fullBox("stsz", 0, 0, bytes { writeInt(0); writeInt(sizes.size); for (s in sizes) writeInt(s) })
        val co64 = fullBox("co64", 0, 0, bytes { writeInt(1); writeLong(chunkOffset) })
        val stbl = box("stbl", stsd + stts + stsc + stsz + co64)
        val minf = box("minf", fullBox("nmhd", 0, 0, ByteArray(0)) + dinf + stbl)
        val mdia = box("mdia", mdhd + hdlr + minf)
        return box("trak", tkhd + mdia)
    }

    /** Les échantillons de texte, dans le même ordre que la table construite ci-dessus. */
    private fun sampleData(cues: List<Cue>): ByteArray {
        val out = ByteArrayOutputStream()
        val d = DataOutputStream(out)
        var t = 0L
        for (c in cues) {
            if (c.startMs > t) d.writeShort(0)
            val txt = c.text.toByteArray(Charsets.UTF_8)
            d.writeShort(txt.size); d.write(txt)
            t = maxOf(t, c.endMs)
        }
        if (cues.isEmpty()) d.writeShort(0)
        return out.toByteArray()
    }

    private fun packLang(l: String): Int {
        val c = (l.lowercase().takeIf { it.length == 3 && it.all { ch -> ch in 'a'..'z' } } ?: "und")
        return ((c[0] - 'a' + 1) shl 10) or ((c[1] - 'a' + 1) shl 5) or (c[2] - 'a' + 1)
    }

    // ------------------------------------------------------------------ outils

    private inline fun forEachChild(b: ByteArray, s: Int, e: Int, f: (String, Int, Int) -> Unit) {
        var p = s
        while (p + 8 <= e) {
            var sz = u32(b, p)
            if (sz == 1L) sz = u64(b, p + 8) else if (sz == 0L) sz = (e - p).toLong()
            if (sz < 8 || p + sz > e) break
            f(String(b, p + 4, 4, Charsets.ISO_8859_1), p, (p + sz).toInt())
            p += sz.toInt()
        }
    }

    private fun bytes(block: DataOutputStream.() -> Unit): ByteArray {
        val out = ByteArrayOutputStream()
        DataOutputStream(out).block()
        return out.toByteArray()
    }

    private fun box(type: String, payload: ByteArray): ByteArray = bytes {
        writeInt(8 + payload.size); write(type.toByteArray(Charsets.ISO_8859_1)); write(payload)
    }

    private fun fullBox(type: String, version: Int, flags: Int, payload: ByteArray): ByteArray =
        box(type, bytes { writeInt((version shl 24) or flags); write(payload) })

    private fun u32(b: ByteArray, p: Int): Long =
        ((b[p].toLong() and 0xFF) shl 24) or ((b[p + 1].toLong() and 0xFF) shl 16) or
            ((b[p + 2].toLong() and 0xFF) shl 8) or (b[p + 3].toLong() and 0xFF)

    private fun u64(b: ByteArray, p: Int): Long = (u32(b, p) shl 32) or u32(b, p + 4)

    private fun putU32(b: ByteArray, p: Int, v: Long) {
        b[p] = (v shr 24).toByte(); b[p + 1] = (v shr 16).toByte(); b[p + 2] = (v shr 8).toByte(); b[p + 3] = v.toByte()
    }

    companion object {
        private val TAGS = Regex("<[^>]+>|\\{[^}]*\\}")

        /** Lit les répliques d'un SRT (celui produit par [Subtitles.toSrt]). */
        fun parseSrt(srt: ByteArray): List<Cue> {
            val text = String(srt, Charsets.UTF_8).removePrefix("\uFEFF").replace("\r", "")
            val time = Regex("(\\d+):(\\d+):(\\d+)[,.](\\d+)\\s*-->\\s*(\\d+):(\\d+):(\\d+)[,.](\\d+)")
            fun ms(h: String, m: String, s: String, f: String) =
                h.toLong() * 3600000 + m.toLong() * 60000 + s.toLong() * 1000 + f.padEnd(3, '0').take(3).toLong()
            return text.split(Regex("\n\\s*\n")).mapNotNull { block ->
                val lines = block.trim().lines()
                val i = lines.indexOfFirst { time.containsMatchIn(it) }
                if (i < 0) return@mapNotNull null
                val g = time.find(lines[i])!!.groupValues
                val body = lines.drop(i + 1).joinToString("\n") { TAGS.replace(it, "") }.trim()
                if (body.isEmpty()) null else Cue(ms(g[1], g[2], g[3], g[4]), ms(g[5], g[6], g[7], g[8]), body)
            }.sortedBy { it.startMs }
        }
    }
}
