package studio.rollando.castor.cast

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
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
import studio.rollando.castor.MainActivity
import studio.rollando.castor.R
import studio.rollando.castor.ui.Images

/** Garde le relais vidéo en vie quand l'écran est éteint ou l'appli en arrière-plan. */
class CastService : Service() {
    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Castor.init(this)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
            }
        )
        startInForeground(build(Castor.state.value))
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        @Suppress("DEPRECATION")
        wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "castor:wifi").apply { acquire() }
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "castor:stream").apply { acquire(6 * 60 * 60 * 1000L) }
        scope.launch {
            Castor.state
                .map { listOf(it.media?.title, it.renderer?.name, it.phase, it.media?.posterUrl) }
                .distinctUntilChanged()
                .collect { s ->
                    nm.notify(ID, build(Castor.state.value))
                    val poster = Castor.state.value.media?.posterUrl
                    if (poster != null && Images.peek(poster) == null && Images.load(poster, 400) != null) {
                        nm.notify(ID, build(Castor.state.value))
                    }
                }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> Castor.stop()
            ACTION_TOGGLE -> Castor.togglePlay()
            else -> startInForeground(build(Castor.state.value))
        }
        return START_NOT_STICKY
    }

    private fun startInForeground(n: Notification) {
        if (Build.VERSION.SDK_INT >= 29) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        else startForeground(ID, n)
    }

    private fun build(s: CastState): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
        fun action(a: String, req: Int) = PendingIntent.getService(this, req,
            Intent(this, CastService::class.java).setAction(a), PendingIntent.FLAG_IMMUTABLE)
        val paused = s.phase == Phase.PAUSED
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle(s.media?.title ?: getString(R.string.app_name))
            .setContentText(s.renderer?.let { getString(R.string.notif_on_tv, it.name) } ?: "")
            .setContentIntent(open)
            .setOngoing(true)
            .apply { Images.peek(s.media?.posterUrl)?.let { setLargeIcon(it) } }
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(Notification.Action.Builder(null,
                getString(if (paused) R.string.action_play else R.string.action_pause), action(ACTION_TOGGLE, 1)).build())
            .addAction(Notification.Action.Builder(null, getString(R.string.action_stop), action(ACTION_STOP, 2)).build())
            .build()
    }

    override fun onDestroy() {
        scope.cancel()
        runCatching { wifiLock?.release() }
        runCatching { wakeLock?.release() }
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "cast"
        private const val ID = 1
        private const val ACTION_STOP = "studio.rollando.castor.STOP"
        private const val ACTION_TOGGLE = "studio.rollando.castor.TOGGLE"

        fun start(ctx: Context) {
            runCatching { ctx.startForegroundService(Intent(ctx, CastService::class.java)) }
        }

        fun stop(ctx: Context) {
            runCatching { ctx.stopService(Intent(ctx, CastService::class.java)) }
        }
    }
}
