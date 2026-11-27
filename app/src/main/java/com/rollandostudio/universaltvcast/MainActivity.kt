package com.rollandostudio.universaltvcast

import android.app.Application
import android.content.Intent
import android.os.Bundle
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.KeyEvent
import androidx.lifecycle.lifecycleScope
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.rollandostudio.universaltvcast.data.Library
import kotlin.math.sqrt
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.rollandostudio.universaltvcast.cast.TvCast
import com.rollandostudio.universaltvcast.cast.Intake
import com.rollandostudio.universaltvcast.ui.TvCastApp
import com.rollandostudio.universaltvcast.ui.TvCastTheme
import com.rollandostudio.universaltvcast.ui.Nav
import com.rollandostudio.universaltvcast.ui.Tab
import com.rollandostudio.universaltvcast.ui.WidgetKit

class TvCastApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        TvCast.init(this)
        val app = this
        com.rollandostudio.universaltvcast.data.Library.onChanged = { WidgetKit.refresh(app) }
        WidgetKit.refresh(app)
    }
}

/** Extensions de fichiers vidéo ou son : lus directement, sans vérifier s'il s'agit d'une page web. */
private val MEDIA_EXT = setOf("mp4", "m4v", "mkv", "webm", "ts", "m2ts", "mts", "avi", "mov", "m3u8", "m3u", "mpd",
    "mp3", "m4a", "aac", "flac", "ogg", "opus", "wav", "jpg", "jpeg", "png", "webp", "gif", "heic")

class MainActivity : ComponentActivity(), SensorEventListener {
    private var lastShake = 0L
    private var shakeCount = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Thème sombre uniquement : icônes claires dans les barres système, quel que soit le réglage du téléphone.
        enableEdgeToEdge(
            statusBarStyle = androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        TvCast.init(this)
        if (savedInstanceState == null) handle(intent)
        setContent { TvCastTheme { TvCastApp() } }
        if (TvCast.state.value.renderers.isEmpty()) TvCast.discover()
        // Pas de demande de notifications au démarrage : elle vient au moment de la lecture, expliquée (NotificationTip).
    }

    override fun onResume() {
        super.onResume()
        val sm = getSystemService(SensorManager::class.java)
        sm?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
    }

    override fun onPause() {
        getSystemService(SensorManager::class.java)?.unregisterListener(this)
        super.onPause()
    }

    /** Secouer le téléphone (deux secousses franches en moins d'une seconde) ouvre l'aide. */
    override fun onSensorChanged(e: SensorEvent) {
        if (!Library.shakeHelp.value || Nav.help.value) return
        val g = sqrt(e.values[0] * e.values[0] + e.values[1] * e.values[1] + e.values[2] * e.values[2]) / SensorManager.GRAVITY_EARTH
        if (g < 2.7f) return
        val now = System.currentTimeMillis()
        if (now - lastShake < 250) return
        shakeCount = if (now - lastShake < 1000) shakeCount + 1 else 1
        lastShake = now
        if (shakeCount >= 2) {
            shakeCount = 0
            Nav.help.value = true
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onStart() {
        super.onStart()
        // Retour dans l'appli : on revérifie quelles télés sont allumées (rien ne passe : pas de risque de couper).
        val s = com.rollandostudio.universaltvcast.cast.TvCast.state.value
        if (!s.active && !s.searching && s.renderers.isNotEmpty()) com.rollandostudio.universaltvcast.cast.TvCast.discover()
    }

    override fun onStop() {
        super.onStop()
        // En petite fenêtre, la vidéo continue ; fenêtre fermée (ou appli quittée sans elle) : on met en pause.
        if (!isInPictureInPictureMode) com.rollandostudio.universaltvcast.cast.TvCast.appHidden()
    }

    // ------------------------------------------------------------------ image dans l'image

    /** Vidéo en cours sur le téléphone : en quittant l'appli, elle continue dans une petite fenêtre. */
    private fun pipWanted(): Boolean {
        val s = com.rollandostudio.universaltvcast.cast.TvCast.state.value
        return s.onPhone && s.media != null && s.slides == null &&
            (s.phase == com.rollandostudio.universaltvcast.cast.Phase.PLAYING || s.phase == com.rollandostudio.universaltvcast.cast.Phase.BUFFERING) &&
            packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE)
    }

    private fun pipParams(): android.app.PictureInPictureParams {
        val a = com.rollandostudio.universaltvcast.cast.PhoneEngine.aspect.coerceIn(0.42f, 2.38f) // limites d'Android
        val ratio = android.util.Rational((a * 1000).toInt(), 1000)
        fun action(icon: Int, label: Int, act: String, code: Int) = android.app.RemoteAction(
            android.graphics.drawable.Icon.createWithResource(this, icon), getString(label), getString(label),
            android.app.PendingIntent.getService(this, 100 + code,
                Intent(this, com.rollandostudio.universaltvcast.cast.CastService::class.java).setAction(act),
                android.app.PendingIntent.FLAG_IMMUTABLE),
        )
        val paused = com.rollandostudio.universaltvcast.cast.TvCast.state.value.phase == com.rollandostudio.universaltvcast.cast.Phase.PAUSED
        return android.app.PictureInPictureParams.Builder().setAspectRatio(ratio).setActions(listOf(
            action(R.drawable.ic_n_rewind, R.string.cd_rewind, com.rollandostudio.universaltvcast.cast.CastService.ACTION_BACK, 1),
            action(if (paused) R.drawable.ic_n_play else R.drawable.ic_n_pause, R.string.action_play, com.rollandostudio.universaltvcast.cast.CastService.ACTION_TOGGLE, 2),
            action(R.drawable.ic_n_forward, R.string.cd_forward10, com.rollandostudio.universaltvcast.cast.CastService.ACTION_FWD, 3),
        )).apply { if (Build.VERSION.SDK_INT >= 31) setSeamlessResizeEnabled(true) }.build()
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (pipWanted()) runCatching { enterPictureInPictureMode(pipParams()) }
    }

    override fun onPictureInPictureModeChanged(inPip: Boolean, newConfig: android.content.res.Configuration) {
        super.onPictureInPictureModeChanged(inPip, newConfig)
        com.rollandostudio.universaltvcast.ui.Nav.pip.value = inPip
        // Lecture / pause : l'icône de la petite fenêtre suit.
        if (inPip) pipWatch = lifecycleScope.launch {
            com.rollandostudio.universaltvcast.cast.TvCast.state.collect { runCatching { setPictureInPictureParams(pipParams()) } }
        } else pipWatch?.cancel()
    }

    private var pipWatch: kotlinx.coroutines.Job? = null

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        // Rouverte depuis les applis récentes : Android redonne le lien d'origine. Ce n'est pas une nouvelle demande,
        // on ne le relance pas sur la télé.
        if (intent != null && intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return
        if (widget(intent)) return
        val parsed = Intake.parse(this, intent) ?: return
        val from = referrer?.takeIf { it.scheme == "android-app" }?.host?.takeIf { it != packageName }
        val media = parsed.copy(sourceApp = from)
        val url = media.url
        // Lien sans extension de vidéo (« Partager » depuis un navigateur…) : si c'est une page web, on l'ouvre dans
        // le navigateur de l'appli, qui y repérera la vidéo, au lieu d'essayer de lire la page elle-même.
        if ((url.startsWith("http://") || url.startsWith("https://")) && com.rollandostudio.universaltvcast.net.Mime.ext(url) !in MEDIA_EXT) {
            lifecycleScope.launch {
                val page = withContext(Dispatchers.IO) {
                    runCatching {
                        val c = com.rollandostudio.universaltvcast.net.Http.open(url, media.headers, range = "bytes=0-0", connectTimeout = 5000, readTimeout = 5000)
                        try { c.contentType?.lowercase()?.startsWith("text/html") == true } finally { c.disconnect() }
                    }.getOrDefault(false)
                }
                if (page) Nav.openUrl.value = url else play(media)
            }
            return
        }
        play(media)
    }

    /** Appui sur un widget : reprendre une vidéo, relancer une chaîne, ouvrir un site. */
    private fun widget(intent: Intent?): Boolean {
        intent ?: return false
        val key = intent.getStringExtra(WidgetKit.EXTRA_KEY)
        when (intent.action) {
            WidgetKit.ACTION_RESUME -> {
                key ?: return true
                val r = com.rollandostudio.universaltvcast.ui.resumeItems(Library.recents.value, Library.bookmarks.value).firstOrNull { it.key == key } ?: return true
                val page = r.openUrl
                if (page != null) {
                    // Page d'un site : rouverte en arrière-plan, la vidéo part seule (l'accueil montre la reprise).
                    Nav.tab.value = Tab.HOME
                    if (Nav.resuming.value == null) { Nav.resuming.value = page; Nav.nextEpisode.value = page }
                } else r.recent?.let { TvCast.open(it.toMedia()); Nav.tab.value = Tab.PLAYER }
            }
            WidgetKit.ACTION_CHANNEL -> {
                key ?: return true
                val c = Library.recents.value.firstOrNull { it.key == key && it.live } ?: return true
                val page = c.pageUrl
                if (page != null && page.startsWith("http")) Nav.openUrl.value = page
                else { TvCast.open(c.toMedia().copy(startSec = 0.0, title = c.channelName)); Nav.tab.value = Tab.PLAYER }
            }
            WidgetKit.ACTION_SITE -> intent.getStringExtra(WidgetKit.EXTRA_URL)?.let { Nav.openUrl.value = it }
            // Photos et vidéos : le sélecteur d'Android s'ouvre tout de suite (voir TvCastApp).
            WidgetKit.ACTION_PICK -> { Nav.tab.value = Tab.HOME; Nav.pick.value = intent.getStringExtra(WidgetKit.EXTRA_WHAT) }
            else -> return false
        }
        return true
    }

    /** Une autre appli nous envoie une vidéo (« Caster sur la télé ») : la télé est le choix explicite. */
    private fun play(media: com.rollandostudio.universaltvcast.cast.Media) {
        TvCast.open(media, toTv = true)
        Nav.tab.value = Tab.PLAYER
    }

    /** Les boutons de volume du téléphone règlent le son de la télé pendant la diffusion. */
    @android.annotation.SuppressLint("RestrictedApi") // faux positif connu : surcharge publique d’Activity
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val s = TvCast.state.value
        if (s.active && !s.onPhone && (s.renderer?.rcUrl != null || s.renderer?.cast == true) &&
            (event.keyCode == KeyEvent.KEYCODE_VOLUME_UP || event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)
        ) {
            if (event.action == KeyEvent.ACTION_DOWN) TvCast.changeVolume(if (event.keyCode == KeyEvent.KEYCODE_VOLUME_UP) 2 else -2)
            return true
        }
        return super.dispatchKeyEvent(event)
    }
}
