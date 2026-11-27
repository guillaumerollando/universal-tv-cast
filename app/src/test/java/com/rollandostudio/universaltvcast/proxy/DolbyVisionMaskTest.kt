package com.rollandostudio.universaltvcast.proxy

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class DolbyVisionMaskTest {
    /** Mini MKV : en-tête EBML, Tracks > TrackEntry > BlockAdditionMapping (dvvC, profil [profile], compatibilité [compat]). */
    private fun mkv(profile: Int, compat: Int): ByteArray {
        val rec = byteArrayOf(1, 0, (profile shl 1).toByte(), 0x35, (compat shl 4).toByte()) + ByteArray(19)
        val extra = byteArrayOf(0x41, 0xED.toByte(), (0x80 or rec.size).toByte()) + rec
        val type = byteArrayOf(0x41, 0xE7.toByte(), 0x84.toByte()) + "dvvC".toByteArray()
        val mapping = byteArrayOf(0x41, 0xE4.toByte(), (0x80 or (type.size + extra.size)).toByte()) + type + extra
        val codec = byteArrayOf(0x86.toByte(), 0x8F.toByte()) + "V_MPEGH/ISO/HEVC".toByteArray().copyOf(15)
        val entry = byteArrayOf(0xAE.toByte(), (0x80 or (codec.size + mapping.size)).toByte()) + codec + mapping
        val tracks = byteArrayOf(0x16, 0x54, 0xAE.toByte(), 0x6B, (0x80 or entry.size).toByte()) + entry
        return byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte(), 0x80.toByte()) + tracks
    }

    @Test fun masqueProfil8() {
        val f = mkv(8, 1)
        val m = MkvProbe.dolbyVisionMask(f)
        assertEquals(1, m.size)
        val (at, bytes) = m[0]
        assertEquals(0x41.toByte(), f[at]); assertEquals(0xE4.toByte(), f[at + 1])
        assertEquals(0xEC.toByte(), bytes[0])
        // même longueur que l'élément d'origine
        assertEquals(3 + (f[at + 2].toInt() and 0x7F), bytes.size)
        assertTrue(bytes.drop(2).all { it == 0.toByte() })
    }

    @Test fun profil5Intact() {
        assertTrue(MkvProbe.dolbyVisionMask(mkv(5, 0)).isEmpty())
    }

    @Test fun pasMkv() {
        assertTrue(MkvProbe.dolbyVisionMask(ByteArray(100)).isEmpty())
    }

    /** Vrai fichier (essai manuel) : MKV_DV=fichier.mkv MKV_DV_OK=fichier-masqué.mkv (masqué à la main, accepté par la télé). */
    @Test fun vraiFichier() {
        val src = System.getenv("MKV_DV")?.let(::File); val ok = System.getenv("MKV_DV_OK")?.let(::File)
        assumeTrue(src != null && ok != null && src.exists() && ok.exists())
        val head = src!!.inputStream().use { it.readNBytes(MkvProbe.HEAD_BYTES) }
        val patched = head.copyOf()
        for ((at, b) in MkvProbe.dolbyVisionMask(head)) System.arraycopy(b, 0, patched, at, b.size)
        assertArrayEquals(ok!!.inputStream().use { it.readNBytes(MkvProbe.HEAD_BYTES) }, patched)
    }
}
