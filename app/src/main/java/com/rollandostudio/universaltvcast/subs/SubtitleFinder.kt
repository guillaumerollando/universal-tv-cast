package com.rollandostudio.universaltvcast.subs

import android.util.Log
import org.json.JSONObject
import com.rollandostudio.universaltvcast.net.Http
import java.net.URLEncoder
import java.util.Locale

/**
 * Trouve des sous-titres sans compte ni clé, comme le fait Stremio :
 * 1. le nom du fichier donne le titre, l'année, la saison et l'épisode ;
 * 2. Cinemeta (l'annuaire de Stremio) donne l'identifiant IMDb ;
 * 3. OpenSubtitles v3 (le service de sous-titres de Stremio) donne la liste par langue.
 */
object SubtitleFinder {
    data class Parsed(val title: String, val year: Int?, val season: Int?, val episode: Int?) {
        val isSeries: Boolean get() = season != null && episode != null
        /** Titre lisible : « Spider-Man Brand New Day (2026) » ou « The Office S02E05 ». */
        val display: String
            get() = when {
                isSeries -> "$title S%02dE%02d".format(season, episode)
                year != null -> "$title ($year)"
                else -> title
            }
    }

    data class Found(val lang: String, val url: String)
    /** Images du film ou de la série (Cinemeta / metahub). */
    data class Art(val poster: String?, val backdrop: String?, val logo: String?)
    data class Result(val name: String, val subs: List<Found>, val art: Art?)

    private val SEP = Regex("[._]+")
    // « S01E05 », « S01E05v2 » (version corrigée d'un épisode, courant pour les animés), « 1x05 ».
    private val EP = Regex("(?i)\\bS(\\d{1,2})[ ._-]?E(\\d{1,3})(?:v\\d)?\\b|\\b(\\d{1,2})x(\\d{2})\\b")
    /**
     * Nommage des animés : « [Groupe] Titre - 05 (1080p) », « Titre 2nd Season - 05 », « Titre S2 - 05 »,
     * « Titre E05 », « Titre Episode 5 » (pas de SxxEyy).
     */
    private val ANIME = Regex(
        "(?i)^(.+?)(?:\\s+(?:S(\\d{1,2})|Season\\s*(\\d{1,2})|(\\d{1,2})(?:st|nd|rd|th)\\s+Season))?" +
            "\\s*(?:-\\s*|\\s(?:E|Ep|Episode)\\.?\\s*)(\\d{1,4})(?:v\\d)?(?=\\s|$)"
    )
    private val YEAR = Regex("\\b(19[3-9]\\d|20[0-4]\\d)\\b")
    private val NOISE = Regex(
        "(?i)\\b(2160p|1080p|720p|480p|4k|uhd|hdr\\d*|dv|bluray|blu-ray|brrip|bdrip|web[- ]?dl|webrip|web|hdtv|dvdrip|remux|" +
            "x26[45]|h\\.?26[45]|hevc|avc|aac\\d?(\\.\\d)?|ac3|eac3|ddp?\\d?(\\.\\d)?|dts(-hd)?|atmos|truehd|10bit|8bit|" +
            "multi|vff|vfq|vf|vostfr|french|truefrench|english|subbed|proper|repack|extended|imax|internal|complete)\\b.*$"
    )

    fun parse(fileName: String): Parsed {
        // L'extension seulement si c'en est une (« .mkv ») : un titre sans extension (serveur DLNA :
        // « Mon.Anime.S01E01 ») garderait sinon « S01E01 » pour une extension.
        val name0 = fileName.substringAfterLast('/')
        val ext = name0.substringAfterLast('.', "")
        val looksExt = ext.length in 2..4 && ext.all { it.isLetterOrDigit() } && !Regex("(?i)^s\\d+e\\d+$|^\\d+$").matches(ext)
        val base = (if (looksExt) name0.substringBeforeLast('.') else name0).let { if (it.length < 3) fileName else it }
        val clean = SEP.replace(base, " ")
            .replace(Regex("[(\\[]((?:19|20)\\d\\d)[)\\]]"), " $1 ") // « (2017) » : on garde l'année
            .replace(Regex("\\[[^]]*]|\\([^)]*\\)"), " ").replace(Regex("\\s+"), " ").trim()
        val ep = EP.find(clean)
        var title = clean
        var season: Int? = null
        var episode: Int? = null
        if (ep != null) {
            season = (ep.groupValues[1].ifEmpty { ep.groupValues[3] }).toIntOrNull()
            episode = (ep.groupValues[2].ifEmpty { ep.groupValues[4] }).toIntOrNull()
            title = clean.substring(0, ep.range.first)
        } else {
            val a = ANIME.find(clean)
            val n = a?.groupValues?.get(5)?.toIntOrNull()
            // Un nombre qui ressemble à une année n'est pas un épisode (« Titre - 2019 »).
            if (a != null && n != null && n in 1..1899) {
                title = a.groupValues[1]
                season = listOf(2, 3, 4).firstNotNullOfOrNull { a.groupValues[it].toIntOrNull() } ?: 1
                episode = n
            }
        }
        val y = YEAR.findAll(title).lastOrNull()
        val year = y?.value?.toIntOrNull()
        if (y != null && y.range.first > 0) title = title.substring(0, y.range.first)
        title = NOISE.replace(title, "").replace(Regex("\\s+"), " ").trim(' ', '-')
        return Parsed(title.ifEmpty { clean }, year, season, episode)
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    /** Cherche l'identifiant IMDb du film ou de la série. */
    internal class Meta(val id: String, val name: String, val poster: String?, val background: String?)

    /** Identifiant IMDb : Cinemeta (le bon type, puis l'autre), puis l'annuaire des animés (Kitsu, titres japonais). */
    internal fun imdb(p: Parsed): Meta? =
        cinemeta(p, if (p.isSeries) "series" else "movie")
            ?: cinemeta(p, if (p.isSeries) "movie" else "series")
            ?: runCatching { kitsu(p) }.getOrNull()

    private fun kitsu(p: Parsed): Meta? {
        val url = "https://anime-kitsu.strem.fun/catalog/anime/kitsu-anime-list/search=${enc(p.title)}.json"
        val metas = JSONObject(String(Http.fetch(url, emptyMap(), connectTimeout = 8000).bytes)).optJSONArray("metas") ?: return null
        for (i in 0 until metas.length()) {
            val m = metas.getJSONObject(i)
            val id = m.optString("imdb_id").takeIf { it.startsWith("tt") } ?: continue
            if (!similar(p.title, m.optString("name")) && !similar(p.title, m.optString("aliases"))) continue
            return Meta(id, m.optString("name", p.title), m.optString("poster").ifEmpty { null }, m.optString("background").ifEmpty { null })
        }
        return null
    }

    private val STOP = setOf("the", "les", "des", "and", "une", "with", "from", "dans", "pour", "season", "saison", "part", "partie")
    private fun words(s: String) = java.text.Normalizer.normalize(s.lowercase(Locale.ROOT), java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "").split(Regex("[^a-z0-9]+")).filter { (it.length >= 4 || it.all(Char::isDigit)) && it !in STOP }.toSet()

    /**
     * Le titre trouvé ressemble-t-il à celui cherché ? L'annuaire renvoie toujours quelque chose, même sans rapport
     * (« Mon Anime » → « I Love You, Man ») : il faut au moins un mot important en commun.
     */
    fun similar(query: String, found: String): Boolean {
        val a = words(query); val b = words(found)
        if (a.isEmpty() || b.isEmpty()) return query.trim().equals(found.trim(), ignoreCase = true)
        return a.any { it in b }
    }

    private fun cinemeta(p: Parsed, type: String): Meta? {
        val url = "https://v3-cinemeta.strem.io/catalog/$type/top/search=${enc(p.title)}.json"
        val metas = JSONObject(String(Http.fetch(url, emptyMap(), connectTimeout = 8000).bytes)).optJSONArray("metas") ?: return null
        var best: JSONObject? = null
        for (i in 0 until metas.length()) {
            val m = metas.getJSONObject(i)
            // Seuls les résultats dont le nom ressemble au titre cherché comptent.
            if (!similar(p.title, m.optString("name"))) continue
            val ri = m.optString("releaseInfo")
            // Fichier de film (pas un épisode) dont l'année est connue des deux côtés et ne colle pas (à un an près) :
            // une autre œuvre au titre voisin (« Big Buck Bunny 2008 » → « Big Buck Massacre », 2026, ou une série
            // homonyme). Mieux vaut pas d'image qu'une image fausse.
            // Sans année connue chez l'annuaire, seul un titre identique compte (« The Big Bunny » ≠ « Big Buck Bunny »).
            val y = ri.take(4).toIntOrNull()
            if (!p.isSeries && p.year != null) {
                val same = words(p.title) == words(m.optString("name"))
                if (if (y != null) kotlin.math.abs(y - p.year) > 1 else !same) continue
            }
            if (best == null) best = m
            if (p.year != null && ri.startsWith(p.year.toString())) { best = m; break }
        }
        val b = best ?: return null
        val id = b.optString("imdb_id").ifEmpty { b.optString("id") }.takeIf { it.startsWith("tt") } ?: return null
        return Meta(id, b.optString("name", p.title), b.optString("poster").ifEmpty { null }, b.optString("background").ifEmpty { null })
    }

    /** Liste des sous-titres disponibles (au plus 3 par langue), langues préférées en premier. */
    fun find(fileName: String, videoSize: Long?, preferred: List<String>): Result? {
        val p = parse(fileName)
        if (p.title.length < 2) return null
        return try {
            val meta = imdb(p) ?: run {
                Log.i("TvCastSubs", "« ${p.display} » : titre introuvable dans les annuaires")
                return null
            }
            val id = meta.id
            val name = meta.name
            val art = Art(
                poster = meta.poster?.replace("/poster/small/", "/poster/medium/") ?: "https://images.metahub.space/poster/medium/$id/img",
                backdrop = if (p.isSeries) "https://episodes.metahub.space/$id/${p.season}/${p.episode}/w780.jpg"
                    else meta.background ?: "https://images.metahub.space/background/medium/$id/img",
                logo = "https://images.metahub.space/logo/medium/$id/img",
            )
            val type = if (p.isSeries) "series" else "movie"
            val fullId = if (p.isSeries) "$id:${p.season}:${p.episode}" else id
            val extra = buildList {
                add("filename=" + enc(fileName.substringAfterLast('/')))
                if (videoSize != null && videoSize > 0) add("videoSize=$videoSize")
            }.joinToString("&")
            val url = "https://opensubtitles-v3.strem.io/subtitles/$type/${enc(fullId)}/$extra.json"
            val arr = JSONObject(String(Http.fetch(url, emptyMap(), connectTimeout = 8000).bytes)).optJSONArray("subtitles")
            val all = mutableListOf<Found>()
            if (arr != null) for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val u = o.optString("url"); val l = o.optString("lang")
                if (u.isNotEmpty() && l.isNotEmpty()) all += Found(l, u)
            }
            val byLang = all.groupBy { norm(it.lang) }.mapValues { it.value.take(3) }
            val order = preferred.map(::norm).distinct()
            val sorted = order.flatMap { byLang[it].orEmpty() } +
                byLang.filterKeys { it !in order }.toSortedMap().values.flatten()
            val display = if (p.isSeries) "$name S%02dE%02d".format(p.season, p.episode) else name
            Log.i("TvCastSubs", "« ${p.display} » → $fullId ($display) : ${all.size} sous-titres, ${byLang.size} langues")
            Result(display, sorted, art)
        } catch (e: Exception) {
            Log.i("TvCastSubs", "recherche échouée pour « ${p.display} » : ${e.message}")
            null
        }
    }

    /** Codes ISO 639-2 (eng, fre, fra, ger, pob…) ramenés à un code court (en, fr, de, pt-BR…). */
    fun norm(code: String): String {
        val c = code.lowercase(Locale.ROOT)
        return when (c) {
            "pob", "pt-br", "ptbr" -> "pt-BR"
            "fre", "fra" -> "fr"; "ger", "deu" -> "de"; "dut", "nld" -> "nl"; "spa", "spn", "ea" -> "es"
            "ita" -> "it"; "eng" -> "en"; "por" -> "pt"; "chi", "zho" -> "zh"; "cze", "ces" -> "cs"
            "gre", "ell" -> "el"; "rum", "ron" -> "ro"; "per", "fas" -> "fa"; "alb", "sqi" -> "sq"
            else -> if (c.length == 3) Locale.getAvailableLocales().firstOrNull {
                runCatching { it.isO3Language == c }.getOrDefault(false)
            }?.language ?: c else c
        }
    }

    /** Nom de la langue dans la langue du téléphone (« Anglais », « Néerlandais »…). */
    fun label(code: String): String {
        val n = norm(code)
        val loc = Locale.forLanguageTag(n)
        return loc.getDisplayName(Locale.getDefault()).replaceFirstChar { it.titlecase(Locale.getDefault()) }.ifEmpty { code }
    }
}
