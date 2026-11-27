package com.rollandostudio.universaltvcast.proxy

import com.rollandostudio.universaltvcast.net.Http
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.net.URL
import java.nio.ByteBuffer
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Manifestes DASH (.mpd) sans DRM, traduits en listes « à la HLS » ([Hls.Plan]) : le reste de l'appli (flux continu
 * pour la télé, son recollé par [Fmp4Merge], horloge, reprise) s'en sert sans rien savoir du DASH.
 *
 * Pris en charge : MP4 fragmenté (CMAF) adressé par SegmentTemplate ($Number$, $Time$, SegmentTimeline), SegmentList,
 * ou SegmentBase (un seul fichier, découpé grâce à son index `sidx`). Vidéos à la demande (MPD « static »).
 * Écartés : DRM (ContentProtection), WebM (VP9/Opus), directs DASH (le lecteur du téléphone les lit, pas la télé).
 */
object Dash {
    /** Préfixe des adresses de piste son d'un MPD (« dash-audio:<n>|<mpd> ») : [Hls.load] les reconnaît. */
    const val AUDIO = "dash-audio:"

    class Protected : Exception("DASH protégé (DRM)")
    /** Direct en DASH : lisible par le lecteur du téléphone, pas envoyé à la télé par le relais. */
    class Live : java.io.IOException("direct DASH")

    fun isDash(url: String, bytes: ByteArray? = null): Boolean =
        url.substringBefore('?').substringBefore('#').endsWith(".mpd", true) || url.startsWith(AUDIO) ||
            (bytes != null && String(bytes, 0, minOf(bytes.size, 512), Charsets.UTF_8).contains("<MPD"))

    private class Rep(val el: Element, val set: Element, val bandwidth: Long, val codecs: String, val mime: String,
        val height: Int, val lang: String?, val label: String?, val main: Boolean)

    private class Mpd(val url: String, val root: Element, val period: Element, val video: List<Rep>, val audio: List<List<Rep>>)

    private fun xml(bytes: ByteArray) = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = false; isExpandEntityReferences = false
    }.newDocumentBuilder().parse(ByteArrayInputStream(bytes)).documentElement

    private fun children(e: Element, tag: String): List<Element> {
        val out = mutableListOf<Element>()
        var n = e.firstChild
        while (n != null) {
            if (n is Element && (n.tagName == tag || n.tagName.endsWith(":$tag"))) out += n
            n = n.nextSibling
        }
        return out
    }
    private fun child(e: Element?, tag: String) = e?.let { children(it, tag).firstOrNull() }
    private fun attr(e: Element?, name: String) = e?.getAttribute(name)?.takeIf { it.isNotEmpty() }

    private fun load(url: String, headers: Map<String, String>): Mpd {
        val f = Http.fetch(url, headers, connectTimeout = 8000)
        val root = xml(f.bytes)
        if (attr(root, "type") == "dynamic") throw Live()
        val period = children(root, "Period").firstOrNull() ?: throw java.io.IOException("MPD sans période")
        val sets = children(period, "AdaptationSet")
        fun reps(set: Element): List<Rep> {
            if (children(set, "ContentProtection").isNotEmpty()) return emptyList()
            val lang = attr(set, "lang")
            val label = child(set, "Label")?.textContent?.trim()?.ifEmpty { null } ?: attr(set, "label")
            val main = children(set, "Role").any { attr(it, "value") == "main" }
            return children(set, "Representation").filter { children(it, "ContentProtection").isEmpty() }.map { r ->
                Rep(r, set, attr(r, "bandwidth")?.toLongOrNull() ?: 0,
                    (attr(r, "codecs") ?: attr(set, "codecs") ?: "").lowercase(),
                    (attr(r, "mimeType") ?: attr(set, "mimeType") ?: "").lowercase(),
                    attr(r, "height")?.toIntOrNull() ?: attr(set, "height")?.toIntOrNull() ?: 0, lang, label, main)
            }
        }
        val protectedAll = sets.isNotEmpty() && sets.all { children(it, "ContentProtection").isNotEmpty() }
        if (protectedAll) throw Protected()
        fun kind(set: Element): String {
            val ct = attr(set, "contentType") ?: attr(set, "mimeType") ?: attr(children(set, "Representation").firstOrNull(), "mimeType") ?: ""
            return when { ct.startsWith("video") -> "video"; ct.startsWith("audio") -> "audio"; else -> ct }
        }
        val mp4 = { r: Rep -> !r.mime.contains("webm") }
        val video = sets.filter { kind(it) == "video" }.flatMap(::reps).filter(mp4)
        val audio = sets.filter { kind(it) == "audio" }.map { reps(it).filter(mp4) }.filter { it.isNotEmpty() }
        if (video.isEmpty()) throw if (sets.any { kind(it) == "video" && children(it, "ContentProtection").isNotEmpty() }) Protected()
            else java.io.IOException("MPD sans image lisible")
        return Mpd(f.finalUrl, root, period, video, audio)
    }

    /** Image choisie : H.264 d'abord (toutes les télés), la meilleure qualité jusqu'au 1080p. */
    private fun pickVideo(m: Mpd): Rep {
        val avc = m.video.filter { it.codecs.startsWith("avc") }.ifEmpty { m.video }
        return avc.filter { it.height in 1..1080 }.maxByOrNull { it.bandwidth } ?: avc.maxBy { it.bandwidth }
    }

    /** Liste d'image du MPD (son à part : [Hls.Plan.splitAudio]). */
    fun plan(url: String, headers: Map<String, String>): Hls.Plan {
        if (url.startsWith(AUDIO)) {
            val n = url.removePrefix(AUDIO).substringBefore('|').toInt()
            val m = load(url.substringAfter('|'), headers)
            val set = m.audio.getOrNull(n) ?: m.audio.first()
            return segments(m, set.maxBy { it.bandwidth }, headers, false)
        }
        val m = load(url, headers)
        return segments(m, pickVideo(m), headers, m.audio.isNotEmpty())
    }

    /** Sous-titres du MPD en un seul fichier (WebVTT, TTML) : les morceaux MP4 de sous-titres sont écartés. */
    fun subtitles(url: String, headers: Map<String, String>): List<Hls.Track> {
        val f = Http.fetch(url, headers, connectTimeout = 8000)
        val root = xml(f.bytes)
        val period = children(root, "Period").firstOrNull() ?: return emptyList()
        val m = Mpd(f.finalUrl, root, period, emptyList(), emptyList())
        return textSets(period).mapIndexedNotNull { i, set ->
            val r = children(set, "Representation").firstOrNull() ?: return@mapIndexedNotNull null
            val mime = (attr(r, "mimeType") ?: attr(set, "mimeType") ?: "").lowercase()
            val rep = Rep(r, set, 0, (attr(r, "codecs") ?: attr(set, "codecs") ?: "").lowercase(), mime, 0, attr(set, "lang"),
                child(set, "Label")?.textContent?.trim(), false)
            val lang = rep.lang
            val name = rep.label ?: lang?.let { Locale.forLanguageTag(it).getDisplayLanguage(Locale.getDefault()).ifEmpty { it }.replaceFirstChar { c -> c.titlecase(Locale.getDefault()) } } ?: "?"
            val segmented = child(r, "SegmentTemplate") != null || child(set, "SegmentTemplate") != null || child(r, "SegmentList") != null
            // Un fichier WebVTT ou TTML : son adresse ; du MP4 (wvtt, stpp) ou des morceaux : lu par [textBytes].
            val uri = if (!segmented && !mime.contains("mp4")) baseUrl(m, rep) else "$TEXT$i|${m.url}"
            Hls.Track(name, lang, uri, false)
        }
    }

    /** Préfixe des pistes de sous-titres à extraire (MP4 ou morceaux) : « dash-text:<n>|<mpd> ». */
    const val TEXT = "dash-text:"

    private fun textSets(period: Element) = children(period, "AdaptationSet").filter { set ->
        val mime = (attr(set, "mimeType") ?: attr(children(set, "Representation").firstOrNull(), "mimeType") ?: "").lowercase()
        attr(set, "contentType") == "text" || mime.startsWith("text/") || mime.contains("ttml") ||
            (mime == "application/mp4" && (attr(children(set, "Representation").firstOrNull(), "codecs") ?: attr(set, "codecs") ?: "").let { it.contains("wvtt") || it.contains("stpp") })
    }

    /** Sous-titres d'une piste « dash-text: », en SRT (morceaux téléchargés et recollés). */
    fun textBytes(uri: String, headers: Map<String, String>): ByteArray {
        val n = uri.removePrefix(TEXT).substringBefore('|').toInt()
        val f = Http.fetch(uri.substringAfter('|'), headers, connectTimeout = 8000)
        val root = xml(f.bytes)
        val period = children(root, "Period").first()
        val set = textSets(period)[n]
        val r = children(set, "Representation").first()
        val mime = (attr(r, "mimeType") ?: attr(set, "mimeType") ?: "").lowercase()
        val m = Mpd(f.finalUrl, root, period, emptyList(), emptyList())
        val rep = Rep(r, set, 0, (attr(r, "codecs") ?: attr(set, "codecs") ?: "").lowercase(), mime, 0, null, null, false)
        val single = child(r, "SegmentTemplate") == null && child(set, "SegmentTemplate") == null && child(r, "SegmentList") == null
        val data = if (single) Http.fetch(baseUrl(m, rep), headers).bytes else {
            val plan = segments(m, rep, headers, false)
            val pool = java.util.concurrent.Executors.newFixedThreadPool(6)
            try {
                val init = plan.segs.first().map?.let { Hls.bytes(it, plan.segs.first().mapRange, headers) } ?: ByteArray(0)
                val parts = plan.segs.map { s -> pool.submit<ByteArray> { runCatching { Hls.bytes(s.url, s.range, headers) }.getOrDefault(ByteArray(0)) } }
                val out = java.io.ByteArrayOutputStream()
                out.write(init); parts.forEach { out.write(it.get()) }
                out.toByteArray()
            } finally { pool.shutdown() }
        }
        return if (mime.contains("mp4")) Mp4Text.toSrt(data) ?: throw java.io.IOException("sous-titres MP4 illisibles") else data
    }

    /** Pistes son du MPD (langue, nom), dans le format des pistes HLS. */
    fun tracks(url: String, headers: Map<String, String>): List<Hls.Track> {
        val m = load(url, headers)
        val pref = Locale.getDefault().language
        val def = m.audio.indexOfFirst { it.first().main }.takeIf { it >= 0 }
            ?: m.audio.indexOfFirst { it.first().lang?.startsWith(pref) == true }.takeIf { it >= 0 } ?: 0
        return m.audio.mapIndexed { i, set ->
            val r = set.first()
            val name = r.label ?: r.lang?.let { Locale.forLanguageTag(it).getDisplayLanguage(Locale.getDefault()).ifEmpty { it }.replaceFirstChar { c -> c.titlecase(Locale.getDefault()) } } ?: "#${i + 1}"
            Hls.Track(name, r.lang, "$AUDIO$i|${m.url}", i == def)
        }
    }

    // ------------------------------------------------------------------ adresses des morceaux

    private fun baseUrl(m: Mpd, r: Rep): String {
        var base = m.url
        for (e in listOf(m.root, m.period, r.set, r.el)) child(e, "BaseURL")?.textContent?.trim()?.takeIf { it.isNotEmpty() }?.let {
            base = URL(URL(base), it).toString()
        }
        return base
    }

    private fun fill(template: String, r: Rep, number: Long, time: Long): String =
        Regex("\\$(RepresentationID|Number|Time|Bandwidth)(%0(\\d+)d)?\\$|\\$\\$").replace(template) { mt ->
            if (mt.value == "$$") return@replace "$"
            val v = when (mt.groupValues[1]) {
                "RepresentationID" -> return@replace attr(r.el, "id") ?: ""
                "Number" -> number
                "Time" -> time
                else -> r.bandwidth
            }
            val w = mt.groupValues[3].toIntOrNull()
            if (w != null) v.toString().padStart(w, '0') else v.toString()
        }

    /** Durée totale (« PT1H32M5.4S »). */
    private fun duration(s: String?): Double {
        s ?: return 0.0
        val m = Regex("P(?:(\\d+)D)?T?(?:(\\d+)H)?(?:(\\d+)M)?(?:([\\d.]+)S)?").find(s) ?: return 0.0
        val (d, h, mi, se) = m.destructured
        return (d.toDoubleOrNull() ?: 0.0) * 86400 + (h.toDoubleOrNull() ?: 0.0) * 3600 + (mi.toDoubleOrNull() ?: 0.0) * 60 + (se.toDoubleOrNull() ?: 0.0)
    }

    private fun segments(m: Mpd, r: Rep, headers: Map<String, String>, splitAudio: Boolean): Hls.Plan {
        val base = baseUrl(m, r)
        val total = duration(attr(m.period, "duration")).takeIf { it > 0 } ?: duration(attr(m.root, "mediaPresentationDuration"))
        val segs = mutableListOf<Hls.Seg>()
        // Le modèle le plus proche gagne (Representation, sinon AdaptationSet, sinon Period).
        val tpl = child(r.el, "SegmentTemplate") ?: child(r.set, "SegmentTemplate") ?: child(m.period, "SegmentTemplate")
        val list = child(r.el, "SegmentList") ?: child(r.set, "SegmentList")
        val single = child(r.el, "SegmentBase") ?: child(r.set, "SegmentBase")
        when {
            tpl != null -> {
                val ts = attr(tpl, "timescale")?.toLongOrNull() ?: 1L
                var number = attr(tpl, "startNumber")?.toLongOrNull() ?: 1L
                val media = attr(tpl, "media") ?: throw java.io.IOException("modèle sans media")
                val init = attr(tpl, "initialization")?.let { URL(URL(base), fill(it, r, number, 0)).toString() }
                val timeline = child(tpl, "SegmentTimeline")
                if (timeline != null) {
                    var t = 0L
                    for (s in children(timeline, "S")) {
                        attr(s, "t")?.toLongOrNull()?.let { t = it }
                        val d = attr(s, "d")?.toLongOrNull() ?: continue
                        var rep = attr(s, "r")?.toIntOrNull() ?: 0
                        if (rep < 0) rep = (((total * ts) - t) / d).toInt().coerceAtLeast(0) - 1
                        repeat(rep + 1) {
                            segs += Hls.Seg(URL(URL(base), fill(media, r, number, t)).toString(), d.toDouble() / ts, number, null, init)
                            t += d; number++
                        }
                    }
                } else {
                    val d = attr(tpl, "duration")?.toLongOrNull() ?: throw java.io.IOException("modèle sans durée")
                    val count = kotlin.math.ceil(total * ts / d).toLong()
                    for (i in 0 until count) {
                        val dur = minOf(d.toDouble() / ts, total - i * d.toDouble() / ts)
                        segs += Hls.Seg(URL(URL(base), fill(media, r, number, i * d)).toString(), dur, number, null, init)
                        number++
                    }
                }
            }
            list != null -> {
                val ts = attr(list, "timescale")?.toLongOrNull() ?: 1L
                val d = attr(list, "duration")?.toLongOrNull()?.toDouble()?.div(ts) ?: 0.0
                val initEl = child(list, "Initialization")
                val init = initEl?.let { URL(URL(base), attr(it, "sourceURL") ?: base).toString() }
                val initRange = attr(initEl, "range")?.let { "bytes=$it" }
                var n = 0L
                for (s in children(list, "SegmentURL")) {
                    segs += Hls.Seg(URL(URL(base), attr(s, "media") ?: base).toString(), d, n++, null, init,
                        attr(s, "mediaRange")?.let { "bytes=$it" }, initRange)
                }
            }
            single != null -> {
                // Un seul fichier : l'index (sidx) donne la place et la durée de chaque fragment.
                val idx = attr(single, "indexRange") ?: throw java.io.IOException("SegmentBase sans index")
                val initRange = attr(child(single, "Initialization"), "range")?.let { "bytes=$it" }
                val (a, b) = idx.split('-').map { it.trim().toLong() }
                val sidx = Hls.bytes(base, "bytes=$a-$b", headers)
                // Les positions comptent depuis la fin de la boîte sidx (la plage d'index peut contenir d'autres boîtes).
                val sidxSize = ByteBuffer.wrap(sidx, 0, 4).int.toLong() and 0xFFFFFFFFL
                segs += sidxSegments(sidx, a + sidxSize, base, initRange ?: "bytes=0-${a - 1}", headers)
            }
            else -> {
                // Profil « à la demande » sans index déclaré : l'index (sidx) suit l'en-tête dans le fichier lui-même.
                val head = Hls.bytes(base, "bytes=0-131071", headers)
                var p = 0
                var found: Pair<Int, Int>? = null
                while (p + 8 <= head.size) {
                    val size = (ByteBuffer.wrap(head, p, 4).int.toLong() and 0xFFFFFFFFL).toInt()
                    val type = String(head, p + 4, 4, Charsets.ISO_8859_1)
                    if (type == "sidx") { found = p to size; break }
                    if (size < 8 || type == "moof" || type == "mdat") break
                    p += size
                }
                val (at, size) = found ?: throw java.io.IOException("adressage DASH inconnu")
                val sidx = if (at + size <= head.size) head.copyOfRange(at, at + size)
                    else Hls.bytes(base, "bytes=$at-${at + size - 1}", headers)
                segs += sidxSegments(sidx, (at + size).toLong(), base, "bytes=0-${at - 1}", headers)
            }
        }
        if (segs.isEmpty()) throw java.io.IOException("MPD sans morceaux")
        return Hls.Plan(m.url, segs, endList = true, targetDuration = segs.maxOf { it.duration }, fmp4 = true,
            splitAudio = splitAudio, encryptedSample = false, dash = true)
    }

    /** Fragments décrits par une boîte `sidx` ; [after] : premier octet qui suit l'index dans le fichier. */
    private fun sidxSegments(b: ByteArray, after: Long, url: String, initRange: String, headers: Map<String, String>, depth: Int = 0): List<Hls.Seg> {
        val bb = ByteBuffer.wrap(b)
        bb.position(8)
        val version = bb.get().toInt(); bb.position(bb.position() + 3)
        bb.int // reference_ID
        val timescale = bb.int.toLong() and 0xFFFFFFFFL
        val first: Long
        if (version == 0) { bb.int; first = bb.int.toLong() and 0xFFFFFFFFL } else { bb.long; first = bb.long }
        bb.short // réservé
        val count = bb.short.toInt() and 0xFFFF
        var offset = after + first
        val out = mutableListOf<Hls.Seg>()
        repeat(count) {
            val word = bb.int.toLong() and 0xFFFFFFFFL
            val size = word and 0x7FFFFFFFL
            val dur = (bb.int.toLong() and 0xFFFFFFFFL).toDouble() / timescale
            bb.int
            if (word ushr 31 == 1L && depth < 3) {
                // Index à plusieurs niveaux : cette entrée désigne un autre sidx, suivi de ses fragments.
                val head = Hls.bytes(url, "bytes=$offset-${offset + minOf(size, 65535) - 1}", headers)
                val childSize = ByteBuffer.wrap(head, 0, 4).int.toLong() and 0xFFFFFFFFL
                val child = if (childSize <= head.size) head.copyOf(childSize.toInt())
                    else Hls.bytes(url, "bytes=$offset-${offset + childSize - 1}", headers)
                out += sidxSegments(child, offset + childSize, url, initRange, headers, depth + 1)
            } else out += Hls.Seg(url, dur, out.size.toLong(), null, url, "bytes=$offset-${offset + size - 1}", initRange)
            offset += size
        }
        return out.mapIndexed { i, s -> Hls.Seg(s.url, s.duration, i.toLong(), null, s.map, s.range, s.mapRange) }
    }
}
