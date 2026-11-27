package studio.rollando.tvcast.proxy

import android.util.Log
import java.io.File

/**
 * Contrôle du direct : le téléphone enregistre le direct au fil de l'eau, morceau par morceau, dans son cache,
 * que la télé lise ou soit en pause. Beaucoup de chaînes ne gardent qu'une minute en ligne : sans cet
 * enregistrement, une pause un peu longue faisait sauter la télé au direct à la reprise.
 *
 * Une ligne du temps (secondes) relie les morceaux ; la télé lit à son rythme ; on sait donc à tout moment de combien
 * elle est en retard sur le direct, et jusqu'où on peut remonter.
 */
class LiveBuffer(
    private val headers: Map<String, String>,
    first: Hls.Plan,
    private val dir: File,
    /** Télécharge un morceau, déjà prêt à envoyer (déchiffré, recollé au besoin). */
    private val fetch: (Hls.Seg) -> ByteArray?,
) {
    class Entry(val seg: Hls.Seg, val time: Double) {
        @Volatile var file: File? = null
        @Volatile var failed = false
        val end: Double get() = time + seg.duration
    }

    private val lock = Object()
    private val entries = ArrayList<Entry>()
    private var plan = first
    /** Plus ancien morceau qu'un lecteur peut encore demander : on enregistre tout à partir de lui. */
    private var fromSeq = Long.MAX_VALUE
    @Volatile private var alive = true
    /** Fin (sur la ligne du temps) du dernier morceau envoyé à la télé. */
    @Volatile var readerEnd = -1.0

    init {
        dir.deleteRecursively()
        dir.mkdirs()
        append(first)
        Thread(::run, "direct-enregistrement").apply { isDaemon = true }.start()
    }

    private fun append(p: Hls.Plan) = synchronized(lock) {
        val lastSeq = entries.lastOrNull()?.seg?.seq ?: Long.MIN_VALUE
        var t = entries.lastOrNull()?.end ?: 0.0
        for (s in p.segs) if (s.seq > lastSeq) { entries += Entry(s, t); t += s.duration }
        lock.notifyAll()
    }

    /** Fin du dernier morceau connu : le direct. */
    val head: Double get() = synchronized(lock) { entries.lastOrNull()?.end ?: 0.0 }

    /** Ce qu'on peut encore lire : enregistré, ou toujours en ligne chez la chaîne. */
    private fun available(e: Entry): Boolean = e.file != null || plan.segs.any { it.seq == e.seg.seq }

    /** Le « direct » tel qu'on le sert : 3 morceaux avant le plus récent (la marge habituelle des lecteurs). */
    private fun livePoint(): Double = entries.getOrNull(entries.size - 3)?.time ?: (entries.firstOrNull()?.time ?: 0.0)

    /** Jusqu'où on peut remonter, en secondes avant le direct. */
    val window: Double get() = synchronized(lock) {
        val first = entries.firstOrNull { available(it) } ?: return 0.0
        (livePoint() - first.time).coerceAtLeast(0.0)
    }

    /** Retard de ce que la télé a reçu sur le direct (indicatif : la télé garde une réserve d'avance). */
    val behind: Double get() = if (readerEnd < 0) 0.0 else (head - readerEnd).coerceAtLeast(0.0)

    /** Morceau où commencer pour être « retard » secondes avant le direct. */
    fun startFor(behindWanted: Double): Long = synchronized(lock) {
        val target = livePoint() - behindWanted
        var i = if (behindWanted <= 0) entries.size - 3 else entries.indexOfLast { it.time <= target + 0.01 }
        i = i.coerceIn(0, maxOf(0, entries.size - 1))
        // Pas plus loin que ce qui est encore lisible.
        while (i < entries.size - 1 && !available(entries[i])) i++
        val seq = entries[i].seg.seq
        if (seq < fromSeq) fromSeq = seq
        lock.notifyAll()
        seq
    }

    /** Attend le morceau « seq » enregistré (ou le suivant lisible, s'il a été perdu). Null : arrêt. */
    fun await(seq: Long): Entry? = synchronized(lock) {
        var want = seq
        while (alive) {
            val e = entries.firstOrNull { it.seg.seq >= want }
            if (e != null) {
                if (e.file != null) return e
                if (e.failed || !available(e)) {
                    // Perdu (téléchargement impossible, ou sorti de la liste de la chaîne) : on passe au suivant.
                    val next = entries.firstOrNull { it.seg.seq > e.seg.seq }
                    if (next != null) { want = next.seg.seq; continue }
                }
            }
            lock.wait(1000)
        }
        null
    }


    fun close() {
        alive = false
        synchronized(lock) { lock.notifyAll() }
        Thread { dir.deleteRecursively() }.start()
    }

    // ---------------------------------------------------------------- enregistrement

    private fun run() {
        var lastReload = System.currentTimeMillis()
        while (alive) {
            val next = synchronized(lock) {
                entries.firstOrNull { it.seg.seq >= fromSeq && it.file == null && !it.failed }
            }
            if (next != null) {
                val data = fetch(next.seg)
                synchronized(lock) {
                    if (data == null) next.failed = true
                    else runCatching {
                        val f = File(dir, "${next.seg.seq}.seg")
                        f.writeBytes(data)
                        next.file = f
                    }.onFailure { next.failed = true }
                    lock.notifyAll()
                }
                trim()
            }
            // Nouvelle version de la liste du direct environ toutes les demi-durées de morceau.
            val every = (plan.targetDuration * 500).toLong().coerceIn(1000, 5000)
            if (System.currentTimeMillis() - lastReload >= every || next == null) {
                if (next == null) sleep(every)
                runCatching { Hls.reload(plan, headers) }.onSuccess { p -> plan = p; append(p) }
                    .onFailure { Log.w("TvCastLive", "liste du direct : ${it.message}") }
                lastReload = System.currentTimeMillis()
            }
        }
    }

    /** Pas plus de 45 minutes ni d'1 Go enregistrés : on oublie le plus ancien. */
    private fun trim() = synchronized(lock) {
        var total = entries.sumOf { it.file?.length() ?: 0L }
        while (entries.size > 3) {
            val oldest = entries.first()
            val span = entries.last().end - oldest.time
            if (span <= 45 * 60 && total <= 1_000_000_000L) break
            if (readerEnd in oldest.time..oldest.end) break // la télé lit encore celui-ci
            total -= oldest.file?.length() ?: 0L
            oldest.file?.delete()
            entries.removeAt(0)
        }
        // Morceaux jamais enregistrés et sortis de la liste de la chaîne : on ne pourra plus les lire.
        val keepFrom = entries.indexOfFirst { available(it) }
        if (keepFrom > 0) repeat(keepFrom) { entries.removeAt(0) }
    }

    private fun sleep(ms: Long) = synchronized(lock) { if (alive) lock.wait(ms) }
}
