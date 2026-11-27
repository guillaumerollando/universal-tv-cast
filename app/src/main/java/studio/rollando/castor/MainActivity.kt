package studio.rollando.castor

import android.Manifest
import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.KeyEvent
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import studio.rollando.castor.data.Library
import kotlin.math.sqrt
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import studio.rollando.castor.cast.Castor
import studio.rollando.castor.cast.Intake
import studio.rollando.castor.ui.CastorApp
import studio.rollando.castor.ui.CastorTheme
import studio.rollando.castor.ui.Nav
import studio.rollando.castor.ui.Tab

class CastorApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Castor.init(this)
        val app = this
        studio.rollando.castor.data.Library.onChanged = { studio.rollando.castor.ui.HomeWidget.refresh(app) }
        studio.rollando.castor.ui.HomeWidget.refresh(app)
    }
}

/** Extensions de fichiers vidéo ou son : lus directement, sans vérifier s'il s'agit d'une page web. */
private val MEDIA_EXT = setOf("mp4", "m4v", "mkv", "webm", "ts", "m2ts", "mts", "avi", "mov", "m3u8", "m3u", "mpd",
    "mp3", "m4a", "aac", "flac", "ogg", "opus", "wav", "jpg", "jpeg", "png", "webp", "gif", "heic")

class MainActivity : ComponentActivity(), SensorEventListener {
    private var lastShake = 0L
    private var shakeCount = 0

    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Thème sombre uniquement : icônes claires dans les barres système, quel que soit le réglage du téléphone.
        enableEdgeToEdge(
            statusBarStyle = androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        Castor.init(this)
        if (savedInstanceState == null) handle(intent)
        setContent { CastorTheme { CastorApp() } }
        if (Castor.state.value.renderers.isEmpty()) Castor.discover()
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
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
        val s = studio.rollando.castor.cast.Castor.state.value
        if (!s.active && !s.searching && s.renderers.isNotEmpty()) studio.rollando.castor.cast.Castor.discover()
    }

    override fun onStop() {
        super.onStop()
        studio.rollando.castor.cast.Castor.appHidden()
    }

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
        if ((url.startsWith("http://") || url.startsWith("https://")) && studio.rollando.castor.net.Mime.ext(url) !in MEDIA_EXT) {
            lifecycleScope.launch {
                val page = withContext(Dispatchers.IO) {
                    runCatching {
                        val c = studio.rollando.castor.net.Http.open(url, media.headers, range = "bytes=0-0", connectTimeout = 5000, readTimeout = 5000)
                        try { c.contentType?.lowercase()?.startsWith("text/html") == true } finally { c.disconnect() }
                    }.getOrDefault(false)
                }
                if (page) Nav.openUrl.value = url else play(media)
            }
            return
        }
        play(media)
    }

    /** Appui sur le widget : reprendre la vidéo, ou relancer une chaîne. */
    private fun widget(intent: Intent?): Boolean {
        val key = intent?.getStringExtra(studio.rollando.castor.ui.HomeWidget.EXTRA_KEY) ?: return intent?.action == studio.rollando.castor.ui.HomeWidget.ACTION_RESUME
        when (intent.action) {
            studio.rollando.castor.ui.HomeWidget.ACTION_RESUME -> {
                val r = studio.rollando.castor.ui.resumeItems(Library.recents.value, Library.bookmarks.value).firstOrNull { it.key == key } ?: return true
                val page = r.openUrl
                if (page != null) {
                    // Page d'un site : rouverte en arrière-plan, la vidéo part seule (l'accueil montre la reprise).
                    Nav.tab.value = Tab.HOME
                    if (Nav.resuming.value == null) { Nav.resuming.value = page; Nav.nextEpisode.value = page }
                } else r.recent?.let { Castor.open(it.toMedia()); Nav.tab.value = Tab.PLAYER }
            }
            studio.rollando.castor.ui.HomeWidget.ACTION_CHANNEL -> {
                val c = Library.recents.value.firstOrNull { it.key == key && it.live } ?: return true
                val page = c.pageUrl
                if (page != null && page.startsWith("http")) Nav.openUrl.value = page
                else { Castor.open(c.toMedia().copy(startSec = 0.0, title = c.channelName)); Nav.tab.value = Tab.PLAYER }
            }
            else -> return false
        }
        return true
    }

    /** Une autre appli nous envoie une vidéo (« Caster sur la télé ») : la télé est le choix explicite. */
    private fun play(media: studio.rollando.castor.cast.Media) {
        Castor.open(media, toTv = true)
        Nav.tab.value = Tab.PLAYER
    }

    /** Les boutons de volume du téléphone règlent le son de la télé pendant la diffusion. */
    @android.annotation.SuppressLint("RestrictedApi") // faux positif connu : surcharge publique d’Activity
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val s = Castor.state.value
        if (s.active && !s.onPhone && (s.renderer?.rcUrl != null || s.renderer?.cast == true) &&
            (event.keyCode == KeyEvent.KEYCODE_VOLUME_UP || event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)
        ) {
            if (event.action == KeyEvent.ACTION_DOWN) Castor.changeVolume(if (event.keyCode == KeyEvent.KEYCODE_VOLUME_UP) 2 else -2)
            return true
        }
        return super.dispatchKeyEvent(event)
    }
}
