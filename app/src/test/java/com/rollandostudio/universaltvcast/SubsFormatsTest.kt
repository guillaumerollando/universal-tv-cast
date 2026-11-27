package com.rollandostudio.universaltvcast

import com.rollandostudio.universaltvcast.proxy.Hls
import com.rollandostudio.universaltvcast.proxy.Subtitles
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class SubsFormatsTest {
    @Test fun ttmlEbu() {
        val ttml = """<?xml version="1.0" encoding="UTF-8"?>
<tt:tt xmlns:tt="http://www.w3.org/ns/ttml" xmlns:ttp="http://www.w3.org/ns/ttml#parameter" ttp:timeBase="media" ttp:frameRate="25">
 <tt:body><tt:div>
  <tt:p begin="00:00:01.500" end="00:00:04.000"><tt:span>Bonjour</tt:span><tt:br/><tt:span>tout le monde</tt:span></tt:p>
  <tt:p begin="00:01:02:12" end="00:01:05:00">Deuxième réplique</tt:p>
  <tt:p begin="70s" dur="2.5s">Troisième</tt:p>
 </tt:div></tt:body></tt:tt>"""
        val srt = String(Subtitles.toSrt(ttml.toByteArray()), Charsets.UTF_8)
        assertTrue(srt, srt.contains("00:00:01,500 --> 00:00:04,000\nBonjour\ntout le monde"))
        assertTrue(srt, srt.contains("00:01:02,480 --> 00:01:05,000\nDeuxième réplique"))
        assertTrue(srt, srt.contains("00:01:10,000 --> 00:01:12,500\nTroisième"))
    }

    /** Réseau : SUBS_URLS (listes maîtresses ou MPD), SUBS_OUT (dossier). */
    @Test fun reels() {
        val urls = System.getenv("SUBS_URLS")?.split(' ')?.filter { it.isNotBlank() }
        val out = System.getenv("SUBS_OUT")?.let(::File)
        assumeTrue(urls != null && out != null)
        urls!!.forEachIndexed { i, u ->
            val r = runCatching {
                val subs = Hls.tracks(u, emptyMap()).second
                val t = subs.firstOrNull { it.lang == "fr" } ?: subs.first()
                val bytes = if (t.uri.startsWith(Hls.VTT_JOIN)) Hls.vttJoin(t.uri.removePrefix(Hls.VTT_JOIN), emptyMap())
                    else if (t.uri.startsWith(com.rollandostudio.universaltvcast.proxy.Dash.TEXT)) com.rollandostudio.universaltvcast.proxy.Dash.textBytes(t.uri, emptyMap())
                    else com.rollandostudio.universaltvcast.net.Http.fetch(t.uri, emptyMap()).bytes
                val srt = String(Subtitles.toSrt(bytes), Charsets.UTF_8)
                "${subs.size} pistes (${subs.joinToString { it.name }}), 1re : ${t.uri.take(80)}\n" + srt.lines().take(12).joinToString("\n") +
                    "\n… ${srt.split("-->").size - 1} répliques"
            }.getOrElse { "ERREUR ${it.javaClass.simpleName} ${it.message}" }
            File(out, "subs$i.txt").writeText(r)
        }
    }
}
