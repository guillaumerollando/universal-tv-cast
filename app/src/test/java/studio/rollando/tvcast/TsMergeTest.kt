package studio.rollando.tvcast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import studio.rollando.tvcast.proxy.TsMerge
import java.io.File

/** Image et son servis à part (deux MPEG-TS fabriqués avec ffmpeg) → un seul flux lisible avec les deux. */
class TsMergeTest {
    private fun res(name: String) = javaClass.classLoader!!.getResourceAsStream(name)!!.readBytes()

    @Test fun mergesVideoAndAudio() {
        val merged = TsMerge().merge(res("video_only.ts"), res("audio_only.ts"))
        assertEquals(0, merged.size % 188)
        val pmt = TsMerge.readPmt(merged)!!
        assertEquals(listOf(0x1B, 0x0F), pmt.second.map { it.first })
        // Vérification par ffprobe s'il est installé : deux pistes, décodables.
        val ffprobe = listOf("/usr/bin/ffprobe", "/usr/local/bin/ffprobe").firstOrNull { File(it).exists() }
        assumeTrue(ffprobe != null)
        val f = File.createTempFile("merged", ".ts").apply { writeBytes(merged); deleteOnExit() }
        val out = ProcessBuilder(ffprobe, "-v", "error", "-show_entries", "stream=codec_type", "-of", "csv=p=0", f.path)
            .redirectErrorStream(true).start().inputStream.bufferedReader().readText()
        assertTrue(out, out.contains("video") && out.contains("audio"))
    }
}
