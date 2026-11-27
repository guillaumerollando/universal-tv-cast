package com.rollandostudio.universaltvcast.proxy

/**
 * Lit le début d'un fichier MKV/WebM pour savoir s'il contient déjà des pistes de sous-titres
 * (l'en-tête « Tracks » est presque toujours dans le premier mégaoctet). Renvoie leurs langues,
 * ou une liste vide s'il n'y en a pas (ou si l'en-tête n'est pas lisible).
 */
object MkvProbe {
    private const val TRACKS = 0x1654AE6BL
    private const val TRACK_ENTRY = 0xAEL
    private const val TRACK_TYPE = 0x83L
    private const val LANGUAGE = 0x22B59CL
    private const val LANGUAGE_BCP47 = 0x22B59DL
    private const val SUBTITLE = 0x11L

    fun isMatroska(head: ByteArray): Boolean =
        head.size >= 4 && head[0] == 0x1A.toByte() && head[1] == 0x45.toByte() && head[2] == 0xDF.toByte() && head[3] == 0xA3.toByte()

    /** Langues des pistes de sous-titres (« fre », « eng », « und »…). */
    fun subtitleLanguages(head: ByteArray): List<String> {
        if (!isMatroska(head)) return emptyList()
        // L'identifiant « Tracks » apparaît aussi dans l'index du début (SeekHead) : on prend la première
        // occurrence qui contient vraiment des pistes.
        var from = 0
        while (true) {
            val start = indexOf(head, byteArrayOf(0x16, 0x54, 0xAE.toByte(), 0x6B), from) ?: return emptyList()
            parseTracks(head, start)?.let { return it }
            from = start + 1
        }
    }

    /** Langues des sous-titres d'un élément Tracks commençant en [start], ou null si ce n'en est pas un. */
    private fun parseTracks(head: ByteArray, start: Int): List<String>? {
        val size = readSize(head, start + 4) ?: return null
        val body = start + 4 + size.second
        val end = if (size.first < 0) head.size else minOf(head.size.toLong(), body + size.first).toInt()
        var entries = 0
        val langs = mutableListOf<String>()
        forEachChild(head, body, end) { id, from, to ->
            if (id != TRACK_ENTRY) return@forEachChild
            entries++
            var type = -1L
            var lang = "eng" // valeur par défaut de Matroska quand la langue n'est pas écrite
            forEachChild(head, from, to) { cid, cf, ct ->
                when (cid) {
                    TRACK_TYPE -> type = readUInt(head, cf, ct)
                    LANGUAGE, LANGUAGE_BCP47 -> lang = String(head, cf, ct - cf, Charsets.US_ASCII).trimEnd('\u0000')
                }
            }
            if (type == SUBTITLE) langs += lang
        }
        return if (entries > 0) langs else null
    }

    /** Chapitre d'un MKV : début et fin (secondes, fin null si absente) et son nom. */
    data class Chapter(val start: Double, val end: Double?, val name: String)

    /**
     * Chapitres d'un MKV (« Opening », « OP », « Partie A », « Ending »…), s'ils sont dans le début du fichier
     * (le cas habituel : avant les images). Liste vide sinon.
     */
    fun chapters(head: ByteArray): List<Chapter> {
        if (!isMatroska(head)) return emptyList()
        var from = 0
        while (true) {
            val start = indexOf(head, byteArrayOf(0x10, 0x43, 0xA7.toByte(), 0x70), from) ?: return emptyList()
            val size = readSize(head, start + 4)
            if (size != null) {
                val body = start + 4 + size.second
                val end = if (size.first < 0) head.size else minOf(head.size.toLong(), body + size.first).toInt()
                val out = mutableListOf<Chapter>()
                forEachChild(head, body, end) { id, ef, et ->
                    if (id != 0x45B9L || out.isNotEmpty()) return@forEachChild // EditionEntry : la première seulement
                    forEachChild(head, ef, et) { cid, af, at ->
                        if (cid != 0xB6L) return@forEachChild // ChapterAtom
                        var s0 = -1L; var e0 = -1L; var name = ""
                        forEachChild(head, af, at) { k, f, t ->
                            when (k) {
                                0x91L -> s0 = readUInt(head, f, t) // ChapterTimeStart (ns)
                                0x92L -> e0 = readUInt(head, f, t) // ChapterTimeEnd (ns)
                                0x80L -> if (name.isEmpty()) forEachChild(head, f, t) { d, df, dt ->
                                    if (d == 0x85L && name.isEmpty()) name = String(head, df, dt - df, Charsets.UTF_8).trimEnd('\u0000')
                                }
                            }
                        }
                        if (s0 >= 0) out += Chapter(s0 / 1e9, e0.takeIf { it > s0 }?.div(1e9), name.trim())
                    }
                }
                if (out.isNotEmpty()) return out.sortedBy { it.start }
            }
            from = start + 1
        }
    }

    private fun forEachChild(b: ByteArray, from: Int, to: Int, f: (Long, Int, Int) -> Unit) {
        var p = from
        while (p < to) {
            val id = readId(b, p) ?: return
            val size = readSize(b, p + id.second) ?: return
            val dataStart = p + id.second + size.second
            val dataEnd = dataStart + size.first
            if (size.first < 0 || dataEnd > to || dataEnd > b.size) return // élément coupé : on s'arrête là
            f(id.first, dataStart, dataEnd.toInt())
            p = dataEnd.toInt()
        }
    }

    /** Identifiant EBML (marqueur de longueur conservé) et sa longueur en octets. */
    private fun readId(b: ByteArray, p: Int): Pair<Long, Int>? {
        if (p >= b.size) return null
        val first = b[p].toInt() and 0xFF
        val len = Integer.numberOfLeadingZeros(first) - 23
        if (len !in 1..4 || p + len > b.size) return null
        var v = 0L
        for (i in 0 until len) v = (v shl 8) or (b[p + i].toLong() and 0xFF)
        return v to len
    }

    /** Taille EBML (marqueur retiré) et sa longueur en octets ; -1 = taille inconnue. */
    private fun readSize(b: ByteArray, p: Int): Pair<Long, Int>? {
        if (p >= b.size) return null
        val first = b[p].toInt() and 0xFF
        val len = Integer.numberOfLeadingZeros(first) - 23
        if (len !in 1..8 || p + len > b.size) return null
        var v = (first and (0xFF shr len)).toLong()
        for (i in 1 until len) v = (v shl 8) or (b[p + i].toLong() and 0xFF)
        val allOnes = (1L shl (7 * len)) - 1
        return (if (v == allOnes) -1L else v) to len
    }

    private fun readUInt(b: ByteArray, from: Int, to: Int): Long {
        var v = 0L
        for (i in from until to) v = (v shl 8) or (b[i].toLong() and 0xFF)
        return v
    }

    private fun indexOf(b: ByteArray, pat: ByteArray, from: Int = 0): Int? {
        outer@ for (i in from..b.size - pat.size) {
            for (j in pat.indices) if (b[i + j] != pat[j]) continue@outer
            return i
        }
        return null
    }
}
