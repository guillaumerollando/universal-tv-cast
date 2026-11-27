package com.rollandostudio.universaltvcast.cast

import android.content.Context
import android.net.Uri
import com.rollandostudio.universaltvcast.net.Http
import com.rollandostudio.universaltvcast.net.Mime
import com.rollandostudio.universaltvcast.proxy.Hls

/** Petites lectures du début d'une source : est-ce une liste HLS, a-t-elle des sous-titres intégrés ? */
internal object MediaProbe {
    /**
     * Ce qu'un fichier contient déjà : langues des sous-titres (MKV) et voix (MKV, MP4 / MOV, TS).
     */
    class Inside(
        val subs: List<String>,
        val voices: List<com.rollandostudio.universaltvcast.proxy.Voices.Voice>,
        val mp4: List<com.rollandostudio.universaltvcast.proxy.Voices.Mp4Track>? = null,
        val ts: com.rollandostudio.universaltvcast.proxy.Voices.TsInfo? = null,
    )

    fun embedded(app: Context, m: Media): Inside {
        val V = com.rollandostudio.universaltvcast.proxy.Voices
        val ext = Mime.ext(m.fileName ?: m.title).ifEmpty { Mime.ext(m.url) }
        val mime = m.mime.orEmpty()
        val none = Inside(emptyList(), emptyList())
        return when {
            ext in setOf("mkv", "webm") || mime.contains("matroska") || mime.contains("webm") -> {
                val head = mkvHead(app, m) ?: return none
                val audio = com.rollandostudio.universaltvcast.proxy.MkvProbe.audioTracks(head)
                Inside(com.rollandostudio.universaltvcast.proxy.MkvProbe.subtitleLanguages(head), audio.mapIndexed { i, a ->
                    com.rollandostudio.universaltvcast.proxy.Voices.Voice("mkv:${a.number}", a.name.ifBlank { V.langName(a.lang) ?: "#${i + 1}" }, a.lang, a.default)
                })
            }
            ext in setOf("mp4", "m4v", "mov") || mime.contains("mp4") || mime.contains("quicktime") -> {
                val r = reader(app, m) ?: return none
                val tracks = V.mp4Tracks(r.first, r.second)
                Inside(emptyList(), V.mp4Voices(tracks), mp4 = tracks)
            }
            ext in setOf("ts", "m2t", "mts") || mime.contains("mp2t") || mime == "video/mpeg" -> {
                val r = reader(app, m) ?: return none
                val info = V.tsInfo(r.second(0, minOf(r.first, 2_000_000L).toInt())) ?: return none
                Inside(emptyList(), V.tsVoices(info), ts = info)
            }
            else -> none
        }
    }

    /** Lecture d'une plage du fichier (taille, lecteur), sur le téléphone ou par le réseau. */
    private fun reader(app: Context, m: Media): Pair<Long, (Long, Int) -> ByteArray>? {
        if (m.isLocal) {
            val uri = Uri.parse(m.url)
            val size = runCatching { app.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } }.getOrNull() ?: return null
            if (size <= 0) return null
            return size to { off, len ->
                app.contentResolver.openInputStream(uri)?.use { ins ->
                    var left = off
                    while (left > 0) { val k = ins.skip(left); if (k <= 0) break; left -= k }
                    readUpTo(ins, len)
                } ?: ByteArray(0)
            }
        }
        val c = Http.open(m.url, m.headers, "bytes=0-0", connectTimeout = 5000, readTimeout = 5000)
        val size = try {
            c.getHeaderField("Content-Range")?.substringAfter('/')?.toLongOrNull() ?: -1L
        } finally { c.disconnect() }
        if (size <= 0) return null
        return size to { off, len ->
            val h = Http.open(m.url, m.headers, "bytes=$off-${off + len - 1}", connectTimeout = 5000, readTimeout = 8000)
            try { if (h.responseCode == 206 || off == 0L) h.inputStream.use { readUpTo(it, len) } else ByteArray(0) } finally { h.disconnect() }
        }
    }

    private fun mkvHead(app: Context, m: Media): ByteArray? {
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
