package studio.rollando.castor.cast

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.mediarouter.media.MediaRouteSelector
import androidx.mediarouter.media.MediaRouter
import com.google.android.gms.cast.CastDevice
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.MediaSeekOptions
import com.google.android.gms.cast.MediaStatus
import com.google.android.gms.cast.MediaTrack
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.cast.framework.media.RemoteMediaClient
import com.google.android.gms.common.api.PendingResult
import com.google.android.gms.common.images.WebImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import studio.rollando.castor.dlna.Renderer
import studio.rollando.castor.dlna.TvBusy
import studio.rollando.castor.dlna.TvError
import java.io.IOException
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Google Cast : découverte des appareils (MediaRouter) et pilotage du lecteur standard de Google.
 * Le lecteur Cast affiche nativement une piste de sous-titres WebVTT envoyée à côté de la vidéo.
 * Toutes les commandes passent par le fil principal (exigence du SDK Cast).
 */
object CastControl {
    private const val TAG = "CastorCast"
    private val main = Handler(Looper.getMainLooper())
    private var context: CastContext? = null
    private var router: MediaRouter? = null
    var onDevices: ((List<Renderer>) -> Unit)? = null

    private val selector: MediaRouteSelector by lazy {
        MediaRouteSelector.Builder()
            .addControlCategory(CastMediaControlIntent.categoryForCast(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID))
            .build()
    }

    private val callback = object : MediaRouter.Callback() {
        override fun onRouteAdded(r: MediaRouter, route: MediaRouter.RouteInfo) = publish()
        override fun onRouteRemoved(r: MediaRouter, route: MediaRouter.RouteInfo) = publish()
        override fun onRouteChanged(r: MediaRouter, route: MediaRouter.RouteInfo) = publish()
    }

    fun init(app: Context) {
        main.post {
            if (router != null) return@post
            runCatching {
                router = MediaRouter.getInstance(app)
                CastContext.getSharedInstance(app, Executors.newSingleThreadExecutor())
                    .addOnSuccessListener { context = it }
                    .addOnFailureListener { Log.i(TAG, "Cast indisponible : ${it.message}") }
            }.onFailure { Log.i(TAG, "Cast indisponible : ${it.message}") }
        }
    }

    /** Lance (ou ralentit) la recherche des appareils Cast. */
    fun scan(active: Boolean) {
        main.post {
            val r = router ?: return@post
            r.removeCallback(callback)
            r.addCallback(selector, callback,
                if (active) MediaRouter.CALLBACK_FLAG_PERFORM_ACTIVE_SCAN else MediaRouter.CALLBACK_FLAG_REQUEST_DISCOVERY)
            publish()
        }
    }

    private fun cleanName(n: String) = n.replace("undefined_", "").trim().ifEmpty { n }

    private fun publish() {
        val r = router ?: return
        val list = r.routes
            .filter { !it.isDefault && it.isEnabled && it.matchesSelector(selector) }
            .map { route ->
                val dev = CastDevice.getFromBundle(route.extras)
                Renderer(
                    name = cleanName(route.name),
                    udn = "cast:" + (dev?.deviceId ?: route.id),
                    avUrl = "", avType = "", rcUrl = null, rcType = null,
                    manufacturer = "Google Cast", model = dev?.modelName?.let(::cleanName),
                    location = dev?.inetAddress?.hostAddress?.let { "cast://$it" } ?: "",
                    cast = true, routeId = route.id,
                )
            }
        Log.i(TAG, "appareils Cast : ${list.joinToString { it.name + " (" + it.host + ")" }.ifEmpty { "aucun" }}")
        onDevices?.invoke(list)
    }

    private fun session(): CastSession? = context?.sessionManager?.currentCastSession?.takeIf { it.isConnected }

    /** Se connecte à l'appareil (ouvre le lecteur Cast sur la télé). */
    private suspend fun connect(r: Renderer): CastSession = withContext(Dispatchers.Main) {
        val ctx = context ?: throw IOException("Google Cast indisponible sur ce téléphone")
        val rt = router ?: throw IOException("Google Cast indisponible")
        val wanted = r.udn.removePrefix("cast:")
        session()?.let { s -> if (s.castDevice?.deviceId == wanted) return@withContext s }
        val route = rt.routes.firstOrNull { it.id == r.routeId } ?: throw TvBusy(IOException("appareil Cast introuvable"))
        Log.i(TAG, "connexion à ${r.name}…")
        withTimeout(12_000) {
            suspendCancellableCoroutine { cont ->
                val sm = ctx.sessionManager
                val l = object : SessionManagerListener<CastSession> {
                    fun done(s: CastSession?, err: Int?) {
                        sm.removeSessionManagerListener(this, CastSession::class.java)
                        if (!cont.isActive) return
                        if (s != null) cont.resume(s) else cont.resumeWithException(TvError("Cast $err"))
                    }
                    override fun onSessionStarted(s: CastSession, id: String) = done(s, null)
                    override fun onSessionResumed(s: CastSession, wasSuspended: Boolean) = done(s, null)
                    override fun onSessionStartFailed(s: CastSession, err: Int) = done(null, err)
                    override fun onSessionResumeFailed(s: CastSession, err: Int) = done(null, err)
                    override fun onSessionStarting(s: CastSession) {}
                    override fun onSessionEnding(s: CastSession) {}
                    override fun onSessionEnded(s: CastSession, err: Int) {}
                    override fun onSessionResuming(s: CastSession, id: String) {}
                    override fun onSessionSuspended(s: CastSession, reason: Int) {}
                }
                sm.addSessionManagerListener(l, CastSession::class.java)
                rt.selectRoute(route)
                cont.invokeOnCancellation { main.post { sm.removeSessionManagerListener(l, CastSession::class.java) } }
            }
        }
    }

    private suspend fun client(r: Renderer): RemoteMediaClient =
        connect(r).remoteMediaClient ?: throw TvBusy(IOException("lecteur Cast indisponible"))

    private suspend fun PendingResult<RemoteMediaClient.MediaChannelResult>.awaitOk(what: String) =
        suspendCancellableCoroutine { c ->
            setResultCallback { res ->
                if (res.status.isSuccess) c.resume(Unit)
                else c.resumeWithException(TvError("Cast $what ${res.status.statusCode}"))
            }
        }

    data class Sub(val url: String, val name: String, val lang: String?)

    /** Envoie la vidéo au lecteur Cast, avec titre, affiche et piste de sous-titres. */
    suspend fun load(r: Renderer, url: String, mime: String, title: String, poster: String?, sub: Sub?, startSec: Double, live: Boolean) =
        withContext(Dispatchers.Main) {
            val c = client(r)
            val md = MediaMetadata(MediaMetadata.MEDIA_TYPE_MOVIE).apply {
                putString(MediaMetadata.KEY_TITLE, title)
                poster?.let { addImage(WebImage(Uri.parse(it))) }
            }
            val tracks = sub?.let {
                listOf(
                    MediaTrack.Builder(1, MediaTrack.TYPE_TEXT)
                        .setSubtype(MediaTrack.SUBTYPE_SUBTITLES)
                        .setContentId(it.url)
                        .setContentType("text/vtt")
                        .setName(it.name)
                        .apply { it.lang?.let { l -> setLanguage(l) } }
                        .build()
                )
            }.orEmpty()
            val info = MediaInfo.Builder(url)
                .setContentUrl(url)
                .setStreamType(if (live) MediaInfo.STREAM_TYPE_LIVE else MediaInfo.STREAM_TYPE_BUFFERED)
                .setContentType(mime)
                .setMetadata(md)
                .setMediaTracks(tracks)
                .build()
            val req = MediaLoadRequestData.Builder()
                .setMediaInfo(info)
                .setAutoplay(true)
                .setCurrentTime((startSec * 1000).toLong())
                .apply { if (tracks.isNotEmpty()) setActiveTrackIds(longArrayOf(1)) }
                .build()
            Log.i(TAG, "chargement $mime sous-titres=${sub != null} départ=${startSec.toInt()} s")
            c.load(req).awaitOk("chargement")
        }

    /** Affiche une photo avec le lecteur Cast. */
    suspend fun loadPhoto(r: Renderer, url: String, title: String) = withContext(Dispatchers.Main) {
        val c = client(r)
        val md = MediaMetadata(MediaMetadata.MEDIA_TYPE_PHOTO).apply { putString(MediaMetadata.KEY_TITLE, title) }
        val info = MediaInfo.Builder(url).setContentUrl(url).setStreamType(MediaInfo.STREAM_TYPE_NONE)
            .setContentType("image/jpeg").setMetadata(md).build()
        c.load(MediaLoadRequestData.Builder().setMediaInfo(info).setAutoplay(true).build()).awaitOk("photo")
    }

    suspend fun play(r: Renderer) = withContext(Dispatchers.Main) { client(r).play().awaitOk("lecture") }
    suspend fun pause(r: Renderer) = withContext(Dispatchers.Main) { client(r).pause().awaitOk("pause") }
    suspend fun stop(r: Renderer) = withContext(Dispatchers.Main) { runCatching { session()?.remoteMediaClient?.stop() } }

    suspend fun seek(r: Renderer, sec: Double) = withContext(Dispatchers.Main) {
        client(r).seek(MediaSeekOptions.Builder().setPosition((sec * 1000).toLong()).build()).awaitOk("déplacement")
    }

    /** Position (s), durée (s) et état au format des télés DLNA (PLAYING, PAUSED_PLAYBACK, TRANSITIONING, STOPPED). */
    suspend fun snapshot(): Triple<Double, Double, String?> = withContext(Dispatchers.Main) {
        val c = session()?.remoteMediaClient ?: return@withContext Triple(0.0, 0.0, "STOPPED")
        val st = when (c.playerState) {
            MediaStatus.PLAYER_STATE_PLAYING -> "PLAYING"
            MediaStatus.PLAYER_STATE_PAUSED -> "PAUSED_PLAYBACK"
            MediaStatus.PLAYER_STATE_BUFFERING, MediaStatus.PLAYER_STATE_LOADING -> "TRANSITIONING"
            MediaStatus.PLAYER_STATE_IDLE -> "STOPPED"
            else -> null
        }
        Triple(c.approximateStreamPosition / 1000.0, c.streamDuration.coerceAtLeast(0) / 1000.0, st)
    }

    /** Erreur de lecture signalée par le lecteur Cast (format refusé…), sinon null. */
    suspend fun lastError(): Int? = withContext(Dispatchers.Main) {
        val s = session()?.remoteMediaClient?.mediaStatus ?: return@withContext null
        if (s.playerState == MediaStatus.PLAYER_STATE_IDLE && s.idleReason == MediaStatus.IDLE_REASON_ERROR) s.idleReason else null
    }

    suspend fun volume(): Int = withContext(Dispatchers.Main) { ((session()?.volume ?: 0.0) * 100).toInt() }
    suspend fun setVolume(v: Int) = withContext(Dispatchers.Main) { runCatching { session()?.volume = v / 100.0 } }

    /** Ferme le lecteur Cast sur la télé et libère l'appareil. */
    fun disconnect() {
        main.post { runCatching { context?.sessionManager?.endCurrentSession(true) } }
    }
}
