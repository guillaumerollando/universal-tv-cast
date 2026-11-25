package studio.rollando.castor.browser

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
        return base.substringAfter("://").removePrefix("www.").trimEnd('/').lowercase()
    }

    private val QUERY = Regex("(?i)([?&](?:ep|episode|e)=)(\\d{1,4})(?!\\d)")
    private val NAMED = Regex("(?i)(?:episode|episodio|épisode|folge|ep|e)[-_ ./]?(\\d{1,4})(?!\\d)")
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
        val t = TRAILING.find(p) ?: return null
        return build(url, after + t.groups[1]!!.range.first, t.groupValues[1])?.copy(explicit = false)
    }

    private fun build(url: String, at: Int, digits: String): Ep? {
        val n = digits.toIntOrNull() ?: return null
        if (n in 1900..2100) return null // une année, pas un épisode
        val next = (n + 1).toString().padStart(digits.length, '0')
        return Ep(n, url.substring(0, at) + next + url.substring(at + digits.length), at = at, len = digits.length)
    }
}
