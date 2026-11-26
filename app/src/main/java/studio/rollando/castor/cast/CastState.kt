package studio.rollando.castor.cast

import androidx.annotation.StringRes
import studio.rollando.castor.dlna.Renderer
import studio.rollando.castor.proxy.Hls
import java.io.IOException

enum class Phase { IDLE, LOADING, SENDING, BUFFERING, PLAYING, PAUSED, STOPPED }

/** Album photo : les photos choisies, celle affichée, et le diaporama automatique. */
data class Slides(val uris: List<String>, val index: Int = 0, val auto: Boolean = false, val videos: Set<Int> = emptySet()) {
    val isVideo: Boolean get() = index in videos
}

data class Notice(@param:StringRes val res: Int, val arg: String? = null, val error: Boolean = false)

data class CastState(
    val renderers: List<Renderer> = emptyList(),
    val renderer: Renderer? = null,
    val searching: Boolean = false,
    val media: Media? = null,
    val isHls: Boolean = false,
    val continuous: Boolean = true,
    val splitAudio: Boolean = false,
    val subIndex: Int = -1,
    val phase: Phase = Phase.IDLE,
    val position: Double = 0.0,
    val duration: Double = 0.0,
    val volume: Int? = null,
    val notice: Notice? = null,
    /** Album photo en cours d'affichage sur la télé. */
    val slides: Slides? = null,
    /** Décalage des sous-titres en secondes (positif : ils apparaissent plus tard). */
    val subDelay: Double = 0.0,
    /** La télé affiche l'écran d'attente de l'appli (fin de vidéo, arrêt). */
    val idle: Boolean = false,
    /** Langues des sous-titres déjà présents dans la vidéo (MKV), à choisir sur la télé. */
    val embeddedSubs: List<String> = emptyList(),
    /** Chaîne ou flux en direct (liste HLS sans fin) : pas de durée, la lecture reprend toujours au direct. */
    val live: Boolean = false,
    /** Direct : retard voulu sur le direct (secondes) et ce que le flux garde en arrière. */
    val liveBehind: Double = 0.0,
    val liveWindow: Double = 0.0,
    /** La lecture se fait sur ce téléphone (dans l'écran de lecture) plutôt que sur la télé. */
    val onPhone: Boolean = false,
    /** Pistes son proposées par la vidéo (« français », « VO »…) et celle choisie. */
    val audioTracks: List<studio.rollando.castor.proxy.Hls.Track> = emptyList(),
    val audioIndex: Int = -1,
) {
    val active: Boolean get() = phase in setOf(Phase.SENDING, Phase.BUFFERING, Phase.PLAYING, Phase.PAUSED)
}

internal class NoTv : IOException("no tv")
internal class NoWifi : IOException("no wifi")
internal class Drm : IOException("drm")
