package studio.rollando.castor.subs

import android.util.Log
import org.json.JSONObject
import studio.rollando.castor.net.Http
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
    data class Result(val name: String, val subs: List<Found>)

    private val SEP = Regex("[._]+")
    private val EP = Regex("(?i)\\bS(\\d{1,2})[ ._-]?E(\\d{1,3})\\b|\\b(\\d{1,2})x(\\d{2})\\b")
    private val YEAR = Regex("\\b(19[3-9]\\d|20[0-4]\\d)\\b")
    private val NOISE = Regex(
        "(?i)\\b(2160p|1080p|720p|480p|4k|uhd|hdr\\d*|dv|bluray|blu-ray|brrip|bdrip|web[- ]?dl|webrip|web|hdtv|dvdrip|remux|" +
            "x26[45]|h\\.?26[45]|hevc|avc|aac\\d?(\\.\\d)?|ac3|eac3|ddp?\\d?(\\.\\d)?|dts(-hd)?|atmos|truehd|10bit|8bit|" +
            "multi|vff|vfq|vf|vostfr|french|truefrench|english|subbed|proper|repack|extended|imax|internal|complete)\\b.*$"
    )

    fun parse(fileName: String): Parsed {
        val base = fileName.substringAfterLast('/').substringBeforeLast('.').let { if (it.length < 3) fileName else it }
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
        }
        val y = YEAR.findAll(title).lastOrNull()
        val year = y?.value?.toIntOrNull()
        if (y != null && y.range.first > 0) title = title.substring(0, y.range.first)
        title = NOISE.replace(title, "").replace(Regex("\\s+"), " ").trim(' ', '-')
        return Parsed(title.ifEmpty { clean }, year, season, episode)
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    /** Cherche l'identifiant IMDb du film ou de la série. */
    private fun imdb(p: Parsed): Pair<String, String>? {
        val type = if (p.isSeries) "series" else "movie"
        val url = "https://v3-cinemeta.strem.io/catalog/$type/top/search=${enc(p.title)}.json"
        val metas = JSONObject(String(Http.fetch(url, emptyMap(), connectTimeout = 8000).bytes)).optJSONArray("metas") ?: return null
        var best: JSONObject? = null
        for (i in 0 until metas.length()) {
            val m = metas.getJSONObject(i)
            if (best == null) best = m
            val ri = m.optString("releaseInfo")
            if (p.year != null && ri.startsWith(p.year.toString())) { best = m; break }
        }
        val b = best ?: return null
        val id = b.optString("imdb_id").ifEmpty { b.optString("id") }.takeIf { it.startsWith("tt") } ?: return null
        return id to b.optString("name", p.title)
    }

    /** Liste des sous-titres disponibles (au plus 3 par langue), langues préférées en premier. */
    fun find(fileName: String, videoSize: Long?, preferred: List<String>): Result? {
        val p = parse(fileName)
        if (p.title.length < 2) return null
        return try {
            val (id, name) = imdb(p) ?: return null
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
            Log.i("CastorSubs", "« ${p.display} » → $fullId ($display) : ${all.size} sous-titres, ${byLang.size} langues")
            Result(display, sorted)
        } catch (e: Exception) {
            Log.i("CastorSubs", "recherche échouée pour « ${p.display} » : ${e.message}")
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
