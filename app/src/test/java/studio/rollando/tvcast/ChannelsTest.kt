package studio.rollando.tvcast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import studio.rollando.tvcast.data.Channels

class ChannelsTest {
    private fun name(t: String) = Channels.find(t)?.name

    @Test fun recognisesFromStreamAddress() {
        assertEquals("Arte", name("https://artesimulcast.akamaized.net/hls/live/2031003/artelive_fr/index.m3u8"))
        assertEquals("TV5Monde Info", name("https://ott.tv5monde.com/Content/HLS/Live/channel(info)/index.m3u8 TV5 Monde Info"))
        assertEquals("ICI RDI", name("https://rcavlive.akamaized.net/hls/live/704025/xcanrdi/master.m3u8"))
        assertEquals("Savoir Média", name("https://hls.savoir.media/live/stream.m3u8"))
        assertEquals("BX1", name("https://59959724487e3.streamlock.net/stream/live/playlist.m3u8 BX1"))
    }

    @Test fun recognisesFromTitleWithSpacesAndAccents() {
        assertEquals("France 2", name("France 2 en direct"))
        assertEquals("Public Sénat", name("Public Sénat"))
        assertEquals("Canal+", name("Canal+"))
        assertEquals("L'Équipe", name("L'Équipe live"))
    }

    @Test fun recognisesWorldChannels() {
        assertEquals("BBC One", name("BBC One HD"))
        assertEquals("BBC News", name("https://vs-hls.bbc.example/bbc_news/index.m3u8"))
        assertEquals("Das Erste", name("Das Erste"))
        assertEquals("Rai News 24", name("https://rainews1-live.akamaized.net/hls/live/598326/rainews1/rainews1/rainews_1800/chunklist.m3u8"))
        assertEquals("Antena 3", name("Antena 3 en directo"))
        assertEquals("NHK World", name("NHK World Japan"))
        assertEquals("Al Jazeera English", name("Al Jazeera English"))
        assertEquals("CNN en Español", name("CNN en Español"))
        assertEquals("ABC News", name("ABC News Live"))
    }

    @Test fun doesNotGuessFromInnocentWords() {
        assertNull(Channels.find("https://example.com/videos/cartes-departement/index.m3u8"))
        assertNull(Channels.find("Mon film de vacances"))
        assertNull(Channels.find("https://cdn.example.com/hls/news/live/sports/one/playlist.m3u8"))
        assertNull(Channels.find("Holiday record of the history class"))
    }
}
