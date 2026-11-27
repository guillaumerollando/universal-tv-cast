package com.rollandostudio.universaltvcast.cast

import android.content.Context
import android.net.Uri
import com.rollandostudio.universaltvcast.net.Http
import com.rollandostudio.universaltvcast.net.Mime
import com.rollandostudio.universaltvcast.proxy.Hls

/** Petites lectures du début d'une source : est-ce une liste HLS, a-t-elle des sous-titres intégrés ? */
internal object MediaProbe {
    /** Ce qu'un MKV contient déjà : langues des sous-titres et pistes son. */
    data class Inside(val subs: List<String>, val audio: List<com.rollandostudio.universaltvcast.proxy.MkvProbe.Audio>)

    /** Lit le début d'un MKV/WebM (1,5 Mo au plus) pour y repérer ses pistes de sous-titres et de son. */
    fun embedded(app: Context, m: Media): Inside {
        val head = mkvHead(app, m) ?: return Inside(emptyList(), emptyList())
        return Inside(com.rollandostudio.universaltvcast.proxy.MkvProbe.subtitleLanguages(head),
            com.rollandostudio.universaltvcast.proxy.MkvProbe.audioTracks(head))
    }

    private fun mkvHead(app: Context, m: Media): ByteArray? {
        val ext = Mime.ext(m.fileName ?: m.title).ifEmpty { Mime.ext(m.url) }
        if (ext !in setOf("mkv", "webm") && m.mime?.contains("matroska") != true) return null
        val max = 1_572_864
        val head = if (m.isLocal) {
            app.contentResolver.openInputStream(Uri.parse(m.url))?.use { readUpTo(it, max) } ?: return null
        } else {
            val c = Http.open(m.url, m.headers, "bytes=0-${max - 1}", connectTimeout = 5000, readTimeout = 5000)
            try { c.inputStream.use { readUpTo(it, max) } } finally { c.disconnect() }
        }
        return head
    }

    /** Lit au plus [max] octets (`readNBytes` n'existe qu'à partir d'Android 13). */
    private fun readUpTo(ins: java.io.InputStream, max: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream(64 * 1024)
        val buf = ByteArray(64 * 1024)
        while (out.size() < max) {
            val n = ins.read(buf, 0, minOf(buf.size, max - out.size()))
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    fun sniffHls(m: Media): Boolean = runCatching {
        val c = Http.open(m.url, m.headers, "bytes=0-63", connectTimeout = 8000)
        try {
            if (c.responseCode >= 400) return@runCatching false
            if (c.contentType?.contains("mpegurl", true) == true || c.contentType?.contains("dash+xml", true) == true) return@runCatching true
            val buf = ByteArray(512)
            val n = c.inputStream.use { it.read(buf) }
            // Liste HLS, ou manifeste DASH (traité de la même façon).
            n > 0 && (Hls.isPlaylist(buf.copyOf(n)) || com.rollandostudio.universaltvcast.proxy.Dash.isDash("", buf.copyOf(n)))
        } finally {
            c.disconnect()
        }
    }.getOrDefault(false)
}
