package studio.rollando.castor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import studio.rollando.castor.subs.SubtitleFinder

class SubtitleFinderTest {
    @Test fun movieName() {
        val p = SubtitleFinder.parse("Spider-Man.Brand.New.Day.2026.BLURAY.1080p.BluRay.x264.AAC5.1-[YTS.MX].mp4")
        assertEquals("Spider-Man Brand New Day", p.title); assertEquals(2026, p.year); assertEquals(false, p.isSeries)
        assertEquals("Spider-Man Brand New Day (2026)", p.display)
    }

    @Test fun seriesName() {
        val p = SubtitleFinder.parse("The.Office.US.S02E05.720p.WEB-DL.x265-GROUP.mkv")
        assertEquals("The Office US", p.title); assertEquals(2, p.season); assertEquals(5, p.episode)
        assertEquals("The Office US S02E05", p.display)
    }

    @Test fun otherShapes() {
        assertEquals("Blade Runner 2049", SubtitleFinder.parse("Blade Runner 2049 (2017) [2160p] HDR.mkv").title)
        assertEquals(2017, SubtitleFinder.parse("Blade Runner 2049 (2017) [2160p] HDR.mkv").year)
        val e = SubtitleFinder.parse("dark_1x03_multi_vff.mkv")
        assertEquals(1, e.season); assertEquals(3, e.episode)
        assertEquals("Le Fabuleux Destin d Amélie Poulain", SubtitleFinder.parse("Le.Fabuleux.Destin.d.Amélie.Poulain.2001.FRENCH.1080p.mkv").title)
    }

    @Test fun animeNames() {
        fun check(name: String, title: String, season: Int, episode: Int) {
            val p = SubtitleFinder.parse(name)
            assertEquals(name, title, p.title); assertEquals(name, season, p.season); assertEquals(name, episode, p.episode)
        }
        check("[SubsPlease] Yomi no Tsugai - 05 (1080p) [A1B2C3D4].mkv", "Yomi no Tsugai", 1, 5)
        check("[Erai-raws] Tsue to Tsurugi no Wistoria 2nd Season - 05 [1080p CR WEB-DL AVC AAC][MultiSub][ABCD].mkv", "Tsue to Tsurugi no Wistoria", 2, 5)
        check("[ASW] Kaiju No. 8 S2 - 03 [1080p HEVC x265 10Bit][AAC].mkv", "Kaiju No 8", 2, 3)
        check("One Piece - 1120 [1080p].mkv", "One Piece", 1, 1120)
        check("Frieren Episode 7 VOSTFR.mp4", "Frieren", 1, 7)
        check("[Judas] Dandadan - 12v2.mkv", "Dandadan", 1, 12)
        check("[Judas] Yomi no Tsugai - S01E01v2.mkv", "Yomi no Tsugai", 1, 1)
        // Pas des épisodes :
        assertEquals(false, SubtitleFinder.parse("Spider-Man - Into the Spider-Verse 2018 1080p.mkv").isSeries)
        assertEquals(false, SubtitleFinder.parse("Some Film - 2019 1080p.mkv").isSeries)
    }

    @Test fun languageCodes() {
        assertEquals("en", SubtitleFinder.norm("eng")); assertEquals("fr", SubtitleFinder.norm("fre"))
        assertEquals("fr", SubtitleFinder.norm("fra")); assertEquals("pt-BR", SubtitleFinder.norm("pob"))
        assertEquals("nl", SubtitleFinder.norm("dut"))
    }
}
