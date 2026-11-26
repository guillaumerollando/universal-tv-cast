package studio.rollando.castor.cast

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.WebSettings
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import java.io.File

/**
 * Lecture sur le téléphone, dans l'écran de lecture de l'appli (même écran, mêmes commandes que pour la télé).
 * Un seul lecteur pour toute l'appli ; l'écran l'affiche dans son cadre, ou en plein écran en paysage.
 *
 * Vidéo : lue à sa source (en-têtes du site, son séparé, sous-titres choisis dans l'appli).
 * Direct : lu depuis l'enregistrement du téléphone (le relais), comme la télé : pause et retour en arrière marchent
 * pareil, même sur les chaînes qui ne gardent qu'une minute en ligne.
 */
@OptIn(UnstableApi::class)
object PhoneEngine {
    /** Ce que l'écran de lecture a besoin de savoir, à chaque instant. */
    data class Status(val phase: Phase, val position: Double, val duration: Double, val ended: Boolean, val error: Boolean)

    private val main = Handler(Looper.getMainLooper())
    private val _player = kotlinx.coroutines.flow.MutableStateFlow<ExoPlayer?>(null)
    /** Le lecteur en cours (l'écran l'affiche) ; null quand rien ne joue sur le téléphone. */
    val playerFlow: kotlinx.coroutines.flow.StateFlow<ExoPlayer?> = _player
    private val player: ExoPlayer? get() = _player.value
    /** Couleur dominante de l'image en cours (ARGB), pour la lueur de l'écran de lecture ; null : aucune. */
    val glow = kotlinx.coroutines.flow.MutableStateFlow<Int?>(null)

    /** Dernier état connu (lisible de n'importe quel fil). */
    @Volatile var last: Status? = null
        private set
    @Volatile var onStatus: ((Status) -> Unit)? = null
    private var ended = false
    private var error = false

    private val ticker = object : Runnable {
        override fun run() { report(); main.postDelayed(this, 500) }
    }

    /** Lance la lecture (remplace ce qui jouait). `liveUrl` : flux continu du relais, pour un direct. */
    fun play(ctx: Context, m: Media, startSec: Double, subtitle: ByteArray?, hls: Boolean, liveUrl: String?) = main.post {
        val p = player ?: build(ctx).also { _player.value = it }
        ended = false
        error = false
        val ua = m.headers.entries.firstOrNull { it.key.equals("User-Agent", true) }?.value
            ?: runCatching { WebSettings.getDefaultUserAgent(ctx) }.getOrNull()
        val http = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setDefaultRequestProperties(if (liveUrl != null) emptyMap() else m.headers.filterKeys { !it.equals("User-Agent", true) })
            .apply { if (ua != null && liveUrl == null) setUserAgent(ua) }
        val data = DefaultDataSource.Factory(ctx, http)
        val source: MediaSource = if (liveUrl != null) {
            // Flux continu (TS ou MP4 fragmenté) servi par le téléphone lui-même.
            ProgressiveMediaSource.Factory(data).createMediaSource(MediaItem.fromUri(liveUrl))
        } else {
            val subs = subtitle?.let { bytes ->
                val f = File(ctx.cacheDir, "phone_subtitles.srt").apply { writeBytes(bytes) }
                listOf(MediaItem.SubtitleConfiguration.Builder(Uri.fromFile(f))
                    .setMimeType(MimeTypes.APPLICATION_SUBRIP).setSelectionFlags(C.SELECTION_FLAG_DEFAULT).build())
            }.orEmpty()
            val item = MediaItem.Builder().setUri(m.url).setSubtitleConfigurations(subs)
                .apply { if (hls) setMimeType(MimeTypes.APPLICATION_M3U8) }.build()
            var s: MediaSource = DefaultMediaSourceFactory(data).createMediaSource(item)
            // Image et son servis à part (certains sites) : joués ensemble.
            m.audioUrl?.let { a ->
                s = MergingMediaSource(true, s, HlsMediaSource.Factory(data).createMediaSource(
                    MediaItem.Builder().setUri(a).setMimeType(MimeTypes.APPLICATION_M3U8).build()))
            }
            s
        }
        p.setMediaSource(source)
        if (liveUrl == null && startSec > 1) p.seekTo((startSec * 1000).toLong())
        p.playWhenReady = true
        p.prepare()
        main.removeCallbacks(ticker)
        main.post(ticker)
    }

    private fun build(ctx: Context): ExoPlayer = ExoPlayer.Builder(ctx.applicationContext)
        .setSeekBackIncrementMs(10_000)
        .setSeekForwardIncrementMs(10_000)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
        .setHandleAudioBecomingNoisy(true)
        .build().apply {
            trackSelectionParameters = trackSelectionParameters.buildUpon()
                .setPreferredTextLanguage(java.util.Locale.getDefault().language).build()
            addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) { if (state == Player.STATE_ENDED) ended = true; report() }
                override fun onIsPlayingChanged(isPlaying: Boolean) = report()
                override fun onPlayerError(e: PlaybackException) {
                    android.util.Log.w("CastorPhone", "lecture impossible : ${e.errorCodeName}", e)
                    error = true
                    report()
                }
            })
        }

    private fun report() {
        val p = player ?: return
        val phase = when {
            error -> Phase.STOPPED
            p.playbackState == Player.STATE_ENDED -> Phase.STOPPED
            p.playbackState == Player.STATE_BUFFERING -> if (p.playWhenReady) Phase.BUFFERING else Phase.PAUSED
            p.playbackState == Player.STATE_READY -> if (p.playWhenReady) Phase.PLAYING else Phase.PAUSED
            else -> Phase.IDLE
        }
        val dur = p.duration.takeIf { it != C.TIME_UNSET && it > 0 }?.div(1000.0) ?: 0.0
        val st = Status(phase, p.currentPosition / 1000.0, dur, ended, error)
        last = st
        onStatus?.invoke(st)
    }

    fun pause() = main.post { player?.pause() }
    fun resume() = main.post { player?.play() }
    fun seekTo(sec: Double) = main.post { player?.seekTo((sec * 1000).toLong()) }

    /** Position de la vidéo (secondes), d'après le dernier état connu. */
    val position: Double get() = last?.position ?: 0.0

    /** Arrête et libère le lecteur (on repasse sur la télé, ou on arrête). */
    fun stop() = main.post {
        main.removeCallbacks(ticker)
        player?.release()
        _player.value = null
        last = null
    }
}
