package studio.rollando.castor.cast

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Parcelable
import android.provider.OpenableColumns

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

    fun parse(ctx: Context, intent: Intent?): Media? {
        intent ?: return null
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
        val enabled = extraParcelables(intent, "subs.enable").filterIsInstance<Uri>().map { it.toString() }
        val defaultSub = subs.indexOfFirst { it.uri in enabled }.takeIf { it >= 0 } ?: if (subs.isEmpty()) -1 else 0

        val title = intent.getStringExtra("title")
            ?: intent.getStringExtra(Intent.EXTRA_TITLE)
            ?: intent.getStringExtra(Intent.EXTRA_SUBJECT)
            ?: nameOf(ctx, uri)

        return Media(
            url = uri.toString(),
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
