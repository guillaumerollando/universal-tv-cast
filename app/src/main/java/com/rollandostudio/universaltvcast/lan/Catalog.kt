package com.rollandostudio.universaltvcast.lan

import android.content.Context
import android.util.Log
import com.rollandostudio.universaltvcast.net.Http
import com.rollandostudio.universaltvcast.subs.SubtitleFinder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Les vidéos du réseau présentées comme une vidéothèque : titre propre, année, affiche, épisodes.
 * Le nom du fichier donne le titre (« Dune.Part.Two.2024.MULTi.1080p.mkv » → « Dune Part Two », 2024) ; l'annuaire de
 * Stremio (Cinemeta, sans compte ni clé, déjà utilisé pour les sous-titres) donne l'affiche et le vrai titre.
 * Seul le titre nettoyé part en ligne, jamais le fichier ni l'adresse de l'appareil, et seulement si le réglage
 * « Affiches des films » est allumé. Réponses gardées sur le téléphone (quelques Ko), affiches dans le cache d'images.
 */
object Catalog {
    /** Ce qu'on sait d'un film, d'une série ou d'un épisode. */
    data class Info(
        val title: String,
        val year: Int? = null,
        val season: Int? = null,
        val episode: Int? = null,
        val poster: String? = null,
        val backdrop: String? = null,
        val logo: String? = null,
        /** Titre de l'épisode (« Ozymandias »), s'il est connu. */
        val episodeTitle: String? = null,
        /** Image de l'épisode (16:9). */
        val still: String? = null,
        /** Jaquette intégrée au fichier (MP4, MKV), copiée en petit sur le téléphone (chemin d'un fichier). */
        val cover: String? = null,
    ) {
        val isEpisode: Boolean get() = season != null && episode != null
    }

    private const val PREFS = "catalog"
    private const val MAX = 600
    private lateinit var app: Context
    private val prefs get() = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Réponses de l'annuaire, par titre recherché : identifiant, nom, affiche, fond (ou « rien trouvé »). */
    private val found = ConcurrentHashMap<String, JSONObject>()
    /** Titres des épisodes, par série. */
    private val episodes = ConcurrentHashMap<String, Map<String, String>>()
    private val gate = Semaphore(4)
    @Volatile private var loaded = false

    fun init(ctx: Context) { app = ctx.applicationContext }

    var online: Boolean
        get() = prefs.getBoolean("online", true)
        set(v) { prefs.edit().putBoolean("online", v).apply() }

    private fun load() {
        if (loaded) return
        loaded = true
        runCatching {
            val o = JSONObject(prefs.getString("found", "{}")!!)
            o.keys().forEach { k -> found[k] = o.getJSONObject(k) }
        }
    }

    @Synchronized
    private fun save() {
        // Les plus anciennes réponses partent au-delà de MAX.
        val keep = found.entries.sortedByDescending { it.value.optLong("t") }.take(MAX)
        val o = JSONObject()
        keep.forEach { (k, v) -> o.put(k, v) }
        prefs.edit().putString("found", o.toString()).apply()
    }

    /** Ce qu'on lit dans le nom seul, sans réseau (affichage immédiat). */
    fun parse(fileName: String): Info {
        val p = SubtitleFinder.parse(fileName)
        return Info(title = prettify(p.title), year = p.year, season = p.season, episode = p.episode)
    }

    /** « dune part two » → « Dune Part Two » (les noms de fichiers sont souvent en minuscules). */
    private fun prettify(t: String): String =
        if (t.any { it.isUpperCase() }) t else t.split(' ').joinToString(" ") { w -> w.replaceFirstChar { it.titlecase() } }

    // ------------------------------------------------------------------ ce que dit le fichier lui-même

    private val inFile = ConcurrentHashMap<String, JSONObject>()
    @Volatile private var inFileLoaded = false
    private val readGate = Semaphore(2)
    private const val COVERS_MAX = 25L * 1024 * 1024

    /** Un titre de balise qui n'est qu'un nom de fichier (« Dune.2021.1080p-GROUPE ») ne vaut pas mieux que le nom. */
    private fun cleanTag(t: String?): String? = t?.trim()?.takeIf {
        it.length > 1 && !Regex("\\w[._]\\w.*[._]").containsMatchIn(it) &&
            !Regex("(?i)\\b(1080p|720p|2160p|x26[45]|hevc|web-?dl|bluray|www\\.|\\.com)\\b").containsMatchIn(it)
    }

    /**
     * Titre, année et jaquette écrits dans le fichier (balises MP4 / MKV), lus par Android sans tout télécharger :
     * seuls l'en-tête et la jaquette transitent. Gardé ensuite (titre en quelques octets, jaquette en petit JPEG).
     */
    suspend fun fromFile(url: String): Info? {
        if (!inFileLoaded) {
            inFileLoaded = true
            runCatching { val o = JSONObject(prefs.getString("infile2", "{}")!!); o.keys().forEach { inFile[it] = o.getJSONObject(it) } }
        }
        inFile[url]?.let { return it.toInfo() }
        return withContext(Dispatchers.IO) {
            readGate.withPermit {
                inFile[url]?.let { return@withPermit it.toInfo() }
                val r = android.media.MediaMetadataRetriever()
                val o = JSONObject().put("t", System.currentTimeMillis())
                try {
                    if (url.startsWith("content:")) r.setDataSource(app, android.net.Uri.parse(url)) else r.setDataSource(url, HashMap())
                    cleanTag(r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_TITLE))?.let { o.put("title", it) }
                    // L'année de sortie (balise « année ») ; pas la date de création du fichier, souvent vide (1904).
                    r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_YEAR)
                        ?.let { Regex("(19|20)\\d\\d").find(it)?.value?.toInt() }
                        ?.takeIf { it in 1920..java.util.Calendar.getInstance().get(java.util.Calendar.YEAR) + 1 }
                        ?.let { o.put("year", it) }
                    r.embeddedPicture?.let { bytes -> saveCover(url, bytes)?.let { o.put("cover", it) } }
                } catch (e: Exception) {
                    Log.i("TvCastLan", "balises illisibles : ${e.message}")
                } finally {
                    runCatching { r.release() }
                }
                inFile[url] = o
                val keep = inFile.entries.sortedByDescending { it.value.optLong("t") }.take(MAX)
                val all = JSONObject(); keep.forEach { (k, v) -> all.put(k, v) }
                inFile.keys.retainAll(keep.map { it.key }.toSet())
                prefs.edit().remove("infile").putString("infile2", all.toString()).apply()
                o.toInfo()
            }
        }
    }

    private fun JSONObject.toInfo(): Info? {
        val t = optString("title").ifEmpty { null }
        val y = optInt("year", 0).takeIf { it > 0 }
        val c = optString("cover").ifEmpty { null }?.takeIf { java.io.File(it).exists() }
        return if (t == null && y == null && c == null) null else Info(title = t ?: "", year = y, cover = c)
    }

    /** Jaquette réduite (400 px) dans le cache ; au-delà de 25 Mo, les plus anciennes partent. */
    private fun saveCover(url: String, bytes: ByteArray): String? = runCatching {
        val dir = java.io.File(app.cacheDir, "covers").apply { mkdirs() }
        val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        var k = 1
        while (maxOf(opts.outWidth, opts.outHeight) / (k * 2) >= 400) k *= 2
        val bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
            android.graphics.BitmapFactory.Options().apply { inSampleSize = k }) ?: return null
        val f = java.io.File(dir, Integer.toHexString(url.hashCode()) + ".jpg")
        f.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 82, it) }
        bmp.recycle()
        var total = 0L
        dir.listFiles()?.sortedByDescending { it.lastModified() }?.forEach { x -> total += x.length(); if (total > COVERS_MAX) x.delete() }
        f.path
    }.getOrNull()

    /**
     * Titre, année, affiche… dans cet ordre : le fichier lui-même (balises, jaquette), puis son nom, puis l'annuaire
     * en ligne pour ce qui manque encore (si le réglage le permet).
     */
    suspend fun info(fileName: String, asSeries: Boolean? = null, url: String? = null): Info {
        val named = parse(fileName)
        val tags = url?.let { fromFile(it) }
        // Les balises corrigent le titre et l'année d'un film ; pour un épisode, le nom du fichier dit mieux la série.
        val base = if (tags != null && !named.isEpisode) named.copy(
            title = tags.title.ifEmpty { named.title }, year = named.year ?: tags.year, cover = tags.cover,
        ) else named.copy(cover = tags?.cover)
        if (!online || base.title.length < 2) return base
        val p = SubtitleFinder.Parsed(base.title, base.year, base.season, base.episode)
        val series = asSeries ?: p.isSeries
        val m = lookup(p.title, p.year, series) ?: return base
        val id = m.optString("id")
        val title = m.optString("name").ifEmpty { base.title }
        val poster = m.optString("poster").ifEmpty { null }?.replace("/poster/small/", "/poster/medium/")
            ?: "https://images.metahub.space/poster/medium/$id/img"
        return if (base.isEpisode) {
            val name = episodeTitles(id)["${base.season}:${base.episode}"]
            base.copy(title = title, poster = poster, logo = "https://images.metahub.space/logo/medium/$id/img",
                backdrop = m.optString("background").ifEmpty { null },
                episodeTitle = name, still = "https://episodes.metahub.space/$id/${base.season}/${base.episode}/w780.jpg")
        } else base.copy(
            title = title, year = base.year ?: m.optString("year").take(4).toIntOrNull(), poster = poster,
            backdrop = m.optString("background").ifEmpty { null } ?: "https://images.metahub.space/background/medium/$id/img",
            logo = "https://images.metahub.space/logo/medium/$id/img",
        )
    }

    /** Une série d'après le nom de son dossier (« Breaking Bad »), pour l'affiche du dossier. */
    suspend fun series(folderName: String): Info? {
        val base = parse(folderName)
        if (!online || base.title.length < 2) return null
        val m = lookup(base.title, base.year, true) ?: return null
        val id = m.optString("id")
        return Info(m.optString("name").ifEmpty { base.title }, poster = m.optString("poster").ifEmpty { null }
            ?: "https://images.metahub.space/poster/medium/$id/img",
            backdrop = m.optString("background").ifEmpty { null }, logo = "https://images.metahub.space/logo/medium/$id/img")
    }

    private suspend fun lookup(title: String, year: Int?, series: Boolean): JSONObject? {
        load()
        val key = "${if (series) "s" else "m"}|${title.lowercase()}|${year ?: ""}"
        found[key]?.let { return it.takeIf { o -> o.has("id") } }
        return withContext(Dispatchers.IO) {
            gate.withPermit {
                found[key]?.let { return@withPermit it.takeIf { o -> o.has("id") } }
                val m = runCatching { SubtitleFinder.imdb(SubtitleFinder.Parsed(title, year, if (series) 1 else null, if (series) 1 else null)) }
                    .onFailure { Log.i("TvCastLan", "annuaire injoignable : ${it.message}") }
                // Erreur réseau : rien de gardé (on réessaiera) ; « rien trouvé » : gardé, pour ne pas redemander.
                if (m.isFailure) return@withPermit null
                val meta = m.getOrNull()
                val o = JSONObject().put("t", System.currentTimeMillis())
                if (meta != null) o.put("id", meta.id).put("name", meta.name).put("poster", meta.poster ?: "")
                    .put("background", meta.background ?: "").put("year", "")
                found[key] = o
                save()
                o.takeIf { it.has("id") }
            }
        }
    }

    /** Titres des épisodes d'une série (« 1:3 » → « … »), demandés une fois par série. */
    private suspend fun episodeTitles(id: String): Map<String, String> {
        episodes[id]?.let { return it }
        val map = withContext(Dispatchers.IO) {
            gate.withPermit {
                runCatching {
                    val j = JSONObject(String(Http.fetch("https://v3-cinemeta.strem.io/meta/series/$id.json", emptyMap(), connectTimeout = 8000).bytes))
                    val v = j.optJSONObject("meta")?.optJSONArray("videos") ?: return@runCatching emptyMap()
                    buildMap {
                        for (i in 0 until v.length()) {
                            val e = v.getJSONObject(i)
                            val name = e.optString("name").ifEmpty { e.optString("title") }
                            if (name.isNotBlank()) put("${e.optInt("season")}:${e.optInt("episode")}", name)
                        }
                    }
                }.getOrDefault(emptyMap())
            }
        }
        episodes[id] = map
        return map
    }

    /** Oublie tout ce qui a été trouvé (réglage éteint, ou « vider le cache »). */
    fun clear() {
        found.clear(); episodes.clear()
        prefs.edit().remove("found").apply()
    }
}
