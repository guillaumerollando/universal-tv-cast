package studio.rollando.castor.cast

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Parcelable
import android.provider.OpenableColumns
import android.util.Log
import studio.rollando.castor.net.Mime

data class SubSource(val uri: String, val name: String)

/** Ce qu'on envoie à la télé. */
data class Media(
    val url: String,
    val title: String,
    val mime: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val subs: List<SubSource> = emptyList(),
    val defaultSub: Int = -1,
    val pageUrl: String? = null,
    /** Appli qui nous a envoyé la vidéo (ex. com.stremio.one), si connue. */
    val sourceApp: String? = null,
    /** Position de reprise demandée par l'appli source, en secondes (« position » / « startfrom »). */
    val startSec: Double = 0.0,
    /** Nom du fichier vidéo, pour retrouver le film et ses sous-titres. */
    val fileName: String? = null,
) {
    /** La vidéo est servie par une autre appli du téléphone (Stremio et son serveur local…). */
    val fromPhoneApp: Boolean get() = runCatching { Uri.parse(url).host }.getOrNull()
        ?.let { it == "127.0.0.1" || it == "localhost" || it == "::1" } == true
    val isLocal: Boolean get() = url.startsWith("content:") || url.startsWith("file:")
    val host: String? get() = if (isLocal) null else runCatching { Uri.parse(pageUrl ?: url).host?.removePrefix("www.") }.getOrNull()
}

/** Lit ce qu'une autre appli nous envoie : « Ouvrir avec / Lecteur externe » ou « Partager ». */
object Intake {
    private val URL_RE = Regex("https?://\\S+")

    /** Note dans le journal tout ce qu'une appli nous transmet (utile pour savoir où sont ses sous-titres). */
    private fun logExtras(intent: Intent) {
        val b = intent.extras
        Log.i("CastorIntake", "action=${intent.action} type=${intent.type} data=${intent.dataString?.take(120)} extras=${b?.keySet()}")
        b?.keySet()?.forEach { k ->
            @Suppress("DEPRECATION")
            val v = b.get(k)
            val txt = when (v) {
                is Array<*> -> v.joinToString(prefix = "[", postfix = "]") { it.toString().take(150) }
                else -> v.toString().take(200)
            }
            Log.i("CastorIntake", "  $k = $txt")
        }
    }

    private val SUB_EXT = setOf("srt", "vtt", "ass", "ssa", "sub", "ttml", "dfxp")

    /** Toute valeur d'un extra dont le nom évoque des sous-titres et qui ressemble à un lien ou un fichier. */
    private fun guessSubtitles(intent: Intent, known: Set<String>): List<Uri> {
        val b = intent.extras ?: return emptyList()
        val out = mutableListOf<Uri>()
        for (k in b.keySet()) {
            if (!k.contains("sub", ignoreCase = true) || k.endsWith(".name") || k.endsWith(".enable")) continue
            @Suppress("DEPRECATION")
            val values: List<Any?> = when (val v = b.get(k)) {
                is Array<*> -> v.toList()
                is ArrayList<*> -> v
                else -> listOf(v)
            }
            for (v in values) {
                val u = when (v) {
                    is Uri -> v
                    is String -> if (v.startsWith("http") || v.startsWith("content:") || v.startsWith("file:")) Uri.parse(v)
                        else if (v.startsWith("/")) Uri.fromFile(java.io.File(v)) else null
                    else -> null
                } ?: continue
                val s = u.toString()
                if (s !in known && (Mime.ext(s) in SUB_EXT || s.contains("subtitle", true) || k.contains("sub", true))) out += u
            }
        }
        return out
    }

    fun parse(ctx: Context, intent: Intent?): Media? {
        intent ?: return null
        logExtras(intent)
        val uri: Uri = when (intent.action) {
            Intent.ACTION_VIEW -> intent.data ?: return null
            Intent.ACTION_SEND -> {
                (extraParcelable(intent, Intent.EXTRA_STREAM) as? Uri)
                    ?: intent.getStringExtra(Intent.EXTRA_TEXT)?.let { URL_RE.find(it)?.value }
                        ?.trimEnd('.', ',', ')', ']', '"', '\'')?.let(Uri::parse)
                    ?: return null
            }
            else -> return null
        }
        if (uri.scheme !in setOf("http", "https", "content", "file")) return null

        // Convention « MX Player » reprise par la plupart des applis : tableau clé, valeur, clé, valeur…
        val headers = LinkedHashMap<String, String>()
        intent.getStringArrayExtra("headers")?.toList()?.chunked(2)?.forEach {
            if (it.size == 2 && it[0].isNotBlank()) headers[it[0]] = it[1]
        }

        val subs = mutableListOf<SubSource>()
        val subUris = extraParcelables(intent, "subs").filterIsInstance<Uri>()
        val subNames = intent.getStringArrayExtra("subs.name")
        subUris.forEachIndexed { i, u -> subs += SubSource(u.toString(), subNames?.getOrNull(i) ?: nameOf(ctx, u)) }
        intent.getStringExtra("subtitles_location")?.let { loc ->
            val u = if (loc.startsWith("/")) Uri.fromFile(java.io.File(loc)) else Uri.parse(loc)
            subs += SubSource(u.toString(), nameOf(ctx, u))
        }
        guessSubtitles(intent, subs.map { it.uri }.toSet()).forEach { u -> subs += SubSource(u.toString(), nameOf(ctx, u)) }
        val enabled = extraParcelables(intent, "subs.enable").filterIsInstance<Uri>().map { it.toString() }
        val defaultSub = subs.indexOfFirst { it.uri in enabled }.takeIf { it >= 0 } ?: if (subs.isEmpty()) -1 else 0

        val title = intent.getStringExtra("title")
            ?: intent.getStringExtra(Intent.EXTRA_TITLE)
            ?: intent.getStringExtra(Intent.EXTRA_SUBJECT)
            ?: nameOf(ctx, uri)

        val startMs = listOf("position", "startfrom", "start_from", "start").firstNotNullOfOrNull { k ->
            @Suppress("DEPRECATION")
            when (val v = intent.extras?.get(k)) {
                is Int -> v.toLong(); is Long -> v; is String -> v.toLongOrNull(); else -> null
            }
        } ?: 0L
        return Media(
            url = uri.toString(),
            startSec = (startMs / 1000.0).coerceAtLeast(0.0),
            fileName = Uri.decode(uri.lastPathSegment ?: "").takeIf { it.contains('.') },
            title = title,
            mime = intent.type?.takeIf { it != "text/plain" },
            headers = headers,
            subs = subs,
            defaultSub = defaultSub,
        )
    }

    fun nameOf(ctx: Context, uri: Uri): String {
        if (uri.scheme == "content") {
            runCatching {
                ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) return c.getString(0)
                }
            }
        }
        return Uri.decode(uri.lastPathSegment ?: uri.host ?: "Video").substringAfterLast('/')
    }

    @Suppress("DEPRECATION")
    private fun extraParcelable(i: Intent, key: String): Parcelable? =
        if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(key, Parcelable::class.java) else i.getParcelableExtra(key)

    @Suppress("DEPRECATION")
    private fun extraParcelables(i: Intent, key: String): List<Parcelable> =
        runCatching {
            (if (Build.VERSION.SDK_INT >= 33) i.getParcelableArrayExtra(key, Parcelable::class.java) else i.getParcelableArrayExtra(key))
                ?.toList()
        }.getOrNull().orEmpty()
}
