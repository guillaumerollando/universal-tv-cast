package com.rollandostudio.universaltvcast.cast

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.rollandostudio.universaltvcast.net.Http
import com.rollandostudio.universaltvcast.net.HttpError
import com.rollandostudio.universaltvcast.net.Net
import com.rollandostudio.universaltvcast.proxy.Hls
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/** La vidéo vient d'une autre appli du téléphone (Stremio…), qui a été fermée. */
internal class SourceGone : Exception()

/** Le serveur de la vidéo ne répond pas (hors ligne, adresse inconnue). */
internal class SourceUnreachable : Exception()

/**
 * Avant d'envoyer quoi que ce soit à la télé : la source donne-t-elle bien la vidéo ? On demande le tout début
 * du fichier (ou un morceau du flux). Seuls les refus nets arrêtent l'envoi : une source lente à répondre
 * (Stremio qui prépare un torrent…) part comme avant.
 */
internal object SourceCheck {
    /** Dernière source vérifiée (pour ne pas refaire la vérification à chaque déplacement dans la vidéo). */
    @Volatile private var verified: Pair<String, Long>? = null

    /** Refus nets : la source ne donnera pas la vidéo, inutile de l'envoyer à la télé. */
    private val DEFINITE = setOf(401, 403, 404, 410, 451)

    suspend fun verify(m: Media, isHls: Boolean) = withContext(Dispatchers.IO) {
        if (m.isLocal) return@withContext
        // UDP / RTP / RTSP / RTMP : pas de vérification HTTP possible.
        if (!m.url.startsWith("http", true)) return@withContext
        verified?.let { (u, t) -> if (u == m.url && System.currentTimeMillis() - t < 60_000) return@withContext }
        val probe = if (isHls) {
            val plan = try { Hls.load(m.url, m.headers) } catch (e: HttpError) {
                if (e.code in DEFINITE) throw e else null
            } catch (e: java.io.IOException) { unreachable(m, e); null }
            if (plan != null && plan.segs.isEmpty()) throw SourceUnreachable()
            // Direct : le dernier morceau (le plus récent) ; vidéo : le premier.
            plan?.let { if (it.endList) it.segs.first().url else it.segs.last().url }
        } else m.url
        if (probe != null) try {
            val c = Http.open(probe, m.headers, range = "bytes=0-1023", connectTimeout = 5000, readTimeout = 8000)
            try {
                val code = c.responseCode
                if (code in DEFINITE) throw HttpError(code)
            } finally { c.disconnect() }
        } catch (e: HttpError) {
            throw e
        } catch (e: SocketTimeoutException) {
            // Impossible de joindre le serveur : hors ligne. Joint mais lent à répondre : on laisse la télé essayer.
            if (e.message?.contains("connect", true) == true) unreachable(m, ConnectException(e.message))
        } catch (e: java.io.IOException) {
            unreachable(m, e)
        }
        verified = m.url to System.currentTimeMillis()
    }

    private fun unreachable(m: Media, e: Exception) {
        if (e is ConnectException || e is UnknownHostException) {
            Log.w("TvCast", "source injoignable (${e.javaClass.simpleName}) : rien n'est envoyé à la télé")
            if (m.fromPhoneApp) throw SourceGone()
            // Pas de réseau du tout ? Le message Wi-Fi habituel ; sinon, la source elle-même est hors ligne.
            if (Net.localIp() == null) throw NoWifi()
            throw SourceUnreachable()
        }
    }
}
