package com.rollandostudio.universaltvcast

import com.rollandostudio.universaltvcast.proxy.MkvProbe
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class SkipsTest {
    /** Réseau et fichier : SKIP_DIR (dossier avec chap.mkv), résultats écrits dans skip.txt. */
    @Test fun sources() {
        val dir = System.getenv("SKIP_DIR")?.let(::File)
        assumeTrue(dir != null)
        val out = StringBuilder()
        out.append("chapitres : ").append(MkvProbe.chapters(File(dir, "chap.mkv").readBytes().copyOf(2_000_000))).append('\n')
        val m = com.rollandostudio.universaltvcast.cast.Skips
        fun t(name: String, f: () -> Any?) = out.append(name).append(" : ").append(runCatching(f).getOrElse { "ERREUR $it" }).append('\n')
        val introDb = m.javaClass.getDeclaredMethod("introDb", String::class.java, Int::class.java, Int::class.java).apply { isAccessible = true }
        val aniSkip = m.javaClass.getDeclaredMethod("aniSkip", String::class.java, Int::class.java, Int::class.java, Double::class.java).apply { isAccessible = true }
        t("TheIntroDB Breaking Bad S01E02") { introDb.invoke(m, "Breaking Bad", 1, 2) }
        t("TheIntroDB The Office S02E01") { introDb.invoke(m, "The Office", 2, 1) }
        t("AniSkip Frieren 1 (1510 s)") { aniSkip.invoke(m, "Sousou no Frieren", 1, 1, 1510.0) }
        t("AniSkip Frieren 1 (durée fausse 600 s)") { aniSkip.invoke(m, "Sousou no Frieren", 1, 1, 600.0) }
        t("AniSkip Jujutsu Kaisen S2E3") { aniSkip.invoke(m, "Jujutsu Kaisen", 2, 3, 0.0) }
        File(dir, "skip.txt").writeText(out.toString())
    }
}
