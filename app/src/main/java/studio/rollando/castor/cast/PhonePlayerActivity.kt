package studio.rollando.castor.cast

import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.WebSettings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.ui.PlayerView
import studio.rollando.castor.R
import studio.rollando.castor.data.Library
import studio.rollando.castor.ui.CastorTheme
import studio.rollando.castor.ui.Nav
import studio.rollando.castor.ui.Tab
import java.io.File

/** La vidéo à ouvrir dans [PhonePlayerActivity] (trop riche pour passer par l'intent : en-têtes, sous-titres). */
object PhonePlayback {
    @Volatile var pending: Castor.PhoneSession? = null
}

/**
 * Lecture plein écran sur le téléphone : même vidéo que sur la télé (en-têtes du site, son séparé, sous-titres
 * décalés), sans pub. « Envoyer sur la télé » y renvoie la vidéo à l'endroit atteint. L'avancement nourrit
 * « Reprendre », comme pendant un cast.
 */
@OptIn(UnstableApi::class)
class PhonePlayerActivity : ComponentActivity() {
    private var player: ExoPlayer? = null
    private var session: Castor.PhoneSession? = null
    private val handler = Handler(Looper.getMainLooper())
    private val saver = object : Runnable {
        override fun run() { saveProgress(); handler.postDelayed(this, 15_000) }
    }
    private var controlsVisible by mutableStateOf(true)
    private var failed by mutableStateOf(false)
    /** Direct : en retard sur le direct (pause, retour en arrière) — le badge propose d'y revenir. */
    private var behindLive by mutableStateOf(false)
    /** Retard sur le direct, en secondes (affiché « −1:24 »). */
    private var liveDelay by mutableStateOf(0L)
    private val liveWatch = object : Runnable {
        override fun run() {
            val p = player
            if (p != null && p.isCurrentMediaItemLive) {
                val offset = p.currentLiveOffset
                // Le lecteur se tient volontairement à quelques segments du direct (sa « cible ») : on n'est en
                // retard que bien au-delà.
                val target = p.currentMediaItem?.liveConfiguration?.targetOffsetMs?.takeIf { it != C.TIME_UNSET } ?: 30_000L
                behindLive = !p.isPlaying || (offset != C.TIME_UNSET && offset > target + 15_000)
                liveDelay = if (offset == C.TIME_UNSET) 0 else ((offset - target) / 1000).coerceAtLeast(0)
            }
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val s = PhonePlayback.pending ?: run { finish(); return }
        session = s
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        val p = buildPlayer(s)
        player = p
        setContent {
            CastorTheme {
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    AndroidView(
                        factory = { ctx ->
                            PlayerView(ctx).apply {
                                player = p
                                keepScreenOn = true
                                setShowSubtitleButton(true)
                                setShowNextButton(false)
                                setShowPreviousButton(false)
                                // Direct : « 29:22 · 30:00 » (la fenêtre gardée par le flux) ne veut rien dire ; le retard
                                // sur le direct est affiché dans le badge du haut.
                                if (s.live) listOf(androidx.media3.ui.R.id.exo_time, androidx.media3.ui.R.id.exo_position, androidx.media3.ui.R.id.exo_duration)
                                    .forEach { id -> findViewById<View>(id)?.visibility = View.GONE }
                                setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { v ->
                                    controlsVisible = v == View.VISIBLE
                                })
                            }
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                    // Barre du haut, avec les commandes du lecteur : retour, titre, renvoi sur la télé.
                    AnimatedVisibility(controlsVisible || failed, enter = fadeIn(), exit = fadeOut()) {
                        Row(
                            Modifier.fillMaxWidth()
                                .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.7f), Color.Transparent)))
                                .safeDrawingPadding().padding(horizontal = 4.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            IconButton(onClick = { finish() }) {
                                Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.cd_back), tint = Color.White)
                            }
                            Text(s.media.title, color = Color.White, style = MaterialTheme.typography.titleMedium,
                                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                            if (s.live) {
                                Spacer(Modifier.width(10.dp))
                                // Badge du direct : rouge au direct ; sinon « Revenir au direct », qui y ramène.
                                androidx.compose.material3.Surface(
                                    onClick = { player?.seekToDefaultPosition(); player?.play(); behindLive = false },
                                    shape = androidx.compose.foundation.shape.RoundedCornerShape(50),
                                    color = if (behindLive) Color.White.copy(alpha = 0.18f) else Color(0xFFE53935),
                                ) {
                                    Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Box(Modifier.width(8.dp).height(8.dp).background(
                                            if (behindLive) Color.White.copy(alpha = 0.6f) else Color.White,
                                            androidx.compose.foundation.shape.CircleShape))
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            when {
                                                !behindLive -> stringResource(R.string.player_live).uppercase()
                                                liveDelay >= 5 -> "−%d:%02d · ".format(liveDelay / 60, liveDelay % 60) + stringResource(R.string.live_back)
                                                else -> stringResource(R.string.live_back)
                                            },
                                            color = Color.White, style = MaterialTheme.typography.labelLarge,
                                        )
                                    }
                                }
                            }
                            Spacer(Modifier.weight(1f))
                            FilledTonalButton(onClick = ::sendToTv, modifier = Modifier.padding(end = 8.dp)) {
                                Icon(Icons.Rounded.Cast, null)
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.phone_to_tv))
                            }
                        }
                    }
                    if (failed) Column(
                        Modifier.align(Alignment.Center).padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(stringResource(R.string.phone_error), color = Color.White, textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(16.dp))
                        Button(onClick = ::sendToTv) { Text(stringResource(R.string.phone_to_tv)) }
                    }
                }
            }
        }
    }

    private fun buildPlayer(s: Castor.PhoneSession): ExoPlayer {
        val m = s.media
        val ua = m.headers.entries.firstOrNull { it.key.equals("User-Agent", true) }?.value
            ?: runCatching { WebSettings.getDefaultUserAgent(this) }.getOrNull()
        val http = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setDefaultRequestProperties(m.headers.filterKeys { !it.equals("User-Agent", true) })
            .apply { if (ua != null) setUserAgent(ua) }
        val data = DefaultDataSource.Factory(this, http)
        // Sous-titres choisis dans l'appli (décalage compris), sinon ceux de la vidéo dans la langue du téléphone.
        val subs = s.subtitle?.let { bytes ->
            val f = File(cacheDir, "phone_subtitles.srt").apply { writeBytes(bytes) }
            listOf(
                MediaItem.SubtitleConfiguration.Builder(Uri.fromFile(f))
                    .setMimeType(MimeTypes.APPLICATION_SUBRIP)
                    .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                    .build()
            )
        }.orEmpty()
        val item = MediaItem.Builder().setUri(m.url).setSubtitleConfigurations(subs)
            .apply { if (s.hls) setMimeType(MimeTypes.APPLICATION_M3U8) }
            .build()
        val factory = DefaultMediaSourceFactory(data)
        var source: MediaSource = factory.createMediaSource(item)
        // Image et son servis à part (certains sites) : le lecteur les joue ensemble.
        m.audioUrl?.let { a ->
            val audio = HlsMediaSource.Factory(data).createMediaSource(
                MediaItem.Builder().setUri(a).setMimeType(MimeTypes.APPLICATION_M3U8).build()
            )
            source = MergingMediaSource(true, source, audio)
        }
        return ExoPlayer.Builder(this)
            .setMediaSourceFactory(factory)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
            .setHandleAudioBecomingNoisy(true)
            .build().apply {
                trackSelectionParameters = trackSelectionParameters.buildUpon()
                    .setPreferredTextLanguage(java.util.Locale.getDefault().language)
                    .build()
                setMediaSource(source)
                if (s.startSec > 1) seekTo((s.startSec * 1000).toLong())
                playWhenReady = true
                addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(state: Int) {
                        if (state == Player.STATE_ENDED) Library.markFinished(m.url)
                    }
                    override fun onPlayerError(error: PlaybackException) {
                        android.util.Log.w("CastorPhone", "lecture impossible : ${error.errorCodeName}", error)
                        failed = true
                    }
                    override fun onVideoSizeChanged(size: VideoSize) {
                        // Vidéo en largeur : on bascule en paysage (et inversement), comme les lecteurs habituels.
                        if (size.width > 0 && size.height > 0) requestedOrientation =
                            if (size.width >= size.height) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                            else ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                    }
                })
                prepare()
            }
    }

    private fun saveProgress() {
        val p = player ?: return
        val m = session?.media ?: return
        val dur = p.duration
        if (session?.live == true || dur == C.TIME_UNSET || dur <= 0 || p.currentPosition <= 0) return
        Library.setProgress(m.url, p.currentPosition / 1000.0, dur / 1000.0)
    }

    private fun sendToTv() {
        val pos = (player?.currentPosition ?: 0L) / 1000.0
        saveProgress()
        player?.pause()
        Castor.resumeOnTv(pos)
        Nav.tab.value = Tab.PLAYER
        finish()
    }

    override fun onStart() { super.onStart(); handler.post(saver); if (session?.live == true) handler.post(liveWatch) }

    override fun onStop() {
        super.onStop()
        handler.removeCallbacks(saver)
        handler.removeCallbacks(liveWatch)
        saveProgress()
        player?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        player?.release()
        player = null
        if (isFinishing) PhonePlayback.pending = null
    }
}
