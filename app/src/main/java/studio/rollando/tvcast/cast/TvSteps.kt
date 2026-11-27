package studio.rollando.tvcast.cast

import android.util.Log
import kotlinx.coroutines.delay
import studio.rollando.tvcast.dlna.Dlna
import studio.rollando.tvcast.dlna.Renderer
import studio.rollando.tvcast.dlna.TvError

/** Gestes DLNA prudents : attendre que la télé soit prête, changer de vidéo sans « Stop », Play avec réessai. */
internal object TvSteps {
    /**
     * Play juste après un changement de vidéo : certaines télés répondent « 701 Transition not available »
     * tant qu'elles chargent encore. On attend qu'elles soient prêtes et on réessaie, au lieu d'échouer.
     */
    suspend fun playWhenReady(r: Renderer) {
        repeat(3) { attempt ->
            try {
                Dlna.play(r)
                return
            } catch (e: TvError) {
                val st = runCatching { Dlna.transportState(r) }.getOrNull()
                if (st == "PLAYING") return
                if (attempt == 2) throw e
                Log.i("TvCast", "télé pas prête pour Play (${e.detail}), nouvel essai")
                delay(700)
                waitUntilSettled(r, 4000)
            }
        }
    }

    /** Attend (au plus quelques secondes) que la télé ait fini de charger ou de se déplacer. */
    suspend fun waitUntilSettled(r: Renderer, maxMs: Long = 4000) {
        val end = System.currentTimeMillis() + maxMs
        while (System.currentTimeMillis() < end) {
            val st = runCatching { Dlna.transportState(r) }.getOrNull()
            if (st != "TRANSITIONING") return
            delay(250)
        }
    }

    /**
     * Change la vidéo de la télé sans « Stop » : sur les Philips Titan OS, Stop ferme le lecteur
     * et renvoie sur la dernière source (HDMI…). La télé refuse un changement pendant la lecture
     * (« Transport locked ») mais l'accepte en pause.
     */
    suspend fun replaceMedia(r: Renderer, url: String, meta: String) {
        val st = runCatching { Dlna.transportState(r) }.getOrNull()
        if (st == "PLAYING" || st == "TRANSITIONING") {
            waitUntilSettled(r, 3000)
            runCatching { Dlna.pause(r) }
            val end = System.currentTimeMillis() + 2000
            while (System.currentTimeMillis() < end &&
                runCatching { Dlna.transportState(r) }.getOrNull() !in setOf("PAUSED_PLAYBACK", "STOPPED", "NO_MEDIA_PRESENT")
            ) delay(200)
        }
        try {
            Dlna.setUri(r, url, meta)
        } catch (e: TvError) {
            // Dernier recours : certaines télés exigent vraiment un arrêt.
            runCatching { Dlna.stop(r) }
            delay(500)
            Dlna.setUri(r, url, meta)
        }
    }
}
