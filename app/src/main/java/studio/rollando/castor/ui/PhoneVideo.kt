package studio.rollando.castor.ui

import android.app.Activity
import android.content.pm.ActivityInfo
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.FullscreenExit
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import studio.rollando.castor.R
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.key
import androidx.compose.material.icons.rounded.Forward10
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import studio.rollando.castor.cast.Castor
import studio.rollando.castor.cast.CastState
import studio.rollando.castor.cast.Phase
import studio.rollando.castor.cast.PhoneEngine

/**
 * La vidéo du téléphone, sans commandes (les commandes sont celles de l'écran de lecture). Un seul affichage à la
 * fois tient la vidéo : en passant du cadre au plein écran (et retour), l'affichage est recréé pour la reprendre.
 */
@OptIn(UnstableApi::class)
@Composable
private fun VideoSurface(modifier: Modifier, active: Boolean = true) {
    val player by PhoneEngine.playerFlow.collectAsState()
    if (!active) { Box(modifier.background(Color.Black)); return }
    key(player) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    keepScreenOn = true
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                    this.player = player
                }
            },
            onRelease = { it.player = null },
            modifier = modifier,
        )
    }
}

/** Double-tap à gauche : −10 s ; à droite : +10 s (comme les lecteurs habituels). Simple appui : [onTap]. */
private fun Modifier.skipGestures(onTap: () -> Unit, onSkip: (Int) -> Unit) = pointerInput(Unit) {
    detectTapGestures(
        onTap = { onTap() },
        onDoubleTap = { o -> onSkip(if (o.x < size.width / 2) -10 else 10) },
    )
}

/** « −10 s » / « +10 s » affiché un instant du côté touché. */
@Composable
private fun SkipFlash(flash: Pair<Int, Long>?, modifier: Modifier) {
    val (delta, _) = flash ?: return
    // Au milieu de la moitié touchée, comme les lecteurs habituels.
    Row(modifier.fillMaxSize()) {
        for (side in listOf(-1, 1)) Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
            if (side == if (delta < 0) -1 else 1) Box(
                Modifier.size(88.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.45f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(if (delta < 0) Icons.Rounded.Replay10 else Icons.Rounded.Forward10, null, Modifier.size(40.dp), tint = Color.White)
            }
        }
    }
}

private fun Activity.orientation(o: Int) { requestedOrientation = o }

/** Dans l'écran de lecture : la vidéo à la place du grand cadre, avec un bouton plein écran. */
@Composable
fun PhoneVideo(state: CastState) {
    val activity = LocalContext.current as? Activity
    val portrait = androidx.compose.ui.platform.LocalConfiguration.current.orientation != android.content.res.Configuration.ORIENTATION_LANDSCAPE
    var flash by remember { mutableStateOf<Pair<Int, Long>?>(null) }
    LaunchedEffect(flash) { if (flash != null) { kotlinx.coroutines.delay(600); flash = null } }
    Box(
        Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(28.dp)).background(Color.Black)
            .skipGestures(onTap = {}, onSkip = { d -> Castor.skip(d.toDouble()); flash = d to System.currentTimeMillis() }),
    ) {
        VideoSurface(Modifier.fillMaxSize(), active = portrait)
        if (state.phase == Phase.SENDING || state.phase == Phase.BUFFERING || state.phase == Phase.LOADING) {
            CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color.White)
        }
        SkipFlash(flash, Modifier)
        IconButton(
            onClick = { activity?.orientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE) },
            modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp),
        ) { Icon(Icons.Rounded.Fullscreen, stringResource(R.string.cd_fullscreen), tint = Color.White) }
    }
}

/**
 * Téléphone tourné : la vidéo en plein écran. Un appui fait apparaître les commandes habituelles (barre, pause,
 * ±10 s, direct), un autre les cache.
 */
@Composable
fun PhoneFullscreen(state: CastState) {
    val activity = LocalContext.current as? Activity
    var controls by remember { mutableStateOf(true) }
    LaunchedEffect(controls, state.phase) {
        if (controls && state.phase == Phase.PLAYING) { kotlinx.coroutines.delay(3500); controls = false }
    }
    fun exit() {
        activity?.orientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT)
    }
    BackHandler { exit() }
    // Barres du système cachées le temps du plein écran.
    DisposableEffect(activity) {
        val w = activity?.window
        val c = w?.let { WindowInsetsControllerCompat(it, it.decorView) }
        c?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        c?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose {
            c?.show(WindowInsetsCompat.Type.systemBars())
            // Revenu en portrait : la rotation automatique reprend la main.
            activity?.window?.decorView?.postDelayed({ activity.orientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED) }, 2500)
        }
    }
    var flash by remember { mutableStateOf<Pair<Int, Long>?>(null) }
    LaunchedEffect(flash) { if (flash != null) { kotlinx.coroutines.delay(600); flash = null } }
    val loading = state.phase == Phase.SENDING || state.phase == Phase.BUFFERING
    Box(
        Modifier.fillMaxSize().background(Color.Black)
            .skipGestures(onTap = { controls = !controls }, onSkip = { d -> Castor.skip(d.toDouble()); flash = d to System.currentTimeMillis() }),
    ) {
        VideoSurface(Modifier.fillMaxSize())
        SkipFlash(flash, Modifier)
        if (loading && !controls) CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color.White)
        AnimatedVisibility(controls, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f))) {
                // En haut : le logo de la chaîne (un film : son titre).
                val m = state.media
                Box(Modifier.align(Alignment.TopStart).safeDrawingPadding().padding(horizontal = 24.dp, vertical = 14.dp)) {
                    if (m != null && state.live) ChannelArt(
                        name = m.channel ?: m.title, logo = m.posterUrl, streamUrl = m.url, pad = ChannelTiles.logoPad,
                        textStyle = MaterialTheme.typography.titleSmall.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.ExtraBold),
                        modifier = Modifier.size(width = ChannelTiles.width, height = ChannelTiles.width * 10 / 16)
                            .clip(RoundedCornerShape(ChannelTiles.corner)),
                    ) else Text(m?.title.orEmpty(), color = Color.White, style = MaterialTheme.typography.titleMedium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                // Au centre : reculer, lecture/pause, avancer.
                CenterControls(state, loading, Modifier.align(Alignment.Center))
                // En bas : la barre (ou celle du direct), et la sortie du plein écran à droite.
                Row(
                    Modifier.fillMaxWidth().align(Alignment.BottomCenter).safeDrawingPadding().padding(start = 24.dp, end = 8.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.weight(1f)) { Progress(state) }
                    IconButton(onClick = ::exit) {
                        Icon(Icons.Rounded.FullscreenExit, stringResource(R.string.cd_fullscreen_exit), tint = Color.White)
                    }
                }
            }
        }
    }
}

/** Commandes du plein écran, au centre : petites touches rondes, lecture/pause ambrée (la patte de l'appli). */
@Composable
private fun CenterControls(s: CastState, loading: Boolean, modifier: Modifier) {
    val c = MaterialTheme.colorScheme
    val canSkip = s.active && (!s.live || s.continuous)
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(40.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { Castor.skip(-10.0) }, enabled = canSkip,
            modifier = Modifier.size(52.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.4f))) {
            Icon(Icons.Rounded.Replay10, stringResource(R.string.cd_rewind), Modifier.size(30.dp), tint = Color.White)
        }
        Box(
            Modifier.size(68.dp).clip(CircleShape).background(c.primary)
                .clickable(enabled = !loading, role = androidx.compose.ui.semantics.Role.Button, onClick = Castor::togglePlay),
            contentAlignment = Alignment.Center,
        ) {
            if (loading) CircularProgressIndicator(Modifier.size(30.dp), color = c.onPrimary, strokeWidth = 3.dp)
            else {
                val playing = s.phase == Phase.PLAYING
                Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    stringResource(if (playing) R.string.action_pause else R.string.action_play), Modifier.size(38.dp), tint = c.onPrimary)
            }
        }
        IconButton(onClick = { Castor.skip(10.0) }, enabled = canSkip && (!s.live || s.liveBehind > 0),
            modifier = Modifier.size(52.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.4f))) {
            Icon(Icons.Rounded.Forward10, stringResource(R.string.cd_forward10), Modifier.size(30.dp), tint = Color.White)
        }
    }
}
