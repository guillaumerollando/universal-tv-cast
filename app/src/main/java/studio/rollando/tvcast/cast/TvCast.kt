package studio.rollando.tvcast.cast

import android.content.Context
import android.net.Uri
import android.util.Log
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
import studio.rollando.tvcast.R
import studio.rollando.tvcast.data.Library
import studio.rollando.tvcast.dlna.Dlna
import studio.rollando.tvcast.dlna.Renderer
import studio.rollando.tvcast.dlna.TvError
import studio.rollando.tvcast.net.Http
import studio.rollando.tvcast.net.HttpError
import studio.rollando.tvcast.net.Mime
import studio.rollando.tvcast.net.Net
import studio.rollando.tvcast.proxy.Hls
import studio.rollando.tvcast.proxy.ProxyServer
import studio.rollando.tvcast.proxy.Subtitles
import studio.rollando.tvcast.subs.SubtitleFinder
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.Executors

private val GENERIC_NAMES = setOf(
    "index", "master", "playlist", "manifest", "stream", "video", "chunklist", "main", "live", "prog_index",
    "media", "mono", "output", "file", "default", "hls", "play", "embed",
    // Mots qui ne désignent aucun film : sans ça, « film.mp4 » était pris pour un vrai film (affiche comprise).
    "film", "movie", "movies", "episode", "ep", "clip", "sample", "test", "trailer", "bande-annonce", "vod",
    "watch", "player", "source", "download", "telechargement", "fichier", "sans titre", "untitled", "new",
)

/**
 * Le chef d'orchestre : choix de la télé, préparation de la vidéo, commandes de lecture.
 * [app] est le contexte de l'application (pas d'un écran) : le garder ici ne retient aucune activité.
 */
@android.annotation.SuppressLint("StaticFieldLeak")
object TvCast {
    internal lateinit var app: Context
    internal val cmd = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    internal val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    internal val _state = MutableStateFlow(CastState())
    val state: StateFlow<CastState> = _state

    internal val proxy by lazy { ProxyServer(app) }
    private var remoteId: String? = null
    private var hlsDuration = 0.0
    private var fmp4 = false
    private var startOffset = 0.0
    private var castTime = 0L
    private var subVersion = 0
    internal var pollJob: Job? = null
    /**
     * Envoi demandé alors qu'aucune télé n'était trouvée : il part dès qu'elle apparaît, mais seulement dans les
     * 2 minutes. Plus tard, la télé retrouvée (rallumée, revenue sur le Wi-Fi) ne doit pas se lancer toute seule.
     */
    private var pendingSince = 0L
    private var pendingCast: Boolean
        get() = pendingSince > 0 && System.currentTimeMillis() - pendingSince < 120_000
        set(v) { pendingSince = if (v) System.currentTimeMillis() else 0L }
    private val sinks = java.util.concurrent.ConcurrentHashMap<String, List<String>>()

    private fun sinksOf(r: Renderer): List<String> =
        sinks.getOrPut(r.udn) { runCatching { Dlna.sinkMimes(r) }.getOrDefault(emptyList()) }

    fun init(ctx: Context) {
        if (::app.isInitialized) return
        app = ctx.applicationContext
        studio.rollando.tvcast.ui.Images.init(app)
        Library.init(app)
        studio.rollando.tvcast.data.IptvStore.init(app)
        studio.rollando.tvcast.data.Epg.init(app)
        studio.rollando.tvcast.data.Power.init(app)
        _state.update { it.copy(continuous = Library.continuousHls.value) }
        CastControl.init(app)
        CastControl.onDevices = { list -> castDevices = list; publish(allDevices()) }
        CastControl.onFinished = { onMediaEnded() }
        PhoneEngine.onStatus = { st -> if (_state.value.onPhone) onPhoneStatus(st) }
        // Avancement noté toutes les 15 s : « Reprendre » saura ce qui est fini.
        scope.launch {
            var last = 0L
            _state.collect { s ->
                val m = s.media ?: return@collect
                if (s.slides != null || s.live || s.duration <= 0 || s.position <= 0) return@collect
                val now = System.currentTimeMillis()
                if (now - last < 15_000) return@collect
                last = now
                Library.setProgress(m.url, s.position, s.duration)
            }
        }
        proxy.onSourceDenied = { _ ->
            if (!isChannel()) _state.update { it.copy(notice = Notice(R.string.err_protected, error = true)) }
        }
        // La source refuse la vidéo quand la télé la demande : inutile d'insister (la télé échouerait, on renverrait,
        // allers-retours sur l'écran) ; on arrête et on dit pourquoi.
        proxy.onSourceError = { code ->
            val s = _state.value
            // Seulement les refus nets (pas une panne passagère du site, 5xx, qui peut se rattraper).
            if (code in setOf(401, 403, 404, 410) && (s.active || s.phase == Phase.SENDING || s.phase == Phase.BUFFERING)) {
                Log.w("TvCast", "la source refuse la vidéo (erreur $code) : arrêt")
                pollJob?.cancel()
                val n = if (isChannel()) Notice(R.string.err_channel_denied, code.toString(), true)
                    else Notice(R.string.err_source, code.toString(), true)
                stop()
                scope.launch { delay(600); _state.update { it.copy(notice = n) } }
            }
        }
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
    @Volatile internal var via: Renderer? = null
    private fun target(): Renderer? = via ?: _state.value.renderer

    /** Vrai pendant qu'une commande est envoyée à la télé : on ne l'interroge pas en même temps. */
    @Volatile internal var busy = false

    /** Commande en cours (pour pouvoir l'annuler pendant un chargement). */
    @Volatile private var running: kotlinx.coroutines.Job? = null

    internal fun exec(block: suspend () -> Unit) = scope.launch(cmd) {
        running = coroutineContext[kotlinx.coroutines.Job]
        busy = true
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("TvCast", "command failed", e)
            fail(e)
        } finally {
            busy = false
        }
    }

    /** Une chaîne (liste IPTV, ou direct) plutôt qu'une vidéo de site. */
    private fun isChannel(): Boolean = _state.value.media?.channel != null || _state.value.live

    private fun fail(e: Exception) {
        val n = when (e) {
            is NoTv -> Notice(R.string.notice_pick_tv, error = true)
            is NoWifi -> Notice(R.string.err_no_wifi, error = true)
            is SourceGone -> Notice(R.string.err_source_app_closed, sourceLabel(_state.value.media ?: return), true)
            is SourceUnreachable -> Notice(R.string.err_source_unreachable, error = true)
            is Drm -> Notice(R.string.err_drm, error = true)
            is TvError -> Notice(R.string.err_tv, e.detail, true)
            is studio.rollando.tvcast.dlna.TvBusy -> Notice(R.string.err_tv_busy, error = true)
            is HttpError -> Notice(if (isChannel()) R.string.err_channel_denied else R.string.err_source, e.code.toString(), true)
            // Le téléphone a son Wi-Fi : c'est la source (le serveur de la chaîne) qui ne répond pas, pas le réseau.
            is UnknownHostException, is ConnectException, is SocketTimeoutException ->
                if (Net.localIp() == null) Notice(R.string.err_network, error = true) else Notice(R.string.err_source_unreachable, error = true)
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
            // Appareils qui ont répondu à la recherche du réseau : allumés (une télé en veille ne répond plus).
            val awake = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
            fun refresh() {
                dlnaDevices = synchronized(found) { found.map { it.copy(awake = it.host in awake) } }
                publish(allDevices())
            }
            try {
                Dlna.discover(app, Library.knownTvs, onAwake = { h -> if (awake.add(h)) refresh() }) { r ->
                    Library.rememberTv(r.location)
                    synchronized(found) { if (found.none { it.udn == r.udn }) found += r }
                    refresh()
                }
            } catch (e: Exception) {
                Log.w("TvCast", "discovery", e)
            }
            refresh()
            CastControl.scan(active = false)
            _state.update { it.copy(searching = false) }
            // Vidéo ouverte sans télé allumée en vue : elle se lit sur le téléphone, tout de suite.
            if (pendingAuto) {
                pendingAuto = false
                val s = _state.value
                if (s.renderer == null && s.media != null && !s.active && playOnTv == null) autoPhone()
            }
            // Télé voulue, mais aucune n'est allumée : pas de « Choisis une télé » inutile, la vidéo part sur le téléphone.
            if (pendingCast && _state.value.renderer == null) {
                val s = _state.value
                if (s.renderers.none { it.awake } && s.media != null && !s.active && !s.onPhone) { pendingCast = false; autoPhone() }
                else if (s.media != null) _state.update { it.copy(notice = Notice(R.string.notice_pick_tv)) }
            }
            if (pendingCast && _state.value.renderer != null && _state.value.media != null) {
                pendingCast = false
                exec { castAt(_state.value.media?.startSec ?: 0.0) }
            }
            if (Album.pendingPhotos && _state.value.renderer != null && _state.value.slides != null) {
                Album.pendingPhotos = false
                exec { Album.showItem(_state.value.slides?.index ?: 0) }
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
            // La télé en cours reste choisie si elle répond, si une vidéo y passe, ou si on vient de la choisir ;
            // sinon (éteinte), on ne l'affiche plus comme connectée. Choix automatique : seulement parmi les allumées.
            val keep = s.renderer?.let { cur -> sorted.firstOrNull { matches(it, cur.udn) } ?: cur }
                ?.takeIf { it.awake || s.active || it.udn == chosenNow }
            val awake = sorted.filter { it.awake }
            val sel = keep ?: awake.firstOrNull { matches(it, Library.lastTv) } ?: awake.singleOrNull()
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
        Album.slideJob?.cancel()
        _state.update { it.copy(slides = it.slides?.copy(auto = false), idle = false) }
        Library.lastTv = null
        pendingCast = false
        if (_state.value.renderer?.cast == true) CastControl.disconnect()
        Album.stopStream()
        via = null
        CastService.stop(app)
        _state.update { it.copy(renderer = null, phase = Phase.IDLE, position = 0.0, volume = null, notice = null) }
    }

    /** Télé choisie à la main pendant cette session (gardée même si elle ne répond pas à la recherche). */
    @Volatile private var chosenNow: String? = null
    /** Vidéo ouverte pendant la recherche des télés : décider télé ou téléphone une fois la recherche finie. */
    @Volatile private var pendingAuto = false

    fun chooseTv(r: Renderer) {
        chosenNow = r.udn
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

    /**
     * Où lire, choisi par l'utilisateur pendant cette session : la télé (true), ce téléphone (false), pas encore
     * choisi (null). Tant qu'il n'a pas choisi, rien ne part sur la télé : il pourrait couper ce qui y passe.
     */
    @Volatile private var playOnTv: Boolean? = null

    /** Met en pause la vidéo de la page ouverte dans le navigateur (branché par le navigateur). */
    @Volatile var pausePageMedia: (() -> Unit)? = null

    /**
     * @param toTv l'utilisateur a explicitement demandé la télé (« Caster sur la télé » depuis une autre appli).
     */
    fun open(media: Media, autoCast: Boolean = true, toTv: Boolean = false) = exec {
        if (toTv) { playOnTv = true; if (_state.value.onPhone) { PhoneEngine.stop(); _state.update { it.copy(onPhone = false) } } }
        pollJob?.cancel()
        Album.slideJob?.cancel()
        Album.stopStream()
        // Sur le téléphone, l'ancienne vidéo s'arrête tout de suite : elle ne doit pas continuer sous le nom de la
        // nouvelle pendant que celle-ci se prépare (elle passait pour une « vidéo de quelques secondes »).
        if (_state.value.onPhone) PhoneEngine.stop()
        _state.update {
            it.copy(media = media, slides = null, phase = Phase.LOADING, isHls = false, splitAudio = false, subIndex = media.defaultSub,
                position = 0.0, duration = 0.0, notice = null, continuous = Library.continuousHls.value, subDelay = 0.0,
                embeddedSubs = emptyList(), live = false, liveBehind = 0.0, liveWindow = 0.0, audioTracks = emptyList(), audioIndex = -1)
        }
        proxy.reset()
        remoteId = null
        hlsDuration = 0.0
        fmp4 = false
        if (!media.isLocal) {
            remoteId = proxy.register(media.url, media.headers, audio = media.audioUrl)
            remoteId?.let { id -> scope.launch { runCatching { proxy.prefetchMeta(id) } } }
            val hls = media.mime?.contains("mpegurl", true) == true || Mime.ext(media.url) == "m3u8" || MediaProbe.sniffHls(media)
            if (hls) {
                val plan = Hls.load(media.url, media.headers)
                if (plan.encryptedSample) throw Drm()
                // Langues et sous-titres de la vidéo (liste maîtresse) : à choisir, sur la télé comme sur le téléphone.
                if (plan.endList) runCatching { Hls.tracks(media.url, media.headers) }
                    .onFailure { Log.w("TvCast", "pistes de la liste : ${it.message}") }
                    .onSuccess { (a, t) -> Log.i("TvCast", "pistes : ${a.size} son, ${t.size} sous-titres") }
                    .getOrNull()?.let { (audio, subs) ->
                    val def = audio.indexOfFirst { it.default }.takeIf { it >= 0 } ?: if (audio.isNotEmpty()) 0 else -1
                    proxy.audioChoice = audio.getOrNull(def)?.uri
                    _state.update { st ->
                        val m = st.media ?: return@update st
                        val extra = subs.filter { t -> m.subs.none { it.uri == t.uri } }.map { SubSource(it.uri, it.name) }
                        st.copy(audioTracks = audio, audioIndex = def, media = m.copy(subs = m.subs + extra))
                    }
                }
                hlsDuration = if (plan.endList) plan.duration else 0.0
                fmp4 = plan.fmp4
                _state.update {
                    it.copy(isHls = true, splitAudio = plan.splitAudio, duration = hlsDuration, live = !plan.endList,
                        liveWindow = if (plan.endList) 0.0 else plan.duration,
                        continuous = if (plan.splitAudio) false else it.continuous)
                }
                // Titre lisible pour un direct ouvert par son lien (« index.m3u8 ») : le nom de la chaîne.
                val t = _state.value.media?.title.orEmpty()
                if (!plan.endList && (t.isBlank() || t.contains(".m3u8", true) || t.startsWith("http"))) {
                    val name = studio.rollando.tvcast.data.Channels.find("${media.url} ${media.pageUrl}")?.name ?: app.getString(R.string.video_live)
                    _state.update { it.copy(media = it.media?.copy(title = name)) }
                }
                // Direct sans image : le logo de la chaîne, s'il est dans une liste importée.
                if (!plan.endList && _state.value.media?.posterUrl == null) {
                    val m0 = _state.value.media
                    studio.rollando.tvcast.data.IptvStore.logoFor(m0?.channel ?: m0?.title, media.url)?.let { logo ->
                        _state.update { it.copy(media = it.media?.copy(posterUrl = logo)) }
                    }
                }
            }
        }
        val probe = scope.async { runCatching { MediaProbe.embeddedSubtitles(app, media) }.getOrDefault(emptyList()) }
        identify()
        val inVideo = kotlinx.coroutines.withTimeoutOrNull(3000) { probe.await() }.orEmpty()
        if (inVideo.isNotEmpty()) {
            // La vidéo a ses propres sous-titres (réglables sur la télé) : on n'en superpose pas d'autres.
            Log.i("TvCastSubs", "sous-titres déjà dans la vidéo : ${inVideo.joinToString()}")
            _state.update { it.copy(embeddedSubs = inVideo, subIndex = -1) }
        }
        _state.value.media?.let { Library.addRecent(it, live = _state.value.live) }
        loadSubtitle()
        _state.update { it.copy(phase = Phase.IDLE) }
        if (autoCast && playOnTv == null) {
            // Pas encore choisi : l'écran de lecture propose la télé ou ce téléphone ; sans télé allumée, téléphone.
            // Jamais d'attente : la recherche des télés tourne déjà en fond (démarrage, retour dans l'appli) ;
            // une télé qui apparaît se propose par le bouton « Regarder sur la télé ».
            val s = _state.value
            when {
                s.renderer != null -> Unit
                // Déjà sur le téléphone : on zappe sur place, sans rien rechercher.
                s.onPhone -> castAt(media.startSec)
                // Recherche en cours (appli tout juste ouverte) : on attend sa fin, quelques secondes au plus.
                s.searching -> pendingAuto = true
                else -> autoPhone()
            }
        } else if (autoCast) {
            if (_state.value.renderer != null || _state.value.onPhone) castAt(media.startSec)
            else if (!_state.value.searching && _state.value.renderers.none { it.awake }) {
                // Aucune télé allumée en vue : on vérifie le réseau, et sans télé la vidéo part sur le téléphone.
                pendingCast = true
                discover()
            } else {
                pendingCast = true
                if (!_state.value.searching) _state.update { it.copy(notice = Notice(R.string.notice_pick_tv)) }
                discover()
            }
        }
    }

    /**
     * L'appli source n'a pas fourni de sous-titres (Stremio n'en transmet jamais) : on les cherche
     * d'après le nom du fichier, avant le premier envoi à la télé (5 s au plus).
     */
    /** L'image de la page arrive après le lancement : la vidéo en cours venue de cette page en profite. */
    fun setPageImage(page: String, image: String) {
        val m0 = _state.value.media ?: return
        if (m0.pageUrl != page || m0.backdropUrl != null) return
        // Sur la vidéo telle qu'elle est maintenant (pas une copie prise avant : on perdrait titre et sous-titres).
        _state.update { st ->
            val m = st.media?.takeIf { it.pageUrl == page && it.backdropUrl == null } ?: return@update st
            st.copy(media = m.copy(posterUrl = m.posterUrl ?: image, backdropUrl = image))
        }
        Library.setRecentImage(page, image)
    }

    /**
     * « Regarder sur ce téléphone » : la télé s'arrête sur l'écran d'attente, le téléphone reprend au même endroit
     * (même moment du direct), dans l'écran de lecture de l'appli. Une seule lecture à la fois.
     */
    fun toPhone() {
        val s = _state.value
        val m = s.media ?: return
        if (s.slides != null || s.onPhone) return
        playOnTv = false
        val at = if (s.live) currentBehind() else if (s.active || s.position > 1) s.position else m.startSec
        pendingCast = false
        if (s.active || s.phase == Phase.LOADING || s.phase == Phase.SENDING) stop(keepRecording = true)
        exec {
            _state.update { it.copy(onPhone = true, notice = null) }
            castAt(at)
        }
    }

    /** Lecture sur le téléphone faute de télé allumée : ce n'est pas un choix, la question reviendra avec une télé. */
    private fun autoPhone() {
        val keep = playOnTv
        toPhone()
        playOnTv = keep
    }

    /** « Regarder sur la télé » depuis le téléphone : la télé reprend au même endroit. */
    fun toTv() {
        val s = _state.value
        if (!s.onPhone) return
        val pos = if (s.live) currentBehind() else PhoneEngine.position
        playOnTv = true
        PhoneEngine.stop()
        _state.update { it.copy(onPhone = false, phase = Phase.IDLE) }
        resumeOnTv(pos)
    }

    /** L'appli passe en arrière-plan : la lecture sur le téléphone se met en pause (pas de son caché). */
    fun appHidden() {
        val s = _state.value
        if (s.onPhone && (s.phase == Phase.PLAYING || s.phase == Phase.BUFFERING)) togglePlay()
    }

    /** Lance (ou relance, après un déplacement) la lecture sur le téléphone. */
    private fun phonePlay(start: Double) {
        val s = _state.value
        val m = s.media ?: return
        pollJob?.cancel()
        _state.update {
            it.copy(phase = Phase.SENDING, notice = null, idle = false,
                liveBehind = if (it.live) start else it.liveBehind, position = if (it.live) 0.0 else start)
        }
        // Direct : depuis l'enregistrement du téléphone (comme la télé), pour pouvoir mettre en pause et revenir.
        // Sauf son à part de l'image (CNews, Dailymotion) : l'enregistrement ne garde que l'image, le lecteur
        // attendait le son sans fin ; le lecteur d'Android lit alors la liste lui-même (son et image).
        val liveUrl = if (s.live && s.isHls && !s.splitAudio && m.audioUrl == null && remoteId != null)
            "${proxy.baseUrl()}/ts/$remoteId/stream.${if (fmp4) "mp4" else "ts"}?start=${start.toLong()}" else null
        if (s.live) liveClock.start(start)
        PhoneEngine.play(app, m, start, proxy.subtitle, s.isHls, liveUrl)
        s.audioTracks.getOrNull(s.audioIndex)?.let { PhoneEngine.selectAudio(it.name, it.lang) }
    }

    private fun onPhoneStatus(st: PhoneEngine.Status) {
        val s = _state.value
        // Nouvelle vidéo en préparation : un dernier état de l'ancienne ne compte plus.
        if (s.phase == Phase.LOADING) return
        if (st.error) {
            _state.update { it.copy(phase = Phase.STOPPED, notice = Notice(R.string.phone_error, error = true)) }
            return
        }
        if (st.ended && !s.live) s.media?.let { Library.markFinished(it.url) }
        val behind = if (s.live) liveClock.update(st.phase == Phase.PLAYING) else s.liveBehind
        val window = if (s.live) liveRecording()?.second ?: s.liveWindow else s.liveWindow
        _state.update {
            it.copy(
                phase = if (it.phase == Phase.SENDING && st.phase == Phase.IDLE) it.phase else st.phase,
                position = if (it.live) 0.0 else shownPosition(st.position, it.position),
                duration = if (it.live) 0.0 else if (st.duration > 0) st.duration else it.duration,
                liveBehind = behind, liveWindow = window,
            )
        }
    }

    /** Retour du téléphone vers la télé : la vidéo y reprend là où le téléphone s'est arrêté. */
    fun resumeOnTv(pos: Double) {
        val m = _state.value.media ?: return
        _state.update { it.copy(media = m.copy(startSec = pos), position = pos) }
        if (_state.value.renderer == null) {
            pendingCast = true
            if (_state.value.renderers.any { it.awake }) _state.update { it.copy(notice = Notice(R.string.notice_pick_tv)) }
            discover()
            return
        }
        // Direct : [pos] est le retard du téléphone sur le direct ; la télé reprend avec le même.
        if (!sending()) exec { castAt(pos) }
    }

    private suspend fun identify() {
        val m = _state.value.media ?: return
        val name = m.fileName ?: return
        // Nom de fichier générique (« index.m3u8 », « master.m3u8 »…) ou direct : il ne dit rien du film, et
        // chercher des sous-titres trouverait n'importe quel film du même nom.
        val base = name.substringAfterLast('/').substringBeforeLast('.').lowercase()
        // Identifiant technique (« 130430-001-A », plein de chiffres) : il ne dit rien du film ; chercher un film à ce
        // nom en trouverait un au hasard (« série »). Le titre de la page reste.
        val technical = base.count { it.isDigit() } >= 5
        if (base in GENERIC_NAMES || technical || (_state.value.isHls && hlsDuration <= 0.0)) {
            // Titre lisible à la place de « index.m3u8 » : « Direct » ou « Vidéo ».
            if (m.title == name || m.title.substringAfterLast('/') == name) _state.update {
                val live = it.isHls && hlsDuration <= 0.0
                // Chaîne connue (Arte, France 24…) : son nom ; sinon « Direct » / « Vidéo ».
                val known = if (live) studio.rollando.tvcast.data.Channels.find("${m.url} ${m.pageUrl}")?.name else null
                it.copy(media = m.copy(title = known ?: app.getString(if (live) R.string.video_live else R.string.video_generic)))
            }
            return
        }
        val size = remoteId?.let { id -> runCatching { proxy.prefetchMeta(id); proxy.totalOf(id) }.getOrNull() }
        val phoneLang = java.util.Locale.getDefault().language
        val preferred = listOf(phoneLang, "en").distinct()
        val result = kotlinx.coroutines.withTimeoutOrNull(5000) {
            kotlinx.coroutines.withContext(Dispatchers.IO) { SubtitleFinder.find(name, size, preferred) }
        }
        val parsed = SubtitleFinder.parse(name)
        val nice = result?.name ?: parsed.display
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
                media = (it.media ?: m).copy(
                    subs = subs, title = if (titleIsFileName) nice else m.title,
                    posterUrl = result?.art?.poster ?: m.posterUrl,
                    backdropUrl = result?.art?.backdrop ?: m.backdropUrl,
                    logoUrl = result?.art?.logo ?: m.logoUrl,
                    season = if (parsed.isSeries) parsed.season else null,
                    episode = if (parsed.isSeries) parsed.episode else null,
                ),
                subIndex = auto,
            )
        }
    }

    // ------------------------------------------------------------------ album (photos et vidéos) : voir Album.kt

    fun openPhotos(uris: List<Uri>) = Album.open(uris)
    fun photoAt(i: Int) = Album.photoAt(i)
    fun nextPhoto(delta: Int) = Album.nextPhoto(delta)
    fun toggleSlideshow() = Album.toggleSlideshow()

    /** Nouvelle vidéo : on oublie tout ce qui concernait la précédente côté relais. */
    internal fun forgetSource() { remoteId = null; hlsDuration = 0.0; fmp4 = false; rawSubtitle = null }

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
        if (sl == null) Library.markFinished(s.media.url)
        Log.i("TvCast", "fin de lecture : ${if (sl != null) "élément suivant de l'album" else "écran d'attente"}")
        exec {
            val cur = _state.value.slides
            if (cur != null && (cur.auto || cur.index < cur.uris.size - 1)) {
                Album.shownAt = System.currentTimeMillis()
                Album.showItem(cur.index + 1)
            } else showIdle()
        }
    }

    /** Affiche l'écran d'attente de l'appli sur la télé (la télé reste sur le lecteur). */
    private suspend fun showIdle() {
        val r = target() ?: return
        Album.stopStream()
        val jpeg = kotlinx.coroutines.withContext(Dispatchers.Default) { IdleScreen.jpeg(app) }
        proxy.putImage("idle", jpeg)
        val url = "${proxy.baseUrl()}/img/idle/attente.jpg"
        val name = app.getString(R.string.app_name)
        if (r.cast) CastControl.loadPhoto(r, url, name)
        else {
            TvSteps.replaceMedia(r, url, Dlna.didl(name, url, "image/jpeg", null, false))
            runCatching { Dlna.play(r) }
        }
        _state.update { it.copy(idle = true, phase = Phase.STOPPED) }
    }

    /** Sous-titres choisis, avant décalage. */
    // ------------------------------------------------------------------ sous-titres (chargement)

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
            Log.w("TvCast", "subtitle", e)
            _state.update { it.copy(subIndex = -1, notice = Notice(R.string.err_subtitle, error = true)) }
        }
    }

    /** Direct : un renvoi automatique a déjà été tenté pour ce cast. */
    @Volatile private var liveRetried = false
    /** La vidéo a déjà été renvoyée parce que la télé n'avait pas démarré. */
    @Volatile private var startRetried = false
    /** Position demandée au dernier envoi (pour le renvoyer à l'identique). */
    @Volatile private var lastCastStart = 0.0

    internal suspend fun castAt(start: Double, forceDlna: Boolean = false, paused: Boolean = false, keepRetry: Boolean = false) {
        // La vidéo part ailleurs : le lecteur de la page ne continue pas derrière.
        pausePageMedia?.invoke()
        // Lecture sur le téléphone : même demande (reprise, déplacement dans le direct, chaîne suivante…), autre écran.
        if (_state.value.onPhone) { phonePlay(start); return }
        if (!keepRetry) { liveRetried = false; startRetried = false }
        lastCastStart = start
        val chosen = _state.value.renderer ?: throw NoTv()
        val s = _state.value
        val m = s.media ?: return
        Net.localIp() ?: throw NoWifi()
        // Vidéo servie par une autre appli du téléphone (Stremio…) : si elle a été fermée, rien ne sert d'envoyer
        // (la télé tenterait, échouerait, et on renverrait) ; on le dit tout de suite.
        pendingPause = false
        livePausedAt = 0L
        // Direct : « start » est le retard voulu sur le direct.
        _state.update { it.copy(phase = Phase.SENDING, notice = null, idle = false, liveBehind = if (it.live) start else it.liveBehind) }
        if (_state.value.live) liveClock.start(start)
        SourceCheck.verify(m, _state.value.isHls)
        val base = proxy.baseUrl()
        var r = chosen
        // Cast ne lit pas notre flux fusionné : on passe par le DLNA de la télé si elle en a un.
        if (chosen.cast && !forceDlna && !(m.audioUrl != null && chosen.fallback != null)) {
            try {
                via = null
                castViaGoogleCast(chosen, s, m, start, base, paused)
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val fb = chosen.fallback ?: throw e
                Log.i("TvCastCast", "Cast a échoué (${e.message}) : repli sur le DLNA de ${fb.name}")
                CastControl.disconnect()
                r = fb
            }
        } else if (chosen.cast) {
            r = chosen.fallback ?: throw NoTv()
        }
        via = if (r !== chosen) r else null
        val tvMimes = sinksOf(r)
        // Une télé qui ne lit pas le HLS reçoit toujours le flux continu.
        // Image et son séparés : seul le flux continu (fusionné par le relais) les réunit.
        val continuous = s.isHls && (s.continuous || m.audioUrl != null || !Dlna.accepts(tvMimes, "application/vnd.apple.mpegurl"))
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
        TvSteps.replaceMedia(r, url, Dlna.didl(m.title, url, mime, subUrl, seekable = !continuous))
        TvSteps.playWhenReady(r)
        castTime = System.currentTimeMillis()
        CastService.start(app)
        _state.update { it.copy(phase = Phase.BUFFERING, position = start) }
        if (!continuous && start > 1) {
            // Reprendre au bon endroit : attendre que la télé lise vraiment, puis s'y placer (2 essais).
            holdPositionUntil = System.currentTimeMillis() + 20_000
            seekTarget = start
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
                TvSteps.waitUntilSettled(r, 4000)
            }
            Log.i("TvCast", "reprise à ${start.toInt()} s : ${if (ok) "faite" else "refusée par la télé"}")
            holdPositionUntil = System.currentTimeMillis() + 2000
        }
        if (paused) {
            // Réglage en cours (sous-titres) : la vidéo attend, en pause, au bon endroit.
            TvSteps.waitUntilSettled(r, 4000)
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
                delay(if (failures >= 2) 10_000 else if (_state.value.live && !studio.rollando.tvcast.data.Power.saving.value) 1000 else 2000)
                if (busy) continue
                val r = target() ?: break
                // Direct : retard réel de la télé et plage enregistrée, d'après l'enregistrement du téléphone.
                if (_state.value.live) {
                    val b = liveClock.update(_state.value.phase == Phase.PLAYING)
                    val w = liveRecording()?.second
                    _state.update { it.copy(liveBehind = b, liveWindow = w ?: it.liveWindow) }
                }
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
                                Log.i("TvCastCast", "format refusé par Cast : repli sur le DLNA")
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
                    if (pendingPause && !r.cast && t == "PLAYING" && !busy) {
                        runCatching { Dlna.pause(r) }.onSuccess { pendingPause = false }
                    }
                    if (t == "PLAYING" && rel > 0) lastPlaying = startOffset + rel
                    val grace = System.currentTimeMillis() - castTime < 12_000
                    _state.update { s ->
                        if (!s.active) return@update s
                        val phase = if (pendingPause) Phase.PAUSED else when (t) {
                            "PLAYING" -> Phase.PLAYING
                            "PAUSED_PLAYBACK", "PAUSED_RECORDING" -> Phase.PAUSED
                            "TRANSITIONING" -> Phase.BUFFERING
                            "STOPPED", "NO_MEDIA_PRESENT" -> if (grace) s.phase else Phase.STOPPED
                            else -> s.phase
                        }
                        s.copy(
                            phase = phase,
                            position = if (rel > 0 || t == "PLAYING") shownPosition(startOffset + rel, s.position) else s.position,
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
                    // La télé n'a toujours pas démarré : souvent une télé en veille (elle répond sur le réseau, accepte
                    // la vidéo, mais reste « en transition » sans rien afficher). On le dit, sans rien couper.
                    if (!sawPlaying && !r.cast && System.currentTimeMillis() - castTime > 20_000 &&
                        (st.phase == Phase.BUFFERING || st.phase == Phase.SENDING) && st.notice == null
                    ) {
                        _state.update { it.copy(notice = Notice(R.string.notice_tv_not_starting, error = true)) }
                    }
                    if (sawPlaying && _state.value.notice?.res == R.string.notice_tv_not_starting) {
                        _state.update { it.copy(notice = null) }
                    }
                    // Arrivée au bout (la télé s'arrête près de la fin) : écran d'attente ou suite de l'album.
                    // Dans un album, la télé qui s'arrête après avoir lu la vidéo = fin : on enchaîne, même si
                    // elle n'a jamais annoncé de durée. Sinon, il faut être près de la fin connue (ou sans durée).
                    val total = _state.value.duration
                    val stopped = t == "STOPPED" || t == "NO_MEDIA_PRESENT"
                    val inAlbum = _state.value.slides?.isVideo == true
                    // Un direct ne finit jamais : si la télé l'arrête, on le renvoie une fois ; si elle l'arrête
                    // encore, on le dit (au lieu de basculer en silence sur l'écran d'attente).
                    if (stopped && sawPlaying && !busy && _state.value.live && !inAlbum) {
                        Log.w("TvCast", "la télé a arrêté le direct (nouvel essai : ${!liveRetried})")
                        if (!liveRetried) {
                            liveRetried = true
                            val back = _state.value.liveBehind
                            exec { castAt(back, keepRetry = true) }
                        } else _state.update { it.copy(phase = Phase.STOPPED, notice = Notice(R.string.err_live_stopped, error = true)) }
                        break
                    }
                    if (stopped && sawPlaying && !busy &&
                        (inAlbum || total <= 0 || lastPlaying >= total - 20)
                    ) { onMediaEnded(); break }
                    // DLNA, durée connue : on passe à la suite juste avant la fin. Sinon la télé ferme son lecteur
                    // et repart sur sa dernière source (HDMI) avant qu'on lui envoie quoi que ce soit.
                    // (Jamais pour un direct : certaines télés lui donnent une durée qui ne veut rien dire.)
                    if (!r.cast && !_state.value.live && t == "PLAYING" && total > 5 && lastPlaying >= total - 2.5 && !busy &&
                        System.currentTimeMillis() >= holdPositionUntil
                    ) { onMediaEnded(); break }
                    // La télé a abandonné sans jamais lire (la Philips rate parfois le premier envoi, surtout en
                    // passant de l'écran d'attente au lecteur vidéo) : on renvoie une fois, comme on le ferait à la main.
                    if (stopped && !sawPlaying && !busy && !grace && !startRetried && !r.cast) {
                        startRetried = true
                        Log.w("TvCast", "la télé n'a pas démarré la lecture : nouvel envoi")
                        val again = lastCastStart
                        exec { castAt(again, keepRetry = true) }
                        break
                    }
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


    // ------------------------------------------------------------------ commandes

    private fun sending() = _state.value.phase == Phase.SENDING || _state.value.phase == Phase.LOADING

    /** « Choisir une télé » depuis l'écran de lecture : la vidéo y partira dès la télé choisie. */
    fun castWhenTvChosen() {
        playOnTv = true
        if (_state.value.media != null) pendingCast = true
    }

    fun cast() {
        if (sending()) return
        if (!_state.value.onPhone) playOnTv = true
        exec { castAt(_state.value.media?.startSec ?: 0.0) }
    }

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
        if (s.onPhone) {
            when (s.phase) {
                Phase.PLAYING, Phase.BUFFERING -> { if (s.live) liveClock.update(true); PhoneEngine.pause() }
                Phase.PAUSED -> { if (s.live) liveClock.update(false); PhoneEngine.resume() }
                else -> castAt(if (s.phase == Phase.STOPPED && s.duration > 0 && s.position < s.duration - 5) s.position else 0.0)
            }
            return
        }
        val r = target() ?: throw NoTv()
        when (s.phase) {
            Phase.PLAYING, Phase.BUFFERING -> {
                if (r.cast) CastControl.pause(r) else pauseWhenReady(r)
                if (s.live) { livePausedAt = System.currentTimeMillis(); liveClock.update(true) }
                _state.update { it.copy(phase = Phase.PAUSED) }
            }
            Phase.PAUSED -> {
                if (pendingPause) {
                    // La pause n'avait pas encore été appliquée (télé en chargement) : on l'annule simplement.
                    pendingPause = false
                    _state.update { it.copy(phase = Phase.BUFFERING) }
                    return
                }
                if (r.cast) CastControl.play(r) else TvSteps.playWhenReady(r)
                // Direct : la lecture reprend là où elle s'était figée ; le retard sur le direct a grandi d'autant.
                livePausedAt = 0L
                // Direct : la télé reprend là où elle s'était figée ; la pause compte dans le retard.
                val b = if (s.live) liveClock.update(false) else s.liveBehind
                _state.update { it.copy(phase = Phase.PLAYING, liveBehind = b) }
            }
            else -> { playOnTv = true; castAt(if (s.phase == Phase.STOPPED && s.duration > 0 && s.position < s.duration - 5) s.position else 0.0) }
        }
    }

    // ------------------------------------------------------------------ déplacement dans la vidéo et direct

    internal var seekJob: Job? = null
    @Volatile private var holdPositionUntil = 0L
    /** Moment visé par le dernier déplacement : affiché tel quel jusqu'à ce que le lecteur y soit vraiment. */
    @Volatile private var seekTarget: Double? = null

    /**
     * Position à afficher : celle que donne le lecteur, sauf juste après un déplacement. Tant qu'il n'a pas rejoint
     * le moment visé (déplacement pas encore fait, ou ancien état reçu en retard), on garde le moment visé : pas
     * d'allers-retours « 50:33 → 50:43 → 50:33 » à l'écran.
     */
    private fun shownPosition(reported: Double, current: Double): Double {
        val t = seekTarget ?: return reported
        if (kotlin.math.abs(reported - t) < 2.5 || System.currentTimeMillis() > holdPositionUntil) {
            seekTarget = null
            return reported
        }
        return current
    }

    /**
     * Les appuis rapprochés (±10 s, glissement) sont regroupés : la télé refuse un déplacement
     * reçu pendant qu'elle traite le précédent (erreur UPnP 710 sur les Philips Titan OS).
     */
    fun seekTo(seconds: Double, debounceMs: Long = 350) {
        val s = _state.value
        val target = seconds.coerceIn(0.0, if (s.duration > 0) s.duration - 1 else Double.MAX_VALUE)
        _state.update { it.copy(position = target) }
        seekTarget = target
        holdPositionUntil = System.currentTimeMillis() + debounceMs + 6000
        seekJob?.cancel()
        seekJob = scope.launch {
            delay(debounceMs)
            exec { doSeek(target) }
        }
    }

    fun skip(delta: Double) {
        val s = _state.value
        if (!s.live) { seekTo(s.position + delta, debounceMs = 700); return }
        // Direct : on remonte (ou redescend) dans ce que garde le flux ; le flux est renvoyé avec ce retard.
        if (!s.active || !s.continuous) return
        // Par rapport à ce qu'on regarde à l'instant de l'appui (et pas une fois le chargement fait).
        liveSeek((currentBehind() - delta).coerceIn(0.0, maxLiveBack(s)), 700)
    }

    /** Direct : retour au direct. */
    fun goLive() {
        val s = _state.value
        if (!s.live || sending()) return
        seekJob?.cancel()
        exec { castAt(0.0) }
    }

    /** Direct : se placer à ce retard (en secondes) sur le direct, depuis la barre de lecture. */
    fun seekLive(behind: Double) {
        val s = _state.value
        if (!s.live || !s.active || !s.continuous) return
        liveSeek(behind.coerceIn(0.0, maxLiveBack(s)), 300)
    }

    /** Déplacement demandé dans le direct, pas encore envoyé : (retard voulu, moment de l'appui). */
    @Volatile private var pendingLive: Pair<Double, Long>? = null

    /** Retard de ce qu'on regarde, maintenant (y compris un déplacement demandé à l'instant). */
    private fun currentBehind(): Double = pendingLive?.let { (b, at) -> b + (System.currentTimeMillis() - at) / 1000.0 }
        ?: liveClock.update(_state.value.phase == Phase.PLAYING)

    private fun liveSeek(target: Double, debounceMs: Long) {
        val at = System.currentTimeMillis()
        pendingLive = target to at
        livePausedAt = 0L
        _state.update { it.copy(liveBehind = target) }
        seekJob?.cancel()
        seekJob = scope.launch {
            delay(debounceMs)
            // Le direct a avancé pendant l'attente : on vise toujours le même moment du programme.
            val (b, t) = pendingLive ?: return@launch
            pendingLive = null
            exec { castAt(b + (System.currentTimeMillis() - t) / 1000.0) }
        }
    }

    private val liveClock = LiveClock()

    /** On peut remonter dans tout ce que garde le flux (ou l'enregistrement), moins les derniers morceaux. */
    fun maxLiveBack(s: CastState) = (s.liveWindow - 5).coerceAtLeast(0.0)

    /** Direct enregistré par le téléphone : (retard de la télé, jusqu'où on peut remonter), en secondes. */
    private fun liveRecording(): Pair<Double, Double>? =
        if (_state.value.live) remoteId?.let { proxy.liveStatus(it) } else null

    /** Direct en pause : depuis quand (pour compter le retard pris). */
    @Volatile private var livePausedAt = 0L

    private suspend fun doSeek(target: Double) {
        val s = _state.value
        if (s.onPhone) { PhoneEngine.seekTo(target); return }
        val r = target() ?: throw NoTv()
        if (r.cast) {
            CastControl.seek(r, target)
            holdPositionUntil = maxOf(holdPositionUntil, System.currentTimeMillis() + 1500)
            return
        }
        if (s.isHls && s.continuous) { castAt(target); return }
        TvSteps.waitUntilSettled(r)
        try {
            Dlna.seek(r, target)
        } catch (e: TvError) {
            // Télé encore occupée : on attend qu'elle soit prête et on réessaie une fois.
            delay(800)
            TvSteps.waitUntilSettled(r)
            Dlna.seek(r, target)
        }
        castTime = System.currentTimeMillis()
        holdPositionUntil = maxOf(holdPositionUntil, System.currentTimeMillis() + 1500)
    }

    /** Pause demandée pendant que la télé chargeait : appliquée dès qu'elle lit (voir le suivi). */
    @Volatile private var pendingPause = false

    /**
     * Pause : pendant un chargement, certaines télés répondent « 701 Transition not available ». On attend
     * qu'elle soit prête ; si elle charge encore, la pause est mise de côté et appliquée dès qu'elle lit.
     */
    private suspend fun pauseWhenReady(r: Renderer) {
        try {
            Dlna.pause(r)
            pendingPause = false
        } catch (e: TvError) {
            TvSteps.waitUntilSettled(r, 5000)
            runCatching { Dlna.pause(r) }
                .onSuccess { pendingPause = false }
                .onFailure { pendingPause = true; Log.i("TvCast", "pause mise de côté : la télé charge encore") }
        }
    }

    /** Annule le chargement ou l'envoi en cours, et revient à l'arrêt. */
    fun cancel() {
        pendingCast = false
        running?.cancel()
        // Tout de suite à l'écran, même si la télé ou la source met du temps à répondre à la demande annulée
        // (la commande annulée s'arrête dès qu'elle reprend la main ; l'arrêt de la télé suit derrière).
        pollJob?.cancel()
        _state.update { it.copy(phase = Phase.IDLE, notice = null, position = 0.0) }
        stop()
    }

    /**
     * Arrêter : la télé affiche l'écran d'attente de l'appli au lieu de quitter le lecteur (un vrai
     * « Stop » renvoie beaucoup de télés sur leur dernière source, HDMI…). « Se déconnecter » libère la télé.
     */
    fun stop(keepRecording: Boolean = false) = exec {
        // Passage télé → téléphone : l'enregistrement du direct continue (on garde de quoi revenir en arrière).
        if (!keepRecording) proxy.stopLive()
        if (_state.value.onPhone) {
            PhoneEngine.stop()
            _state.update { it.copy(phase = Phase.STOPPED) }
            return@exec
        }
        Album.slideJob?.cancel()
        seekJob?.cancel()
        _state.update { it.copy(slides = it.slides?.copy(auto = false)) }
        pollJob?.cancel()
        val r = target()
        if (r != null && runCatching { showIdle() }.isFailure) {
            Album.stopStream()
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

    /** Choisit la piste son (langue) ; la vidéo reprend au même endroit avec elle. */
    // ------------------------------------------------------------------ langue et sous-titres (réglages)

    fun selectAudio(index: Int) {
        val s = _state.value
        val t = s.audioTracks.getOrNull(index) ?: return
        if (index == s.audioIndex) return
        proxy.audioChoice = t.uri
        _state.update { it.copy(audioIndex = index) }
        if (s.onPhone) PhoneEngine.selectAudio(t.name, t.lang)
        else if (s.active) exec { castAt(currentPosition()) }
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
