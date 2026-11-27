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

    private val EP_NUMBER = Regex("(?iu)(?<!\\p{L})(?:épisode|episode|episodio|folge|ep\\.?|e)\\s*(\\d{1,4})(?!\\d)|\\bS\\d{1,2}E(\\d{1,4})")

    /** Numéro d'épisode écrit dans le titre (« Mon Anime – Épisode 12 » → 12), quand l'adresse n'en a pas. */
    /** « Prisonnière 951 (1/4) » : partie 1 sur 4. */
    private val PART_OF = Regex("\\((\\d{1,2})\\s*/\\s*(\\d{1,2})\\)")

    fun episode(title: String): Int? = EP_NUMBER.find(title)?.let { m ->
        (m.groups[1] ?: m.groups[2])?.value?.toIntOrNull()
    } ?: PART_OF.find(title)?.let { m ->
        val n = m.groupValues[1].toInt(); val of = m.groupValues[2].toInt()
        n.takeIf { it in 1..of && of >= 2 }
    }

    private val WATCH_TAIL = Regex("(?iu)\\s*[–—|:-]\\s*(regarder|voir|watch|ansehen|guarda|ver|kijk|free download|téléchargement)\\b.*$")

    /** Titre à montrer pour une vidéo : nettoyé, sans « – Regarder la série » / « – Watch now » à la fin. */
    fun display(title: String, host: String): String =
        MEDIA_FILE.replace(WATCH_TAIL.replace(clean(title, host), "").trim(), "").trim().ifBlank { clean(title, host) }

    /** Page d'un fichier (Wikimedia Commons…) : « File:Big Buck Bunny 4K.webm » → « Big Buck Bunny 4K ». */
    private val MEDIA_FILE = Regex("(?i)^(file|fichier|datei|archivo|bestand|ficheiro|arquivo|plik|soubor|файл|dosya):\\s*|\\.(webm|ogv|ogg|mp4|m4v|mkv|mov)$")

    /** Retire le nom du site déclaré par la page (« Le loup - Maternelle Éducatif » → « Le loup »). */
    fun withoutSite(title: String, site: String?): String {
        if (site.isNullOrBlank() || title.isBlank()) return title
        val s = site.lowercase().filter { it.isLetterOrDigit() }
        if (s.length < 3) return title
        val parts = SEPARATORS.split(title.trim()).filter { p -> p.lowercase().filter { it.isLetterOrDigit() } != s }
        return parts.joinToString(" – ").ifBlank { title }
    }

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
