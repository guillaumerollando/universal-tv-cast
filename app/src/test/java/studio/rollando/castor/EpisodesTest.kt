package studio.rollando.castor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import studio.rollando.castor.browser.Episodes

class EpisodesTest {
    private fun next(u: String) = Episodes.find(u)?.next

    @Test fun commonShapes() {
        assertEquals("https://site.tv/anime/one-piece/episode-13", next("https://site.tv/anime/one-piece/episode-12"))
        assertEquals("https://site.tv/watch/frieren-ep-08-vostfr/", next("https://site.tv/watch/frieren-ep-07-vostfr/"))
        assertEquals("https://site.tv/v.php?id=9&ep=4", next("https://site.tv/v.php?id=9&ep=3"))
        assertEquals("https://site.tv/series/dark/saison-1/6", next("https://site.tv/series/dark/saison-1/5"))
        assertEquals("https://site.tv/naruto-episode-100.html", next("https://site.tv/naruto-episode-99.html"))
        assertEquals(12, Episodes.find("https://site.tv/x/e12")?.number)
    }

    @Test fun sameSeriesSameKey() {
        val k = Episodes.seriesKey("https://www.site.tv/anime/one-piece/episode-12")
        assertEquals(k, Episodes.seriesKey("https://site.tv/anime/one-piece/episode-13/"))
        assertEquals(Episodes.seriesKey("https://site.tv/v.php?id=9&ep=3"), Episodes.seriesKey("https://site.tv/v.php?id=9&ep=4"))
        org.junit.Assert.assertNotEquals(k, Episodes.seriesKey("https://site.tv/anime/naruto/episode-12"))
        assertEquals("site.tv", Episodes.seriesKey("https://site.tv/"))
    }

    @Test fun notEpisodes() {
        assertNull(Episodes.find("https://site.tv/"))
        assertNull(Episodes.find("https://site.tv/films/dune-2021"))
        assertNull(Episodes.find("https://site.tv/search?q=naruto"))
    }
}
