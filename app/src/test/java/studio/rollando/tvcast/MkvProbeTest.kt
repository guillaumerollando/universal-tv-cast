package studio.rollando.tvcast

import org.junit.Assert.assertEquals
import org.junit.Test
import studio.rollando.tvcast.proxy.MkvProbe

/** Repérage des sous-titres déjà présents dans un MKV (fichiers fabriqués avec ffmpeg). */
class MkvProbeTest {
    private fun res(name: String) = javaClass.classLoader!!.getResourceAsStream(name)!!.readBytes()

    @Test fun findsEmbeddedSubtitles() {
        assertEquals(listOf("fre", "eng"), MkvProbe.subtitleLanguages(res("subs.mkv")))
    }

    @Test fun noSubtitles() {
        assertEquals(emptyList<String>(), MkvProbe.subtitleLanguages(res("nosubs.mkv")))
        assertEquals(emptyList<String>(), MkvProbe.subtitleLanguages(ByteArray(100)))
    }
}
