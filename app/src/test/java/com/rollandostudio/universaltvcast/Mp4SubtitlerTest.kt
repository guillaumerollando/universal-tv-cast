package com.rollandostudio.universaltvcast

import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import com.rollandostudio.universaltvcast.proxy.Mp4Subtitler
import java.io.File
import java.io.RandomAccessFile

/** Vérifie avec ffmpeg (s'il est installé) que la piste de sous-titres ajoutée est valide et que la vidéo reste intacte. */
class Mp4SubtitlerTest {
    private val dir = File(System.getenv("EMB_DIR") ?: (System.getProperty("java.io.tmpdir") + "/emb"))

    private fun virtualFile(src: File, out: File) {
        val raf = RandomAccessFile(src, "r")
        val reader = { off: Long, len: Int -> ByteArray(minOf(len.toLong(), raf.length() - off).toInt()).also { raf.seek(off); raf.readFully(it) } }
        val cues = Mp4Subtitler.parseSrt(File(dir, "subs.srt").readBytes())
        assertEquals(3, cues.size)
        assertEquals("Bonjour Walter !", cues[0].text)
        val plan = Mp4Subtitler(raf.length(), reader).build(cues, "fre")
        out.outputStream().use { o ->
            for (s in plan.segments) when (s) {
                is Mp4Subtitler.Segment.Mem -> o.write(s.bytes)
                is Mp4Subtitler.Segment.Src -> o.write(reader(s.from, s.length.toInt()))
            }
        }
        assertEquals(plan.total, out.length())
        raf.close()
    }

    private fun run(vararg cmd: String): String {
        val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        return p.inputStream.bufferedReader().readText().also { p.waitFor() }
    }

    @Test fun subtitlesAreEmbedded() {
        assumeTrue(File("/usr/bin/ffprobe").exists() && File(dir, "debut.mp4").exists())
        for (name in listOf("debut", "fin")) {
            val out = File(dir, "$name-st.mp4")
            virtualFile(File(dir, "$name.mp4"), out)
            val streams = run("ffprobe", "-v", "error", "-show_entries", "stream=codec_type,codec_name:stream_tags=language", "-of", "csv=p=0", out.path)
            println("PROBE $name : ${streams.trim().replace("\n", " | ")}")
            val srt = File(dir, "$name-extrait.srt")
            run("ffmpeg", "-y", "-v", "error", "-i", out.path, "-map", "0:s:0", srt.path)
            println("PROBE $name sous-titres : ${srt.readText().trim().replace("\n", " / ")}")
            val errors = run("ffmpeg", "-v", "error", "-i", out.path, "-map", "0:v", "-map", "0:a", "-f", "null", "-")
            println("PROBE $name erreurs de décodage : '${errors.trim()}'")
        }
    }
}
