package tv.own.owntv.player

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * German4K 3.2.1 (p-11): Obergrenze für hängende mpv-Kerne. Beim Heap-Profiling blieben zwei übrig, weil nichts
 * begrenzte, wie viele verworfene Kerne gleichzeitig leben. Nachgebaut wird die Entscheidungsfolge des Players
 * (ensureInit → Kern hängt → kernHaengtVerwerfen → nächster Titel) mit echten Abbau-Fäden aus [MpvRestKerne].
 */
class MpvKernGrenzeTest {
    private val nieFrei = CountDownLatch(1)
    private val faeden = mutableListOf<Thread>()

    @Before fun setUp() = MpvRestKerne.zuruecksetzenFuerTest()

    @After fun aufraeumen() {
        nieFrei.countDown()
        faeden.forEach { it.join(1_000) }
        MpvRestKerne.zuruecksetzenFuerTest()
    }

    /** Ein Kern hängt für immer: verwerfen wie der Player (Abbau-Faden, der erst bei [frei] weiterkommt). */
    private fun verwerfen(frei: CountDownLatch = nieFrei) {
        faeden += MpvRestKerne.abbauen(stoppen = { frei.await() }, zerstoeren = {}, gestorben = {})
    }

    /** Spielt [n] Titel, deren mpv-Kern jedes Mal hängt. Liefert (gebaute Kerne, höchste Rest-Zahl, ExoPlayer-Starts). */
    private fun lauf(an: Boolean, n: Int): Triple<Int, Int, Int> {
        var sperre = false
        var gebaut = 0
        var maxRest = 0
        var exo = 0
        repeat(n) {
            val lebend = MpvRestKerne.anzahl()
            if (MpvKernGrenze.sperreLoesen(sperre, lebend)) sperre = false
            if (MpvKernGrenze.mpvGesperrt(an, sperre, lebend)) {
                exo++ // kein neuer Kern — der Titel startet auf ExoPlayer
                return@repeat
            }
            gebaut++
            // Der neue Kern hängt: vor dem Verwerfen prüfen, ob schon ein Rest lebt.
            if (MpvKernGrenze.exoVorVerwerfen(an, MpvRestKerne.anzahl())) sperre = true
            verwerfen()
            maxRest = maxOf(maxRest, MpvRestKerne.anzahl())
        }
        return Triple(gebaut, maxRest, exo)
    }

    @Test fun `ohne rest-kern wird normal verworfen, keine sperre`() {
        assertFalse(MpvKernGrenze.exoVorVerwerfen(an = true, lebend = 0))
        assertFalse(MpvKernGrenze.mpvGesperrt(an = true, sperre = false, lebend = 1))
    }

    @Test fun `lebt ein rest-kern, geht ein weiterer haenger vorher auf exo`() {
        assertEquals(1, MpvKernGrenze.MAX_REST)
        assertTrue(MpvKernGrenze.exoVorVerwerfen(an = true, lebend = 1))
        assertTrue(MpvKernGrenze.mpvGesperrt(an = true, sperre = true, lebend = 1))
    }

    @Test fun `zehn haengende titel - nie mehr als ein weiterer kern neben dem rest, danach exo`() {
        val (gebaut, maxRest, exo) = lauf(an = true, n = 10)
        assertEquals("Kern 1 hängt → Rest; Kern 2 (Kopie/Software) hängt → Exo-Sperre; dann kein Kern mehr", 2, gebaut)
        assertTrue("Rest-Kerne $maxRest", maxRest <= MpvKernGrenze.MAX_REST + 1)
        assertEquals(8, exo)
    }

    @Test fun `schalter aus - altes verhalten ohne grenze`() {
        val (gebaut, maxRest, exo) = lauf(an = false, n = 5)
        assertEquals(5, gebaut)
        assertEquals(5, maxRest)
        assertEquals(0, exo)
    }

    @Test fun `sperre faellt, sobald der rest-kern antwortet und zerstoert ist`() {
        val frei = CountDownLatch(1)
        verwerfen(frei)
        assertTrue(MpvKernGrenze.mpvGesperrt(an = true, sperre = true, lebend = MpvRestKerne.anzahl()))
        frei.countDown()
        faeden.last().join(1_000)
        val lebend = MpvRestKerne.anzahl()
        assertEquals(0, lebend)
        assertTrue(MpvKernGrenze.sperreLoesen(sperre = true, lebend = lebend))
        assertFalse(MpvKernGrenze.mpvGesperrt(an = true, sperre = true, lebend = lebend))
        assertTrue(frei.await(0, TimeUnit.MILLISECONDS))
    }
}
