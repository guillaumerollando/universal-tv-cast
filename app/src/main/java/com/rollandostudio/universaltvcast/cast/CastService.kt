package com.rollandostudio.universaltvcast.cast

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import com.rollandostudio.universaltvcast.MainActivity
import com.rollandostudio.universaltvcast.R
import com.rollandostudio.universaltvcast.ui.Images

/** Garde le relais vidéo en vie quand l'écran est éteint ou l'appli en arrière-plan. */
class CastService : Service() {
    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    /** Session média : vrai lecteur dans les notifications et sur l'écran verrouillé. */
    private var session: MediaSession? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        TvCast.init(this)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
            }
        )
        session = MediaSession(this, "UniversalTVCast").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() { if (isPausedNow(TvCast.state.value)) toggle() }
                override fun onPause() { if (!isPausedNow(TvCast.state.value)) toggle() }
                override fun onStop() { TvCast.stop() }
                override fun onSeekTo(pos: Long) { TvCast.seekTo(pos / 1000.0) }
                override fun onFastForward() { TvCast.skip(10.0) }
                override fun onRewind() { TvCast.skip(-10.0) }
                override fun onSkipToNext() { if (photoShown(TvCast.state.value)) TvCast.nextPhoto(1) else TvCast.skip(10.0) }
                override fun onCustomAction(action: String, extras: android.os.Bundle?) {
                    when (action) {
                        CUSTOM_BACK -> TvCast.skip(-10.0)
                        CUSTOM_FWD -> TvCast.skip(10.0)
                    }
                }
                override fun onSkipToPrevious() { if (photoShown(TvCast.state.value)) TvCast.nextPhoto(-1) else TvCast.skip(-10.0) }
            })
            isActive = true
        }
        updateSession(TvCast.state.value)
        startInForeground(build(TvCast.state.value))
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        @Suppress("DEPRECATION")
        wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "tvcast:wifi").apply { acquire() }
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "tvcast:stream").apply { acquire(6 * 60 * 60 * 1000L) }
        scope.launch {
            TvCast.state
                .map { listOf(it.media?.title, it.renderer?.name, it.phase, it.media?.posterUrl, it.duration.toLong(),
                    it.position.toLong() / 5, it.slides?.index, it.slides?.auto, it.idle) }
                .distinctUntilChanged()
                .collect { s ->
                    updateSession(TvCast.state.value)
                    nm.notify(ID, build(TvCast.state.value))
                    val poster = TvCast.state.value.media?.posterUrl
                    if (poster != null && Images.peek(poster) == null && Images.load(poster, 400) != null) {
                        nm.notify(ID, build(TvCast.state.value))
                    }
                }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> TvCast.stop()
            ACTION_TOGGLE -> toggle()
            ACTION_BACK -> if (photoShown(TvCast.state.value)) TvCast.nextPhoto(-1) else TvCast.skip(-10.0)
            ACTION_FWD -> if (photoShown(TvCast.state.value)) TvCast.nextPhoto(1) else TvCast.skip(10.0)
            else -> startInForeground(build(TvCast.state.value))
        }
        return START_NOT_STICKY
    }

    private fun startInForeground(n: Notification) {
        if (Build.VERSION.SDK_INT >= 29) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        else startForeground(ID, n)
    }

    /** Photo de l'album : lecture/pause = diaporama ; sinon la vidéo. */
    private fun photoShown(s: CastState) = s.slides != null && !(s.slides.isVideo && s.media != null)

    private fun isPausedNow(s: CastState) =
        if (photoShown(s)) s.slides?.auto != true else s.phase == Phase.PAUSED || s.phase == Phase.STOPPED

    private fun toggle() {
        if (photoShown(TvCast.state.value)) TvCast.toggleSlideshow() else TvCast.togglePlay()
    }

    private var tileKey: String? = null
    private var tile: android.graphics.Bitmap? = null

    /**
     * Image du lecteur du système : l'affiche s'il y en a une ; sinon une vignette à la couleur de la chaîne (ou
     * tirée du titre) avec son nom. Sans image, Android met l'icône de l'appli en grand (double logo) et le fond
     * prend une couleur au hasard.
     */
    private fun artwork(s: CastState): android.graphics.Bitmap? {
        val m = s.media ?: return null
        val poster = Images.peek(m.posterUrl)
        // Direct avec logo : le logo centré sur le fond choisi pour lui dans l'appli (même rendu que l'écran de lecture).
        val logoBg = m.posterUrl?.let { com.rollandostudio.universaltvcast.ui.ChannelArtColors.background[it] }
        if (s.live && poster != null && logoBg != null) {
            val key = "logo|" + m.posterUrl
            if (key == tileKey) return tile
            val size = 512
            val bmp = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
            val c = android.graphics.Canvas(bmp)
            c.drawColor(logoBg)
            val box = size * 0.72f
            val k = minOf(box / poster.width, box / poster.height)
            val w = poster.width * k
            val h = poster.height * k
            c.drawBitmap(poster, null, android.graphics.RectF((size - w) / 2, (size - h) / 2, (size + w) / 2, (size + h) / 2),
                android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG or android.graphics.Paint.FILTER_BITMAP_FLAG))
            tileKey = key
            tile = bmp
            return bmp
        }
        poster?.let { return it }
        val key = m.title + "|" + m.url
        if (key == tileKey) return tile
        // Le nom et la couleur d'une chaîne connue : seulement pour un direct (un film peut s'appeler « Histoire »…).
        val known = if (s.live) com.rollandostudio.universaltvcast.data.Channels.find("${m.url} ${m.pageUrl} ${m.title}") else null
        val color = (known?.color ?: com.rollandostudio.universaltvcast.data.Channels.colorFor(m.title)).toInt()
        val size = 512
        val bmp = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(bmp)
        val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        val dark = android.graphics.Color.rgb(
            (android.graphics.Color.red(color) * 0.45).toInt(), (android.graphics.Color.green(color) * 0.45).toInt(),
            (android.graphics.Color.blue(color) * 0.45).toInt(),
        )
        p.shader = android.graphics.LinearGradient(0f, 0f, size.toFloat(), size.toFloat(), color, dark, android.graphics.Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, size.toFloat(), size.toFloat(), p)
        p.shader = null
        p.color = android.graphics.Color.WHITE
        p.textAlign = android.graphics.Paint.Align.CENTER
        p.typeface = runCatching { androidx.core.content.res.ResourcesCompat.getFont(this, R.font.inter_display_extrabold) }.getOrNull()
            ?: android.graphics.Typeface.DEFAULT_BOLD
        val name = known?.name ?: m.title
        p.textSize = 120f
        while (p.measureText(name) > size * 0.82f && p.textSize > 40f) p.textSize -= 6f
        val fm = p.fontMetrics
        c.drawText(name, size / 2f, size / 2f - (fm.ascent + fm.descent) / 2f, p)
        tileKey = key
        tile = bmp
        return bmp
    }

    /** Titre, durée, position et état, pour le lecteur du système (notification, écran verrouillé). */
    private fun updateSession(s: CastState) {
        val ss = session ?: return
        val title = s.media?.title ?: if (s.slides != null) getString(R.string.home_photos) else getString(R.string.app_name)
        ss.setMetadata(MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, title)
            .putString(MediaMetadata.METADATA_KEY_ARTIST, s.renderer?.let { getString(R.string.notif_on_tv, it.name) } ?: "")
            .putLong(MediaMetadata.METADATA_KEY_DURATION, if (s.media != null && s.duration > 0) (s.duration * 1000).toLong() else -1L)
            .apply { artwork(s)?.let { putBitmap(MediaMetadata.METADATA_KEY_ART, it) } }
            .build())
        val state = if (photoShown(s)) {
            if (s.slides?.auto == true) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED
        } else when (s.phase) {
            Phase.PLAYING -> PlaybackState.STATE_PLAYING
            Phase.PAUSED -> PlaybackState.STATE_PAUSED
            Phase.BUFFERING, Phase.SENDING, Phase.LOADING -> PlaybackState.STATE_BUFFERING
            Phase.STOPPED -> PlaybackState.STATE_STOPPED
            Phase.IDLE -> PlaybackState.STATE_NONE
        }
        val seekable = s.media != null && s.duration > 0
        val photo = photoShown(s)
        ss.setPlaybackState(PlaybackState.Builder()
            // Direct : pas de position (le lecteur du système n'affiche alors pas de « 00:00 … 00:00 »).
            .setState(state, if (s.live) PlaybackState.PLAYBACK_POSITION_UNKNOWN else (s.position * 1000).toLong(),
                if (state == PlaybackState.STATE_PLAYING && !photo) 1f else 0f)
            .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
                PlaybackState.ACTION_STOP or
                // Album : photo précédente / suivante. Vidéo : −10 s / +10 s (boutons personnalisés ci-dessous).
                (if (photo) PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS
                else PlaybackState.ACTION_FAST_FORWARD or PlaybackState.ACTION_REWIND) or
                (if (seekable) PlaybackState.ACTION_SEEK_TO else 0L))
            .apply {
                if (!photo) {
                    addCustomAction(PlaybackState.CustomAction.Builder(CUSTOM_BACK, getString(R.string.cd_rewind), R.drawable.ic_n_rewind).build())
                    addCustomAction(PlaybackState.CustomAction.Builder(CUSTOM_FWD, getString(R.string.cd_forward10), R.drawable.ic_n_forward).build())
                }
            }
            .build())
    }

    private fun build(s: CastState): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
        fun action(a: String, req: Int) = PendingIntent.getService(this, req,
            Intent(this, CastService::class.java).setAction(a), PendingIntent.FLAG_IMMUTABLE)
        fun button(icon: Int, label: Int, a: String, req: Int) =
            Notification.Action.Builder(Icon.createWithResource(this, icon), getString(label), action(a, req)).build()
        val paused = isPausedNow(s)
        val album = photoShown(s)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle(s.media?.title ?: if (s.slides != null) getString(R.string.home_photos) else getString(R.string.app_name))
            .setContentText(s.renderer?.let { getString(R.string.notif_on_tv, it.name) } ?: "")
            .setContentIntent(open)
            .setOngoing(true)
            .apply { artwork(s)?.let { setLargeIcon(it) } }
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            // Lecteur : −10 s (ou photo précédente), lecture/pause, +10 s (ou suivante), arrêt.
            .addAction(button(if (album) R.drawable.ic_n_prev else R.drawable.ic_n_rewind,
                if (album) R.string.cd_prev_photo else R.string.cd_rewind, ACTION_BACK, 3))
            .addAction(button(if (paused) R.drawable.ic_n_play else R.drawable.ic_n_pause,
                if (paused) R.string.action_play else R.string.action_pause, ACTION_TOGGLE, 1))
            .addAction(button(if (album) R.drawable.ic_n_next else R.drawable.ic_n_forward, if (album) R.string.cd_next_photo else R.string.cd_forward10, ACTION_FWD, 4))
            .addAction(button(R.drawable.ic_n_stop, R.string.action_stop, ACTION_STOP, 2))
            .setStyle(Notification.MediaStyle().setMediaSession(session?.sessionToken).setShowActionsInCompactView(0, 1, 2))
            .build()
    }

    override fun onDestroy() {
        scope.cancel()
        runCatching { session?.release() }
        runCatching { wifiLock?.release() }
        runCatching { wakeLock?.release() }
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "cast"
        private const val ID = 1
        private const val ACTION_STOP = "com.rollandostudio.universaltvcast.STOP"
        internal const val ACTION_TOGGLE = "com.rollandostudio.universaltvcast.TOGGLE"
        internal const val ACTION_BACK = "com.rollandostudio.universaltvcast.BACK"
        private const val CUSTOM_BACK = "back10"
        private const val CUSTOM_FWD = "fwd10"
        internal const val ACTION_FWD = "com.rollandostudio.universaltvcast.FWD"

        fun start(ctx: Context) {
            runCatching { ctx.startForegroundService(Intent(ctx, CastService::class.java)) }
        }

        fun stop(ctx: Context) {
            runCatching { ctx.stopService(Intent(ctx, CastService::class.java)) }
        }
    }
}
