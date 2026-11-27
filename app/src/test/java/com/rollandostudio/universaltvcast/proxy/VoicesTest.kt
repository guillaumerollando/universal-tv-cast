package com.rollandostudio.universaltvcast.proxy

import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile

/** Essais sur de vrais fichiers à deux voix (fre puis eng) : VOICES_DIR=dossier (o_deux_voix.mp4, p_…, q_….ts). */
class VoicesTest {
    private val dir = System.getenv("VOICES_DIR")?.let(::File)

    private fun reader(f: File): (Long, Int) -> ByteArray = { off, len ->
        RandomAccessFile(f, "r").use { r -> r.seek(off); val b = ByteArray(minOf(len.toLong(), r.length() - off).toInt()); r.readFully(b); b }
    }

    private fun mp4(name: String) {
        val f = File(dir, name)
        val tracks = Voices.mp4Tracks(f.length(), reader(f))
        val voices = Voices.mp4Voices(tracks)
        println("$name : $voices")
        assertEquals(listOf("fre", "eng"), voices.map { it.lang })
    }

    @Test fun mp4Debut() { assumeTrue(dir?.exists() == true); mp4("o_deux_voix.mp4") }
    @Test fun mp4Fin() { assumeTrue(dir?.exists() == true); mp4("p_deux_voix_finmoov.mp4") }

    @Test fun ts() {
        assumeTrue(dir?.exists() == true)
        val f = File(dir, "q_deux_voix.ts")
        val all = f.readBytes()
        val info = Voices.tsInfo(all.copyOf(2_000_000))!!
        val voices = Voices.tsVoices(info)
        println("ts : $voices")
        assertEquals(listOf("fre", "eng"), voices.map { it.lang })
    }
}
