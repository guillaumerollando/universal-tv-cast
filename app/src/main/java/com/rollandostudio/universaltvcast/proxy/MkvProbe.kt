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

    /** Ce qu'on lit du début d'un MKV pour trouver ses pistes (l'en-tête « Tracks » y est presque toujours). */
    const val HEAD_BYTES = 256 * 1024

    /**
     * Où masquer la déclaration Dolby Vision d'un MKV (élément BlockAdditionMapping « dvcC / dvvC » des pistes) :
     * chaque élément est remplacé par un élément vide (« Void ») de même longueur. Le décodeur de la télé lit alors
     * la couche de base HDR10 et ignore les données Dolby Vision des images. Seulement quand cette couche de base est
     * compatible (profils 8.x, 7) : un profil 5 n'a pas d'image lisible sans Dolby Vision, on n'y touche pas.
     */
    fun dolbyVisionMask(head: ByteArray): List<Pair<Int, ByteArray>> {
        if (!isMatroska(head)) return emptyList()
        val out = mutableListOf<Pair<Int, ByteArray>>()
        var from = 0
        while (out.isEmpty()) {
            val start = indexOf(head, byteArrayOf(0x16, 0x54, 0xAE.toByte(), 0x6B), from) ?: break
            val size = readSize(head, start + 4)
            if (size != null) {
                val body = start + 4 + size.second
                val end = if (size.first < 0) head.size else minOf(head.size.toLong(), body + size.first).toInt()
                forEachChild(head, body, end) { id, ef, et ->
                    if (id != TRACK_ENTRY) return@forEachChild
                    var p = ef
                    while (p < et) { // les enfants de la piste, avec la position de leur en-tête
                        val cid = readId(head, p) ?: break
                        val csz = readSize(head, p + cid.second) ?: break
                        val dataStart = p + cid.second + csz.second
                        val dataEnd = dataStart + csz.first.toInt()
                        if (csz.first < 0 || dataEnd > et) break
                        if (cid.first == BLOCK_ADDITION_MAPPING && hasHdr10Base(head, dataStart, dataEnd)) {
                            void(dataEnd - p)?.let { out += p to it }
                        }
                        p = dataEnd
                    }
                }
            }
            from = start + 1
        }
        return out
    }

    private const val BLOCK_ADDITION_MAPPING = 0x41E4L
    private const val BLOCK_ADD_ID_EXTRA_DATA = 0x41EDL

    /** Configuration Dolby Vision (dvcC / dvvC) dont la couche de base se lit seule (compatibilité ≠ 0). */
    private fun hasHdr10Base(b: ByteArray, from: Int, to: Int): Boolean {
        var ok = false
        forEachChild(b, from, to) { id, f, t ->
            if (id == BLOCK_ADD_ID_EXTRA_DATA && t - f >= 5) {
                val profile = (b[f + 2].toInt() and 0xFF) shr 1
                val compat = (b[f + 4].toInt() and 0xFF) shr 4
                ok = profile != 5 && compat != 0
            }
        }
        return ok
    }

    /** Élément « Void » (0xEC) d'exactement [total] octets, ou null si impossible. */
    private fun void(total: Int): ByteArray? {
        if (total < 2) return null
        val content = total - 2
        if (content > 126) { // taille sur 8 octets
            if (total < 9) return null
            val c = (total - 9).toLong()
            val v = ByteArray(total); v[0] = 0xEC.toByte(); v[1] = 0x01
            for (i in 0 until 7) v[2 + i] = (c shr (8 * (6 - i))).toByte()
            return v
        }
        return ByteArray(total).also { it[0] = 0xEC.toByte(); it[1] = (0x80 or content).toByte() }
    }

    fun isMatroska(head: ByteArray): Boolean =
        head.size >= 4 && head[0] == 0x1A.toByte() && head[1] == 0x45.toByte() && head[2] == 0xDF.toByte() && head[3] == 0xA3.toByte()

    /** Piste son d'un MKV : numéro, nom (« French VFQ »), langue, par défaut, et place de sa déclaration (début, longueur). */
    data class Audio(val number: Long, val name: String, val lang: String, val default: Boolean, val entryAt: Int, val entryLen: Int)

    /** Pistes son d'un MKV, dans l'ordre du fichier (vide si l'en-tête n'est pas lisible). */
    fun audioTracks(head: ByteArray): List<Audio> {
        if (!isMatroska(head)) return emptyList()
        var from = 0
        while (true) {
            val start = indexOf(head, byteArrayOf(0x16, 0x54, 0xAE.toByte(), 0x6B), from) ?: return emptyList()
            val size = readSize(head, start + 4)
            if (size != null) {
                val body = start + 4 + size.second
                val end = if (size.first < 0) head.size else minOf(head.size.toLong(), body + size.first).toInt()
                val out = mutableListOf<Audio>()
                var entries = 0
                var p = body
                while (p < end) { // enfants de « Tracks » avec la place de leur en-tête (CRC-32, Void, TrackEntry…)
                    val id = readId(head, p) ?: break
                    val sz = readSize(head, p + id.second) ?: break
                    val ef = p + id.second + sz.second
                    if (sz.first < 0 || sz.first > end - ef) break // élément coupé ou faux « Tracks » (index du début)
                    val et = ef + sz.first.toInt()
                    if (id.first == TRACK_ENTRY) {
                        entries++
                        var type = -1L; var number = -1L; var name = ""; var lang = "eng"; var def = true
                        forEachChild(head, ef, et) { cid, cf, ct ->
                            when (cid) {
                                TRACK_TYPE -> type = readUInt(head, cf, ct)
                                0xD7L -> number = readUInt(head, cf, ct)
                                0x536EL -> name = String(head, cf, ct - cf, Charsets.UTF_8).trimEnd('\u0000')
                                LANGUAGE, LANGUAGE_BCP47 -> lang = String(head, cf, ct - cf, Charsets.US_ASCII).trimEnd('\u0000')
                                0x88L -> def = readUInt(head, cf, ct) != 0L
                            }
                        }
                        if (type == AUDIO && number >= 0) out += Audio(number, name, lang, def, p, et - p)
                    }
                    p = et
                }
                if (entries > 0) return out
            }
            from = start + 1
        }
    }

    private const val AUDIO = 2L

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
