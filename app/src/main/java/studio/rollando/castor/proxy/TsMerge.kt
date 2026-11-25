package studio.rollando.castor.proxy

import java.io.ByteArrayOutputStream

/**
 * Fusionne une image et un son servis séparément (deux flux MPEG-TS : certains sites découpent ainsi leurs
 * épisodes) en un seul MPEG-TS que n'importe quelle télé sait lire. Les paquets des deux flux sont entrelacés
 * selon leurs horodatages (PTS) ; la piste son reçoit son propre identifiant et une table PMT commune les annonce.
 */
class TsMerge {
    private var videoPid = -1
    private var videoType = 0x1B
    private var pcrPid = -1
    private var audioSrcPid = -1
    private var audioType = 0x0F
    private val ccTables = IntArray(2)

    /** Un morceau d'image et le son qui l'accompagne → un morceau complet. */
    fun merge(video: ByteArray, audio: ByteArray): ByteArray {
        if (videoPid < 0) readPmt(video)?.let { (pcr, streams) ->
            pcrPid = pcr
            streams.firstOrNull { it.first in VIDEO_TYPES }?.let { videoType = it.first; videoPid = it.second }
        }
        if (audioSrcPid < 0) readPmt(audio)?.second?.firstOrNull { it.first in AUDIO_TYPES }?.let {
            audioType = it.first; audioSrcPid = it.second
        }
        if (videoPid < 0 || audioSrcPid < 0) return video // structure inconnue : on garde l'image telle quelle
        if (pcrPid < 0 || pcrPid == 0x1FFF) pcrPid = videoPid

        val v = packets(video).filter { pidOf(it) == videoPid || (pidOf(it) == pcrPid && pcrPid != videoPid) }
        val a = packets(audio).filter { pidOf(it) == audioSrcPid }.map { remap(it, AUDIO_PID) }

        val out = ByteArrayOutputStream(video.size + audio.size + 376)
        out.write(tables())
        var i = 0; var j = 0
        var tv = Long.MIN_VALUE; var ta = Long.MIN_VALUE
        while (i < v.size || j < a.size) {
            if (i < v.size) pts(v[i])?.let { tv = it }
            if (j < a.size) pts(a[j])?.let { ta = it }
            // Celui qui est « en retard » passe d'abord (son et image avancent ensemble).
            val takeVideo = j >= a.size || (i < v.size && tv <= ta)
            if (takeVideo) out.write(v[i++]) else out.write(a[j++])
        }
        return out.toByteArray()
    }

    private fun tables(): ByteArray {
        val pat = byteArrayOf(0x00, 0xB0.toByte(), 0x0D, 0x00, 0x01, 0xC1.toByte(), 0x00, 0x00,
            0x00, 0x01, 0xF0.toByte(), 0x00)
        val pmt = byteArrayOf(0x02, 0xB0.toByte(), 0x17, 0x00, 0x01, 0xC1.toByte(), 0x00, 0x00,
            (0xE0 or (pcrPid ushr 8)).toByte(), pcrPid.toByte(), 0xF0.toByte(), 0x00,
            videoType.toByte(), (0xE0 or (videoPid ushr 8)).toByte(), videoPid.toByte(), 0xF0.toByte(), 0x00,
            audioType.toByte(), (0xE0 or (AUDIO_PID ushr 8)).toByte(), AUDIO_PID.toByte(), 0xF0.toByte(), 0x00)
        return section(0, pat, 0) + section(0x1000, pmt, 1)
    }

    private fun section(pid: Int, body: ByteArray, ccIndex: Int): ByteArray {
        val crc = crc32(body)
        val payload = byteArrayOf(0) + body + byteArrayOf((crc ushr 24).toByte(), (crc ushr 16).toByte(), (crc ushr 8).toByte(), crc.toByte())
        val p = ByteArray(188) { 0xFF.toByte() }
        p[0] = 0x47; p[1] = (0x40 or (pid ushr 8)).toByte(); p[2] = pid.toByte()
        p[3] = (0x10 or ccTables[ccIndex]).toByte(); ccTables[ccIndex] = (ccTables[ccIndex] + 1) and 0x0F
        System.arraycopy(payload, 0, p, 4, payload.size)
        return p
    }

    companion object {
        const val AUDIO_PID = 0x1F0
        private val VIDEO_TYPES = setOf(0x01, 0x02, 0x10, 0x1B, 0x24)
        private val AUDIO_TYPES = setOf(0x03, 0x04, 0x0F, 0x11, 0x81, 0x87)

        fun packets(d: ByteArray): List<ByteArray> {
            val start = (0 until minOf(d.size, 188 * 4)).firstOrNull { d[it] == 0x47.toByte() } ?: return emptyList()
            val l = ArrayList<ByteArray>(d.size / 188)
            var p = start
            while (p + 188 <= d.size) {
                if (d[p] == 0x47.toByte()) { l += d.copyOfRange(p, p + 188); p += 188 } else p++
            }
            return l
        }

        fun pidOf(p: ByteArray) = ((p[1].toInt() and 0x1F) shl 8) or (p[2].toInt() and 0xFF)

        private fun remap(p: ByteArray, pid: Int): ByteArray {
            val c = p.copyOf()
            c[1] = ((c[1].toInt() and 0xE0) or (pid ushr 8)).toByte(); c[2] = pid.toByte()
            return c
        }

        private fun payloadStart(p: ByteArray): Int {
            val afc = (p[3].toInt() ushr 4) and 3
            return if (afc == 2 || afc == 3) 5 + (p[4].toInt() and 0xFF) else 4
        }

        /** PTS (90 kHz) d'un paquet qui commence un PES, sinon null. */
        fun pts(p: ByteArray): Long? {
            if (p[1].toInt() and 0x40 == 0) return null
            val q = payloadStart(p)
            if (q + 14 > 188 || p[q].toInt() != 0 || p[q + 1].toInt() != 0 || p[q + 2].toInt() != 1) return null
            if (p[q + 7].toInt() and 0x80 == 0) return null
            val b = q + 9
            return ((p[b].toLong() and 0x0E) shl 29) or ((p[b + 1].toLong() and 0xFF) shl 22) or
                ((p[b + 2].toLong() and 0xFE) shl 14) or ((p[b + 3].toLong() and 0xFF) shl 7) or ((p[b + 4].toLong() and 0xFE) ushr 1)
        }

        /** Lit la PMT : PID de l'horloge (PCR) et liste (type de piste, PID). */
        fun readPmt(d: ByteArray): Pair<Int, List<Pair<Int, Int>>>? {
            val ps = packets(d)
            var pmtPid = -1
            for (p in ps) {
                if (p[1].toInt() and 0x40 == 0) continue
                val pid = pidOf(p)
                var q = payloadStart(p)
                if (q >= 188) continue
                q += 1 + (p[q].toInt() and 0xFF)
                if (pid == 0 && pmtPid < 0 && q + 12 < 188) {
                    pmtPid = ((p[q + 10].toInt() and 0x1F) shl 8) or (p[q + 11].toInt() and 0xFF)
                } else if (pid == pmtPid && q + 12 < 188 && p[q].toInt() == 2) {
                    val end = minOf(188, q + 3 + (((p[q + 1].toInt() and 0x0F) shl 8) or (p[q + 2].toInt() and 0xFF)) - 4)
                    val pcr = ((p[q + 8].toInt() and 0x1F) shl 8) or (p[q + 9].toInt() and 0xFF)
                    val info = ((p[q + 10].toInt() and 0x0F) shl 8) or (p[q + 11].toInt() and 0xFF)
                    var e = q + 12 + info
                    val streams = mutableListOf<Pair<Int, Int>>()
                    while (e + 5 <= end) {
                        val type = p[e].toInt() and 0xFF
                        val esPid = ((p[e + 1].toInt() and 0x1F) shl 8) or (p[e + 2].toInt() and 0xFF)
                        streams += type to esPid
                        e += 5 + (((p[e + 3].toInt() and 0x0F) shl 8) or (p[e + 4].toInt() and 0xFF))
                    }
                    return pcr to streams
                }
            }
            return null
        }

        private fun crc32(data: ByteArray): Int {
            var crc = -1
            for (b in data) {
                crc = crc xor ((b.toInt() and 0xFF) shl 24)
                repeat(8) { crc = if (crc and Int.MIN_VALUE != 0) (crc shl 1) xor 0x04C11DB7 else crc shl 1 }
            }
            return crc
        }
    }
}
