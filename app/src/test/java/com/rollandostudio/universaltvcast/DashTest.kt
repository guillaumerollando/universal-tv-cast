package com.rollandostudio.universaltvcast

import com.rollandostudio.universaltvcast.proxy.Dash
import com.rollandostudio.universaltvcast.proxy.Fmp4Merge
import com.rollandostudio.universaltvcast.proxy.Hls
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** DASH réel (réseau) : DASH_URLS = adresses séparées par des espaces, DASH_OUT = dossier des résultats. */
class DashTest {
    @Test fun manifestes() {
        val urls = System.getenv("DASH_URLS")?.split(' ')?.filter { it.isNotBlank() }
        val out = System.getenv("DASH_OUT")?.let(::File)
        assumeTrue(urls != null && out != null)
        urls!!.forEachIndexed { i, url ->
            val f = File(out, "dash$i.txt")
            runCatching {
                val v = Hls.load(url, emptyMap())
                val tracks = Hls.tracks(url, emptyMap()).first
                val a = (tracks.firstOrNull { it.default } ?: tracks.firstOrNull())?.let { Hls.load(it.uri, emptyMap()) }
                val m = Fmp4Merge()
                val vs = v.segs[1]; val v2 = v.segs[2]
                val vInit = Hls.bytes(vs.map!!, vs.mapRange, emptyMap())
                val body = java.io.ByteArrayOutputStream()
                if (a != null) {
                    val as1 = a.segs[1]
                    body.write(m.init(vInit, Hls.bytes(as1.map!!, as1.mapRange, emptyMap()))!!)
                    var at = a.segs.take(1).sumOf { it.duration }; var ai = 1
                    var vt = v.segs.take(1).sumOf { it.duration }
                    for (s in listOf(vs, v2)) {
                        vt += s.duration
                        val snd = java.io.ByteArrayOutputStream()
                        while (ai < a.segs.size && at < vt) { val x = a.segs[ai++]; snd.write(Hls.bytes(x.url, x.range, emptyMap())); at += x.duration }
                        body.write(m.merge(Hls.bytes(s.url, s.range, emptyMap()), snd.toByteArray()))
                    }
                } else {
                    body.write(vInit); listOf(vs, v2).forEach { body.write(Hls.bytes(it.url, it.range, emptyMap())) }
                }
                File(out, "dash$i.mp4").writeBytes(body.toByteArray())
                "ok ${v.segs.size} morceaux image (${"%.1f".format(v.duration)} s), ${tracks.size} pistes son : ${tracks.joinToString { it.name + (if (it.default) "*" else "") }}"
            }.fold({ f.writeText(it) }, { f.writeText("ERREUR ${it.javaClass.simpleName} ${it.message}") })
        }
    }
}
