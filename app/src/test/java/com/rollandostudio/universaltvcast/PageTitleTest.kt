package com.rollandostudio.universaltvcast

import org.junit.Assert.assertEquals
import org.junit.Test
import com.rollandostudio.universaltvcast.browser.PageTitle

class PageTitleTest {
    @Test fun seriesName() {
        assertEquals("Mon Anime", PageTitle.series("Mon Anime – Épisode 12"))
        assertEquals("One Piece", PageTitle.series("One Piece Episode 1120 VOSTFR"))
        assertEquals("Dark", PageTitle.series("Dark S01E05"))
        assertEquals("Black Clover Season 2", PageTitle.series("Black Clover Season 2"))
    }

    @Test fun episodeFromTitle() {
        assertEquals(12, PageTitle.episode("Mon Anime – Épisode 12"))
        assertEquals(1120, PageTitle.episode("One Piece Episode 1120 VOSTFR"))
        assertEquals(5, PageTitle.episode("Dark S01E05"))
        assertEquals(3, PageTitle.episode("Black Clover Season 2 Ep. 3"))
        assertEquals(null, PageTitle.episode("Black Clover Season 2"))
        assertEquals(null, PageTitle.episode("Dune Part Two"))
    }

    @Test fun cleansSiteNameAndHooks() {
        assertEquals("Black Clover Season 2", PageTitle.clean("Regarder maintenant : Black Clover Season 2 · Otakubinge", "otakubinge.com"))
        assertEquals("One Piece Épisode 1120", PageTitle.clean("One Piece Épisode 1120 en streaming - AnimeSama", "anime-sama.fr"))
        assertEquals("Dune", PageTitle.clean("Watch Dune online free | MovieSite", "moviesite.to"))
        assertEquals("Mon Anime – Épisode 12", PageTitle.clean("Mon Anime – Épisode 12", "10.0.2.2"))
    }

    @Test fun partOfSeriesInTitleAndAddress() {
        org.junit.Assert.assertEquals(1, com.rollandostudio.universaltvcast.browser.PageTitle.episode("Prisonnière 951 (1/4) - Regarder le film complet | ARTE"))
        val e = com.rollandostudio.universaltvcast.browser.Episodes.find("https://www.arte.tv/fr/videos/130430-001-A/prisonniere-951-1-4/")
        org.junit.Assert.assertEquals(1, e?.number)
        org.junit.Assert.assertEquals("https://www.arte.tv/fr/videos/130430-002-A/prisonniere-951-2-4/", e?.next) // vérifié : vraie page de la partie 2
        org.junit.Assert.assertEquals(12, com.rollandostudio.universaltvcast.browser.Episodes.find("https://site.tv/anime/x/episode-12")?.number)
    }

    @Test fun displayTitlesWithoutSiteFormulas() {
        org.junit.Assert.assertEquals("Prisonnière 951 (1/4)",
            com.rollandostudio.universaltvcast.browser.PageTitle.display("Prisonnière 951 (1/4) - Regarder la série | ARTE", "www.arte.tv"))
        org.junit.Assert.assertEquals("Charlie Chaplin Festival",
            com.rollandostudio.universaltvcast.browser.PageTitle.display("Charlie Chaplin Festival : Free Download, Borrow, and Streaming : Internet Archive", "archive.org"))
    }

    @Test fun mediaFilePage() {
        org.junit.Assert.assertEquals("Big Buck Bunny 4K",
            com.rollandostudio.universaltvcast.browser.PageTitle.display("File:Big Buck Bunny 4K.webm - Wikimedia Commons", "commons.wikimedia.org"))
        org.junit.Assert.assertEquals("Big Buck Bunny 4K",
            com.rollandostudio.universaltvcast.browser.PageTitle.display("Fichier:Big Buck Bunny 4K.webm — Wikimedia Commons", "commons.wikimedia.org"))
    }

    @Test fun declaredSiteName() {
        org.junit.Assert.assertEquals("Le loup (documentaire)",
            com.rollandostudio.universaltvcast.browser.PageTitle.withoutSite("Le loup (documentaire) - Maternelle Éducatif", "Maternelle Éducatif"))
        org.junit.Assert.assertEquals("Charge – Blender Open Movie",
            com.rollandostudio.universaltvcast.browser.PageTitle.withoutSite("Charge - Blender Open Movie - Blender Video", "Blender Video"))
    }
}
