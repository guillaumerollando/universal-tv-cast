package com.rollandostudio.universaltvcast.data

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

/**
 * Ménage au démarrage : les caches de l'appli ont chacun leur plafond (images 30 Mo, affiches 25 Mo…), sauf celui du
 * navigateur intégré (WebView). Au-delà de 150 Mo de cache en tout, on vide celui du navigateur ; Android peut aussi
 * vider le cache de lui-même quand la place manque.
 */
object Housekeeping {
    private const val CACHE_MAX = 150L * 1024 * 1024

    fun run(ctx: Context) {
        val app = ctx.applicationContext
        Thread({
            runCatching {
                val cache = app.cacheDir
                if (cache.walkTopDown().filter { it.isFile }.sumOf { it.length() } <= CACHE_MAX) return@runCatching
                // Le cache HTTP de Chromium (WebView) : il se refait au fil de la navigation.
                cache.listFiles()?.filter { it.isDirectory && it.name.equals("WebView", true) }?.forEach { it.deleteRecursively() }
                android.util.Log.i("TvCast", "ménage : cache du navigateur vidé (plus de 150 Mo de cache)")
            }
        }, "tvcast-menage").apply { priority = Thread.MIN_PRIORITY }.start()
    }
}
