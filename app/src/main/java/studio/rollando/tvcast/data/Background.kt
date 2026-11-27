package studio.rollando.tvcast.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.annotation.StringRes
import studio.rollando.tvcast.R

/**
 * Android (et surtout certaines surcouches : Xiaomi, Samsung, Huawei…) met en veille les applis en arrière-plan.
 * Pour TvCast c'est grave : si l'appli qui sert la vidéo (Stremio…) s'endort, la télé se fige dès qu'on avance ;
 * si TvCast s'endort, le relais s'arrête. On prévient l'utilisateur avant que le problème n'arrive.
 */
object Background {
    private val AGGRESSIVE = setOf("xiaomi", "redmi", "poco", "samsung", "huawei", "honor", "oppo", "realme", "oneplus", "vivo", "meizu", "asus")

    val aggressiveBrand: Boolean get() = Build.MANUFACTURER.lowercase() in AGGRESSIVE || Build.BRAND.lowercase() in AGGRESSIVE

    /** Vrai si Android a le droit de mettre [pkg] en veille (pas sur la liste blanche de la batterie). */
    fun canSleep(ctx: Context, pkg: String): Boolean = runCatching {
        !ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(pkg)
    }.getOrDefault(true)

    /** Faut-il prévenir pour cette appli ? Seulement si Android a encore le droit de l'endormir. */
    fun atRisk(ctx: Context, pkg: String): Boolean = canSleep(ctx, pkg)

    /** Les mots exacts du réglage selon la marque du téléphone. */
    @StringRes
    fun howTo(): Int = when (Build.MANUFACTURER.lowercase()) {
        "xiaomi", "redmi", "poco" -> R.string.tip_howto_xiaomi
        "samsung" -> R.string.tip_howto_samsung
        "huawei", "honor" -> R.string.tip_howto_huawei
        "oppo", "realme", "oneplus" -> R.string.tip_howto_oppo
        else -> R.string.tip_howto_generic
    }

    fun openSettings(ctx: Context, pkg: String) {
        runCatching {
            ctx.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    fun appLabel(ctx: Context, pkg: String): String = runCatching {
        val pm = ctx.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)
}
