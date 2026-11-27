package studio.rollando.castor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import studio.rollando.castor.data.Epg
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** Recalage des sources du guide qui se trompent d'heure (cas relevés dans le guide France, novembre 2026). */
class EpgShiftTest {
    private val paris = TimeZone.getTimeZone("Europe/Paris")
    private fun t(s: String) = SimpleDateFormat("yyyyMMddHHmmss Z", Locale.US).parse(s)!!.time

    @Test fun ecartLuDansLeTitre() {
        // France Télévisions : « Journal 20h00 » annoncé à 19 h (heure de Paris) → 1 h trop tôt.
        assertEquals(-1, Epg.hourGap("Journal 20h00", t("20261126190000 +0100"), paris))
        // SFR : « Le 6h info » à 6 h de Paris (écrit 05:00 UTC) → juste.
        assertEquals(0, Epg.hourGap("Le 6h info", t("20261125050000 +0000"), paris))
        assertEquals(0, Epg.hourGap("13h15, le dimanche", t("20261129131500 +0100"), paris))
        // Pas d'heure dans le titre, ou heure sans rapport (épisode à 20 h 37 pour « 19h sports ») : rien.
        assertNull(Epg.hourGap("Envoyé spécial", t("20261126201000 +0100"), paris))
        assertNull(Epg.hourGap("19h sports", t("20261126203700 +0100"), paris))
    }

    @Test fun decalageDUneSource() {
        assertEquals(-1, Epg.sourceShift(List(64) { -1 } + List(7) { 0 } + listOf(1, 1, 1, -2, -2)))
        assertEquals(0, Epg.sourceShift(List(50) { 0 } + List(12) { 1 } + listOf(3, 3, -2)))
        // Trop peu de titres, ou pas de majorité nette : on ne touche à rien.
        assertEquals(0, Epg.sourceShift(List(5) { -1 }))
        assertEquals(0, Epg.sourceShift(List(10) { -1 } + List(8) { 0 }))
    }
}

class EpgPickTest {
    @Test fun programmesQuiSeChevauchent() {
        val m = 60_000L
        val bloc = Epg.Prog("Soirée séries", 0, 180 * m)
        val ep1 = Epg.Prog("The Rookie (1)", 10 * m, 60 * m)
        val ep2 = Epg.Prog("The Rookie (2)", 60 * m, 110 * m)
        val (now, next) = Epg.pick(listOf(bloc, ep1, ep2), 70 * m)!!
        assertEquals("The Rookie (2)", now.title)
        assertNull(next)
        assertEquals("The Rookie (2)", Epg.pick(listOf(bloc, ep1, ep2), 30 * m)!!.second?.title)
    }

    @Test fun grilleSansLesBlocs() {
        val m = 60_000L
        val bloc = Epg.Prog("Soirée séries", 0, 180 * m)
        val ep1 = Epg.Prog("The Rookie (1)", 10 * m, 60 * m)
        val ep2 = Epg.Prog("The Rookie (2)", 60 * m, 110 * m)
        val film = Epg.Prog("Film", 180 * m, 300 * m)
        assertEquals(listOf("The Rookie (1)", "The Rookie (2)", "Film"), Epg.withoutBlocks(listOf(bloc, ep1, ep2, film)).map { it.title })
        // Un programme qui n'en recouvre qu'un seul reste (deux sources pour la même émission).
        assertEquals(2, Epg.withoutBlocks(listOf(bloc, ep1)).size)
        // La même émission annoncée par deux sources (majuscules différentes) : une seule fois.
        assertEquals(1, Epg.withoutBlocks(listOf(Epg.Prog("Journal Météo Climat", 0, m), Epg.Prog("Journal Météo climat", 0, 2 * m))).size)
    }
}
