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

    @Test fun languageCodes() {
        assertEquals("en", SubtitleFinder.norm("eng")); assertEquals("fr", SubtitleFinder.norm("fre"))
        assertEquals("fr", SubtitleFinder.norm("fra")); assertEquals("pt-BR", SubtitleFinder.norm("pob"))
        assertEquals("nl", SubtitleFinder.norm("dut"))
    }
}
