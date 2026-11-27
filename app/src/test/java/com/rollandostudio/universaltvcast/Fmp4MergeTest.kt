package com.rollandostudio.universaltvcast

import com.rollandostudio.universaltvcast.proxy.Fmp4Merge
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Fusion image + son CMAF sur de vrais morceaux (dossier donné par FMP4_SAMPLES ; ignoré sinon). */
class Fmp4MergeTest {
    @Test fun fusionReelle() {
        val dir = System.getenv("FMP4_SAMPLES")?.let(::File)
        assumeTrue(dir != null && File(dir, "v_init.mp4").exists())
        val m = Fmp4Merge()
        fun f(n: String) = File(dir, n).readBytes()
        val out = File(dir, "fusion.mp4").outputStream()
        out.write(m.init(f("v_init.mp4"), f("a_init.mp4"))!!)
        out.write(m.merge(f("v_447.m4s"), f("a_447.m4s") + f("a_448.m4s")))
        out.write(m.merge(f("v_448.m4s"), f("a_449.m4s")))
        out.close()
    }
}
