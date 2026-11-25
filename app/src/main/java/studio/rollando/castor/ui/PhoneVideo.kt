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
import studio.rollando.castor.cast.CastState
import studio.rollando.castor.cast.Phase
import studio.rollando.castor.cast.PhoneEngine

/** La vidéo du téléphone, sans commandes (les commandes sont celles de l'écran de lecture). */
@OptIn(UnstableApi::class)
@Composable
private fun VideoSurface(modifier: Modifier) {
    val player by PhoneEngine.playerFlow.collectAsState()
    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                useController = false
                keepScreenOn = true
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                setShutterBackgroundColor(android.graphics.Color.BLACK)
            }
        },
        update = { it.player = player },
        onRelease = { it.player = null },
        modifier = modifier,
    )
}

private fun Activity.orientation(o: Int) { requestedOrientation = o }

/** Dans l'écran de lecture : la vidéo à la place du grand cadre, avec un bouton plein écran. */
@Composable
fun PhoneVideo(state: CastState) {
    val activity = LocalContext.current as? Activity
    Box(
        Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(28.dp)).background(Color.Black),
    ) {
        VideoSurface(Modifier.fillMaxSize())
        if (state.phase == Phase.SENDING || state.phase == Phase.BUFFERING || state.phase == Phase.LOADING) {
            CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color.White)
        }
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
    Box(
        Modifier.fillMaxSize().background(Color.Black)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { controls = !controls },
    ) {
        VideoSurface(Modifier.fillMaxSize())
        if (state.phase == Phase.SENDING || state.phase == Phase.BUFFERING) {
            CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color.White)
        }
        AnimatedVisibility(controls, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize()) {
                Row(
                    Modifier.fillMaxWidth().align(Alignment.TopStart)
                        .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.7f), Color.Transparent)))
                        .safeDrawingPadding().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(state.media?.title.orEmpty(), color = Color.White, style = MaterialTheme.typography.titleMedium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    IconButton(onClick = ::exit) { Icon(Icons.Rounded.FullscreenExit, stringResource(R.string.cd_fullscreen_exit), tint = Color.White) }
                }
                Column(
                    Modifier.fillMaxWidth().align(Alignment.BottomCenter)
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.75f))))
                        .safeDrawingPadding().padding(horizontal = 24.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Progress(state)
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Box(Modifier.size(width = 420.dp, height = 88.dp)) { Controls(state) }
                    }
                }
            }
        }
    }
}
