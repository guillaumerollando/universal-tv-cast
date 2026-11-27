package studio.rollando.tvcast

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import studio.rollando.tvcast.cast.TsMuxer
import java.io.ByteArrayOutputStream
import kotlin.random.Random

/** Le multiplexeur MPEG-TS du diaporama : chaque image doit ressortir intacte, quelle que soit sa taille. */
class TsMuxerTest {
    @Test fun framesSurviveMuxing() {
        val mux = TsMuxer()
        val rnd = Random(7)
        for (size in (1..400) + listOf(160, 161, 162, 163, 170, 175, 176, 177, 178, 183, 184, 185, 360, 361, 367, 368, 5000, 70000)) {
            val frame = ByteArray(size) { rnd.nextInt().toByte() }
            val key = size % 3 == 0
            val ts = mux.frame(frame, 90_000L + size, key)
            assertEquals(0, ts.size % 188)
            val pes = ByteArrayOutputStream()
            for (i in ts.indices step 188) {
                assertEquals(0x47.toByte(), ts[i])
                val afc = (ts[i + 3].toInt() ushr 4) and 3
                var p = i + 4
                if (afc == 3 || afc == 2) p += 1 + (ts[i + 4].toInt() and 0xFF)
                if (afc == 1 || afc == 3) pes.write(ts, p, i + 188 - p)
            }
            val b = pes.toByteArray()
            val payload = b.copyOfRange(9 + (b[8].toInt() and 0xFF), b.size)
            assertArrayEquals("taille $size", byteArrayOf(0, 0, 0, 1, 0x09, 0xF0.toByte()) + frame, payload)
        }
    }
}
