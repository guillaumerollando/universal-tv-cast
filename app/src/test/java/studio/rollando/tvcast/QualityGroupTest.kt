package studio.rollando.tvcast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import studio.rollando.tvcast.browser.VideoSniffer

/** Plusieurs qualités d'une même vidéo : une seule proposition (Wikimedia Commons, PeerTube…). */
class QualityGroupTest {
    private val s = VideoSniffer()
    private val c = "https://upload.wikimedia.org/wikipedia/commons/transcoded/c/c0/Big_Buck_Bunny_4K.webm/Big_Buck_Bunny_4K.webm"

    @Test fun memeVideoEnPlusieursQualites() {
        val orig = "https://upload.wikimedia.org/wikipedia/commons/c/c0/Big_Buck_Bunny_4K.webm"
        assertEquals(s.stemOf(orig), s.stemOf("$c.480p.vp9.webm"))
        assertEquals(s.stemOf(orig), s.stemOf("$c.360p.mpeg4.mov"))
        assertEquals(s.stemOf("https://x.org/static/web-videos/9d1c-720.mp4"), s.stemOf("https://x.org/static/web-videos/9d1c-360.mp4"))
    }

    @Test fun episodesDifferents() {
        // Numéros d'épisode : jamais pris pour une qualité.
        assertNotEquals(s.stemOf("https://x.org/one-piece-1071.mp4"), s.stemOf("https://x.org/one-piece-1072.mp4"))
        assertNotEquals(s.stemOf("https://x.org/serie-e12-720p.mp4"), s.stemOf("https://x.org/serie-e13-720p.mp4"))
    }

    @Test fun meilleureQualitePourLaTele() {
        val orig = "https://upload.wikimedia.org/wikipedia/commons/c/c0/Big_Buck_Bunny_4K.webm"
        assertTrue(s.fileScore("$c.1080p.vp9.webm") > s.fileScore(orig))           // pas l'original 4K
        assertTrue(s.fileScore("$c.1080p.vp9.webm") > s.fileScore("$c.480p.vp9.webm"))
        assertTrue(s.fileScore("https://x.org/a-720.mp4") > s.fileScore("https://x.org/a-720.webm")) // MP4 d'abord
    }
}
