package studio.rollando.tvcast.browser

/**
 * Repère un numéro d'épisode dans l'adresse d'une page (« …/episode-12 », « …-ep-12-vostfr/ », « ?ep=12 »,
 * « …/saison-1/12 ») et construit l'adresse de l'épisode suivant. Sans garantie : c'est le site qui décide,
 * mais la plupart des sites de streaming suivent ces formes.
 */
object Episodes {
    /** [explicit] : le mot « épisode » (ou ep=) est dans l'adresse, pas seulement un nombre à la fin. */
    data class Ep(val number: Int, val next: String, val explicit: Boolean = true, val at: Int = 0, val len: Int = 0)

    /**
     * Clé d'une série : l'adresse sans le numéro d'épisode (ni http, www, #ancre). Deux épisodes de la même
     * série ont la même clé ; deux séries d'un même site, non.
     */
    fun seriesKey(url: String): String {
        val u = url.substringBefore('#')
        val ep = find(u)
        val base = if (ep != null) u.substring(0, ep.at) + "#" + u.substring(ep.at + ep.len) else u.substringBefore('?')
        // Identifiant qui porte aussi le numéro de partie (Arte : « 130430-003-A ») : sans lui, chaque épisode
        // aurait sa propre clé et « Reprendre » montrerait la série plusieurs fois.
        val noId = if (ep != null) PROGRAM_ID.replace(base) { m -> "/${m.groupValues[1]}-#-${m.groupValues[3]}/" } else base
        return noId.substringAfter("://").removePrefix("www.").trimEnd('/').lowercase()
    }

    private val QUERY = Regex("(?i)([?&](?:ep|episode|e)=)(\\d{1,4})(?!\\d)")
    // Le mot doit commencer là (« prisonnier-e-951 » n'est pas « e951 ») : pas de lettre juste avant.
    private val NAMED = Regex("(?i)(?<![a-z])(?:episode|episodio|épisode|folge|ep|e)[-_ ./]?(\\d{1,4})(?!\\d)")
    /** « …-1-4/ » : partie 1 sur 4 (Arte et d'autres écrivent ainsi « (1/4) »). */
    private val PART_OF = Regex("[-_](\\d{1,2})[-_](\\d{1,2})/?$")
    private val TRAILING = Regex("(?i)[-_/](\\d{1,4})(?=(?:[-_/.](?:vostfr|vf|vo|fr|en|sub|dub|hd|html?|php))*/?$)")

    fun find(url: String): Ep? {
        val cut = url.indexOfFirst { it == '?' || it == '#' }.let { if (it < 0) url.length else it }
        val path = url.substring(0, cut)
        val rest = url.substring(cut)
        QUERY.find(rest)?.let { m -> return build(url, cut + m.groups[2]!!.range.first, m.groupValues[2]) }
        val after = path.indexOf("://").let { if (it < 0) 0 else path.indexOf('/', it + 3).coerceAtLeast(0) }
        val p = path.substring(after)
        val named = NAMED.findAll(p).lastOrNull()
        if (named != null) return build(url, after + named.groups[1]!!.range.first, named.groupValues[1])
        PART_OF.find(p)?.let { m ->
            val n = m.groupValues[1].toInt(); val of = m.groupValues[2].toInt()
            if (n in 1..of && of in 2..99) return build(url, after + m.groups[1]!!.range.first, m.groupValues[1])?.let { e ->
                // Dernière partie : pas de suivant.
                if (n == of) e.copy(next = "") else e.copy(next = bumpProgramId(e.next, n))
            }
        }
        val t = TRAILING.find(p) ?: return null
        return build(url, after + t.groups[1]!!.range.first, t.groupValues[1])?.copy(explicit = false)
    }

    /** Identifiant de programme qui porte aussi le numéro de partie (Arte : « 130430-001-A » → « 130430-002-A »). */
    private val PROGRAM_ID = Regex("/(\\d{4,})-(\\d{3})-([A-Z])/")

    internal fun bumpProgramId(next: String, n: Int): String {
        val m = PROGRAM_ID.find(next) ?: return next
        if (m.groupValues[2].toInt() != n) return next
        val id = "/${m.groupValues[1]}-${(n + 1).toString().padStart(3, '0')}-${m.groupValues[3]}/"
        return next.replaceRange(m.range, id)
    }

    private fun build(url: String, at: Int, digits: String): Ep? {
        val n = digits.toIntOrNull() ?: return null
        if (n in 1900..2100) return null // une année, pas un épisode
        val next = (n + 1).toString().padStart(digits.length, '0')
        return Ep(n, url.substring(0, at) + next + url.substring(at + digits.length), at = at, len = digits.length)
    }
}
