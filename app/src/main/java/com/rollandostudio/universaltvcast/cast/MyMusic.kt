package com.rollandostudio.universaltvcast.cast

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * « Ma musique » pour l'ambiance : des morceaux du téléphone, dans le désordre et en boucle, glissés dans la vidéo
 * comme une radio ([Radio.Feed]) : les trames MP3 ou AAC sont lues telles quelles (sans réencodage) ; l'AAC des
 * fichiers .m4a reçoit l'en-tête ADTS que le flux de la télé attend. Titre et artiste viennent des balises du fichier.
 *
 * Tous les morceaux d'une même séance ont le même format (la vidéo n'annonce qu'une piste son) : celui de la plupart
 * des morceaux choisis ; les autres sont laissés de côté.
 */
object MyMusic {
    private const val TAG = "TvCastMusic"
    data class Track(val uri: String, val name: String)

    private var prefs: android.content.SharedPreferences? = null
    private val _tracks = MutableStateFlow<List<Track>>(emptyList())
    val tracks: StateFlow<List<Track>> = _tracks
    private const val MAX = 300

    fun init(ctx: Context) {
        prefs = ctx.getSharedPreferences("ambiance", Context.MODE_PRIVATE)
        _tracks.value = runCatching {
            val a = JSONArray(prefs!!.getString("my_music", "[]"))
            (0 until a.length()).map { a.getJSONObject(it).let { o -> Track(o.getString("u"), o.optString("n")) } }
        }.getOrDefault(emptyList())
    }

    /** Ajoute les morceaux choisis (droit de lecture gardé d'un lancement à l'autre). */
    fun add(ctx: Context, uris: List<Uri>) {
        val list = _tracks.value.toMutableList()
        for (u in uris) {
            runCatching { ctx.contentResolver.takePersistableUriPermission(u, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            if (list.none { it.uri == u.toString() }) list += Track(u.toString(), label(ctx, u))
        }
        save(list.takeLast(MAX))
    }

    fun clear() = save(emptyList())

    private fun save(list: List<Track>) {
        _tracks.value = list
        val a = JSONArray(); list.forEach { a.put(JSONObject().put("u", it.uri).put("n", it.name)) }
        prefs?.edit()?.putString("my_music", a.toString())?.apply()
    }

    /** « Artiste - Titre » d'après les balises, sinon le nom du fichier. */
    private fun label(ctx: Context, u: Uri): String = runCatching {
        MediaMetadataRetriever().use { r ->
            r.setDataSource(ctx, u)
            val t = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)?.trim().orEmpty()
            val a = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)?.trim().orEmpty()
            when { t.isNotEmpty() && a.isNotEmpty() -> "$a - $t"; t.isNotEmpty() -> t; else -> null }
        }
    }.getOrNull() ?: Intake.nameOf(ctx, u).substringBeforeLast('.')

    private fun typeOf(ctx: Context, u: Uri): Int? = runCatching {
        val x = MediaExtractor(); try {
            x.setDataSource(ctx, u, null)
            (0 until x.trackCount).firstNotNullOfOrNull { i ->
                when (x.getTrackFormat(i).getString(MediaFormat.KEY_MIME)) { "audio/mpeg" -> 0x03; "audio/mp4a-latm" -> 0x0F; else -> null }
            }
        } finally { x.release() }
    }.getOrNull()

    /** Le « flux » de la musique choisie, à brancher comme une radio ; null si aucun morceau n'est lisible. */
    fun feed(ctx: Context): Radio.Feed? {
        val tracks = _tracks.value
        if (tracks.isEmpty()) return null
        val types = tracks.associateWith { typeOf(ctx, Uri.parse(it.uri)) }
        val type = types.values.filterNotNull().groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: return null
        val playable = tracks.filter { types[it] == type }
        Log.i(TAG, "${playable.size} morceau(x) en ${if (type == 0x03) "MP3" else "AAC"} (${tracks.size - playable.size} laissés de côté)")
        val input = java.io.PipedInputStream(256 * 1024)
        val out = java.io.PipedOutputStream(input)
        lateinit var feed: Radio.Feed
        feed = Radio.Feed(Radio.Station(ctx.getString(com.rollandostudio.universaltvcast.R.string.amb_mood_mymusic), "local:"), null, input, type)
        Thread({
            try {
                while (true) for (t in playable.shuffled()) {
                    feed.title(t.name)
                    write(ctx, Uri.parse(t.uri), type, out)
                }
            } catch (e: java.io.IOException) {
                // Ambiance arrêtée : le tuyau est fermé, on s'arrête.
            } catch (e: Exception) {
                Log.i(TAG, "lecture : ${e.message}")
            } finally { runCatching { out.close() } }
        }, "ambiance-music").apply { isDaemon = true }.start()
        return feed
    }

    /** Écrit les trames d'un morceau (avec l'en-tête ADTS pour l'AAC). */
    private fun write(ctx: Context, u: Uri, type: Int, out: java.io.OutputStream) {
        val x = MediaExtractor()
        try {
            x.setDataSource(ctx, u, null)
            val track = (0 until x.trackCount).firstOrNull { x.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true } ?: return
            x.selectTrack(track)
            val f = x.getTrackFormat(track)
            val rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val profile = if (f.containsKey(MediaFormat.KEY_AAC_PROFILE)) f.getInteger(MediaFormat.KEY_AAC_PROFILE) else 2
            val buf = java.nio.ByteBuffer.allocate(64 * 1024)
            val bytes = ByteArray(64 * 1024)
            while (true) {
                buf.clear()
                val n = x.readSampleData(buf, 0)
                if (n < 0) break
                buf.get(bytes, 0, n)
                if (type == 0x0F) out.write(adts(n, rate, channels, profile))
                out.write(bytes, 0, n)
                x.advance()
            }
        } catch (e: java.io.IOException) {
            throw e
        } catch (e: Exception) {
            Log.i(TAG, "morceau illisible : ${e.message}")
        } finally { x.release() }
    }

    private val RATES = intArrayOf(96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350)

    /** En-tête ADTS de 7 octets d'une trame AAC de [size] octets. */
    private fun adts(size: Int, rate: Int, channels: Int, profile: Int): ByteArray {
        val len = size + 7
        val fi = RATES.indexOf(rate).takeIf { it >= 0 } ?: 4
        val p = (profile - 1).coerceIn(0, 3)
        return byteArrayOf(
            0xFF.toByte(), 0xF1.toByte(),
            ((p shl 6) or (fi shl 2) or ((channels shr 2) and 1)).toByte(),
            (((channels and 3) shl 6) or ((len shr 11) and 3)).toByte(),
            ((len shr 3) and 0xFF).toByte(),
            (((len and 7) shl 5) or 0x1F).toByte(),
            0xFC.toByte(),
        )
    }
}
