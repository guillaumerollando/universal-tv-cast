package com.rollandostudio.universaltvcast.cast

import android.net.Uri
import android.util.Log
import com.rollandostudio.universaltvcast.net.Http
import com.rollandostudio.universaltvcast.proxy.MkvProbe
import com.rollandostudio.universaltvcast.subs.SubtitleFinder
import org.json.JSONObject
import java.net.URLEncoder

/**
 * Où sont le générique de début, le récapitulatif et le générique de fin d'un épisode, pour un bouton « Passer ».
 * Trois sources, dans l'ordre :
 * 1. les chapitres du fichier (MKV : « Opening », « OP », « Ending », « Générique »… très courants pour les animés) ;
 * 2. TheIntroDB (séries, d'après l'identifiant IMDb, la saison et l'épisode) ;
 * 3. AniSkip (animés, d'après l'identifiant MyAnimeList trouvé par Kitsu et le numéro de l'épisode), retenu
 *    seulement si la durée de l'épisode concorde (sinon ce n'est sans doute pas le même).
 * Seuls le titre nettoyé et les numéros partent en ligne, jamais le fichier ni l'adresse.
 */
object Skips {
    enum class Kind { INTRO, RECAP, CREDITS }
    /** Passage à sauter, en secondes ; [end] null : jusqu'à la fin de la vidéo. */
    data class Segment(val kind: Kind, val start: Double, val end: Double?)

    private const val TAG = "TvCastSkip"
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private val INTRO = Regex("(?i)^(op|op\\s*\\d+|opening|intro|introduction|générique( de début)?|generique( de debut)?|title sequence|main title)$")
    private val CREDITS = Regex("(?i)^(ed|ed\\s*\\d+|ending|outro|credits|end credits|générique( de fin)?|generique( de fin)?|fin)$")
    private val RECAP = Regex("(?i)^(recap|résumé|resume|previously|précédemment|precedemment|avant\\s*-?\\s*première|preview|next episode|aperçu|apercu)$")

    /** Tous les passages trouvés pour cette vidéo ([duration] : durée connue, ou 0). */
    fun find(app: android.content.Context, m: Media, duration: Double): List<Segment> {
        chapters(app, m).takeIf { it.isNotEmpty() }?.let { Log.i(TAG, "chapitres : $it"); return it }
        val (title, season, episode) = episodeOf(m) ?: return emptyList()
        runCatching { introDb(title, season, episode) }.onFailure { Log.i(TAG, "TheIntroDB : ${it.message}") }
            .getOrNull()?.takeIf { it.isNotEmpty() }?.let { Log.i(TAG, "TheIntroDB : $it"); return it }
        runCatching { aniSkip(title, season, episode, duration) }.onFailure { Log.i(TAG, "AniSkip : ${it.message}") }
            .getOrNull()?.takeIf { it.isNotEmpty() }?.let { Log.i(TAG, "AniSkip : $it"); return it }
        return emptyList()
    }

    // ------------------------------------------------------------------ 1. chapitres du fichier

    private fun chapters(app: android.content.Context, m: Media): List<Segment> {
        // Le nom affiché a pu perdre son extension : on regarde aussi l'adresse du fichier.
        val exts = listOfNotNull(m.fileName, m.title, m.url.substringBefore('?')).map { com.rollandostudio.universaltvcast.net.Mime.ext(it) }
        if (exts.none { it in setOf("mkv", "webm", "mka") } && m.mime?.contains("matroska") != true) return emptyList()
        val max = 2_097_152
        val head = runCatching {
            if (m.isLocal) app.contentResolver.openInputStream(Uri.parse(m.url))?.use { read(it, max) }
            else Http.open(m.url, m.headers, "bytes=0-${max - 1}", connectTimeout = 5000, readTimeout = 8000).let { c ->
                try { c.inputStream.use { read(it, max) } } finally { c.disconnect() }
            }
        }.getOrNull() ?: return emptyList()
        val ch = MkvProbe.chapters(head)
        Log.i(TAG, "chapitres du fichier (${head.size} octets lus) : ${ch.map { it.name }}")
        return ch.mapIndexedNotNull { i, c ->
            val kind = when {
                INTRO.matches(c.name) -> Kind.INTRO
                CREDITS.matches(c.name) -> Kind.CREDITS
                RECAP.matches(c.name) -> Kind.RECAP
                else -> return@mapIndexedNotNull null
            }
            Segment(kind, c.start, c.end ?: ch.getOrNull(i + 1)?.start)
        }
    }

    private fun read(ins: java.io.InputStream, max: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream(256 * 1024)
        val buf = ByteArray(64 * 1024)
        // Serveur qui coupe avant la taille annoncée : on garde ce qui est arrivé (les chapitres sont au début).
        try {
            while (out.size() < max) { val n = ins.read(buf, 0, minOf(buf.size, max - out.size())); if (n < 0) break; out.write(buf, 0, n) }
        } catch (e: java.io.IOException) { if (out.size() == 0) throw e }
        return out.toByteArray()
    }

    // ------------------------------------------------------------------ quel épisode ?

    /** (titre de la série, saison, épisode), d'après le nom du fichier ou le titre de la page ; null pour un film. */
    internal fun episodeOf(m: Media): Triple<String, Int, Int>? {
        m.fileName?.let { f ->
            val p = SubtitleFinder.parse(f)
            if (p.isSeries) return Triple(p.title, p.season!!, p.episode!!)
        }
        if (m.season != null && m.episode != null) {
            val t = m.fileName?.let { SubtitleFinder.parse(it).title } ?: com.rollandostudio.universaltvcast.browser.PageTitle.series(m.title)
            return Triple(t, m.season, m.episode)
        }
        // Page d'un site (« Jujutsu Kaisen – Épisode 5 ») : numéro d'épisode dans le titre ou l'adresse.
        val ep = com.rollandostudio.universaltvcast.browser.PageTitle.episode(m.title)
            ?: m.pageUrl?.let { com.rollandostudio.universaltvcast.browser.Episodes.find(it)?.number } ?: return null
        val series = com.rollandostudio.universaltvcast.browser.PageTitle.series(m.title).trim()
        if (series.length < 2) return null
        val season = Regex("(?i)(?:saison|season|s)\\s*0*(\\d{1,2})").find(m.title)?.groupValues?.get(1)?.toIntOrNull() ?: 1
        return Triple(series, season, ep)
    }

    // ------------------------------------------------------------------ 2. TheIntroDB (séries)

    private fun introDb(title: String, season: Int, episode: Int): List<Segment> {
        val meta = SubtitleFinder.imdb(SubtitleFinder.Parsed(title, null, season, episode)) ?: return emptyList()
        val url = "https://api.theintrodb.org/v2/media?imdb_id=${enc(meta.id)}&season=$season&episode=$episode"
        val j = JSONObject(String(Http.fetch(url, emptyMap(), connectTimeout = 6000).bytes))
        fun list(key: String, kind: Kind) = j.optJSONArray(key)?.let { a ->
            (0 until a.length()).mapNotNull { i ->
                val o = a.getJSONObject(i)
                val s = o.optLong("start_ms", -1).takeIf { it >= 0 } ?: return@mapNotNull null
                Segment(kind, s / 1000.0, if (o.isNull("end_ms")) null else o.optLong("end_ms") / 1000.0)
            }
        }.orEmpty()
        return list("recap", Kind.RECAP) + list("intro", Kind.INTRO) + list("credits", Kind.CREDITS)
    }

    // ------------------------------------------------------------------ 3. AniSkip (animés)

    private fun malId(title: String, season: Int): Long? {
        val queries = if (season > 1) listOf("$title Season $season", "$title $season") else listOf(title)
        for (q in queries) {
            val j = JSONObject(String(Http.fetch("https://kitsu.io/api/edge/anime?filter%5Btext%5D=${enc(q)}&page%5Blimit%5D=1&include=mappings",
                emptyMap(), connectTimeout = 6000).bytes))
            // L'animé trouvé doit ressembler au titre cherché (sinon un autre animé : mauvais passages).
            val data = j.optJSONArray("data")?.optJSONObject(0)?.optJSONObject("attributes") ?: continue
            val names = listOf(data.optString("canonicalTitle"), data.optJSONObject("titles")?.toString().orEmpty(),
                data.optJSONArray("abbreviatedTitles")?.toString().orEmpty())
            if (names.none { SubtitleFinder.similar(title, it) }) continue
            val inc = j.optJSONArray("included") ?: continue
            for (i in 0 until inc.length()) {
                val a = inc.getJSONObject(i).optJSONObject("attributes") ?: continue
                if (a.optString("externalSite") == "myanimelist/anime") return a.optString("externalId").toLongOrNull()
            }
        }
        return null
    }

    private fun aniSkip(title: String, season: Int, episode: Int, duration: Double): List<Segment> {
        val mal = malId(title, season) ?: return emptyList()
        val len = if (duration > 0) "%.3f".format(java.util.Locale.ROOT, duration) else "0"
        val url = "https://api.aniskip.com/v2/skip-times/$mal/$episode?types=op&types=ed&types=recap&types=mixed-op&types=mixed-ed&episodeLength=$len"
        val j = JSONObject(String(Http.fetch(url, emptyMap(), connectTimeout = 6000).bytes))
        if (!j.optBoolean("found")) return emptyList()
        val r = j.optJSONArray("results") ?: return emptyList()
        return (0 until r.length()).mapNotNull { i ->
            val o = r.getJSONObject(i)
            // Durée de l'épisode trop différente : sans doute une autre version, ou un autre animé du même nom.
            val l = o.optDouble("episodeLength", 0.0)
            if (duration > 0 && l > 0 && kotlin.math.abs(l - duration) > 45) return@mapNotNull null
            val iv = o.optJSONObject("interval") ?: return@mapNotNull null
            val kind = when (o.optString("skipType")) {
                "op", "mixed-op" -> Kind.INTRO
                "ed", "mixed-ed" -> Kind.CREDITS
                "recap" -> Kind.RECAP
                else -> return@mapNotNull null
            }
            Segment(kind, iv.optDouble("startTime"), iv.optDouble("endTime"))
        }
    }
}
