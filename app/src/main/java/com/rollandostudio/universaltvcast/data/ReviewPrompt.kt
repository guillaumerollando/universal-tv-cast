package com.rollandostudio.universaltvcast.data

import android.content.Context

/**
 * Quand proposer de noter l'appli (fenêtre officielle du Play Store), sans jamais déranger :
 * - après de vrais bons moments : au moins 3 vidéos regardées 10 minutes ou plus (ou jusqu'au bout), sur au moins
 *   2 jours différents, et l'appli installée depuis 3 jours au moins ;
 * - au calme : en revenant sur l'accueil, jamais pendant une lecture ;
 * - rarement : 3 fois au plus en tout, espacées de 4 mois (et Google en limite encore le nombre de son côté).
 * Aucune question avant (« Vous aimez l'appli ? »), aucune récompense : les règles de Google et du studio.
 */
object ReviewPrompt {
    private const val PREFS = "review"
    private const val MIN_GOOD = 3
    private const val MIN_DAYS = 2
    private const val MIN_AGE_MS = 3L * 24 * 3600 * 1000
    private const val GAP_MS = 120L * 24 * 3600 * 1000
    private const val MAX_ASKS = 3

    private lateinit var app: Context
    private val prefs get() = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun init(ctx: Context) {
        app = ctx.applicationContext
        if (!prefs.contains("since")) prefs.edit().putLong("since", System.currentTimeMillis()).apply()
    }

    /** Une vidéo a été bien regardée (appelé à chaque point d'avancement ; compté une fois par vidéo). */
    fun watched(url: String, pos: Double, dur: Double, finished: Boolean = false) {
        if (!::app.isInitialized) return
        if (!(pos >= 600 || (finished && dur >= 300))) return
        val key = url.hashCode().toString()
        val seen = prefs.getStringSet("seen", emptySet()).orEmpty()
        if (key in seen) return
        val day = (System.currentTimeMillis() / 86_400_000L).toString()
        prefs.edit()
            .putStringSet("seen", (seen + key).toList().takeLast(50).toSet())
            .putInt("good", prefs.getInt("good", 0) + 1)
            .putStringSet("days", (prefs.getStringSet("days", emptySet()).orEmpty() + day).toList().takeLast(10).toSet())
            .apply()
    }

    /** Le bon moment est-il venu ? (À appeler en arrivant sur l'accueil, rien en lecture.) */
    fun due(): Boolean {
        if (!::app.isInitialized) return false
        val p = prefs
        val now = System.currentTimeMillis()
        return p.getInt("good", 0) >= MIN_GOOD &&
            p.getStringSet("days", emptySet()).orEmpty().size >= MIN_DAYS &&
            now - p.getLong("since", now) >= MIN_AGE_MS &&
            now - p.getLong("asked", 0) >= GAP_MS &&
            p.getInt("asks", 0) < MAX_ASKS
    }

    /** La fenêtre a été demandée : on repart pour 4 mois au moins. */
    fun asked() {
        prefs.edit().putLong("asked", System.currentTimeMillis()).putInt("asks", prefs.getInt("asks", 0) + 1)
            .putInt("good", 0).remove("days").apply()
    }
}
