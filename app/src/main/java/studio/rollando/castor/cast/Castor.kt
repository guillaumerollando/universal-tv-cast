package studio.rollando.castor.cast

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.annotation.StringRes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import studio.rollando.castor.R
import studio.rollando.castor.data.Library
import studio.rollando.castor.dlna.Dlna
import studio.rollando.castor.dlna.Renderer
import studio.rollando.castor.dlna.TvError
import studio.rollando.castor.net.Http
import studio.rollando.castor.net.HttpError
import studio.rollando.castor.net.Mime
import studio.rollando.castor.net.Net
import studio.rollando.castor.proxy.Hls
import studio.rollando.castor.proxy.ProxyServer
import studio.rollando.castor.proxy.Subtitles
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.Executors

enum class Phase { IDLE, LOADING, SENDING, BUFFERING, PLAYING, PAUSED, STOPPED }

data class Notice(@StringRes val res: Int, val arg: String? = null, val error: Boolean = false)

data class CastState(
    val renderers: List<Renderer> = emptyList(),
    val renderer: Renderer? = null,
    val searching: Boolean = false,
    val media: Media? = null,
    val isHls: Boolean = false,
    val continuous: Boolean = true,
    val splitAudio: Boolean = false,
    val subIndex: Int = -1,
    val phase: Phase = Phase.IDLE,
    val position: Double = 0.0,
    val duration: Double = 0.0,
    val volume: Int? = null,
    val notice: Notice? = null,
) {
    val active: Boolean get() = phase in setOf(Phase.SENDING, Phase.BUFFERING, Phase.PLAYING, Phase.PAUSED)
}

private class NoTv : IOException("no tv")
private class NoWifi : IOException("no wifi")
private class Drm : IOException("drm")

/** Le chef d'orchestre : choix de la télé, préparation de la vidéo, commandes de lecture. */
object Castor {
    private lateinit var app: Context
    private val cmd = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(CastState())
    val state: StateFlow<CastState> = _state

    private val proxy by lazy { ProxyServer(app) }
    private var remoteId: String? = null
    private var hlsDuration = 0.0
    private var fmp4 = false
    private var startOffset = 0.0
    private var castTime = 0L
    private var subVersion = 0
    private var pollJob: Job? = null
    private var pendingCast = false
    private val sinks = java.util.concurrent.ConcurrentHashMap<String, List<String>>()

    private fun sinksOf(r: Renderer): List<String> =
        sinks.getOrPut(r.udn) { runCatching { Dlna.sinkMimes(r) }.getOrDefault(emptyList()) }

    fun init(ctx: Context) {
        if (::app.isInitialized) return
        app = ctx.applicationContext
        Library.init(app)
        _state.update { it.copy(continuous = Library.continuousHls.value) }
    }

    private fun exec(block: suspend () -> Unit) = scope.launch(cmd) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("Castor", "command failed", e)
            fail(e)
        }
    }

    private fun fail(e: Exception) {
        val n = when (e) {
            is NoTv -> Notice(R.string.notice_pick_tv, error = true)
            is NoWifi -> Notice(R.string.err_no_wifi, error = true)
            is Drm -> Notice(R.string.err_drm, error = true)
            is TvError -> Notice(R.string.err_tv, e.detail, true)
            is HttpError -> Notice(R.string.err_source, e.code.toString(), true)
            is UnknownHostException, is ConnectException, is SocketTimeoutException -> Notice(R.string.err_network, error = true)
            else -> Notice(R.string.err_generic, e.message ?: e.javaClass.simpleName, true)
        }
        _state.update {
            it.copy(notice = n, phase = if (it.phase == Phase.SENDING || it.phase == Phase.LOADING) Phase.IDLE else it.phase)
        }
    }

    fun dismissNotice() = _state.update { it.copy(notice = null) }

    // ------------------------------------------------------------------ télés

    fun discover() {
        if (_state.value.searching) return
        _state.update { it.copy(searching = true) }
        scope.launch {
            val found = mutableListOf<Renderer>()
            try {
                Dlna.discover(app, Library.knownTvs) { r ->
                    Library.rememberTv(r.location)
                    synchronized(found) { if (found.none { it.udn == r.udn }) found += r }
                    publish(synchronized(found) { found.toList() })
                }
            } catch (e: Exception) {
                Log.w("Castor", "discovery", e)
            }
            publish(synchronized(found) { found.toList() })
            _state.update { it.copy(searching = false) }
            if (pendingCast && _state.value.renderer != null && _state.value.media != null) {
                pendingCast = false
                exec { castAt(0.0) }
            }
        }
    }

    /** Ajout manuel d'une télé par son adresse IP. */
    fun addByIp(ip: String) {
        _state.update { it.copy(searching = true) }
        scope.launch {
            val r = runCatching { Dlna.probe(ip.trim()) }.getOrNull()
            _state.update { it.copy(searching = false) }
            if (r == null) {
                _state.update { it.copy(notice = Notice(R.string.err_ip_not_found, ip.trim(), true)) }
                return@launch
            }
            Library.rememberTv(r.location)
            publish((_state.value.renderers.filter { it.udn != r.udn } + r))
            chooseTv(r)
        }
    }

    private fun publish(list: List<Renderer>) {
        val sorted = list.sortedBy { it.name.lowercase() }
        _state.update { s ->
            val keep = s.renderer?.let { cur -> sorted.firstOrNull { it.udn == cur.udn } ?: cur }
            val sel = keep ?: sorted.firstOrNull { it.udn == Library.lastTv } ?: sorted.singleOrNull()
            s.copy(renderers = sorted, renderer = sel)
        }
    }

    fun chooseTv(r: Renderer) {
        Library.lastTv = r.udn
        val previous = _state.value.renderer
        _state.update { it.copy(renderer = r, volume = null) }
        if (previous?.udn != r.udn) {
            val s = _state.value
            if (s.active) exec {
                previous?.let { p -> runCatching { Dlna.stop(p) } }
                castAt(s.position)
            } else if (pendingCast && s.media != null) {
                pendingCast = false
                exec { castAt(0.0) }
            }
        }
    }

    // ------------------------------------------------------------------ vidéo

    fun open(media: Media, autoCast: Boolean = true) = exec {
        pollJob?.cancel()
        _state.update {
            it.copy(media = media, phase = Phase.LOADING, isHls = false, splitAudio = false, subIndex = media.defaultSub,
                position = 0.0, duration = 0.0, notice = null, continuous = Library.continuousHls.value)
        }
        proxy.reset()
        remoteId = null
        hlsDuration = 0.0
        fmp4 = false
        if (!media.isLocal) {
            remoteId = proxy.register(media.url, media.headers)
            val hls = media.mime?.contains("mpegurl", true) == true || Mime.ext(media.url) == "m3u8" || sniffHls(media)
            if (hls) {
                val plan = Hls.load(media.url, media.headers)
                if (plan.encryptedSample) throw Drm()
                hlsDuration = if (plan.endList) plan.duration else 0.0
                fmp4 = plan.fmp4
                _state.update {
                    it.copy(isHls = true, splitAudio = plan.splitAudio, duration = hlsDuration,
                        continuous = if (plan.splitAudio) false else it.continuous)
                }
            }
            Library.addRecent(media)
        }
        loadSubtitle()
        _state.update { it.copy(phase = Phase.IDLE) }
        if (autoCast) {
            if (_state.value.renderer != null) castAt(0.0)
            else {
                pendingCast = true
                _state.update { it.copy(notice = Notice(R.string.notice_pick_tv)) }
                discover()
            }
        }
    }

    private fun sniffHls(m: Media): Boolean = runCatching {
        val c = Http.open(m.url, m.headers, "bytes=0-63", connectTimeout = 8000)
        try {
            if (c.responseCode >= 400) return@runCatching false
            if (c.contentType?.contains("mpegurl", true) == true) return@runCatching true
            val buf = ByteArray(64)
            val n = c.inputStream.use { it.read(buf) }
            n > 0 && Hls.isPlaylist(buf.copyOf(n))
        } finally {
            c.disconnect()
        }
    }.getOrDefault(false)

    private fun loadSubtitle() {
        proxy.subtitle = null
        val s = _state.value
        val sub = s.media?.subs?.getOrNull(s.subIndex) ?: return
        try {
            val uri = Uri.parse(sub.uri)
            val bytes = if (uri.scheme == "http" || uri.scheme == "https") Http.fetch(sub.uri, s.media.headers).bytes
            else app.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: throw IOException("unreadable")
            proxy.subtitle = Subtitles.toSrt(bytes)
            subVersion++
        } catch (e: Exception) {
            Log.w("Castor", "subtitle", e)
            _state.update { it.copy(subIndex = -1, notice = Notice(R.string.err_subtitle, error = true)) }
        }
    }

    private suspend fun castAt(start: Double) {
        val r = _state.value.renderer ?: throw NoTv()
        val s = _state.value
        val m = s.media ?: return
        Net.localIp() ?: throw NoWifi()
        _state.update { it.copy(phase = Phase.SENDING, notice = null) }
        val base = proxy.baseUrl()
        val tvMimes = sinksOf(r)
        // Une télé qui ne lit pas le HLS reçoit toujours le flux continu.
        val continuous = s.isHls && (s.continuous || !Dlna.accepts(tvMimes, "application/vnd.apple.mpegurl"))
        if (continuous != (s.isHls && s.continuous)) _state.update { it.copy(continuous = true) }
        val (url, rawMime) = when {
            m.isLocal -> {
                val uri = Uri.parse(m.url)
                val type = app.contentResolver.getType(uri)?.takeIf { Mime.isMedia(it) } ?: m.mime ?: Mime.guess(m.title)
                "$base/l/${proxy.registerLocal(uri)}/media.${Mime.ext(m.title).ifEmpty { "mp4" }}" to type
            }
            continuous -> "$base/ts/$remoteId/stream.${if (fmp4) "mp4" else "ts"}?start=${start.toLong()}" to
                (if (fmp4) "video/mp4" else "video/mp2t")
            s.isHls -> "$base/r/$remoteId/index.m3u8" to "application/vnd.apple.mpegurl"
            else -> "$base/r/$remoteId/video.${Mime.ext(m.url).ifEmpty { "mp4" }}" to
                (m.mime?.takeIf { Mime.isMedia(it) } ?: Mime.guess(m.url))
        }
        val mime = Dlna.adaptMime(rawMime, tvMimes)
        proxy.mimeOverride = mime.takeIf { it != rawMime }
        val subUrl = proxy.subtitle?.let { "$base/sub/$subVersion/subtitles.srt" }
        proxy.subtitleUrl = subUrl
        startOffset = if (continuous) start.toLong().toDouble() else 0.0
        runCatching { Dlna.stop(r) }
        Dlna.setUri(r, url, Dlna.didl(m.title, url, mime, subUrl, seekable = !continuous))
        Dlna.play(r)
        castTime = System.currentTimeMillis()
        CastService.start(app)
        _state.update { it.copy(phase = Phase.BUFFERING, position = start) }
        if (!continuous && start > 1) {
            delay(2500)
            runCatching { Dlna.seek(r, start) }
        }
        if (_state.value.volume == null) runCatching { Dlna.getVolume(r) }.onSuccess { v -> _state.update { it.copy(volume = v) } }
        startPolling()
    }

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = scope.launch {
            var stoppedTicks = 0
            while (isActive) {
                delay(1000)
                val r = _state.value.renderer ?: break
                try {
                    val (rel, dur) = Dlna.position(r)
                    val t = Dlna.transportState(r)
                    val grace = System.currentTimeMillis() - castTime < 10_000
                    _state.update { s ->
                        if (!s.active) return@update s
                        val phase = when (t) {
                            "PLAYING" -> Phase.PLAYING
                            "PAUSED_PLAYBACK", "PAUSED_RECORDING" -> Phase.PAUSED
                            "TRANSITIONING" -> Phase.BUFFERING
                            "STOPPED", "NO_MEDIA_PRESENT" -> if (grace) s.phase else Phase.STOPPED
                            else -> s.phase
                        }
                        s.copy(
                            phase = phase,
                            position = if (rel > 0 || phase == Phase.PLAYING) startOffset + rel else s.position,
                            duration = when {
                                hlsDuration > 0 -> hlsDuration
                                dur > 0 -> dur
                                else -> s.duration
                            },
                        )
                    }
                    if (_state.value.phase == Phase.STOPPED) {
                        if (++stoppedTicks >= 3) { CastService.stop(app); break }
                    } else stoppedTicks = 0
                } catch (_: Exception) {
                }
            }
        }
    }

    // ------------------------------------------------------------------ commandes

    fun cast() = exec { castAt(0.0) }

    fun togglePlay() = exec {
        val s = _state.value
        val r = s.renderer ?: throw NoTv()
        when (s.phase) {
            Phase.PLAYING, Phase.BUFFERING -> { Dlna.pause(r); _state.update { it.copy(phase = Phase.PAUSED) } }
            Phase.PAUSED -> { Dlna.play(r); _state.update { it.copy(phase = Phase.PLAYING) } }
            else -> castAt(if (s.phase == Phase.STOPPED && s.duration > 0 && s.position < s.duration - 5) s.position else 0.0)
        }
    }

    fun seekTo(seconds: Double) = exec {
        val s = _state.value
        val r = s.renderer ?: throw NoTv()
        val target = seconds.coerceIn(0.0, if (s.duration > 0) s.duration - 1 else Double.MAX_VALUE)
        if (s.isHls && s.continuous) castAt(target)
        else {
            _state.update { it.copy(position = target) }
            Dlna.seek(r, target)
        }
    }

    fun skip(delta: Double) = seekTo(_state.value.position + delta)

    fun stop() = exec {
        pollJob?.cancel()
        _state.value.renderer?.let { r -> runCatching { Dlna.stop(r) } }
        _state.update { it.copy(phase = Phase.STOPPED) }
        CastService.stop(app)
    }

    fun changeVolume(delta: Int) = exec {
        val r = _state.value.renderer ?: throw NoTv()
        val current = _state.value.volume ?: Dlna.getVolume(r)
        val v = (current + delta).coerceIn(0, 100)
        _state.update { it.copy(volume = v) }
        Dlna.setVolume(r, v)
    }

    fun setContinuous(on: Boolean) = exec {
        Library.setContinuous(on)
        val s = _state.value
        _state.update { it.copy(continuous = on) }
        if (s.active && s.isHls && s.continuous != on) castAt(s.position)
    }

    fun selectSubtitle(index: Int) = exec {
        _state.update { it.copy(subIndex = index) }
        loadSubtitle()
        val s = _state.value
        if (s.active) castAt(s.position)
    }

    fun addSubtitle(uri: Uri, name: String) = exec {
        val m = _state.value.media ?: return@exec
        val subs = m.subs + SubSource(uri.toString(), name)
        _state.update { it.copy(media = m.copy(subs = subs), subIndex = subs.lastIndex) }
        loadSubtitle()
        val s = _state.value
        if (s.active && s.subIndex >= 0) castAt(s.position)
    }
}
