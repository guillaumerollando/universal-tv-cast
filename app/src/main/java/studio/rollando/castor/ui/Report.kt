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
            putExtra(Intent.EXTRA_SUBJECT, ctx.getString(R.string.report_subject, BuildConfig.VERSION_NAME))
            putExtra(Intent.EXTRA_TEXT, body)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { ctx.startActivity(i) }
    }
}
