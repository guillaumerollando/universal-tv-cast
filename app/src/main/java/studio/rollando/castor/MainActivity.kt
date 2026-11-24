package studio.rollando.castor

import android.Manifest
import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
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

class MainActivity : ComponentActivity() {
    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Castor.init(this)
        if (savedInstanceState == null) handle(intent)
        setContent { CastorTheme { CastorApp() } }
        if (Castor.state.value.renderers.isEmpty()) Castor.discover()
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        val media = Intake.parse(this, intent) ?: return
        Castor.open(media)
        Nav.tab.value = Tab.PLAYER
    }

    /** Les boutons de volume du téléphone règlent le son de la télé pendant la diffusion. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val s = Castor.state.value
        if (s.active && s.renderer?.rcUrl != null &&
            (event.keyCode == KeyEvent.KEYCODE_VOLUME_UP || event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)
        ) {
            if (event.action == KeyEvent.ACTION_DOWN) Castor.changeVolume(if (event.keyCode == KeyEvent.KEYCODE_VOLUME_UP) 2 else -2)
            return true
        }
        return super.dispatchKeyEvent(event)
    }
}
