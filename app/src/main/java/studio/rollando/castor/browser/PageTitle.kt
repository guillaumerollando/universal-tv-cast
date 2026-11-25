package studio.rollando.castor.browser

/**
 * Titre lisible d'une vidéo trouvée sur un site : on retire le nom du site (« … · Otakubinge », « … | Site »)
 * et les formules d'accroche (« Regarder maintenant : », « Watch … online », « … en streaming »).
 */
object PageTitle {
    private val SEPARATORS = Regex("\\s+[·|•–—-]\\s+")
    private val PREFIX = Regex(
        "(?i)^(regarder( maintenant)?|voir( maintenant)?|watch( now)?|stream(ing)?|ver|guarda|assistir|ansehen|kijk)\\s*[:\\-–]?\\s+"
    )
    private val SUFFIX = Regex("(?i)\\s+(en streaming|streaming( vf| vostfr)?( gratuit)?|online free|online|free online|gratuit|en ligne|hd)$")

    private val EPISODE = Regex("(?iu)\\s*[–—:|·-]?\\s*(?<!\\p{L})(épisode|episode|episodio|folge|ep\\.?|e)\\s*\\d+.*$|\\s*S\\d{1,2}E\\d{1,3}.*$")

    /** Nom de la série seul (pour un favori qui suivra les épisodes) : « Mon Anime – Épisode 12 » → « Mon Anime ». */
    fun series(title: String): String = EPISODE.replace(title, "").trim().ifBlank { title }

    fun clean(title: String, host: String): String {
        if (title.isBlank()) return title
        val site = host.removePrefix("www.").substringBefore('.').lowercase().filter { it.isLetterOrDigit() }
        // On garde les morceaux qui ne sont pas le nom du site.
        val parts = SEPARATORS.split(title.trim()).filter { p ->
            val n = p.lowercase().filter { it.isLetterOrDigit() }
            !(site.length >= 3 && (n.contains(site) || site.contains(n) && n.length >= 4))
        }
        var t = parts.joinToString(" – ").ifBlank { title }.trim()
        repeat(2) { t = PREFIX.replace(t, "").trim(); t = SUFFIX.replace(t, "").trim() }
        return t.ifBlank { title.trim() }
    }
}
