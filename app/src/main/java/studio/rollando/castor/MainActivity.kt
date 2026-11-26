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
    }
}

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
        val parsed = Intake.parse(this, intent) ?: return
        val from = referrer?.takeIf { it.scheme == "android-app" }?.host?.takeIf { it != packageName }
        val media = parsed.copy(sourceApp = from)
        // Une autre appli nous envoie une vidéo (« Caster sur la télé ») : la télé est le choix explicite.
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
