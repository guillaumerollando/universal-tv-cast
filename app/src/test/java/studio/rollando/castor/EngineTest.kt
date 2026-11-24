package studio.rollando.castor

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import studio.rollando.castor.dlna.Dlna
import studio.rollando.castor.proxy.Hls
import studio.rollando.castor.proxy.Subtitles
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread

class EngineTest {
    private lateinit var server: ServerSocket
    private val pages = HashMap<String, String>()
    private val seenReferers = java.util.Collections.synchronizedList(mutableListOf<String?>())
    private val base get() = "http://127.0.0.1:${server.localPort}"

    /** Mini serveur HTTP : une connexion par requête. */
    @Before fun start() {
        server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val s = try { server.accept() } catch (_: Exception) { break }
                s.use {
                    val r = it.getInputStream().bufferedReader()
                    val path = r.readLine()?.split(' ')?.getOrNull(1)?.substringBefore('?') ?: return@use
                    var ref: String? = null
                    while (true) {
                        val l = r.readLine() ?: break
                        if (l.isEmpty()) break
                        if (l.startsWith("Referer:", true)) ref = l.substringAfter(':').trim()
                    }
                    seenReferers += ref
                    val body = pages[path]
                    val out = it.getOutputStream()
                    if (body == null) out.write("HTTP/1.1 404 NF\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                    else {
                        val b = body.toByteArray()
                        out.write("HTTP/1.1 200 OK\r\nContent-Type: application/vnd.apple.mpegurl\r\nContent-Length: ${b.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        out.write(b)
                    }
                    out.flush()
                }
            }
        }
    }

    @After fun stop() = server.close()

    @Test fun masterPicksBestMuxedVariantAndSendsHeaders() {
        pages["/hls/master.m3u8"] = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360
            low/index.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=3000000,RESOLUTION=1920x1080
            high/index.m3u8
        """.trimIndent()
        pages["/hls/high/index.m3u8"] = """
            #EXTM3U
            #EXT-X-TARGETDURATION:6
            #EXT-X-MEDIA-SEQUENCE:7
            #EXT-X-KEY:METHOD=AES-128,URI="key.bin",IV=0x000102030405060708090a0b0c0d0e0f
            #EXTINF:6.0,
            seg7.ts
            #EXTINF:4.5,
            /abs/seg8.ts
            #EXT-X-ENDLIST
        """.trimIndent()
        val plan = Hls.load("$base/hls/master.m3u8", mapOf("Referer" to "https://site.example/page"))
        assertEquals("$base/hls/high/index.m3u8", plan.url)
        assertEquals(2, plan.segs.size)
        assertEquals(10.5, plan.duration, 0.001)
        assertEquals(7L, plan.segs[0].seq)
        assertEquals("$base/hls/high/seg7.ts", plan.segs[0].url)
        assertEquals("$base/abs/seg8.ts", plan.segs[1].url)
        assertEquals("$base/hls/high/key.bin", plan.segs[0].key!!.uri)
        assertEquals(15, plan.segs[0].key!!.iv!![15].toInt())
        assertTrue(plan.endList)
        assertFalse(plan.splitAudio)
        assertTrue(seenReferers.all { it == "https://site.example/page" })
    }

    @Test fun separateAudioIsFlagged() {
        pages["/m.m3u8"] = """
            #EXTM3U
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aud",NAME="en",URI="audio.m3u8"
            #EXT-X-STREAM-INF:BANDWIDTH=2000000,AUDIO="aud"
            v.m3u8
        """.trimIndent()
        pages["/v.m3u8"] = "#EXTM3U\n#EXTINF:5,\na.ts\n#EXT-X-ENDLIST\n"
        assertTrue(Hls.load("$base/m.m3u8", emptyMap()).splitAudio)
    }

    @Test fun tsResyncSkipsJunkPrefix() {
        val ts = ByteArray(188 * 3) { if (it % 188 == 0) 0x47 else 1 }
        val junk = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0, 0, 0)
        assertArrayEquals(ts, Hls.syncTs(junk + ts))
        assertArrayEquals(ts, Hls.syncTs(ts))
    }

    @Test fun vttBecomesSrt() {
        val vtt = "WEBVTT\n\nNOTE hi\n\n00:01.500 --> 00:03.000 align:start\n<v Bob>Salut <i>toi</i>\n\n01:02:03.004 --> 01:02:04.000\nLigne 2\n"
        val srt = String(Subtitles.toSrt(vtt.toByteArray())).removePrefix("﻿")
        assertEquals("1\n00:00:01,500 --> 00:00:03,000\nSalut <i>toi</i>\n\n2\n01:02:03,004 --> 01:02:04,000\nLigne 2\n", srt)
    }

    @Test fun assBecomesSrt() {
        val ass = "[Script Info]\nTitle: x\n\n[Events]\nFormat: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\n" +
            "Dialogue: 0,0:00:01.50,0:00:02.00,Default,,0,0,0,,{\\i1}Bonjour,{\\i0} toi\\Nça va ?\n"
        val srt = String(Subtitles.toSrt(ass.toByteArray())).removePrefix("﻿")
        assertEquals("1\n00:00:01,500 --> 00:00:02,000\nBonjour, toi\nça va ?\n", srt)
    }

    @Test fun latin1SubtitlesAreDecoded() {
        val srt = "1\n00:00:01,000 --> 00:00:02,000\nÉté\n".toByteArray(Charsets.ISO_8859_1)
        assertTrue(String(Subtitles.toSrt(srt), Charsets.UTF_8).contains("Été"))
    }

    @Test fun timeHelpers() {
        assertEquals("1:02:03", Dlna.hms(3723.9))
        assertEquals(3723.5, Dlna.parseHms("01:02:03.500"), 0.001)
        assertEquals(0.0, Dlna.parseHms("NOT_IMPLEMENTED"), 0.0)
    }

    @Test fun didlIsEscapedAndCarriesSubtitles() {
        val d = Dlna.didl("Tom & Jerry <1>", "http://x/v?a=1&b=2", "video/mp4", "http://x/s.srt", true)
        assertTrue(d.contains("Tom &amp; Jerry &lt;1&gt;"))
        assertTrue(d.contains("http://x/v?a=1&amp;b=2"))
        assertTrue(d.contains("sec:CaptionInfoEx"))
        assertTrue(d.contains("DLNA.ORG_OP=01"))
    }
}

/** Une fausse télé DLNA : vérifie les commandes SOAP envoyées et la lecture des réponses. */
class FakeTvTest {
    @Test fun soapCommandsReachTheTv() {
        val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        val actions = java.util.Collections.synchronizedList(mutableListOf<String>())
        val bodies = java.util.Collections.synchronizedList(mutableListOf<String>())
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val s = try { server.accept() } catch (_: Exception) { break }
                s.use {
                    val input = it.getInputStream()
                    val head = StringBuilder()
                    while (!head.endsWith("\r\n\r\n")) { val c = input.read(); if (c < 0) break; head.append(c.toChar()) }
                    val len = Regex("(?i)content-length:\\s*(\\d+)").find(head)?.groupValues?.get(1)?.toInt() ?: 0
                    val body = String(input.readNBytes(len))
                    val action = Regex("(?i)soapaction:\\s*\"[^#]*#(\\w+)\"").find(head)!!.groupValues[1]
                    actions += action; bodies += body
                    val reply = when (action) {
                        "GetPositionInfo" -> "<RelTime>0:01:05</RelTime><TrackDuration>1:30:00</TrackDuration>"
                        "GetTransportInfo" -> "<CurrentTransportState>PLAYING</CurrentTransportState>"
                        "GetVolume" -> "<CurrentVolume>17</CurrentVolume>"
                        else -> ""
                    }
                    val code = if (action == "Seek") "500 Internal" else "200 OK"
                    val xml = if (action == "Seek") "<s:Envelope><s:Body><s:Fault><detail><UPnPError><errorCode>710</errorCode><errorDescription>Seek mode not supported</errorDescription></UPnPError></detail></s:Fault></s:Body></s:Envelope>"
                    else "<s:Envelope><s:Body><u:R>$reply</u:R></s:Body></s:Envelope>"
                    it.getOutputStream().write("HTTP/1.1 $code\r\nContent-Length: ${xml.length}\r\nConnection: close\r\n\r\n$xml".toByteArray())
                }
            }
        }
        val url = "http://127.0.0.1:${server.localPort}/ctl"
        val tv = studio.rollando.castor.dlna.Renderer("Philips", "uuid:1", url, "urn:schemas-upnp-org:service:AVTransport:1",
            url, "urn:schemas-upnp-org:service:RenderingControl:1", "Philips", "TV")
        Dlna.setUri(tv, "http://phone/v?a=1&b=2", Dlna.didl("Film", "http://phone/v?a=1&b=2", "video/mp2t", null, false))
        Dlna.play(tv)
        assertEquals(65.0 to 5400.0, Dlna.position(tv))
        assertEquals("PLAYING", Dlna.transportState(tv))
        assertEquals(17, Dlna.getVolume(tv))
        Dlna.setVolume(tv, 20)
        val err = runCatching { Dlna.seek(tv, 10.0) }.exceptionOrNull()
        assertTrue(err is studio.rollando.castor.dlna.TvError && err.message!!.contains("710"))
        server.close()
        assertEquals(listOf("SetAVTransportURI", "Play", "GetPositionInfo", "GetTransportInfo", "GetVolume", "SetVolume", "Seek"), actions)
        // Les métadonnées sont échappées deux fois (XML dans du XML) : l'URL reste intacte une fois décodée.
        assertTrue(bodies[0].contains("<CurrentURI>http://phone/v?a=1&amp;b=2</CurrentURI>"))
        assertTrue(bodies[0].contains("&lt;DIDL-Lite"))
        assertTrue(bodies[5].contains("<DesiredVolume>20</DesiredVolume>"))
    }
}
