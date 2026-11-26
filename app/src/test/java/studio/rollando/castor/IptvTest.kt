package studio.rollando.castor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import studio.rollando.castor.data.Iptv
import java.util.Locale

class IptvTest {
    private val list = """
        #EXTM3U x-tvg-url="https://epg.example/a.xml.gz, https://epg.example/b.xml.gz"
        #EXTINF:-1 tvg-name="Kanali 7" tvg-logo="https://i.imgur.com/rL2v9pM.png" tvg-id="Kanali7.al" group-title="Albania",Kanali 7 Ⓢ
        https://fe.tring.al/delta/105/out/u/1200_1.m3u8
        #EXTINF:-1 tvg-id="2MMonde.ma@SD" tvg-logo="https://i.imgur.com/MvpntzA.png" http-referrer="http://www.radio2m.ma/" http-user-agent="Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:134.0) Gecko/20100101 Firefox/134.0" group-title="General",2M Monde (360p)
        #EXTVLCOPT:http-referrer=http://www.radio2m.ma/
        https://cdn.example.ma/2m/index.m3u8
        #EXTINF:-1 group-title="News",France 24 Ⓨ
        https://www.youtube.com/c/FRANCE24/live
        #EXTINF:-1 tvg-logo="" group-title="Movies;Kids",Ciné, Toons (1080p) [Geo-blocked]
        #EXTVLCOPT:http-user-agent=VLC/3.0
        http://example.com/cine.m3u8
        #EXTINF:-1,Doublon
        https://fe.tring.al/delta/105/out/u/1200_1.m3u8
    """.trimIndent()

    @Test fun recognisesChannelListsNotHls() {
        assertTrue(Iptv.isChannelList(list))
        assertFalse(Iptv.isChannelList("#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXTINF:6.0,\nseg1.ts\n"))
        assertFalse(Iptv.isChannelList("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\nlow.m3u8\n"))
        assertFalse(Iptv.isChannelList("<html>"))
    }

    @Test fun parsesNamesLogosGroupsAndHeaders() {
        val c = Iptv.parse(list)
        assertEquals(listOf("Kanali 7", "2M Monde", "Ciné, Toons"), c.map { it.name })
        assertEquals("https://i.imgur.com/rL2v9pM.png", c[0].logo)
        assertEquals("Albania", c[0].group)
        assertEquals("http://www.radio2m.ma/", c[1].headers["Referer"])
        assertTrue(c[1].headers["User-Agent"]!!.startsWith("Mozilla/5.0"))
        assertNull(c[2].logo)
        assertEquals("Movies", c[2].group)
        assertEquals("VLC/3.0", c[2].headers["User-Agent"])
    }

    @Test fun namesListsFromTheirAddress() {
        assertEquals("Free-TV", Iptv.nameFor("https://raw.githubusercontent.com/Free-TV/IPTV/master/playlist.m3u8", Locale.FRENCH))
        assertEquals("iptv-org · France", Iptv.nameFor("https://iptv-org.github.io/iptv/countries/fr.m3u", Locale.FRENCH))
        assertEquals("iptv-org · Français", Iptv.nameFor("https://iptv-org.github.io/iptv/languages/fra.m3u", Locale.FRENCH))
        assertEquals("iptv-org", Iptv.nameFor("https://iptv-org.github.io/iptv/index.m3u", Locale.FRENCH))
    }

    @Test fun findsXtreamGuideAndHeaderGuides() {
        assertEquals("http://tv.example:8080/xmltv.php?username=bob&password=s3cr",
            Iptv.xtreamGuide("http://tv.example:8080/get.php?username=bob&password=s3cr&type=m3u_plus&output=ts"))
        assertNull(Iptv.xtreamGuide("https://iptv-org.github.io/iptv/countries/fr.m3u"))
        assertEquals(listOf("https://epg.example/a.xml.gz", "https://epg.example/b.xml.gz"), Iptv.guideUrls(list))
        val c = Iptv.parse(list)
        assertEquals("Kanali7.al", c[0].tvgId)
        assertEquals("ma", c[1].country)
    }
}
