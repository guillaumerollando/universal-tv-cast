package com.rollandostudio.universaltvcast.cast

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
    private const val OWN_SUBS = "tvcast-subs"
    /** Ce que l'écran de lecture a besoin de savoir, à chaque instant. */
    data class Status(val phase: Phase, val position: Double, val duration: Double, val ended: Boolean, val error: Boolean)

    private val main = Handler(Looper.getMainLooper())
    private val _player = kotlinx.coroutines.flow.MutableStateFlow<ExoPlayer?>(null)
    /** Le lecteur en cours (l'écran l'affiche) ; null quand rien ne joue sur le téléphone. */
    val playerFlow: kotlinx.coroutines.flow.StateFlow<ExoPlayer?> = _player

    /** Proportions de l'image (largeur / hauteur), pour la petite fenêtre « image dans l'image ». */
    val aspect: Float get() = player?.videoSize?.let { if (it.width > 0 && it.height > 0) it.width * it.pixelWidthHeightRatio / it.height else null } ?: (16f / 9f)
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
        wantedAudio = null
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon().clearOverridesOfType(C.TRACK_TYPE_AUDIO)
            .clearOverridesOfType(C.TRACK_TYPE_TEXT).setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false).build()
        textTracks.value = emptyList(); textSelected.value = -1
        // Sous-titres choisis par l'appli (carte « Sous-titres ») : le lecteur n'affiche que ceux-là, jamais d'autres
        // pistes de la vidéo de lui-même (DASH, HLS : il prenait la langue du téléphone).
        appSubs = m.subs.isNotEmpty() || subtitle != null
        appSubtitle = subtitle != null
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
                listOf(MediaItem.SubtitleConfiguration.Builder(Uri.fromFile(f)).setId(OWN_SUBS)
                    .setMimeType(MimeTypes.APPLICATION_SUBRIP).setSelectionFlags(C.SELECTION_FLAG_DEFAULT).build())
            }.orEmpty()
            val item = MediaItem.Builder().setUri(m.url).setSubtitleConfigurations(subs)
                .apply {
                    // DASH : le lecteur du téléphone le lit directement (module DASH de Media3).
                    val dash = m.mime?.contains("dash", true) == true || m.url.substringBefore('?').endsWith(".mpd", true)
                    if (dash) setMimeType(MimeTypes.APPLICATION_MPD) else if (hls) setMimeType(MimeTypes.APPLICATION_M3U8)
                }.build()
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
                override fun onTracksChanged(tracks: androidx.media3.common.Tracks) { applyAudio(); readTextTracks() }
                // Pour comprendre un « problème de son » : qui a interrompu la lecture, et le volume.
                override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                    if (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS || reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY)
                        android.util.Log.w("TvCastPhone", "lecture interrompue par le système (raison $reason : son pris par une autre appli ou casque débranché)")
                    report()
                }
                override fun onVolumeChanged(volume: Float) { android.util.Log.i("TvCastPhone", "volume du lecteur : $volume") }
                override fun onAudioSessionIdChanged(audioSessionId: Int) { android.util.Log.i("TvCastPhone", "session audio $audioSessionId") }
                override fun onPlayerError(e: PlaybackException) {
                    android.util.Log.w("TvCastPhone", "lecture impossible : ${e.errorCodeName}", e)
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

    /** Sous-titres intégrés à la vidéo (MKV, MP4), tels que le lecteur du téléphone les voit. */
    data class TextTrack(val label: String?, val lang: String?)
    val textTracks = kotlinx.coroutines.flow.MutableStateFlow<List<TextTrack>>(emptyList())
    /** Piste de sous-titres affichée sur le téléphone : son rang dans [textTracks], ou -1 (aucune). */
    val textSelected = kotlinx.coroutines.flow.MutableStateFlow(-1)

    @Volatile private var appSubs = false
    @Volatile private var appSubtitle = false

    private fun readTextTracks() {
        val p = player ?: return
        if (appSubs) {
            val groups = p.currentTracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }
            val own = groups.firstNotNullOfOrNull { g -> (0 until g.length).firstOrNull { g.getTrackFormat(it).id?.contains(OWN_SUBS) == true }?.let { g to it } }
            val b = p.trackSelectionParameters.buildUpon()
            if (appSubtitle && own != null) b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                .setOverrideForType(androidx.media3.common.TrackSelectionOverride(own.first.mediaTrackGroup, own.second))
            else b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            val next = b.build()
            if (next != p.trackSelectionParameters) p.trackSelectionParameters = next
            textTracks.value = emptyList()
            return
        }
        val groups = p.currentTracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }
        val list = mutableListOf<TextTrack>()
        var sel = -1
        groups.forEach { g ->
            for (i in 0 until g.length) {
                val f = g.getTrackFormat(i)
                if (g.isTrackSelected(i)) sel = list.size
                list += TextTrack(f.label, f.language)
            }
        }
        textTracks.value = list
        textSelected.value = sel
    }

    /** Choisit la piste de sous-titres intégrée [index] sur le téléphone, ou aucune (-1). */
    fun selectText(index: Int) = main.post {
        val p = player ?: return@post
        val b = p.trackSelectionParameters.buildUpon()
        if (index < 0) b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
        else {
            var n = 0
            loop@ for (g in p.currentTracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }) for (i in 0 until g.length) {
                if (n++ == index) {
                    b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                        .setOverrideForType(androidx.media3.common.TrackSelectionOverride(g.mediaTrackGroup, i))
                    break@loop
                }
            }
        }
        p.trackSelectionParameters = b.build()
        textSelected.value = index
    }

    /** Piste son voulue (nom ou langue) : appliquée dès que le lecteur connaît les pistes de la vidéo. */
    @Volatile private var wantedAudio: Pair<String, String?>? = null

    fun selectAudio(name: String, lang: String?) = main.post { wantedAudio = name to lang; applyAudio() }

    private fun applyAudio() {
        val p = player ?: return
        val (name, lang) = wantedAudio ?: return
        val groups = p.currentTracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
        val pick = groups.firstNotNullOfOrNull { g -> (0 until g.length).firstOrNull { g.getTrackFormat(it).label == name }?.let { g to it } }
            ?: groups.firstNotNullOfOrNull { g -> (0 until g.length).firstOrNull { lang != null && g.getTrackFormat(it).language == lang }?.let { g to it } }
            ?: return
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setOverrideForType(androidx.media3.common.TrackSelectionOverride(pick.first.mediaTrackGroup, pick.second)).build()
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
