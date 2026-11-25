package studio.rollando.castor.proxy

import android.content.Context
import android.net.Uri
import android.util.Log
import studio.rollando.castor.net.Http
import studio.rollando.castor.net.Mime
import studio.rollando.castor.net.Net
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.concurrent.thread

/**
 * Petit serveur HTTP sur le téléphone, que la télé interroge.
 * Il relaie la vidéo en ajoutant les en-têtes exigés par le site (Referer, cookies…),
 * réécrit les listes HLS, peut assembler un flux HLS en un seul flux continu,
 * sert les fichiers du téléphone et les sous-titres convertis.
 */
class ProxyServer(private val ctx: Context) {
    enum class Kind { MAIN, PLAYLIST, SEGMENT, KEY }
    private class Remote(val url: String, val headers: Map<String, String>, val kind: Kind)
    private class Req(val method: String, val seg: List<String>, val query: Map<String, String>, val headers: Map<String, String>)

    private val server = ServerSocket(0, 50)
    val port: Int get() = server.localPort
    private val pool = Executors.newCachedThreadPool()
    private val remotes = ConcurrentHashMap<String, Remote>()
    private val idsByUrl = ConcurrentHashMap<String, String>()
    private val locals = ConcurrentHashMap<String, Uri>()
    private val counter = AtomicInteger(0)

    /** Taille, type et prise en charge des plages d'une vidéo directe, pour répondre tout de suite à la télé. */
    private class Meta(val total: Long, val type: String?, val ranges: Boolean)
    private val metas = ConcurrentHashMap<String, Meta>()

    @Volatile var subtitle: ByteArray? = null
    @Volatile var subtitleUrl: String? = null
    /** Nom du format tel que la télé l'accepte (ex. « video/mpeg » au lieu de « video/mp2t »). */
    @Volatile var mimeOverride: String? = null

    init {
        thread(isDaemon = true, name = "castor-proxy") {
            while (!server.isClosed) {
                val s = try { server.accept() } catch (_: IOException) { break }
                pool.execute { handle(s) }
            }
        }
    }

    fun baseUrl(): String = "http://${Net.localIp() ?: "127.0.0.1"}:$port"

    fun reset() {
        remotes.clear(); idsByUrl.clear(); locals.clear(); metas.clear()
        subtitle = null; subtitleUrl = null; mimeOverride = null
    }

    fun register(url: String, headers: Map<String, String>, kind: Kind = Kind.MAIN): String =
        idsByUrl.getOrPut("$kind|$url") {
            val id = "r" + counter.incrementAndGet()
            remotes[id] = Remote(url, headers, kind)
            id
        }

    /**
     * Interroge la source une fois (premier octet seulement) pour connaître taille et type.
     * La télé pose ces questions (HEAD) pendant le changement de vidéo et attend la réponse :
     * avec une source lente (torrent via Stremio…), elle restait bloquée.
     */
    fun prefetchMeta(id: String): Boolean {
        val r = remotes[id] ?: return false
        metas[id]?.let { return true }
        val t0 = System.currentTimeMillis()
        val conn = runCatching { Http.open(r.url, r.headers, "bytes=0-0", readTimeout = 60_000) }.getOrNull() ?: return false
        try {
            val code = conn.responseCode
            val ct = conn.contentType?.substringBefore(';')?.trim()
            if (code >= 400 || ct?.contains("mpegurl", true) == true) return false
            val total = when (code) {
                206 -> conn.getHeaderField("Content-Range")?.substringAfterLast('/')?.toLongOrNull() ?: -1
                else -> conn.getHeaderField("Content-Length")?.toLongOrNull() ?: -1
            }
            metas[id] = Meta(total, ct, code == 206)
            Log.i(TAG, "meta $id code=$code total=$total type=$ct en ${System.currentTimeMillis() - t0} ms")
            return true
        } finally {
            conn.disconnect()
        }
    }

    /** Taille de la vidéo si connue (pour la recherche de sous-titres). */
    fun totalOf(id: String): Long? = metas[id]?.total?.takeIf { it > 0 }

    fun registerLocal(uri: Uri): String {
        val id = "l" + counter.incrementAndGet()
        locals[id] = uri
        return id
    }

    // ------------------------------------------------------------------ requêtes

    private fun handle(sock: Socket) {
        sock.use { s ->
            try {
                s.soTimeout = 60_000
                s.tcpNoDelay = true
                val input = BufferedInputStream(s.getInputStream())
                val out = BufferedOutputStream(s.getOutputStream(), 64 * 1024)
                val requestLine = readLine(input) ?: return
                val headers = HashMap<String, String>()
                while (true) {
                    val l = readLine(input) ?: break
                    if (l.isEmpty()) break
                    val i = l.indexOf(':')
                    if (i > 0) headers[l.substring(0, i).trim().lowercase()] = l.substring(i + 1).trim()
                }
                val parts = requestLine.split(' ')
                if (parts.size < 2) return
                val target = parts[1]
                val req = Req(
                    method = parts[0].uppercase(),
                    seg = target.substringBefore('?').trim('/').split('/'),
                    query = target.substringAfter('?', "").split('&').filter { '=' in it }
                        .associate { it.substringBefore('=') to Uri.decode(it.substringAfter('=')) },
                    headers = headers,
                )
                if (req.seg.getOrNull(0) != "r" || remotes[req.seg.getOrNull(1)]?.kind == Kind.MAIN) {
                    Log.i(TAG, "télé → ${req.method} ${target.take(80)} range=${headers["range"]}")
                }
                if (req.method == "OPTIONS") {
                    head(out, 204, "No Content", listOf("Access-Control-Max-Age" to "86400", "Content-Length" to "0"))
                    out.flush()
                    return
                }
                // Beaucoup de télés cherchent un fichier de sous-titres du même nom, à côté de la vidéo (video.srt…).
                val ext = Mime.ext(req.seg.lastOrNull() ?: "")
                if (req.seg.getOrNull(0) in setOf("r", "l") && ext in setOf("srt", "smi", "sami", "ass", "ssa", "sub", "vtt", "txt")) {
                    Log.i(TAG, "la télé cherche des sous-titres : ${req.seg.lastOrNull()} → ${if (subtitle != null) "servis" else "aucun"}")
                    serveSubtitle(req, out)
                    out.flush()
                    return
                }
                when (req.seg.getOrNull(0)) {
                    "r" -> serveRemote(req, out)
                    "ts" -> serveContinuous(req, out)
                    "l" -> serveLocal(req, out)
                    "sub" -> serveSubtitle(req, out)
                    else -> empty(out, 404, "Not Found")
                }
                out.flush()
            } catch (e: IOException) {
                // Souvent normal (la télé ferme la connexion pour avancer ou s'arrêter), mais utile au diagnostic.
                Log.i(TAG, "connexion close : ${e.javaClass.simpleName} ${e.message}")
            } catch (e: Exception) {
                Log.w(TAG, "proxy", e)
            }
        }
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(c.toChar())
            if (sb.length > 16 * 1024) throw IOException("line too long")
        }
    }

    private fun head(out: OutputStream, code: Int, reason: String, h: List<Pair<String, String>>) {
        val sb = StringBuilder("HTTP/1.1 $code $reason\r\n")
        for ((k, v) in h) sb.append(k).append(": ").append(v).append("\r\n")
        sb.append("Server: Android/1.0 UPnP/1.0 DLNADOC/1.50 Castor/1.0\r\n")
        // CORS : le lecteur Google Cast (une page web) exige ces en-têtes pour la vidéo, le HLS et les sous-titres.
        sb.append("Access-Control-Allow-Origin: *\r\n")
        sb.append("Access-Control-Allow-Methods: GET, HEAD, OPTIONS\r\n")
        sb.append("Access-Control-Allow-Headers: *\r\n")
        sb.append("Access-Control-Expose-Headers: Content-Length, Content-Range, Accept-Ranges, Content-Type\r\n")
        sb.append("Connection: close\r\n\r\n")
        out.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
    }

    private fun empty(out: OutputStream, code: Int, reason: String) =
        head(out, code, reason, listOf("Content-Length" to "0"))

    private fun dlna(seekable: Boolean): List<Pair<String, String>> {
        val h = mutableListOf(
            "transferMode.dlna.org" to "Streaming",
            "contentFeatures.dlna.org" to
                "DLNA.ORG_OP=${if (seekable) "01" else "00"};DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000",
        )
        subtitleUrl?.let { h += "CaptionInfo.sec" to it }
        return h
    }

    // ------------------------------------------------------------------ vidéo distante

    private fun serveRemote(req: Req, out: OutputStream) {
        val id = req.seg.getOrNull(1) ?: ""
        val r = remotes[id] ?: return empty(out, 404, "Not Found")
        if (r.kind == Kind.MAIN && req.method == "HEAD") {
            if (metas[id] == null) prefetchMeta(id)
            metas[id]?.let { m ->
                val type = mimeOverride ?: if (Mime.isMedia(m.type)) m.type!! else Mime.guess(r.url)
                val h = mutableListOf("Content-Type" to type)
                if (m.ranges) h += "Accept-Ranges" to "bytes"
                val start = req.headers["range"]?.let { Regex("bytes=(\\d+)-").find(it)?.groupValues?.get(1)?.toLongOrNull() }
                if (start != null && m.ranges && m.total > 0 && start < m.total) {
                    h += "Content-Range" to "bytes $start-${m.total - 1}/${m.total}"
                    h += "Content-Length" to (m.total - start).toString()
                    head(out, 206, "Partial Content", h + dlna(seekable = true))
                } else {
                    if (m.total > 0) h += "Content-Length" to m.total.toString()
                    head(out, 200, "OK", h + dlna(seekable = m.ranges))
                }
                Log.i(TAG, "HEAD $id -> (mémoire) total=${m.total} plage=${req.headers["range"]}")
                return
            }
        }
        val t0 = System.currentTimeMillis()
        val range = if (r.kind == Kind.MAIN) req.headers["range"] else null
        val conn = Http.open(r.url, r.headers, range, readTimeout = if (r.kind == Kind.MAIN) 120_000 else 30_000)
        try {
            val code = conn.responseCode
            if (r.kind == Kind.MAIN) Log.i(TAG, "${req.method} $id range=$range -> source $code " +
                "len=${conn.getHeaderField("Content-Length")} en ${System.currentTimeMillis() - t0} ms")
            if (code >= 400) return empty(out, code, "Upstream Error")
            val ct = conn.contentType?.substringBefore(';')?.trim()

            if (r.kind == Kind.PLAYLIST || ct?.contains("mpegurl", true) == true ||
                (r.kind == Kind.MAIN && Mime.ext(r.url) == "m3u8")
            ) {
                val bytes = conn.inputStream.use { it.readBytes() }
                if (Hls.isPlaylist(bytes)) {
                    val body = rewritePlaylist(String(bytes, Charsets.UTF_8), conn.url.toString(), r.headers)
                        .toByteArray(Charsets.UTF_8)
                    head(out, 200, "OK", listOf(
                        "Content-Type" to "application/vnd.apple.mpegurl",
                        "Content-Length" to body.size.toString(),
                        "Cache-Control" to "no-cache",
                    ))
                    if (req.method != "HEAD") out.write(body)
                    return
                }
                sendBytes(req, out, bytes, ct ?: Mime.guess(r.url))
                return
            }

            when (r.kind) {
                Kind.SEGMENT -> {
                    var bytes = conn.inputStream.use { it.readBytes() }
                    val ext = Mime.ext(r.url)
                    val type = when {
                        Mime.isMedia(ct) -> ct!!
                        ext == "m4s" || ext == "mp4" -> "video/mp4"
                        else -> "video/mp2t"
                    }
                    if (type == "video/mp2t") bytes = Hls.syncTs(bytes)
                    sendBytes(req, out, bytes, type)
                }
                Kind.KEY -> sendBytes(req, out, conn.inputStream.use { it.readBytes() }, "application/octet-stream")
                else -> {
                    val type = mimeOverride ?: if (Mime.isMedia(ct)) ct!! else Mime.guess(r.url)
                    val h = mutableListOf("Content-Type" to type, "Accept-Ranges" to "bytes")
                    conn.getHeaderField("Content-Length")?.let { h += "Content-Length" to it }
                    conn.getHeaderField("Content-Range")?.let { h += "Content-Range" to it }
                    val chunked = conn.getHeaderField("Content-Length") == null && req.method != "HEAD"
                    if (chunked) h += "Transfer-Encoding" to "chunked"
                    h += dlna(seekable = true)
                    if (code == 206) head(out, 206, "Partial Content", h) else head(out, 200, "OK", h)
                    if (req.method != "HEAD") {
                        if (chunked) {
                            val c = ChunkedOutputStream(out)
                            conn.inputStream.use { it.copyTo(c, 64 * 1024) }
                            c.finish()
                        } else conn.inputStream.use { it.copyTo(out, 64 * 1024) }
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun sendBytes(req: Req, out: OutputStream, bytes: ByteArray, type: String) {
        head(out, 200, "OK", listOf("Content-Type" to type, "Content-Length" to bytes.size.toString()) + dlna(true))
        if (req.method != "HEAD") out.write(bytes)
    }

    private val uriAttr = Regex("URI=\"([^\"]+)\"")

    private fun rewritePlaylist(text: String, base: String, headers: Map<String, String>): String {
        var nextIsPlaylist = false
        return text.lines().joinToString("\n") { raw ->
            val line = raw.trim()
            when {
                line.isEmpty() -> raw
                line.startsWith("#") -> {
                    if (line.startsWith("#EXT-X-STREAM-INF")) nextIsPlaylist = true
                    val kind = when {
                        line.startsWith("#EXT-X-KEY") || line.startsWith("#EXT-X-SESSION-KEY") -> Kind.KEY
                        line.startsWith("#EXT-X-MAP") -> Kind.SEGMENT
                        else -> Kind.PLAYLIST
                    }
                    uriAttr.replace(raw) { m ->
                        "URI=\"" + proxied(Hls.resolve(base, m.groupValues[1]), headers, kind) + "\""
                    }
                }
                else -> {
                    val abs = Hls.resolve(base, line)
                    val kind = if (nextIsPlaylist || Mime.ext(abs) == "m3u8") Kind.PLAYLIST else Kind.SEGMENT
                    nextIsPlaylist = false
                    proxied(abs, headers, kind)
                }
            }
        }
    }

    private fun proxied(abs: String, headers: Map<String, String>, kind: Kind): String {
        val id = register(abs, headers, kind)
        val name = when (kind) {
            Kind.PLAYLIST -> "index.m3u8"
            Kind.KEY -> "key.bin"
            else -> abs.substringBefore('?').substringAfterLast('/').replace(Regex("[^A-Za-z0-9._-]"), "").take(40)
                .ifEmpty { "seg.ts" }
        }
        return "${baseUrl()}/r/$id/$name"
    }

    // ------------------------------------------------------------------ HLS en flux continu

    private fun serveContinuous(req: Req, out0: OutputStream) {
        val r = remotes[req.seg.getOrNull(1)] ?: return empty(out0, 404, "Not Found")
        val start = req.query["start"]?.toDoubleOrNull() ?: 0.0
        val fmp4Path = req.seg.lastOrNull()?.endsWith(".mp4") == true
        val type = mimeOverride ?: if (fmp4Path) "video/mp4" else "video/mp2t"
        // Taille inconnue d'avance : envoi « par blocs » (chunked). Sans cela, certaines télés
        // (Philips Titan OS, lecteur GStreamer) attendent indéfiniment.
        if (req.method == "HEAD") {
            head(out0, 200, "OK", listOf("Content-Type" to type, "Cache-Control" to "no-cache") + dlna(false))
            return
        }
        var plan = Hls.load(r.url, r.headers)
        head(out0, 200, "OK", listOf(
            "Content-Type" to type,
            "Cache-Control" to "no-cache",
            "Transfer-Encoding" to "chunked",
        ) + dlna(false))
        val out = ChunkedOutputStream(out0)

        var idx = 0
        if (plan.endList) {
            var acc = 0.0
            while (idx < plan.segs.size - 1 && acc + plan.segs[idx].duration <= start) {
                acc += plan.segs[idx].duration; idx++
            }
        } else {
            idx = (plan.segs.size - 3).coerceAtLeast(0)
        }
        val keys = ConcurrentHashMap<String, ByteArray>()
        val queue = ArrayDeque<Pair<Hls.Seg, Future<ByteArray?>>>()
        var lastSeq = -1L
        var currentMap: String? = null
        var failures = 0
        try {
            while (true) {
                while (queue.size < 3 && idx < plan.segs.size) {
                    val s = plan.segs[idx++]
                    val fmp4 = plan.fmp4
                    queue.add(s to pool.submit<ByteArray?> { fetchSegment(s, r.headers, keys, !fmp4) })
                }
                if (queue.isEmpty()) {
                    if (plan.endList) break
                    Thread.sleep((plan.targetDuration * 500).toLong().coerceIn(1000, 5000))
                    plan = Hls.reload(plan, r.headers)
                    idx = plan.segs.indexOfFirst { it.seq > lastSeq }.let { if (it < 0) plan.segs.size else it }
                    continue
                }
                val (seg, fut) = queue.removeFirst()
                val data = try { fut.get(90, TimeUnit.SECONDS) } catch (_: Exception) { null }
                if (data == null) {
                    if (++failures > 8) break
                    continue
                }
                failures = 0
                val map = seg.map
                if (map != null && map != currentMap) {
                    out.write(Http.fetch(map, r.headers).bytes)
                    currentMap = map
                }
                out.write(data)
                out.flush()
                lastSeq = seg.seq
            }
            out.finish()
        } finally {
            queue.forEach { it.second.cancel(true) }
        }
    }

    private fun fetchSegment(s: Hls.Seg, headers: Map<String, String>, keys: MutableMap<String, ByteArray>, ts: Boolean): ByteArray? {
        var data: ByteArray? = null
        for (attempt in 0 until 3) {
            data = runCatching { Http.fetch(s.url, headers).bytes }.getOrNull()
            if (data != null) break
            Thread.sleep(500L * (attempt + 1))
        }
        var d = data ?: return null
        val k = s.key
        if (k != null && k.method == "AES-128" && k.uri != null) {
            val key = keys.getOrPut(k.uri) { Http.fetch(k.uri, headers).bytes }
            val iv = k.iv ?: ByteBuffer.allocate(16).putLong(8, s.seq).array()
            val c = Cipher.getInstance("AES/CBC/PKCS5Padding")
            c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            d = c.doFinal(d)
        }
        return if (ts) Hls.syncTs(d) else d
    }

    // ------------------------------------------------------------------ fichiers du téléphone

    private fun serveLocal(req: Req, out: OutputStream) {
        val uri = locals[req.seg.getOrNull(1)] ?: return empty(out, 404, "Not Found")
        val cr = ctx.contentResolver
        val afd = cr.openAssetFileDescriptor(uri, "r") ?: return empty(out, 404, "Not Found")
        afd.use {
            var total = afd.length
            if (total < 0) total = afd.parcelFileDescriptor.statSize
            val type = mimeOverride ?: cr.getType(uri)?.takeIf { Mime.isMedia(it) } ?: Mime.guess(uri.toString())
            val range = parseRange(req.headers["range"], total)
            afd.createInputStream().use { ins ->
                if (range == null) {
                    val h = mutableListOf("Content-Type" to type, "Accept-Ranges" to "bytes")
                    if (total > 0) h += "Content-Length" to total.toString()
                    head(out, 200, "OK", h + dlna(true))
                    if (req.method != "HEAD") ins.copyTo(out, 64 * 1024)
                } else {
                    val (a, b) = range
                    head(out, 206, "Partial Content", listOf(
                        "Content-Type" to type,
                        "Accept-Ranges" to "bytes",
                        "Content-Range" to "bytes $a-$b/$total",
                        "Content-Length" to (b - a + 1).toString(),
                    ) + dlna(true))
                    if (req.method != "HEAD") {
                        var toSkip = a
                        while (toSkip > 0) {
                            val n = ins.skip(toSkip)
                            if (n <= 0) break
                            toSkip -= n
                        }
                        copyLimited(ins, out, b - a + 1)
                    }
                }
            }
        }
    }

    private fun parseRange(h: String?, total: Long): Pair<Long, Long>? {
        if (h == null || total <= 0) return null
        val m = Regex("bytes=(\\d*)-(\\d*)").find(h) ?: return null
        val a = m.groupValues[1]
        val b = m.groupValues[2]
        return when {
            a.isEmpty() && b.isNotEmpty() -> (total - b.toLong()).coerceAtLeast(0) to total - 1
            a.isNotEmpty() -> {
                val s = a.toLong()
                if (s >= total) return null
                s to (b.toLongOrNull() ?: (total - 1)).coerceAtMost(total - 1)
            }
            else -> null
        }
    }

    private fun copyLimited(ins: InputStream, out: OutputStream, len: Long) {
        val buf = ByteArray(64 * 1024)
        var left = len
        while (left > 0) {
            val n = ins.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (n < 0) break
            out.write(buf, 0, n)
            left -= n
        }
    }

    // ------------------------------------------------------------------ sous-titres

    private fun serveSubtitle(req: Req, out: OutputStream) {
        val srt = subtitle ?: return empty(out, 404, "Not Found")
        val vtt = Mime.ext(req.seg.lastOrNull() ?: "") == "vtt"
        val bytes = if (vtt) Subtitles.srtToVtt(srt) else srt
        head(out, 200, "OK", listOf(
            "Content-Type" to (if (vtt) "text/vtt; charset=utf-8" else "text/srt; charset=utf-8"),
            "Content-Length" to bytes.size.toString(),
        ))
        if (req.method != "HEAD") out.write(bytes)
    }

    /** Encodage HTTP/1.1 « chunked ». */
    private class ChunkedOutputStream(private val out: OutputStream) : OutputStream() {
        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
        override fun write(b: ByteArray, off: Int, len: Int) {
            if (len == 0) return
            out.write((Integer.toHexString(len) + "\r\n").toByteArray(Charsets.ISO_8859_1))
            out.write(b, off, len)
            out.write("\r\n".toByteArray(Charsets.ISO_8859_1))
        }
        override fun flush() = out.flush()
        fun finish() { out.write("0\r\n\r\n".toByteArray(Charsets.ISO_8859_1)); out.flush() }
    }

    companion object { private const val TAG = "CastorProxy" }
}
