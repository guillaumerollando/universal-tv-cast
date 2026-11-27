package com.rollandostudio.universaltvcast.proxy

/**
 * Horloge continue pour un flux MPEG-TS assemblé de morceaux HLS. Aux coupures (#EXT-X-DISCONTINUITY : publicité
 * insérée, pré-roll, changement d'encodeur), les horodatages repartent d'ailleurs ; une télé qui reçoit un seul flux
 * continu se fige alors (elle attend une image « dans le futur » ou la jette « dans le passé »).
 * On décale donc tous les horodatages (PTS, DTS, PCR) d'un morceau après coupure pour qu'il suive le précédent.
 * Rien n'est réencodé : seuls quelques octets d'en-tête changent.
 */
class TsClock {
    /** Décalage appliqué, en unités 90 kHz (horloge des PTS). */
    private var offset = 0L
    /** Plus grand horodatage (déjà décalé) envoyé, et l'écart moyen entre deux images. */
    private var lastPts = -1L
    private var step = 3600L // 40 ms : une image à 25 i/s, en attendant de la mesurer

    /** Morceau suivant ; [discontinuity] : il suit une coupure dans la liste. Modifie [data] sur place. */
    fun process(data: ByteArray, discontinuity: Boolean): ByteArray {
        if (discontinuity && lastPts >= 0) {
            firstPts(data)?.let { first -> offset = (lastPts + step - first) }
        }
        var p = 0
        var prev = -1L
        while (p + 188 <= data.size) {
            if (data[p].toInt() != 0x47) { p++; continue } // resynchronisation
            if (offset != 0L) shift(data, p)
            TsMerge.pts(data.copyOfRange(p, p + 188))?.let { t ->
                if (prev >= 0 && t > prev && t - prev < 9000) step = (step * 7 + (t - prev)) / 8
                prev = t
                if (t > lastPts || lastPts - t > MAX / 2) lastPts = t
            }
            p += 188
        }
        return data
    }

    private fun firstPts(d: ByteArray): Long? {
        var p = 0
        var min: Long? = null
        var n = 0
        while (p + 188 <= d.size && n < 400) {
            if (d[p].toInt() == 0x47) {
                TsMerge.pts(d.copyOfRange(p, p + 188))?.let { t -> if (min == null || t < min!!) min = t }
                n++
            }
            p += 188
        }
        return min
    }

    /** Décale PCR (champ d'adaptation) et PTS/DTS (en-tête PES) du paquet qui commence en [p]. */
    private fun shift(d: ByteArray, p: Int) {
        val afc = (d[p + 3].toInt() ushr 4) and 3
        var q = p + 4
        if (afc and 2 != 0) {
            val len = d[q].toInt() and 0xFF
            if (len > 0 && (d[q + 1].toInt() and 0x10) != 0 && len >= 7) {
                // PCR : 33 bits de base (90 kHz) + 6 réservés + 9 bits d'extension.
                val b = q + 2
                var base = ((d[b].toLong() and 0xFF) shl 25) or ((d[b + 1].toLong() and 0xFF) shl 17) or
                    ((d[b + 2].toLong() and 0xFF) shl 9) or ((d[b + 3].toLong() and 0xFF) shl 1) or ((d[b + 4].toLong() and 0x80) ushr 7)
                base = (base + offset).mod(MAX)
                d[b] = (base ushr 25).toByte(); d[b + 1] = (base ushr 17).toByte(); d[b + 2] = (base ushr 9).toByte()
                d[b + 3] = (base ushr 1).toByte()
                d[b + 4] = ((d[b + 4].toInt() and 0x7F) or ((base and 1).toInt() shl 7)).toByte()
            }
            q += 1 + len
        }
        if (afc and 1 == 0 || (d[p + 1].toInt() and 0x40) == 0 || q + 14 > p + 188) return
        if (d[q].toInt() != 0 || d[q + 1].toInt() != 0 || d[q + 2].toInt() != 1) return
        val flags = (d[q + 7].toInt() and 0xC0) ushr 6
        if (flags and 2 != 0) rewrite(d, q + 9)
        if (flags == 3 && q + 19 <= p + 188) rewrite(d, q + 14)
    }

    /** PTS ou DTS sur 5 octets (marqueurs conservés). */
    private fun rewrite(d: ByteArray, b: Int) {
        var t = ((d[b].toLong() and 0x0E) shl 29) or ((d[b + 1].toLong() and 0xFF) shl 22) or
            ((d[b + 2].toLong() and 0xFE) shl 14) or ((d[b + 3].toLong() and 0xFF) shl 7) or ((d[b + 4].toLong() and 0xFE) ushr 1)
        t = (t + offset).mod(MAX)
        d[b] = ((d[b].toInt() and 0xF1) or (((t ushr 30) and 7).toInt() shl 1)).toByte()
        d[b + 1] = (t ushr 22).toByte()
        d[b + 2] = ((((t ushr 15) and 0x7F).toInt() shl 1) or 1).toByte()
        d[b + 3] = (t ushr 7).toByte()
        d[b + 4] = (((t and 0x7F).toInt() shl 1) or 1).toByte()
    }

    private companion object { const val MAX = 1L shl 33 }
}
