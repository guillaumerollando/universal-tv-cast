package studio.rollando.tvcast.cast

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.util.Log
import android.view.Surface
import java.io.ByteArrayOutputStream
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.BlockingQueue
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.min

/**
 * Diaporama diffusé comme une seule vidéo en direct : la télé ouvre son lecteur une fois, et chaque photo
 * arrive par un fondu dans le même flux, sans l'écran noir que provoque l'ouverture d'un nouveau fichier.
 *
 * Le téléphone dessine la photo courante 25 fois par seconde dans l'encodeur H.264 matériel, puis met
 * l'image en MPEG-TS. Deux façons de la servir :
 * - HLS en direct (segments d'une seconde) pour Google Cast ;
 * - un flux MPEG-TS continu pour les télés DLNA.
 */
class SlideStream private constructor(private val codec: MediaCodec, private val surface: Surface) {

    companion object {
        private const val TAG = "TvCastSlides"
        const val W = 1920
        const val H = 1080
        private const val FPS = 25
        private const val KEEP = 4

        /** Démarre l'encodeur ; null si le téléphone ne sait pas encoder en H.264. */
        fun start(): SlideStream? = runCatching {
            val f = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, W, H).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, 6_000_000)
                setInteger(MediaFormat.KEY_FRAME_RATE, FPS)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
            val c = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            c.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val s = c.createInputSurface()
            c.start()
            SlideStream(c, s).also { it.run() }
        }.onFailure { Log.w(TAG, "encodeur indisponible", it) }.getOrNull()
    }

    @Volatile private var running = true
    @Volatile private var current: Bitmap? = null
    @Volatile private var previous: Bitmap? = null
    @Volatile private var fadeStart = 0L
    private val fadeMs = 450L

    /** Affiche cette photo (fondu depuis la précédente). */
    fun show(bmp: Bitmap) {
        previous = current
        current = bmp
        fadeStart = System.currentTimeMillis()
    }

    fun stop() {
        running = false
        subscribers.forEach { it.offer(ByteArray(0)) }
    }

    // ------------------------------------------------------------------ dessin et encodage

    private fun run() {
        Thread({ drawLoop() }, "slides-draw").start()
        Thread({ drainLoop() }, "slides-encode").start()
    }

    private fun drawLoop() {
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        val frameNs = 1_000_000_000L / FPS
        var next = System.nanoTime()
        try {
            while (running) {
                val canvas = runCatching { surface.lockHardwareCanvas() }.getOrElse { surface.lockCanvas(null) }
                try {
                    canvas.drawColor(Color.BLACK)
                    val t = ((System.currentTimeMillis() - fadeStart).toFloat() / fadeMs).coerceIn(0f, 1f)
                    previous?.takeIf { t < 1f && !it.isRecycled }?.let { draw(canvas, it, paint, 255) }
                    current?.takeIf { !it.isRecycled }?.let { draw(canvas, it, paint, (255 * t).toInt()) }
                } finally {
                    surface.unlockCanvasAndPost(canvas)
                }
                next += frameNs
                val wait = next - System.nanoTime()
                if (wait > 0) Thread.sleep(wait / 1_000_000, (wait % 1_000_000).toInt()) else next = System.nanoTime()
            }
        } catch (e: Exception) {
            Log.w(TAG, "dessin", e)
        } finally {
            runCatching { codec.signalEndOfInputStream() }
        }
    }

    /** Photo entière, centrée, sur fond noir. */
    private fun draw(c: Canvas, b: Bitmap, p: Paint, alpha: Int) {
        val k = min(W.toFloat() / b.width, H.toFloat() / b.height)
        val w = b.width * k
        val h = b.height * k
        p.alpha = alpha
        c.drawBitmap(b, null, RectF((W - w) / 2, (H - h) / 2, (W + w) / 2, (H + h) / 2), p)
    }

    private fun drainLoop() {
        val info = MediaCodec.BufferInfo()
        var config = ByteArray(0)
        var firstPts = -1L
        try {
            while (true) {
                val i = codec.dequeueOutputBuffer(info, 100_000)
                if (i < 0) { if (!running) break else continue }
                val buf = codec.getOutputBuffer(i)!!
                val data = ByteArray(info.size)
                buf.position(info.offset); buf.get(data)
                codec.releaseOutputBuffer(i, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) { config = data; continue }
                if (firstPts < 0) firstPts = info.presentationTimeUs
                val key = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                val pts90k = (info.presentationTimeUs - firstPts) * 9 / 100 + 90_000 // départ à 1 s
                onFrame(if (key) config + data else data, pts90k, key)
            }
        } catch (e: Exception) {
            Log.w(TAG, "encodage", e)
        } finally {
            running = false
            runCatching { codec.stop() }
            runCatching { codec.release() }
            runCatching { surface.release() }
            subscribers.forEach { it.offer(ByteArray(0)) }
            Log.i(TAG, "flux du diaporama arrêté")
        }
    }

    // ------------------------------------------------------------------ segments HLS et flux continu

    private val ts = TsMuxer()
    private val segments = ArrayDeque<Pair<Int, ByteArray>>() // numéro → contenu
    private val durations = HashMap<Int, Double>()
    private var seq = 0
    private var segStartPts = -1L
    private var seg = ByteArrayOutputStream()
    private val lock = Object()
    private val subscribers = CopyOnWriteArrayList<BlockingQueue<ByteArray>>()

    private val waiting = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<BlockingQueue<ByteArray>, Boolean>())

    private fun onFrame(annexB: ByteArray, pts: Long, key: Boolean) {
        val tables = if (key) ts.tables() else null
        val packets = ts.frame(annexB, pts, key)
        synchronized(lock) {
            if (key) {
                if (segStartPts >= 0 && seg.size() > 0) {
                    segments.addLast(seq to seg.toByteArray())
                    durations[seq] = (pts - segStartPts) / 90_000.0
                    seq++
                    while (segments.size > KEEP) durations.remove(segments.removeFirst().first)
                    lock.notifyAll()
                }
                seg = ByteArrayOutputStream()
                segStartPts = pts
                seg.write(tables!!)
            }
            if (segStartPts >= 0) seg.write(packets)
        }
        val live = if (tables != null) tables + packets else packets
        for (q in subscribers) {
            if (q in waiting) { if (!key) continue; waiting.remove(q) } // un nouveau client commence sur une image clé
            if (!q.offer(live)) { subscribers.remove(q); q.clear(); q.offer(ByteArray(0)) } // client trop lent
        }
    }

    /** Attend qu'il y ait au moins [n] segments prêts (au plus [maxMs]). */
    fun awaitSegments(n: Int, maxMs: Long): Boolean {
        val end = System.currentTimeMillis() + maxMs
        synchronized(lock) {
            while (segments.size < n && running) {
                val left = end - System.currentTimeMillis()
                if (left <= 0) return false
                lock.wait(left)
            }
            return segments.size >= n
        }
    }

    fun playlist(): String = synchronized(lock) {
        val sb = StringBuilder("#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:2\n")
        sb.append("#EXT-X-MEDIA-SEQUENCE:").append(segments.firstOrNull()?.first ?: 0).append('\n')
        for ((n, _) in segments) {
            sb.append("#EXTINF:").append(String.format(java.util.Locale.ROOT, "%.3f", durations[n] ?: 1.0)).append(",\n")
            sb.append("seg").append(n).append(".ts\n")
        }
        sb.toString()
    }

    fun segment(n: Int): ByteArray? = synchronized(lock) { segments.firstOrNull { it.first == n }?.second }

    /** Flux continu : les paquets arrivent dans la file, en commençant à la prochaine image clé. Fin = tableau vide. */
    fun subscribe(): BlockingQueue<ByteArray> = ArrayBlockingQueue<ByteArray>(400).also { waiting += it; subscribers += it }
    fun unsubscribe(q: BlockingQueue<ByteArray>) { subscribers.remove(q) }
    val alive: Boolean get() = running
}

/** Multiplexeur MPEG-TS minimal : une piste vidéo H.264 (PID 0x100), PAT/PMT, PTS et PCR. */
class TsMuxer {
    private val cc = IntArray(0x2000)

    fun tables(): ByteArray = section(0, byteArrayOf(
        0x00, 0xB0.toByte(), 0x0D, 0x00, 0x01, 0xC1.toByte(), 0x00, 0x00, // PAT, ts id 1, version 0
        0x00, 0x01, 0xF0.toByte(), 0x00, // programme 1 → PMT PID 0x1000
    )) + section(0x1000, byteArrayOf(
        0x02, 0xB0.toByte(), 0x12, 0x00, 0x01, 0xC1.toByte(), 0x00, 0x00, // PMT, programme 1
        0xE1.toByte(), 0x00, 0xF0.toByte(), 0x00, // PCR PID 0x100, pas d'info programme
        0x1B, 0xE1.toByte(), 0x00, 0xF0.toByte(), 0x00, // H.264 sur PID 0x100
    ))

    private fun section(pid: Int, body: ByteArray): ByteArray {
        val crc = crc32(body)
        val payload = byteArrayOf(0) + body + byteArrayOf((crc ushr 24).toByte(), (crc ushr 16).toByte(), (crc ushr 8).toByte(), crc.toByte())
        val p = ByteArray(188) { 0xFF.toByte() }
        p[0] = 0x47; p[1] = (0x40 or (pid ushr 8)).toByte(); p[2] = pid.toByte(); p[3] = (0x10 or next(pid)).toByte()
        System.arraycopy(payload, 0, p, 4, payload.size)
        return p
    }

    /** Une image (Annex-B) → paquets TS. */
    fun frame(annexB: ByteArray, pts: Long, key: Boolean): ByteArray {
        val aud = byteArrayOf(0, 0, 0, 1, 0x09, 0xF0.toByte())
        val pesHeader = byteArrayOf(
            0, 0, 1, 0xE0.toByte(), 0, 0, // longueur 0 : non bornée (vidéo)
            0x80.toByte(), 0x80.toByte(), 5,
            (0x21 or ((pts ushr 29).toInt() and 0x0E)).toByte(), (pts ushr 22).toByte(),
            ((pts ushr 14).toInt() or 1).toByte(), (pts ushr 7).toByte(), ((pts shl 1).toInt() or 1).toByte(),
        )
        val pes = pesHeader + aud + annexB
        val out = ByteArrayOutputStream(pes.size + pes.size / 184 * 4 + 376)
        var off = 0
        var first = true
        val pcr = (pts - 9000).coerceAtLeast(0) // PCR 100 ms avant l'affichage
        while (off < pes.size) {
            val p = ByteArray(188)
            p[0] = 0x47
            p[1] = ((if (first) 0x40 else 0) or 0x01).toByte(); p[2] = 0x00
            val left = pes.size - off
            var af = ByteArray(0)
            if (first) {
                // Champ d'adaptation : PCR (+ accès aléatoire sur une image clé).
                af = byteArrayOf(7, (0x10 or if (key) 0x40 else 0).toByte(),
                    (pcr ushr 25).toByte(), (pcr ushr 17).toByte(), (pcr ushr 9).toByte(), (pcr ushr 1).toByte(),
                    (((pcr and 1) shl 7).toInt() or 0x7E).toByte(), 0)
            }
            var room = 184 - af.size
            if (left < room) {
                // Bourrage dans le champ d'adaptation pour remplir le dernier paquet.
                val stuff = room - left
                af = if (af.isEmpty()) {
                    if (stuff == 1) byteArrayOf(0) else byteArrayOf((stuff - 1).toByte(), 0) + ByteArray(stuff - 2) { 0xFF.toByte() }
                } else {
                    byteArrayOf((af[0] + stuff).toByte()) + af.copyOfRange(1, af.size) + ByteArray(stuff) { 0xFF.toByte() }
                }
                room = left
            }
            p[3] = ((if (af.isNotEmpty()) 0x30 else 0x10) or next(0x100)).toByte()
            System.arraycopy(af, 0, p, 4, af.size)
            System.arraycopy(pes, off, p, 4 + af.size, room)
            off += room
            first = false
            out.write(p)
        }
        return out.toByteArray()
    }

    private fun next(pid: Int): Int { val c = cc[pid]; cc[pid] = (c + 1) and 0x0F; return c }

    private fun crc32(data: ByteArray): Int {
        var crc = -1
        for (b in data) {
            crc = crc xor ((b.toInt() and 0xFF) shl 24)
            repeat(8) { crc = if (crc and Int.MIN_VALUE != 0) (crc shl 1) xor 0x04C11DB7 else crc shl 1 }
        }
        return crc
    }
}
