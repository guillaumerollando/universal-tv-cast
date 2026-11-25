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
import kotlinx.coroutines.async
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
import studio.rollando.castor.subs.SubtitleFinder
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.Executors

enum class Phase { IDLE, LOADING, SENDING, BUFFERING, PLAYING, PAUSED, STOPPED }

/** Album photo : les photos choisies, celle affichée, et le diaporama automatique. */
data class Slides(val uris: List<String>, val index: Int = 0, val auto: Boolean = false, val videos: Set<Int> = emptySet()) {
    val isVideo: Boolean get() = index in videos
}

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
    /** Album photo en cours d'affichage sur la télé. */
    val slides: Slides? = null,
    /** Décalage des sous-titres en secondes (positif : ils apparaissent plus tard). */
    val subDelay: Double = 0.0,
    /** La télé affiche l'écran d'attente de l'appli (fin de vidéo, arrêt). */
    val idle: Boolean = false,
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
        CastControl.init(app)
        CastControl.onDevices = { list -> castDevices = list; publish(allDevices()) }
        CastControl.onFinished = { onMediaEnded() }
    }

    @Volatile private var dlnaDevices: List<Renderer> = emptyList()
    @Volatile private var castDevices: List<Renderer> = emptyList()
    /**
     * Une seule entrée par télé : si elle a Cast et DLNA (même adresse IP), on garde Cast — il affiche
     * les sous-titres — avec le DLNA en secours.
     */
    private fun allDevices(): List<Renderer> {
        val dlna = dlnaDevices
        val merged = castDevices.map { c ->
            val twin = dlna.firstOrNull { it.host.isNotEmpty() && it.host == c.host }
            if (twin == null) c else c.copy(name = twin.name, fallback = twin)
        }
        val twins = merged.mapNotNull { it.fallback?.udn }.toSet()
        return merged + dlna.filter { it.udn !in twins }
    }

    /** L'appareil réellement piloté (l'entrée DLNA de secours si Cast a été abandonné pour cette vidéo). */
    @Volatile private var via: Renderer? = null
    private fun target(): Renderer? = via ?: _state.value.renderer

    /** Vrai pendant qu'une commande est envoyée à la télé : on ne l'interroge pas en même temps. */
    @Volatile private var busy = false

    private fun exec(block: suspend () -> Unit) = scope.launch(cmd) {
        busy = true
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("Castor", "command failed", e)
            fail(e)
        } finally {
            busy = false
        }
    }

    private fun fail(e: Exception) {
        val n = when (e) {
            is NoTv -> Notice(R.string.notice_pick_tv, error = true)
            is NoWifi -> Notice(R.string.err_no_wifi, error = true)
            is Drm -> Notice(R.string.err_drm, error = true)
            is TvError -> Notice(R.string.err_tv, e.detail, true)
            is studio.rollando.castor.dlna.TvBusy -> Notice(R.string.err_tv_busy, error = true)
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
        CastControl.scan(active = true)
        scope.launch {
            val found = mutableListOf<Renderer>()
            try {
                Dlna.discover(app, Library.knownTvs) { r ->
                    Library.rememberTv(r.location)
                    synchronized(found) { if (found.none { it.udn == r.udn }) found += r }
                    dlnaDevices = synchronized(found) { found.toList() }
                    publish(allDevices())
                }
            } catch (e: Exception) {
                Log.w("Castor", "discovery", e)
            }
            dlnaDevices = synchronized(found) { found.toList() }
            publish(allDevices())
            CastControl.scan(active = false)
            _state.update { it.copy(searching = false) }
            if (pendingCast && _state.value.renderer != null && _state.value.media != null) {
                pendingCast = false
                exec { castAt(_state.value.media?.startSec ?: 0.0) }
            }
            if (pendingPhotos && _state.value.renderer != null && _state.value.slides != null) {
                pendingPhotos = false
                exec { showItem(_state.value.slides?.index ?: 0) }
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
            dlnaDevices = dlnaDevices.filter { it.udn != r.udn } + r
            publish(allDevices())
            chooseTv(r)
        }
    }

    private fun publish(list: List<Renderer>) {
        val sorted = list.sortedBy { it.name.lowercase() }
        _state.update { s ->
            fun matches(r: Renderer, udn: String?) = udn != null && (r.udn == udn || r.fallback?.udn == udn)
            val keep = s.renderer?.let { cur -> sorted.firstOrNull { matches(it, cur.udn) } ?: cur }
            val sel = keep ?: sorted.firstOrNull { matches(it, Library.lastTv) } ?: sorted.singleOrNull()
            s.copy(renderers = sorted, renderer = sel)
        }
    }

    /**
     * Se déconnecter de la télé : on cesse de la piloter, on coupe le service et on oublie le choix,
     * pour pouvoir repartir de zéro. La télé reçoit un « Stop » (ou la fin de la session Cast) : elle quitte
     * l'écran d'attente et revient à sa dernière source, comme avant la diffusion.
     */
    fun disconnect() {
        // Libérer vraiment la télé : on arrête son lecteur (écran d'attente, photo ou vidéo), elle revient
        // d'elle-même à ce qu'elle affichait avant.
        val tv = target()
        if (tv != null && !tv.cast) scope.launch { runCatching { Dlna.stop(tv) } }
        pollJob?.cancel()
        seekJob?.cancel()
        slideJob?.cancel()
        _state.update { it.copy(slides = it.slides?.copy(auto = false), idle = false) }
        Library.lastTv = null
        pendingCast = false
        if (_state.value.renderer?.cast == true) CastControl.disconnect()
        stopStream()
        via = null
        CastService.stop(app)
        _state.update { it.copy(renderer = null, phase = Phase.IDLE, position = 0.0, volume = null, notice = null) }
    }

    fun chooseTv(r: Renderer) {
        Library.lastTv = r.udn
        val previous = _state.value.renderer
        _state.update { it.copy(renderer = r, volume = null) }
        if (previous?.udn != r.udn) {
            val s = _state.value
            if (s.active) exec {
                previous?.let { p -> if (p.cast) CastControl.disconnect() else runCatching { Dlna.stop(p) } }
                castAt(s.position)
            } else if (pendingCast && s.media != null) {
                pendingCast = false
                exec { castAt(_state.value.media?.startSec ?: 0.0) }
            }
        }
    }

    // ------------------------------------------------------------------ vidéo

    fun open(media: Media, autoCast: Boolean = true) = exec {
        pollJob?.cancel()
        slideJob?.cancel()
        stopStream()
        _state.update {
            it.copy(media = media, slides = null, phase = Phase.LOADING, isHls = false, splitAudio = false, subIndex = media.defaultSub,
                position = 0.0, duration = 0.0, notice = null, continuous = Library.continuousHls.value, subDelay = 0.0)
        }
        proxy.reset()
        remoteId = null
        hlsDuration = 0.0
        fmp4 = false
        if (!media.isLocal) {
            remoteId = proxy.register(media.url, media.headers)
            remoteId?.let { id -> scope.launch { runCatching { proxy.prefetchMeta(id) } } }
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
        }
        identify()
        _state.value.media?.let { Library.addRecent(it) }
        loadSubtitle()
        _state.update { it.copy(phase = Phase.IDLE) }
        if (autoCast) {
            if (_state.value.renderer != null) castAt(media.startSec)
            else {
                pendingCast = true
                _state.update { it.copy(notice = Notice(R.string.notice_pick_tv)) }
                discover()
            }
        }
    }

    /**
     * L'appli source n'a pas fourni de sous-titres (Stremio n'en transmet jamais) : on les cherche
     * d'après le nom du fichier, avant le premier envoi à la télé (5 s au plus).
     */
    private suspend fun identify() {
        val m = _state.value.media ?: return
        val name = m.fileName ?: return
        val size = remoteId?.let { id -> runCatching { proxy.prefetchMeta(id); proxy.totalOf(id) }.getOrNull() }
        val phoneLang = java.util.Locale.getDefault().language
        val preferred = listOf(phoneLang, "en").distinct()
        val result = kotlinx.coroutines.withTimeoutOrNull(5000) {
            kotlinx.coroutines.withContext(Dispatchers.IO) { SubtitleFinder.find(name, size, preferred) }
        }
        val nice = result?.name ?: SubtitleFinder.parse(name).display
        val keepSubs = m.subs.isNotEmpty() // l'appli source en a fourni : on les garde
        val counts = HashMap<String, Int>()
        val subs = if (keepSubs) m.subs else result?.subs.orEmpty().map { f ->
            val label = SubtitleFinder.label(f.lang)
            val n = (counts[label] ?: 0) + 1
            counts[label] = n
            SubSource(f.url, if (n == 1) label else "$label $n")
        }
        // Sélection automatique : la langue du téléphone, sinon l'anglais.
        val auto = if (keepSubs) _state.value.subIndex else preferred.firstNotNullOfOrNull { lang ->
            result?.subs?.indexOfFirst { SubtitleFinder.norm(it.lang) == lang }?.takeIf { it >= 0 }
        } ?: -1
        val titleIsFileName = m.title == name || m.title.contains('.') && !m.title.contains(' ')
        _state.update {
            it.copy(
                media = m.copy(
                    subs = subs, title = if (titleIsFileName) nice else m.title,
                    posterUrl = result?.art?.poster ?: m.posterUrl,
                    backdropUrl = result?.art?.backdrop ?: m.backdropUrl,
                    logoUrl = result?.art?.logo ?: m.logoUrl,
                ),
                subIndex = auto,
            )
        }
    }

    // ------------------------------------------------------------------ album (photos et vidéos)

    private var slideJob: Job? = null
    private var pendingPhotos = false
    @Volatile private var shownAt = 0L
    /** Photos déjà préparées (ou en préparation) pour la télé : la suivante s'affiche sans attendre. */
    private val jpegs = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.Deferred<android.graphics.Bitmap>>()

    private fun jpegAsync(uri: String) = jpegs.getOrPut(uri) {
        scope.async(Dispatchers.Default) { Photos.decode(app, Uri.parse(uri)) }
    }

    /** Diaporama en flux vidéo continu (pas d'écran noir entre les photos). */
    @Volatile private var stream: SlideStream? = null
    @Volatile private var streamOnTv = false
    /** La télé a refusé le flux pour cet album : on revient aux photos une par une. */
    @Volatile private var streamFailed = false

    private fun stopStream() {
        stream?.stop()
        stream = null
        proxy.slides = null
        streamOnTv = false
    }

    /** Ouvre le flux du diaporama sur la télé (une seule fois par album). */
    private suspend fun sendStreamToTv(chosen: Renderer, st: SlideStream): Renderer {
        val base = proxy.baseUrl()
        val title = app.getString(R.string.home_photos)
        if (chosen.cast) {
            kotlinx.coroutines.withContext(Dispatchers.IO) { st.awaitSegments(3, 8000) }
            try {
                CastControl.load(chosen, "$base/s/live.m3u8", "application/x-mpegurl", title, null, null, 0.0, live = true)
                // Le lecteur Cast peut refuser le flux un peu plus tard : on revient alors aux photos.
                scope.launch {
                    delay(7000)
                    if (stream === st && streamOnTv && CastControl.lastError() != null) {
                        Log.i("CastorSlides", "flux refusé par le lecteur Cast : photos une par une")
                        streamFailed = true
                        stopStream()
                        _state.value.slides?.let { sl -> exec { showItem(sl.index) } }
                    }
                }
                return chosen
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val fb = chosen.fallback ?: throw e
                CastControl.disconnect()
                return sendStreamToTv(fb, st)
            }
        }
        kotlinx.coroutines.withContext(Dispatchers.IO) { st.awaitSegments(1, 5000) }
        val url = "$base/s/live.ts"
        replaceMedia(chosen, url, Dlna.didl(title, url, "video/mpeg", null, false))
        Dlna.play(chosen)
        return chosen
    }

    /** Prépare les photos voisines pendant que celle-ci est affichée, et oublie les lointaines. */
    private fun prefetchAround(sl: Slides, idx: Int) {
        val n = sl.uris.size
        val near = listOf(1, -1, 2).map { (idx + it).mod(n) }.filter { it != idx && it !in sl.videos }
        near.forEach { jpegAsync(sl.uris[it]) }
        val keep = (near + idx).map { sl.uris[it] }.toSet()
        jpegs.keys.filter { it !in keep }.forEach { jpegs.remove(it) }
    }

    /** Affiche sur la télé les photos et vidéos choisies, une par une (diaporama possible). */
    fun openPhotos(uris: List<Uri>) {
        if (uris.isEmpty()) return
        pollJob?.cancel(); seekJob?.cancel(); slideJob?.cancel()
        jpegs.clear()
        stopStream()
        streamFailed = false
        val videos = uris.indices.filter { i ->
            val t = runCatching { app.contentResolver.getType(uris[i]) }.getOrNull().orEmpty()
            t.startsWith("video/") || (t.isEmpty() && Mime.isMedia(Mime.guess(uris[i].toString())))
        }.toSet()
        _state.update {
            it.copy(media = null, slides = Slides(uris.map(Uri::toString), videos = videos), phase = Phase.LOADING, notice = null,
                isHls = false, position = 0.0, duration = 0.0, idle = false, subDelay = 0.0)
        }
        exec { showItem(0) }
    }

    private suspend fun showItem(i: Int) {
        val sl = _state.value.slides ?: return
        val idx = i.mod(sl.uris.size)
        if (idx in sl.videos) playAlbumVideo(idx) else showPhoto(idx)
    }

    /** Pas de télé choisie : on lance la recherche, l'album s'affichera dès qu'elle est trouvée. */
    private fun needTv(): Boolean {
        if (_state.value.renderer != null) return false
        pendingPhotos = true
        _state.update { it.copy(phase = Phase.IDLE, notice = Notice(R.string.notice_pick_tv)) }
        discover()
        return true
    }

    private suspend fun showPhoto(idx: Int) {
        val sl = _state.value.slides ?: return
        if (needTv()) return
        val chosen = _state.value.renderer ?: return
        Net.localIp() ?: throw NoWifi()
        pollJob?.cancel()
        _state.update {
            it.copy(slides = it.slides?.copy(index = idx), media = null, phase = Phase.SENDING, idle = false,
                position = 0.0, duration = 0.0)
        }
        val uri = Uri.parse(sl.uris[idx])
        val bmp = try {
            jpegAsync(sl.uris[idx]).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            jpegs.remove(sl.uris[idx]); throw e
        }
        prefetchAround(sl, idx)

        // Diaporama en flux vidéo : la télé garde le même lecteur ouvert, la photo change par un fondu.
        if (!streamFailed && Library.smoothSlides.value) {
            val st = stream?.takeIf { it.alive } ?: SlideStream.start()?.also {
                stopStream(); stream = it; proxy.slides = it
            }
            if (st != null) {
                st.show(bmp)
                if (!streamOnTv) {
                    try {
                        val used = sendStreamToTv(chosen, st)
                        via = if (used !== chosen) used else null
                        streamOnTv = true
                        Log.i("CastorSlides", "diaporama en flux sur ${used.name} (${if (used.cast) "HLS Cast" else "MPEG-TS DLNA"})")
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.i("CastorSlides", "flux refusé (${e.message}) : photos une par une")
                        streamFailed = true
                        stopStream()
                    }
                }
                if (streamOnTv) {
                    shownAt = System.currentTimeMillis()
                    CastService.start(app)
                    _state.update { it.copy(phase = Phase.PLAYING) }
                    return
                }
            } else streamFailed = true
        }

        val jpeg = kotlinx.coroutines.withContext(Dispatchers.Default) { Photos.jpeg(bmp) }
        val imgId = proxy.registerImage(jpeg)
        val url = "${proxy.baseUrl()}/img/$imgId/photo.jpg"
        val name = Intake.nameOf(app, uri)
        var r = chosen
        if (chosen.cast) {
            try {
                CastControl.loadPhoto(chosen, url, name)
                r = chosen
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                r = chosen.fallback ?: throw e
                CastControl.disconnect()
            }
        }
        if (!r.cast) {
            showImageDlna(r, url, imgId, Dlna.didl(name, url, "image/jpeg", null, false))
        }
        via = if (r !== chosen) r else null
        shownAt = System.currentTimeMillis()
        CastService.start(app)
        _state.update { it.copy(phase = Phase.PLAYING) }
    }

    /**
     * Affiche une photo sur une télé DLNA et vérifie qu'elle la télécharge vraiment. Après une vidéo, certaines
     * télés (Philips Titan OS) ferment leur lecteur : la première commande peut se perdre pendant qu'il rouvre.
     * Sans téléchargement au bout de 4 s, on renvoie la photo une fois.
     */
    private suspend fun showImageDlna(r: Renderer, url: String, imgId: String, meta: String) {
        for (attempt in 0..1) {
            try {
                // Changement direct (une seule commande) ; sinon pause préalable, comme pour une vidéo.
                try {
                    Dlna.setUriQuick(r, url, meta)
                } catch (e: TvError) {
                    replaceMedia(r, url, meta)
                }
                runCatching { Dlna.playQuick(r) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: java.net.SocketTimeoutException) {
                Log.i("Castor", "la télé ne répond pas pour la photo (essai ${attempt + 1})")
                if (attempt == 1) throw e
                delay(800)
                continue
            }
            val end = System.currentTimeMillis() + 4000
            while (System.currentTimeMillis() < end && !proxy.imageFetched(imgId)) delay(150)
            if (proxy.imageFetched(imgId)) return
            Log.i("Castor", "photo non téléchargée par la télé : nouvel envoi")
            delay(500)
        }
    }

    /** Vidéo de l'album : lue en entier, puis l'album passe à l'élément suivant. */
    private suspend fun playAlbumVideo(idx: Int) {
        val sl = _state.value.slides ?: return
        if (needTv()) return
        pollJob?.cancel()
        val uri = Uri.parse(sl.uris[idx])
        stopStream()
        proxy.reset()
        remoteId = null; hlsDuration = 0.0; fmp4 = false; rawSubtitle = null
        val m = Media(url = uri.toString(), title = Intake.nameOf(app, uri), mime = app.contentResolver.getType(uri))
        _state.update {
            it.copy(slides = it.slides?.copy(index = idx), media = m, isHls = false, splitAudio = false, subIndex = -1,
                position = 0.0, duration = 0.0, subDelay = 0.0, idle = false)
        }
        prefetchAround(sl, idx)
        castAt(0.0)
    }

    /** Élément demandé (le dernier appui gagne) et vrai si son affichage attend déjà son tour. */
    @Volatile private var wantedIndex = 0
    @Volatile private var showQueued = false

    /**
     * Les appuis rapprochés sont regroupés : si la télé est lente, on n'empile pas les photos, seule la
     * dernière demandée est envoyée. Le téléphone affiche la nouvelle photo tout de suite.
     */
    fun photoAt(i: Int) {
        val sl = _state.value.slides ?: return
        wantedIndex = i.mod(sl.uris.size)
        _state.update { it.copy(slides = it.slides?.copy(index = wantedIndex)) }
        shownAt = System.currentTimeMillis()
        if (showQueued) return
        showQueued = true
        exec {
            showQueued = false
            showItem(wantedIndex)
        }
    }

    fun nextPhoto(delta: Int) {
        val sl = _state.value.slides ?: return
        photoAt((if (showQueued) wantedIndex else sl.index) + delta)
    }

    /** Diaporama : une photo toutes les 6 secondes ; une vidéo est lue jusqu'au bout. */
    fun toggleSlideshow() {
        val sl = _state.value.slides ?: return
        val on = !sl.auto
        _state.update { it.copy(slides = it.slides?.copy(auto = on)) }
        slideJob?.cancel()
        if (!on) return
        shownAt = System.currentTimeMillis()
        if (_state.value.idle || _state.value.phase == Phase.STOPPED) exec { showItem(sl.index + 1) }
        slideJob = scope.launch {
            while (isActive) {
                delay(500)
                val cur = _state.value.slides ?: break
                if (!cur.auto) break
                if (cur.isVideo || busy || _state.value.phase != Phase.PLAYING) continue
                if (System.currentTimeMillis() - shownAt < 6000) continue
                shownAt = System.currentTimeMillis()
                kotlinx.coroutines.withContext(cmd) { runCatching { showItem(cur.index + 1) } }
            }
        }
    }

    // ------------------------------------------------------------------ fin de lecture et écran d'attente

    @Volatile private var endHandled = -1L

    /**
     * La vidéo est arrivée au bout. Plutôt que de laisser la télé repartir sur sa dernière source (HDMI…),
     * on enchaîne : élément suivant de l'album, sinon l'écran d'attente de l'appli.
     */
    private fun onMediaEnded() {
        val s = _state.value
        if (s.idle || s.media == null) return
        val sl = s.slides
        if (sl != null && !sl.isVideo) return // une photo ne « finit » pas
        if (endHandled == castTime) return
        endHandled = castTime
        pollJob?.cancel()
        Log.i("Castor", "fin de lecture : ${if (sl != null) "élément suivant de l'album" else "écran d'attente"}")
        exec {
            val cur = _state.value.slides
            if (cur != null && (cur.auto || cur.index < cur.uris.size - 1)) {
                shownAt = System.currentTimeMillis()
                showItem(cur.index + 1)
            } else showIdle()
        }
    }

    /** Affiche l'écran d'attente de l'appli sur la télé (la télé reste sur le lecteur). */
    private suspend fun showIdle() {
        val r = target() ?: return
        stopStream()
        val jpeg = kotlinx.coroutines.withContext(Dispatchers.Default) { IdleScreen.jpeg(app) }
        proxy.putImage("idle", jpeg)
        val url = "${proxy.baseUrl()}/img/idle/attente.jpg"
        val name = app.getString(R.string.app_name)
        if (r.cast) CastControl.loadPhoto(r, url, name)
        else {
            replaceMedia(r, url, Dlna.didl(name, url, "image/jpeg", null, false))
            runCatching { Dlna.play(r) }
        }
        _state.update { it.copy(idle = true, phase = Phase.STOPPED) }
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

    /** Sous-titres choisis, avant décalage. */
    private var rawSubtitle: ByteArray? = null

    private fun applySubtitle() {
        proxy.subtitle = rawSubtitle?.let { Subtitles.shift(it, (_state.value.subDelay * 1000).toLong()) }
        subVersion++
    }

    private fun loadSubtitle() {
        proxy.subtitle = null
        rawSubtitle = null
        val s = _state.value
        val sub = s.media?.subs?.getOrNull(s.subIndex) ?: return
        try {
            val uri = Uri.parse(sub.uri)
            val bytes = if (uri.scheme == "http" || uri.scheme == "https") Http.fetch(sub.uri, s.media.headers).bytes
            else app.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: throw IOException("unreadable")
            rawSubtitle = Subtitles.toSrt(bytes)
            applySubtitle()
        } catch (e: Exception) {
            Log.w("Castor", "subtitle", e)
            _state.update { it.copy(subIndex = -1, notice = Notice(R.string.err_subtitle, error = true)) }
        }
    }

    private suspend fun castAt(start: Double, forceDlna: Boolean = false, paused: Boolean = false) {
        val chosen = _state.value.renderer ?: throw NoTv()
        val s = _state.value
        val m = s.media ?: return
        Net.localIp() ?: throw NoWifi()
        _state.update { it.copy(phase = Phase.SENDING, notice = null, idle = false) }
        val base = proxy.baseUrl()
        var r = chosen
        if (chosen.cast && !forceDlna) {
            try {
                via = null
                castViaGoogleCast(chosen, s, m, start, base, paused)
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val fb = chosen.fallback ?: throw e
                Log.i("CastorCast", "Cast a échoué (${e.message}) : repli sur le DLNA de ${fb.name}")
                CastControl.disconnect()
                r = fb
            }
        } else if (chosen.cast) {
            r = chosen.fallback ?: throw NoTv()
        }
        via = if (r !== chosen) r else null
        val tvMimes = sinksOf(r)
        // Une télé qui ne lit pas le HLS reçoit toujours le flux continu.
        val continuous = s.isHls && (s.continuous || !Dlna.accepts(tvMimes, "application/vnd.apple.mpegurl"))
        if (continuous != (s.isHls && s.continuous)) _state.update { it.copy(continuous = true) }
        // Télé DLNA qui ignore les sous-titres externes : on les intègre au MP4 (piste texte), sans réencodage.
        val embedUrl = embeddedUrl(m, s, continuous, base)
        val (url, rawMime) = when {
            embedUrl != null -> embedUrl to "video/mp4"
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
        // N'arrêter que si la télé lit déjà quelque chose : sur certaines télés (Philips Titan OS),
        // un « Stop » à vide ferme le lecteur et renvoie sur la dernière source (HDMI…).
        // Description minimale : une affiche (albumArtURI) faisait clignoter la Philips à chaque déplacement.
        replaceMedia(r, url, Dlna.didl(m.title, url, mime, subUrl, seekable = !continuous))
        playWhenReady(r)
        castTime = System.currentTimeMillis()
        CastService.start(app)
        _state.update { it.copy(phase = Phase.BUFFERING, position = start) }
        if (!continuous && start > 1) {
            // Reprendre au bon endroit : attendre que la télé lise vraiment, puis s'y placer (2 essais).
            holdPositionUntil = System.currentTimeMillis() + 20_000
            val end = System.currentTimeMillis() + 15_000
            while (System.currentTimeMillis() < end &&
                runCatching { Dlna.transportState(r) }.getOrNull() != "PLAYING"
            ) delay(400)
            delay(600)
            var ok = false
            for (attempt in 0 until 3) {
                ok = runCatching { Dlna.seek(r, start) }.isSuccess
                if (ok) break
                delay(1200)
                waitUntilSettled(r, 4000)
            }
            Log.i("Castor", "reprise à ${start.toInt()} s : ${if (ok) "faite" else "refusée par la télé"}")
            holdPositionUntil = System.currentTimeMillis() + 2000
        }
        if (paused) {
            // Réglage en cours (sous-titres) : la vidéo attend, en pause, au bon endroit.
            waitUntilSettled(r, 4000)
            runCatching { Dlna.pause(r) }.onSuccess { _state.update { it.copy(phase = Phase.PAUSED) } }
        }
        if (_state.value.volume == null) runCatching { Dlna.getVolume(r) }.onSuccess { v -> _state.update { it.copy(volume = v) } }
        startPolling()
    }

    private var embeddedKey: String? = null

    private fun embeddedUrl(m: Media, s: CastState, continuous: Boolean, base: String): String? {
        val srt = proxy.subtitle ?: return null
        if (continuous || s.isHls || !Library.embedSubs.value) return null
        val mp4 = Mime.ext(m.url) in setOf("mp4", "m4v", "mov") || Mime.ext(m.title) in setOf("mp4", "m4v", "mov") ||
            m.mime?.contains("mp4") == true || (m.isLocal && app.contentResolver.getType(Uri.parse(m.url))?.contains("mp4") == true)
        if (!mp4) return null
        val id = if (m.isLocal) proxy.registerLocal(Uri.parse(m.url)) else remoteId ?: return null
        val lang = m.subs.getOrNull(s.subIndex)?.let { langOf(it.name) } ?: "und"
        if (!proxy.prepareEmbedded(id, srt, lang)) return null
        embeddedKey = id
        return "$base/v/$id/video-st$subVersion.mp4"
    }

    /** Code de langue ISO 639-2 à partir du nom affiché (« Français » → fre). */
    private fun langOf(label: String): String = runCatching {
        java.util.Locale.getAvailableLocales().firstOrNull {
            it.getDisplayLanguage(java.util.Locale.getDefault()).equals(label.substringBefore(' '), true)
        }?.isO3Language
    }.getOrNull() ?: "und"

    /** Envoi vers un appareil Google Cast : vidéo par le relais, sous-titres en piste WebVTT, titre et affiche. */
    private suspend fun castViaGoogleCast(r: Renderer, s: CastState, m: Media, start: Double, base: String, paused: Boolean = false) {
        val (url, mime) = when {
            m.isLocal -> {
                val uri = Uri.parse(m.url)
                val type = app.contentResolver.getType(uri)?.takeIf { Mime.isMedia(it) } ?: m.mime ?: Mime.guess(m.title)
                "$base/l/${proxy.registerLocal(uri)}/media.${Mime.ext(m.title).ifEmpty { "mp4" }}" to type
            }
            s.isHls -> "$base/r/$remoteId/index.m3u8" to "application/x-mpegurl"
            else -> "$base/r/$remoteId/video.${Mime.ext(m.url).ifEmpty { "mp4" }}" to
                (m.mime?.takeIf { Mime.isMedia(it) && it != "video/*" } ?: Mime.guess(m.url))
        }
        proxy.subtitleUrl = null
        proxy.mimeOverride = null
        val sub = proxy.subtitle?.let {
            val chosen = m.subs.getOrNull(s.subIndex)
            CastControl.Sub("$base/sub/$subVersion/subtitles.vtt", chosen?.name ?: "Sous-titres", null)
        }
        startOffset = 0.0
        CastControl.load(r, url, mime, m.title, m.posterUrl, sub, start, live = s.isHls && hlsDuration <= 0.0, autoplay = !paused)
        castTime = System.currentTimeMillis()
        CastService.start(app)
        _state.update { it.copy(phase = if (paused) Phase.PAUSED else Phase.BUFFERING, position = start) }
        if (_state.value.volume == null) runCatching { CastControl.volume() }.onSuccess { v -> _state.update { it.copy(volume = v) } }
        startPolling()
    }

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = scope.launch {
            var stoppedTicks = 0
            var failures = 0
            var waitingSince = 0L
            var lastPlaying = 0.0 // dernière position vue en lecture, pour reconnaître la fin
            var sawPlaying = false // la télé a vraiment lu cette vidéo
            while (isActive) {
                delay(if (failures >= 2) 10_000 else 2000)
                if (busy) continue
                val r = target() ?: break
                try {
                    val rel: Double
                    val dur: Double
                    val t: String?
                    if (r.cast) {
                        val snap = CastControl.snapshot()
                        rel = snap.first; dur = snap.second; t = snap.third
                        if (CastControl.lastError() != null) {
                            val fb = r.fallback
                            if (fb != null) {
                                // Le lecteur Cast refuse ce format : on repasse en DLNA, au même endroit.
                                Log.i("CastorCast", "format refusé par Cast : repli sur le DLNA")
                                CastControl.disconnect()
                                val at = _state.value.position
                                exec { castAt(at, forceDlna = true) }
                                break
                            } else if (_state.value.notice?.res != R.string.err_cast_format) {
                                _state.update { it.copy(notice = Notice(R.string.err_cast_format, error = true)) }
                            }
                        }
                    } else {
                        val (p, d) = Dlna.position(r)
                        rel = p; dur = d; t = Dlna.transportState(r)
                    }
                    failures = 0
                    if (t == "PLAYING") sawPlaying = true
                    if (t == "PLAYING" && rel > 0) lastPlaying = startOffset + rel
                    val grace = System.currentTimeMillis() - castTime < 12_000
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
                            position = if (System.currentTimeMillis() < holdPositionUntil) s.position
                                else if (rel > 0 || t == "PLAYING") startOffset + rel else s.position,
                            duration = when {
                                hlsDuration > 0 -> hlsDuration
                                dur > 0 -> dur
                                else -> s.duration
                            },
                        )
                    }
                    // La télé attend des données qui ne viennent pas, d'une appli du téléphone (Stremio…) :
                    // Android l'a très probablement mise en veille.
                    val st = _state.value
                    if (st.phase == Phase.BUFFERING) {
                        if (waitingSince == 0L) waitingSince = System.currentTimeMillis()
                        if (System.currentTimeMillis() - waitingSince > 15_000 && st.media?.fromPhoneApp == true &&
                            st.notice?.res != R.string.notice_source_asleep
                        ) {
                            _state.update { it.copy(notice = Notice(R.string.notice_source_asleep, sourceLabel(st.media))) }
                        }
                    } else waitingSince = 0L
                    // Arrivée au bout (la télé s'arrête près de la fin) : écran d'attente ou suite de l'album.
                    // Dans un album, la télé qui s'arrête après avoir lu la vidéo = fin : on enchaîne, même si
                    // elle n'a jamais annoncé de durée. Sinon, il faut être près de la fin connue (ou sans durée).
                    val total = _state.value.duration
                    val stopped = t == "STOPPED" || t == "NO_MEDIA_PRESENT"
                    val inAlbum = _state.value.slides?.isVideo == true
                    if (stopped && sawPlaying && !busy &&
                        (inAlbum || total <= 0 || lastPlaying >= total - 20)
                    ) { onMediaEnded(); break }
                    // DLNA, durée connue : on passe à la suite juste avant la fin. Sinon la télé ferme son lecteur
                    // et repart sur sa dernière source (HDMI) avant qu'on lui envoie quoi que ce soit.
                    if (!r.cast && t == "PLAYING" && total > 5 && lastPlaying >= total - 2.5 && !busy &&
                        System.currentTimeMillis() >= holdPositionUntil
                    ) { onMediaEnded(); break }
                    if (_state.value.phase == Phase.STOPPED) {
                        if (++stoppedTicks >= 3) { CastService.stop(app); break }
                    } else stoppedTicks = 0
                } catch (_: Exception) {
                    failures++
                }
            }
        }
    }

    private fun sourceLabel(m: Media): String {
        val pkg = m.sourceApp ?: return app.getString(R.string.source_generic)
        return runCatching {
            val pm = app.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        }.getOrDefault(pkg)
    }

    private fun clockText(sec: Double): String {
        val t = sec.toLong()
        return if (t >= 3600) String.format(java.util.Locale.ROOT, "%d:%02d:%02d", t / 3600, (t / 60) % 60, t % 60)
        else String.format(java.util.Locale.ROOT, "%d:%02d", t / 60, t % 60)
    }

    // ------------------------------------------------------------------ commandes

    private fun sending() = _state.value.phase == Phase.SENDING || _state.value.phase == Phase.LOADING

    fun cast() { if (!sending()) exec { castAt(_state.value.media?.startSec ?: 0.0) } }

    /** Vrai si la vidéo passe par le DLNA de secours d'une télé Cast. */
    val usingFallback: Boolean get() = via != null

    /** Renvoie la vidéo par le DLNA de la télé (dépannage : format refusé par le lecteur Cast). */
    fun castWithDlna() {
        if (!sending()) exec {
            val at = if (_state.value.active) currentPosition() else _state.value.media?.startSec ?: 0.0
            if (_state.value.renderer?.cast == true) CastControl.disconnect()
            castAt(at, forceDlna = true)
        }
    }

    fun togglePlay() {
        pausedForPanel = false
        if (!sending()) exec { togglePlayNow() }
    }

    /** Vrai si l'appli a mis la vidéo en pause le temps d'un réglage (sous-titres). */
    @Volatile private var pausedForPanel = false

    /** On ouvre le réglage des sous-titres : la vidéo se met d'abord en pause, puis on règle tranquillement. */
    /**
     * Juste avant de changer les sous-titres : la vidéo se met en pause (une fois), le temps du réglage.
     * Ouvrir le panneau sans rien changer ne coupe pas la lecture.
     */
    private fun pauseForAdjust() {
        val s = _state.value
        // Seulement si la vidéo joue vraiment : pendant le chargement, la télé refuse Pause (erreur 701).
        if (pausedForPanel || s.slides != null || s.phase != Phase.PLAYING) return
        pausedForPanel = true
        exec {
            val r = target() ?: return@exec
            runCatching { if (r.cast) CastControl.pause(r) else Dlna.pause(r) }
                .onSuccess { _state.update { it.copy(phase = Phase.PAUSED) } }
        }
    }

    /** Réglage refermé : la lecture reprend si c'est l'appli qui l'avait mise en pause. */
    fun panelClosed() {
        if (!pausedForPanel) return
        pausedForPanel = false
        delayJob?.let { j -> if (j.isActive) { scope.launch { j.join(); exec { resumeIfPaused() } }; return } }
        exec { resumeIfPaused() }
    }

    private suspend fun resumeIfPaused() {
        if (_state.value.phase == Phase.PAUSED) togglePlayNow()
    }

    private suspend fun togglePlayNow() {
        val s = _state.value
        val r = target() ?: throw NoTv()
        when (s.phase) {
            Phase.PLAYING, Phase.BUFFERING -> {
                if (r.cast) CastControl.pause(r) else Dlna.pause(r)
                _state.update { it.copy(phase = Phase.PAUSED) }
            }
            Phase.PAUSED -> {
                if (r.cast) CastControl.play(r) else Dlna.play(r)
                _state.update { it.copy(phase = Phase.PLAYING) }
            }
            else -> castAt(if (s.phase == Phase.STOPPED && s.duration > 0 && s.position < s.duration - 5) s.position else 0.0)
        }
    }

    private var seekJob: Job? = null
    @Volatile private var holdPositionUntil = 0L

    /**
     * Les appuis rapprochés (±10 s, glissement) sont regroupés : la télé refuse un déplacement
     * reçu pendant qu'elle traite le précédent (erreur UPnP 710 sur les Philips Titan OS).
     */
    fun seekTo(seconds: Double, debounceMs: Long = 350) {
        val s = _state.value
        val target = seconds.coerceIn(0.0, if (s.duration > 0) s.duration - 1 else Double.MAX_VALUE)
        _state.update { it.copy(position = target) }
        holdPositionUntil = System.currentTimeMillis() + debounceMs + 4000
        seekJob?.cancel()
        seekJob = scope.launch {
            delay(debounceMs)
            exec { doSeek(target) }
        }
    }

    fun skip(delta: Double) = seekTo(_state.value.position + delta, debounceMs = 700)

    private suspend fun doSeek(target: Double) {
        val s = _state.value
        val r = target() ?: throw NoTv()
        if (r.cast) {
            CastControl.seek(r, target)
            holdPositionUntil = System.currentTimeMillis() + 1500
            return
        }
        if (s.isHls && s.continuous) { castAt(target); return }
        waitUntilSettled(r)
        try {
            Dlna.seek(r, target)
        } catch (e: TvError) {
            // Télé encore occupée : on attend qu'elle soit prête et on réessaie une fois.
            delay(800)
            waitUntilSettled(r)
            Dlna.seek(r, target)
        }
        castTime = System.currentTimeMillis()
        holdPositionUntil = System.currentTimeMillis() + 1500
    }

    /**
     * Play juste après un changement de vidéo : certaines télés répondent « 701 Transition not available »
     * tant qu'elles chargent encore. On attend qu'elles soient prêtes et on réessaie, au lieu d'échouer.
     */
    private suspend fun playWhenReady(r: Renderer) {
        repeat(3) { attempt ->
            try {
                Dlna.play(r)
                return
            } catch (e: TvError) {
                val st = runCatching { Dlna.transportState(r) }.getOrNull()
                if (st == "PLAYING") return
                if (attempt == 2) throw e
                Log.i("Castor", "télé pas prête pour Play (${e.detail}), nouvel essai")
                delay(700)
                waitUntilSettled(r, 4000)
            }
        }
    }

    /** Attend (au plus quelques secondes) que la télé ait fini de charger ou de se déplacer. */
    private suspend fun waitUntilSettled(r: Renderer, maxMs: Long = 4000) {
        val end = System.currentTimeMillis() + maxMs
        while (System.currentTimeMillis() < end) {
            val st = runCatching { Dlna.transportState(r) }.getOrNull()
            if (st != "TRANSITIONING") return
            delay(250)
        }
    }

    /**
     * Change la vidéo de la télé sans « Stop » : sur les Philips Titan OS, Stop ferme le lecteur
     * et renvoie sur la dernière source (HDMI…). La télé refuse un changement pendant la lecture
     * (« Transport locked ») mais l'accepte en pause.
     */
    private suspend fun replaceMedia(r: Renderer, url: String, meta: String) {
        val st = runCatching { Dlna.transportState(r) }.getOrNull()
        if (st == "PLAYING" || st == "TRANSITIONING") {
            waitUntilSettled(r, 3000)
            runCatching { Dlna.pause(r) }
            val end = System.currentTimeMillis() + 2000
            while (System.currentTimeMillis() < end &&
                runCatching { Dlna.transportState(r) }.getOrNull() !in setOf("PAUSED_PLAYBACK", "STOPPED", "NO_MEDIA_PRESENT")
            ) delay(200)
        }
        try {
            Dlna.setUri(r, url, meta)
        } catch (e: TvError) {
            // Dernier recours : certaines télés exigent vraiment un arrêt.
            runCatching { Dlna.stop(r) }
            delay(500)
            Dlna.setUri(r, url, meta)
        }
    }

    /**
     * Arrêter : la télé affiche l'écran d'attente de l'appli au lieu de quitter le lecteur (un vrai
     * « Stop » renvoie beaucoup de télés sur leur dernière source, HDMI…). « Se déconnecter » libère la télé.
     */
    fun stop() = exec {
        slideJob?.cancel()
        seekJob?.cancel()
        _state.update { it.copy(slides = it.slides?.copy(auto = false)) }
        pollJob?.cancel()
        val r = target()
        if (r != null && runCatching { showIdle() }.isFailure) {
            stopStream()
            if (r.cast) CastControl.stop(r) else runCatching { Dlna.stop(r) }
        }
        _state.update { it.copy(phase = Phase.STOPPED) }
        CastService.stop(app)
    }

    fun changeVolume(delta: Int) = exec {
        val r = target() ?: throw NoTv()
        val current = _state.value.volume ?: if (r.cast) CastControl.volume() else Dlna.getVolume(r)
        val v = (current + delta).coerceIn(0, 100)
        _state.update { it.copy(volume = v) }
        if (r.cast) CastControl.setVolume(v) else Dlna.setVolume(r, v)
    }

    fun setContinuous(on: Boolean) = exec {
        Library.setContinuous(on)
        val s = _state.value
        _state.update { it.copy(continuous = on) }
        if (s.active && s.isHls && s.continuous != on) castAt(s.position)
    }

    fun selectSubtitle(index: Int) {
        pauseForAdjust()
        selectSubtitleNow(index)
    }

    private fun selectSubtitleNow(index: Int) = exec {
        _state.update { it.copy(subIndex = index) }
        loadSubtitle()
        val s = _state.value
        if (s.active) castAt(currentPosition(), paused = pausedForPanel)
    }

    private var delayJob: Job? = null

    /**
     * Décale les sous-titres (±). Les appuis rapprochés sont regroupés : la télé ne recharge qu'une fois,
     * à l'endroit où elle en est.
     */
    fun shiftSubtitles(delta: Double) {
        val v = (Math.round((_state.value.subDelay + delta) * 10) / 10.0).coerceIn(-60.0, 60.0)
        if (v == _state.value.subDelay) return
        pauseForAdjust()
        _state.update { it.copy(subDelay = v) }
        delayJob?.cancel()
        delayJob = scope.launch {
            delay(1300)
            exec {
                applySubtitle()
                val s = _state.value
                if (s.active && rawSubtitle != null) castAt(currentPosition(), paused = pausedForPanel)
            }
        }
    }

    fun resetSubtitleDelay() = shiftSubtitles(-_state.value.subDelay)

    /** Position exacte de la lecture (le lecteur Cast la connaît à la seconde près). */
    private suspend fun currentPosition(): Double {
        val r = target()
        if (r?.cast == true) runCatching { CastControl.snapshot().first }.getOrNull()?.takeIf { it > 0 }?.let { return it }
        return _state.value.position
    }

    fun addSubtitle(uri: Uri, name: String) {
        pauseForAdjust()
        addSubtitleNow(uri, name)
    }

    private fun addSubtitleNow(uri: Uri, name: String) = exec {
        val m = _state.value.media ?: return@exec
        val subs = m.subs + SubSource(uri.toString(), name)
        _state.update { it.copy(media = m.copy(subs = subs), subIndex = subs.lastIndex) }
        loadSubtitle()
        val s = _state.value
        if (s.active && s.subIndex >= 0) castAt(currentPosition(), paused = pausedForPanel)
    }
}
