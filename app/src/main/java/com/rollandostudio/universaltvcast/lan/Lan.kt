package com.rollandostudio.universaltvcast.lan

import com.rollandostudio.universaltvcast.net.Mime
import java.net.InetSocketAddress
import java.net.Socket

/** Un appareil du réseau qui partage des vidéos : serveur multimédia (DLNA) ou ordinateur / NAS (dossiers partagés). */
data class LanDevice(
    val kind: Kind,
    val name: String,
    val host: String,
    /** Port SMB ou HTTP ; -1 : port habituel. */
    val port: Int = -1,
    val id: String = "${kind.name}:$host:$port",
    val controlUrl: String? = null,
    val controlType: String? = null,
    val model: String? = null,
    /** Serveur multimédia : adresse de sa description (pour le retrouver sans recherche). */
    val location: String? = null,
    /** Dossiers partagés : partage donné à la main (« \\PC\Films »), ouvert sans demander la liste des partages. */
    val share: String? = null,
) {
    enum class Kind { MEDIA_SERVER, SHARE }

    /** « 192.168.1.40 » ou « 192.168.1.40:4450 » (port non habituel). */
    val address: String get() = if (kind == Kind.SHARE && port > 0 && port != 445) "$host:$port" else host
}

/** Un élément d'un dossier : sous-dossier, vidéo, musique ou photo. */
data class LanEntry(
    val name: String,
    val folder: Boolean,
    /** Dossier DLNA : son identifiant ; fichier DLNA : son adresse http ; SMB : son chemin depuis le partage. */
    val ref: String,
    val kind: Kind = Kind.OTHER,
    val mime: String? = null,
    val size: Long? = null,
    val duration: Double? = null,
    val thumb: String? = null,
    val device: LanDevice? = null,
    /** Date de modification (ms), pour « ajoutés récemment ». */
    val date: Long? = null,
) {
    enum class Kind { VIDEO, AUDIO, PHOTO, OTHER }
}

object Lan {
    private val VIDEO = setOf("mp4", "m4v", "mkv", "webm", "ts", "m2ts", "mts", "avi", "mov", "wmv", "flv", "mpg", "mpeg", "vob", "3gp", "ogv", "divx")
    private val AUDIO = setOf("mp3", "m4a", "aac", "flac", "ogg", "opus", "wav", "wma", "alac", "aiff")
    private val PHOTO = setOf("jpg", "jpeg", "png", "heic", "heif", "webp", "gif", "bmp")

    fun kindOf(mime: String?, name: String): LanEntry.Kind {
        val m = mime?.lowercase()?.takeIf { it.contains('/') && it != "application/octet-stream" }
        val ext = Mime.ext(name)
        return when {
            m != null && (m.startsWith("video/") || m.contains("mpegurl")) -> LanEntry.Kind.VIDEO
            m != null && m.startsWith("audio/") -> LanEntry.Kind.AUDIO
            m != null && m.startsWith("image/") -> LanEntry.Kind.PHOTO
            ext in VIDEO -> LanEntry.Kind.VIDEO
            ext in AUDIO -> LanEntry.Kind.AUDIO
            ext in PHOTO -> LanEntry.Kind.PHOTO
            else -> LanEntry.Kind.OTHER
        }
    }

    /** Type d'un fichier d'après son nom (pour les dossiers partagés, qui ne le donnent pas). */
    fun mimeOf(name: String): String = when (val ext = Mime.ext(name)) {
        "jpg", "jpeg" -> "image/jpeg"
        "png", "webp", "gif", "bmp", "heic", "heif" -> "image/$ext"
        "wmv" -> "video/x-ms-wmv"
        "flv" -> "video/x-flv"
        "mpg", "mpeg", "vob" -> "video/mpeg"
        "3gp" -> "video/3gpp"
        "wma" -> "audio/x-ms-wma"
        else -> if (ext in AUDIO || ext in VIDEO) Mime.guess(name) else "application/octet-stream"
    }

    /** Dossiers d'abord, puis par nom, les nombres dans l'ordre naturel (« Épisode 2 » avant « Épisode 10 »). */
    val ORDER: Comparator<LanEntry> = compareBy<LanEntry> { !it.folder }.then { a, b -> natural(a.name, b.name) }

    fun natural(a: String, b: String): Int {
        val ra = Regex("\\d+|\\D+").findAll(a.lowercase()).map { it.value }.toList()
        val rb = Regex("\\d+|\\D+").findAll(b.lowercase()).map { it.value }.toList()
        for (i in 0 until minOf(ra.size, rb.size)) {
            val x = ra[i]; val y = rb[i]
            val c = if (x[0].isDigit() && y[0].isDigit()) x.trimStart('0').length.compareTo(y.trimStart('0').length)
                .takeIf { it != 0 } ?: x.trimStart('0').compareTo(y.trimStart('0'))
            else java.text.Collator.getInstance().compare(x, y)
            if (c != 0) return c
        }
        return ra.size - rb.size
    }

    fun portOpen(host: String, port: Int, timeout: Int = 300): Boolean = try {
        Socket().use { it.connect(InetSocketAddress(host, port), timeout); true }
    } catch (_: Exception) {
        false
    }

    /** Partage indiqué après l'adresse (« \\PC-SALON\Films », « 192.168.1.40/Films ») ; null s'il n'y en a pas. */
    fun parseShare(input: String): String? = input.trim().removePrefix("smb://").trimStart('\\', '/').replace('\\', '/')
        .split('/').getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() }

    /** « 192.168.1.40:4450 » → (« 192.168.1.40 », 4450) ; « \\PC-SALON\Films » → (« PC-SALON », -1). */
    fun parseAddress(input: String): Pair<String, Int>? {
        var s = input.trim().removePrefix("smb://").removePrefix("http://").trimStart('\\', '/')
        s = s.substringBefore('/').substringBefore('\\')
        if (s.isEmpty() || s.contains(' ')) return null
        val port = s.substringAfterLast(':', "").toIntOrNull()
        return (if (port != null) s.substringBeforeLast(':') else s) to (port ?: -1)
    }
}
