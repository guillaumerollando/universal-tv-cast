package studio.rollando.castor.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Économie d'énergie : choisie dans les Réglages, ou automatique quand le téléphone est lui-même en économie d'énergie.
 * Elle retire ce qui ne sert qu'au confort visuel (lueur de la vidéo, flou en verre fumé, ondes animées) et espace le
 * suivi du direct. La vidéo, elle, garde toute sa qualité.
 */
object Power {
    private val _saving = MutableStateFlow(false)
    val saving: StateFlow<Boolean> = _saving
    private var system = false

    fun init(ctx: Context) {
        val app = ctx.applicationContext
        val pm = app.getSystemService(Context.POWER_SERVICE) as PowerManager
        system = pm.isPowerSaveMode
        refresh()
        app.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) { system = pm.isPowerSaveMode; refresh() }
        }, IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED))
    }

    /** Le réglage de l'appli a changé, ou celui du téléphone. */
    fun refresh() { _saving.value = Library.powerSaver.value || system }

    /** Le téléphone est en économie d'énergie (l'appli suit, même sans réglage). */
    val bySystem: Boolean get() = system
}
