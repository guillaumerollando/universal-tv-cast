package com.rollandostudio.universaltvcast.cast

import android.content.Context
import android.net.Uri
import com.rollandostudio.universaltvcast.net.Http
import com.rollandostudio.universaltvcast.net.Mime
import com.rollandostudio.universaltvcast.proxy.Hls

/** Petites lectures du début d'une source : est-ce une liste HLS, a-t-elle des sous-titres intégrés ? */
internal object MediaProbe {
    /** Lit le début d'un MKV/WebM (1,5 Mo au plus) pour y repérer des pistes de sous-titres. */
    fun embeddedSubtitles(app: Context, m: Media): List<String> {
        val ext = Mime.ext(m.fileName ?: m.title).ifEmpty { Mime.ext(m.url) }
        if (ext !in setOf("mkv", "webm") && m.mime?.contains("matroska") != true) return emptyList()
        val max = 1_572_864
        val head = if (m.isLocal) {
            app.contentResolver.openInputStream(Uri.parse(m.url))?.use { readUpTo(it, max) } ?: return emptyList()
        } else {
            val c = Http.open(m.url, m.headers, "bytes=0-${max - 1}", connectTimeout = 5000, readTimeout = 5000)
            try { c.inputStream.use { readUpTo(it, max) } } finally { c.disconnect() }
        }
        return com.rollandostudio.universaltvcast.proxy.MkvProbe.subtitleLanguages(head)
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
            if (c.contentType?.contains("mpegurl", true) == true) return@runCatching true
            val buf = ByteArray(64)
            val n = c.inputStream.use { it.read(buf) }
            n > 0 && Hls.isPlaylist(buf.copyOf(n))
        } finally {
            c.disconnect()
        }
    }.getOrDefault(false)
}
