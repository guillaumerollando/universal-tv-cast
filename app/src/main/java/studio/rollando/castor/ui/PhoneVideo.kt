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
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.animation.core.VectorConverter
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.customActions
import kotlinx.coroutines.launch
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
import androidx.compose.ui.draw.shadow
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
import androidx.compose.animation.core.animateFloat
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Spacer

/**
 * La vidéo du téléphone, sans commandes (les commandes sont celles de l'écran de lecture). Un seul affichage à la
 * fois tient la vidéo : en passant du cadre au plein écran (et retour), l'affichage est recréé pour la reprendre.
 */
@OptIn(UnstableApi::class)
@Composable
private fun VideoSurface(modifier: Modifier, active: Boolean = true, onColor: ((Color) -> Unit)? = null) {
    val player by PhoneEngine.playerFlow.collectAsState()
    if (!active) { Box(modifier.background(Color.Black)); return }
    var view by remember { mutableStateOf<PlayerView?>(null) }
    // Couleur de l'image, pour la lueur autour de la vidéo : une vignette de 32 × 18 px copiée toutes les 0,4 s,
    // seulement pendant la lecture (rien à calculer en pause ; la copie se fait sur la carte graphique).
    if (onColor != null) LaunchedEffect(view) {
        val v = view ?: return@LaunchedEffect
        val bmp = android.graphics.Bitmap.createBitmap(32, 18, android.graphics.Bitmap.Config.ARGB_8888)
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        while (true) {
            kotlinx.coroutines.delay(400)
            if (PhoneEngine.last?.phase != Phase.PLAYING || studio.rollando.castor.data.Power.saving.value) continue
            val sv = v.videoSurfaceView as? android.view.SurfaceView ?: continue
            if (!sv.holder.surface.isValid || sv.width == 0) continue
            val ok = kotlinx.coroutines.suspendCancellableCoroutine<Boolean> { cont ->
                runCatching {
                    android.view.PixelCopy.request(sv, bmp, { r -> if (cont.isActive) cont.resumeWith(Result.success(r == android.view.PixelCopy.SUCCESS)) }, handler)
                }.onFailure { if (cont.isActive) cont.resumeWith(Result.success(false)) }
            }
            if (ok) onColor(dominant(bmp))
        }
    }
    key(player) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    view = this
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

/** Un saut en cours d'affichage : côté (−1 / 1), total cumulé (10, 20, 30…), point touché, numéro de l'appui. */
private data class Skip(val side: Int, val total: Int, val at: androidx.compose.ui.geometry.Offset, val tap: Int)

/** État du retour visuel des sauts, partagé entre le geste et l'affichage. */
private class SkipState {
    var current by mutableStateOf<Skip?>(null)
    var lastAt = 0L
    /** Juste après un double appui, chaque appui simple ajoute 10 s (sans redemander un double appui). */
    val seeking: Boolean get() = current != null && android.os.SystemClock.uptimeMillis() - lastAt < 900
}

/**
 * Double appui à gauche : −10 s ; à droite : +10 s. Puis, tant que l'animation dure, chaque appui ajoute 10 s
 * (« 20 secondes », « 30 secondes »…), comme dans les lecteurs vidéo habituels. Simple appui sinon : [onTap].
 */
private fun Modifier.skipGestures(skip: SkipState, onTap: () -> Unit) = pointerInput(skip) {
    fun skipAt(o: androidx.compose.ui.geometry.Offset) {
        val side = if (o.x < size.width / 2) -1 else 1
        val prev = skip.current
        val total = if (prev != null && prev.side == side && skip.seeking) prev.total + 10 else 10
        skip.current = Skip(side, total, o, (prev?.tap ?: 0) + 1)
        skip.lastAt = android.os.SystemClock.uptimeMillis()
        Castor.skip(side * 10.0)
    }
    detectTapGestures(
        onTap = { o -> if (skip.seeking) skipAt(o) else onTap() },
        onDoubleTap = { o -> skipAt(o) },
    )
}

/**
 * Retour visuel d'un saut, du côté touché : une grande vague en arc qui part du bord, une onde depuis le doigt et,
 * au milieu, la même animation que les boutons ±10 s.
 */
@Composable
private fun SkipOverlay(skip: SkipState, modifier: Modifier = Modifier) {
    val s = skip.current
    // Disparaît peu après le dernier appui.
    LaunchedEffect(s?.tap) {
        if (s != null) { kotlinx.coroutines.delay(850); if (skip.current?.tap == s.tap) skip.current = null }
    }
    var shown by remember { mutableStateOf<Skip?>(null) }
    if (s != null) shown = s
    val visible = s != null
    val alpha by androidx.compose.animation.core.animateFloatAsState(if (visible) 1f else 0f,
        androidx.compose.animation.core.tween(if (visible) 120 else 260), label = "skip")
    val k = shown ?: return
    if (alpha == 0f && !visible) return
    // Onde depuis le doigt, relancée à chaque appui.
    val ripple = remember { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(k.tap) { ripple.snapTo(0f); ripple.animateTo(1f, androidx.compose.animation.core.tween(500)) }
    androidx.compose.foundation.layout.BoxWithConstraints(modifier.fillMaxSize().graphicsLayer { this.alpha = alpha }) {
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        val left = k.side < 0
        androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
            val half = androidx.compose.ui.geometry.Rect(if (left) 0f else w / 2f, 0f, if (left) w / 2f else w, h)
            clipRect(half.left, half.top, half.right, half.bottom) {
                // Arc : un grand cercle dont le centre est hors de l'image, côté bord.
                val r = h * 0.95f + w * 0.12f
                val cx = if (left) w * 0.42f - r else w * 0.58f + r
                drawCircle(Color.White.copy(alpha = 0.13f), radius = r, center = androidx.compose.ui.geometry.Offset(cx, h / 2f))
                // Onde depuis le doigt.
                drawCircle(Color.White.copy(alpha = 0.22f * (1f - ripple.value)), radius = (w * 0.32f) * ripple.value, center = k.at)
            }
        }
        // Au milieu du côté touché : la même animation que les boutons ±10 s (rebond, flèche qui pivote, onde,
        // « +10 » qui monte et s'additionne).
        Box(
            Modifier.fillMaxHeight().fillMaxWidth(0.5f).align(if (left) Alignment.CenterStart else Alignment.CenterEnd),
            contentAlignment = Alignment.Center,
        ) {
            SkipBurst(forward = !left, tap = k.tap, total = k.total) { iconMod ->
                Box(Modifier.size(56.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.35f)), contentAlignment = Alignment.Center) {
                    Icon(if (left) Icons.Rounded.Replay10 else Icons.Rounded.Forward10,
                        stringResource(R.string.skip_seconds, k.total), Modifier.size(32.dp).then(iconMod), tint = Color.White)
                }
            }
        }
    }
}

private fun Activity.orientation(o: Int) { requestedOrientation = o }

/** Dans l'écran de lecture : la vidéo à la place du grand cadre, avec un bouton plein écran. */
@Composable
fun PhoneVideo(state: CastState, active: Boolean = true, onGlow: ((Color) -> Unit)? = null) {
    val activity = androidx.activity.compose.LocalActivity.current
    val portrait = androidx.compose.ui.platform.LocalConfiguration.current.orientation != android.content.res.Configuration.ORIENTATION_LANDSCAPE
    val skip = remember { SkipState() }
    Box(
        Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(28.dp)).background(Color.Black)
            .skipGestures(skip, onTap = {}),
    ) {
        VideoSurface(Modifier.fillMaxSize(), active = portrait && active, onColor = onGlow)
        if (state.phase == Phase.SENDING || state.phase == Phase.BUFFERING || state.phase == Phase.LOADING) {
            CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color.White)
        }
        SkipOverlay(skip)
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
    val activity = androidx.activity.compose.LocalActivity.current
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
    val skip = remember { SkipState() }
    val loading = state.phase == Phase.SENDING || state.phase == Phase.BUFFERING
    Box(
        Modifier.fillMaxSize().background(Color.Black)
            .skipGestures(skip, onTap = { controls = !controls }),
    ) {
        VideoSurface(Modifier.fillMaxSize())
        SkipOverlay(skip)
        if (loading && !controls) CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color.White)
        AnimatedVisibility(controls, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f))) {
                // En haut : le logo de la chaîne (un film : son titre).
                val m = state.media
                Row(Modifier.align(Alignment.TopStart).safeDrawingPadding().padding(horizontal = 24.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    if (m != null && state.live) ChannelArt(
                        name = m.channel ?: m.title, logo = m.posterUrl, streamUrl = m.url, pad = ChannelTiles.logoPad,
                        style = LogoLab.style,
                        textStyle = MaterialTheme.typography.titleSmall.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.ExtraBold),
                        modifier = Modifier.size(width = ChannelTiles.width, height = ChannelTiles.width * 10 / 16)
                            .clip(RoundedCornerShape(ChannelTiles.corner)),
                    ) else Text(m?.title.orEmpty(), color = Color.White, style = MaterialTheme.typography.titleMedium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (m != null && state.live) {
                        androidx.compose.foundation.layout.Spacer(Modifier.size(14.dp))
                        androidx.compose.runtime.CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides Color.White) {
                            NowNext(m, Modifier.widthIn(max = 420.dp), compact = true)
                        }
                    }
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
        AnimatedSkip(forward = false, onSkip = { Castor.skip(-10.0) }, accent = Color.White) { iconMod, click ->
            IconButton(onClick = click, enabled = canSkip,
                modifier = Modifier.size(52.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.4f))) {
                Icon(Icons.Rounded.Replay10, stringResource(R.string.cd_rewind), Modifier.size(30.dp).then(iconMod), tint = Color.White)
            }
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
        AnimatedSkip(forward = true, onSkip = { Castor.skip(10.0) }, accent = Color.White) { iconMod, click ->
            IconButton(onClick = click, enabled = canSkip && (!s.live || s.liveBehind >= 1),
                modifier = Modifier.size(52.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.4f))) {
                Icon(Icons.Rounded.Forward10, stringResource(R.string.cd_forward10), Modifier.size(30.dp).then(iconMod), tint = Color.White)
            }
        }
    }
}

/** Coin où se tient la vignette (gardé d'un écran à l'autre, le temps de la session). */
private data class MiniCorner(val right: Boolean, val bottom: Boolean)
private var miniCorner by androidx.compose.runtime.mutableStateOf(MiniCorner(right = true, bottom = true))

/**
 * Hors de l'écran de lecture (accueil, chaînes…) pendant une lecture sur le téléphone : la vidéo continue dans une
 * petite vignette. Un appui ramène à l'écran de lecture. Comme sur YouTube, on la déplace du doigt (elle se range dans
 * le coin le plus proche), et on la jette hors de l'écran par un côté pour arrêter la lecture.
 */
@Composable
fun MiniPlayer(state: CastState, bottom: androidx.compose.ui.unit.Dp, modifier: Modifier = Modifier) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val density = androidx.compose.ui.platform.LocalDensity.current
    val closeLabel = stringResource(R.string.action_stop)
    androidx.compose.foundation.layout.BoxWithConstraints(
        modifier.fillMaxSize().statusBarsPadding().padding(start = 12.dp, end = 12.dp, top = 72.dp, bottom = bottom),
    ) {
        val w = with(density) { 192.dp.toPx() }
        val h = with(density) { 108.dp.toPx() }
        val maxX = (constraints.maxWidth - w).coerceAtLeast(0f)
        val maxY = (constraints.maxHeight - h).coerceAtLeast(0f)
        fun spot(c: MiniCorner) = androidx.compose.ui.geometry.Offset(if (c.right) maxX else 0f, if (c.bottom) maxY else 0f)
        val pos = remember { androidx.compose.animation.core.Animatable(spot(miniCorner), androidx.compose.ui.geometry.Offset.VectorConverter) }
        // La place change (bouton « 1 vidéo » du navigateur, rotation) : la vignette suit, en douceur.
        LaunchedEffect(maxX, maxY) { if (!pos.isRunning) pos.animateTo(spot(miniCorner), androidx.compose.animation.core.tween(220)) }
        var leaving by remember { mutableStateOf(false) }
        fun dismiss(toRight: Boolean) {
            leaving = true
            scope.launch {
                pos.animateTo(pos.value.copy(x = if (toRight) maxX + w * 1.4f else -w * 1.4f), androidx.compose.animation.core.tween(200))
                Castor.stop()
            }
        }
        Box(
            Modifier
                .offset { androidx.compose.ui.unit.IntOffset(pos.value.x.toInt(), pos.value.y.toInt()) }
                .size(width = 192.dp, height = 108.dp)
                .graphicsLayer {
                    // S'efface à mesure qu'elle sort de l'écran.
                    val out = maxOf(-pos.value.x, pos.value.x - maxX).coerceAtLeast(0f)
                    alpha = (1f - out / (w * 1.1f)).coerceIn(0f, 1f)
                }
                .androidx_shadow()
                .clip(RoundedCornerShape(16.dp)).background(Color.Black)
                .pointerInput(maxX, maxY) {
                    val tracker = androidx.compose.ui.input.pointer.util.VelocityTracker()
                    var total = androidx.compose.ui.geometry.Offset.Zero
                    detectDragGestures(
                        onDragStart = { tracker.resetTracking(); total = androidx.compose.ui.geometry.Offset.Zero },
                        onDrag = { change, drag ->
                            change.consume()
                            total += drag
                            tracker.addPosition(change.uptimeMillis, total)
                            scope.launch { pos.snapTo(pos.value + drag) }
                        },
                        onDragEnd = {
                            val v = tracker.calculateVelocity()
                            val p = pos.value
                            val fast = with(density) { 1800.dp.toPx() }
                            // Jetée : plus d’à moitié sortie par un côté, ou lancée vite vers le bord dont elle est proche.
                            val outLeft = p.x < -w * 0.55f || (v.x < -fast && p.x < maxX * 0.3f)
                            val outRight = p.x > maxX + w * 0.55f || (v.x > fast && p.x > maxX * 0.7f)
                            if (outLeft || outRight) dismiss(outRight)
                            else {
                                // Sinon, le coin vers lequel elle allait (position + un peu d'élan).
                                val c = MiniCorner(right = p.x + v.x * 0.12f > maxX / 2, bottom = p.y + v.y * 0.12f > maxY / 2)
                                miniCorner = c
                                scope.launch {
                                    pos.animateTo(spot(c), androidx.compose.animation.core.spring(dampingRatio = 0.72f,
                                        stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow),
                                        initialVelocity = androidx.compose.ui.geometry.Offset(v.x, v.y))
                                }
                            }
                        },
                        onDragCancel = { scope.launch { pos.animateTo(spot(miniCorner)) } },
                    )
                }
                .clickable(enabled = !leaving, role = androidx.compose.ui.semantics.Role.Button, onClickLabel = state.media?.title) {
                    Nav.iptv.value = null
                    Nav.tab.value = Tab.PLAYER
                }
                .semantics {
                    customActions = listOf(androidx.compose.ui.semantics.CustomAccessibilityAction(closeLabel) { Castor.stop(); true })
                },
        ) {
            VideoSurface(Modifier.fillMaxSize())
            if (state.phase == Phase.SENDING || state.phase == Phase.BUFFERING) {
                CircularProgressIndicator(Modifier.align(Alignment.Center).size(28.dp), color = Color.White, strokeWidth = 3.dp)
            }
        }
    }
}

private fun Modifier.androidx_shadow() = this.shadow(12.dp, RoundedCornerShape(16.dp))

/** Couleur d'ensemble de l'image, un peu ravivée (une image terne donnerait une lueur grise). */
private fun dominant(b: android.graphics.Bitmap): Color {
    var r = 0L; var g = 0L; var bl = 0L; var n = 0
    val px = IntArray(b.width * b.height)
    b.getPixels(px, 0, b.width, 0, 0, b.width, b.height) // en une fois (576 pixels)
    for (p in px) {
        r += android.graphics.Color.red(p); g += android.graphics.Color.green(p); bl += android.graphics.Color.blue(p); n++
    }
    if (n == 0) return Color.Transparent
    val hsv = FloatArray(3)
    android.graphics.Color.RGBToHSV((r / n).toInt(), (g / n).toInt(), (bl / n).toInt(), hsv)
    // Sur le fond sombre de l'appli, une lueur trop sombre ne se verrait pas : on la garde assez claire et colorée.
    hsv[1] = (hsv[1] * 1.6f).coerceIn(0.25f, 1f)
    hsv[2] = hsv[2].coerceIn(0.45f, 0.85f)
    return Color(android.graphics.Color.HSVToColor(hsv))
}
