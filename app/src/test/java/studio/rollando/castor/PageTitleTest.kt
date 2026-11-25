package studio.rollando.castor

import org.junit.Assert.assertEquals
import org.junit.Test
import studio.rollando.castor.browser.PageTitle

class PageTitleTest {
    @Test fun seriesName() {
        assertEquals("Mon Anime", PageTitle.series("Mon Anime – Épisode 12"))
        assertEquals("One Piece", PageTitle.series("One Piece Episode 1120 VOSTFR"))
        assertEquals("Dark", PageTitle.series("Dark S01E05"))
        assertEquals("Black Clover Season 2", PageTitle.series("Black Clover Season 2"))
    }

    @Test fun cleansSiteNameAndHooks() {
        assertEquals("Black Clover Season 2", PageTitle.clean("Regarder maintenant : Black Clover Season 2 · Otakubinge", "otakubinge.com"))
        assertEquals("One Piece Épisode 1120", PageTitle.clean("One Piece Épisode 1120 en streaming - AnimeSama", "anime-sama.fr"))
        assertEquals("Dune", PageTitle.clean("Watch Dune online free | MovieSite", "moviesite.to"))
        assertEquals("Mon Anime – Épisode 12", PageTitle.clean("Mon Anime – Épisode 12", "10.0.2.2"))
    }
}
