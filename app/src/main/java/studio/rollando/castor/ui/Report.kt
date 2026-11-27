package studio.rollando.castor.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import studio.rollando.castor.BuildConfig
import studio.rollando.castor.R
import studio.rollando.castor.cast.CastState

/** « Signaler un problème » : ouvre l'appli e-mail avec quelques infos utiles. Rien n'est envoyé sans l'utilisateur. */
object Report {
    private const val TO = "rollandostudio@gmail.com"

    fun send(ctx: Context, s: CastState) {
        val tv = s.renderer?.let { listOfNotNull(it.name, it.manufacturer, it.model).distinct().joinToString(" · ") } ?: "—"
        val last = s.notice?.let { n -> if (n.arg != null) ctx.getString(n.res, n.arg) else ctx.getString(n.res) } ?: "—"
        val source = s.media?.let { m -> listOfNotNull(m.sourceApp, m.host).joinToString(" · ").ifEmpty { "—" } } ?: "—"
        val body = buildString {
            append(ctx.getString(R.string.report_intro)).append("\n\n\n\n—\n")
            append("Universal TV Cast ").append(BuildConfig.VERSION_NAME).append("\n")
            append("Android ").append(Build.VERSION.RELEASE).append(" · ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append("\n")
            append("TV : ").append(tv).append("\n")
            append("Source : ").append(source).append("\n")
            append("État : ").append(s.phase).append(if (s.isHls) " · HLS" + (if (s.continuous) " continu" else " direct") else "").append("\n")
            append("Dernier message : ").append(last).append("\n")
        }
        val i = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:")).apply {
            putExtra(Intent.EXTRA_EMAIL, arrayOf(TO))
            // Sujet fixe, non traduit : la routine qui trie la boîte du studio repère « [Bug] ».
            putExtra(Intent.EXTRA_SUBJECT, "[Bug] Universal TV Cast " + BuildConfig.VERSION_NAME)
            putExtra(Intent.EXTRA_TEXT, body)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        // Aucune appli de mail : l'adresse est copiée, pour l'écrire depuis où l'on veut.
        runCatching { ctx.startActivity(i) }.onFailure {
            val cb = ctx.getSystemService(android.content.ClipboardManager::class.java)
            cb?.setPrimaryClip(android.content.ClipData.newPlainText("email", TO))
            android.widget.Toast.makeText(ctx, ctx.getString(R.string.report_no_mail, TO), android.widget.Toast.LENGTH_LONG).show()
        }
    }

    /** « Signaler ce site » : la vidéo d'une page n'a pas été repérée ; l'adresse nous permet de la rendre compatible. */
    fun site(ctx: Context, pageUrl: String) {
        val host = runCatching { Uri.parse(pageUrl).host?.removePrefix("www.") }.getOrNull().orEmpty()
        val body = buildString {
            append(ctx.getString(R.string.report_site_intro)).append("\n\n\n\n—\n")
            append("Page : ").append(pageUrl).append("\n")
            append("Universal TV Cast ").append(BuildConfig.VERSION_NAME).append("\n")
            append("Android ").append(Build.VERSION.RELEASE).append(" · ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append("\n")
        }
        val i = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:")).apply {
            putExtra(Intent.EXTRA_EMAIL, arrayOf(TO))
            putExtra(Intent.EXTRA_SUBJECT, "[Site] $host · Universal TV Cast " + BuildConfig.VERSION_NAME)
            putExtra(Intent.EXTRA_TEXT, body)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { ctx.startActivity(i) }.onFailure {
            val cb = ctx.getSystemService(android.content.ClipboardManager::class.java)
            cb?.setPrimaryClip(android.content.ClipData.newPlainText("email", TO))
            android.widget.Toast.makeText(ctx, ctx.getString(R.string.report_no_mail, TO), android.widget.Toast.LENGTH_LONG).show()
        }
    }
}
