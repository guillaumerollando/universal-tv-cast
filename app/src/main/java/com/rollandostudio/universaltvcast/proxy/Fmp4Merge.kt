package com.rollandostudio.universaltvcast.proxy

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * Recolle le son à l'image pour les vidéos « MP4 par morceaux » (CMAF : Arte, et beaucoup de sites) quand le site les
 * sert à part. Les télés DLNA qui ne lisent pas le HLS reçoivent alors un seul MP4 fragmenté avec les deux pistes.
 *
 * - l'en-tête : la boîte `moov` de l'image, à laquelle on ajoute la piste du son (renumérotée) et son `trex` ;
 * - chaque morceau : les fragments `moof`/`mdat` de l'image, puis ceux du son, piste renumérotée dans `tfhd`,
 *   numéros de séquence (`mfhd`) qui se suivent. Rien n'est réencodé ni recopié en mémoire au-delà d'un morceau.
 */
class Fmp4Merge {
    private var audioId = 2
    private var seq = 1

    private class Box(val type: String, val start: Int, val size: Int, val header: Int) {
        val end: Int get() = start + size
        val body: Int get() = start + header
    }

    /** Boîtes de premier niveau de [b] entre [from] et [to]. */
    private fun boxes(b: ByteArray, from: Int = 0, to: Int = b.size): List<Box> {
        val out = mutableListOf<Box>()
        var p = from
        while (p + 8 <= to) {
            val bb = ByteBuffer.wrap(b, p, 8)
            var size = bb.int.toLong() and 0xFFFFFFFFL
            val type = String(b, p + 4, 4, Charsets.ISO_8859_1)
            var header = 8
            if (size == 1L && p + 16 <= to) { size = ByteBuffer.wrap(b, p + 8, 8).long; header = 16 }
            if (size == 0L) size = (to - p).toLong()
            if (size < header || p + size > to) break
            out += Box(type, p, size.toInt(), header)
            p += size.toInt()
        }
        return out
    }

    private fun putInt(b: ByteArray, at: Int, v: Int) { ByteBuffer.wrap(b, at, 4).putInt(v) }
    private fun getInt(b: ByteArray, at: Int) = ByteBuffer.wrap(b, at, 4).int

    private fun box(type: String, vararg parts: ByteArray): ByteArray {
        val len = 8 + parts.sumOf { it.size }
        val out = ByteArrayOutputStream(len)
        out.write(ByteBuffer.allocate(4).putInt(len).array())
        out.write(type.toByteArray(Charsets.ISO_8859_1))
        parts.forEach(out::write)
        return out.toByteArray()
    }

    private fun slice(b: ByteArray, x: Box) = b.copyOfRange(x.start, x.end)

    /** Numéro de piste de `tkhd` (version 0 : après 8 octets de dates ; version 1 : après 16). */
    private fun tkhdIdAt(b: ByteArray, tkhd: Box) = tkhd.body + 4 + if (b[tkhd.body].toInt() == 1) 16 else 8

    /** En-tête fusionné (ftyp + moov à deux pistes). Null si l'un des deux en-têtes est illisible. */
    fun init(video: ByteArray, audio: ByteArray): ByteArray? {
        val vTop = boxes(video)
        val aTop = boxes(audio)
        val vMoov = vTop.firstOrNull { it.type == "moov" } ?: return null
        val aMoov = aTop.firstOrNull { it.type == "moov" } ?: return null
        val vKids = boxes(video, vMoov.body, vMoov.end)
        val aKids = boxes(audio, aMoov.body, aMoov.end)
        val aTrak = aKids.firstOrNull { it.type == "trak" } ?: return null
        val aMvex = aKids.firstOrNull { it.type == "mvex" } ?: return null
        val aTrex = boxes(audio, aMvex.body, aMvex.end).firstOrNull { it.type == "trex" } ?: return null

        // Numéro libre pour le son : après les pistes de l'image.
        val vIds = vKids.filter { it.type == "trak" }.mapNotNull { t ->
            boxes(video, t.body, t.end).firstOrNull { it.type == "tkhd" }?.let { getInt(video, tkhdIdAt(video, it)) }
        }
        audioId = (vIds.maxOrNull() ?: 1) + 1

        val trak = slice(audio, aTrak)
        boxes(trak, 8, trak.size).firstOrNull { it.type == "tkhd" }?.let { putInt(trak, tkhdIdAt(trak, it), audioId) }
        val trex = slice(audio, aTrex)
        putInt(trex, 12, audioId)

        val moov = ByteArrayOutputStream()
        for (k in vKids) {
            val bytes = slice(video, k)
            when (k.type) {
                // next_track_ID : les 4 derniers octets de mvhd.
                "mvhd" -> { putInt(bytes, bytes.size - 4, audioId + 1); moov.write(bytes) }
                "mvex" -> {
                    val inner = ByteArrayOutputStream()
                    boxes(video, k.body, k.end).forEach { inner.write(slice(video, it)) }
                    inner.write(trex)
                    moov.write(box("mvex", inner.toByteArray()))
                }
                "pssh" -> Unit
                else -> moov.write(bytes)
            }
            // La piste du son, juste après la dernière piste de l'image.
            if (k.type == "trak" && k === vKids.last { it.type == "trak" }) moov.write(trak)
        }
        val out = ByteArrayOutputStream()
        vTop.firstOrNull { it.type == "ftyp" }?.let { out.write(slice(video, it)) }
        out.write(box("moov", moov.toByteArray()))
        return out.toByteArray()
    }

    /** Fragments d'un morceau (moof + mdat), séquence renumérotée ; [asAudio] : piste du son renumérotée. */
    private fun fragments(b: ByteArray, asAudio: Boolean, out: ByteArrayOutputStream) {
        for (x in boxes(b)) {
            when (x.type) {
                "moof" -> {
                    val m = slice(b, x)
                    for (k in boxes(m, 8, m.size)) {
                        if (k.type == "mfhd") putInt(m, k.body + 4, seq++)
                        if (k.type == "traf" && asAudio) {
                            boxes(m, k.body, k.end).firstOrNull { it.type == "tfhd" }?.let { putInt(m, it.body + 4, audioId) }
                        }
                    }
                    out.write(m)
                }
                "mdat" -> out.write(b, x.start, x.size)
                // styp, sidx, prft, emsg… : inutiles une fois recollés.
                else -> Unit
            }
        }
    }

    /** Un morceau d'image et le son qui couvre la même durée, l'un après l'autre. */
    fun merge(video: ByteArray, audio: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(video.size + audio.size)
        fragments(video, false, out)
        if (audio.isNotEmpty()) fragments(audio, true, out)
        return out.toByteArray()
    }
}
