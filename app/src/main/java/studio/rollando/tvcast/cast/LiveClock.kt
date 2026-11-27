package studio.rollando.tvcast.cast

/**
 * Retard sur le direct, compté à l'horloge : celui demandé au départ, plus tout le temps passé sans lecture
 * (pause, chargement). Stable, et juste à la seconde ; le téléphone n'a pas à deviner ce que la télé a en réserve.
 */
internal class LiveClock {
    private var base = 0.0
    private var since = 0L
    private var played = 0.0
    private var tick = 0L
    /** La lecture a vraiment démarré (avant : on affiche le retard demandé, sans le faire grandir). */
    private var started = false
    @Synchronized fun start(behind: Double) {
        val n = System.currentTimeMillis(); base = behind; since = n; played = 0.0; tick = n; started = false
    }
    @Synchronized fun update(playing: Boolean): Double {
        val n = System.currentTimeMillis()
        if (since == 0L) return 0.0
        // Le direct se mesure depuis le vrai départ de la lecture : le temps de chargement ne compte pas (sinon
        // « Revenir au direct » afficherait aussitôt « −0:05 »). Avant le départ, le retard demandé, sans bouger.
        if (!started) {
            if (playing) { started = true; since = n; played = 0.0 }
            tick = n
            return base
        }
        if (playing) played += (n - tick) / 1000.0
        tick = n
        return (base + (n - since) / 1000.0 - played).coerceAtLeast(0.0)
    }
}
