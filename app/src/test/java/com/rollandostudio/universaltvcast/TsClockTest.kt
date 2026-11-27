package com.rollandostudio.universaltvcast

import com.rollandostudio.universaltvcast.proxy.TsClock
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Deux morceaux TS d'horloges différentes (pub insérée) recollés en une ligne de temps continue (dossier TS_SAMPLES). */
class TsClockTest {
    @Test fun coupure() {
        val dir = System.getenv("TS_SAMPLES")?.let(::File)
        assumeTrue(dir != null && File(dir, "a.ts").exists())
        val c = TsClock()
        File(dir, "suite.ts").outputStream().use { o ->
            o.write(c.process(File(dir, "a.ts").readBytes(), false))
            o.write(c.process(File(dir, "b.ts").readBytes(), true))
        }
    }
}
