package tv.own.owntv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.player.LiveHaenger.Belegt

/** German4K 3.2.1 (p-17): Live-Hänger auf 1-Verbindungs-Zugängen (haenger_sauber, live_zeitsprung). */
class LiveHaengerTest {
    private val belegt: (Int) -> Boolean = { it == 458 || it == 460 }

    private fun nach(an: Boolean = true, status: Int? = 458, gespielt: Boolean = true, schon: Boolean = false) =
        LiveHaenger.nachBelegt(an, status, belegt, gespielt, schon)

    @Test fun `458 auf Neuversuch - genau eine Wiederholung`() {
        assertEquals(Belegt.WIEDERHOLEN, nach())
        assertEquals(Belegt.WIEDERHOLEN, nach(status = 460))
    }

    @Test fun `zweites 458 - aufgeben statt Leiter`() = assertEquals(Belegt.AUFGEBEN, nach(schon = true))

    @Test fun `anderer Fehler - alte Leiter`() {
        assertEquals(Belegt.LEITER, nach(status = 500))
        assertEquals(Belegt.LEITER, nach(status = null))
    }

    @Test fun `vor dem ersten Bild - nicht zustaendig (Sitzungsfreigabe beim Start)`() =
        assertEquals(Belegt.LEITER, nach(gespielt = false))

    @Test fun `Schalter aus - altes Verhalten`() {
        assertEquals(Belegt.LEITER, nach(an = false))
        assertEquals(Belegt.LEITER, nach(an = false, schon = true))
        assertFalse(LiveHaenger.vorherSchliessen(false))
        assertTrue(LiveHaenger.vorherSchliessen(true))
    }

    @Test fun `Waechter wartet die eine Wiederholung ab`() {
        // Wiederholung bei 13,5 s geplant, Wächter-Frist endet bei 15 s → Rest bis 13,5 + 4 + 8 = 25,5 s.
        assertEquals(10_500L, LiveHaenger.waechterNachfristMs(true, 13_500L, 15_000L))
        assertEquals(0L, LiveHaenger.waechterNachfristMs(true, 0L, 15_000L))
        assertEquals(0L, LiveHaenger.waechterNachfristMs(false, 13_500L, 15_000L))
        assertEquals(0L, LiveHaenger.waechterNachfristMs(true, 1_000L, 60_000L))
        // nie mehr als eine Wartezeit + Öffnen, auch bei verstellter Uhr
        assertEquals(LiveHaenger.LEITUNG_WARTE_MS + LiveHaenger.OEFFNEN_MS, LiveHaenger.waechterNachfristMs(true, 50_000L, 1_000L))
    }

    // German4K 3.2.1 (t-12): nur Rückwärtssprung, nur mit haenger_sauber.
    @Test fun `Zeitsprung - nur rueckwaerts ab 10 s`() {
        // k10: -113,664 s (Schleife der Datei)
        assertTrue(LiveHaenger.istZeitsprung(true, true, 1_000_120_039_999L, 1_000_006_375_999L))
        assertTrue(LiveHaenger.istZeitsprung(true, true, 10_000_000L, 0L))
        assertFalse(LiveHaenger.istZeitsprung(true, true, 10_000_000L, 10_000L)) // 9,99 s zurück
        // Vorwärtssprung (Werbeeinschnitt, Encoder-Neustart beim Restream): Media3 gleicht aus → kein Kandidat
        assertFalse(LiveHaenger.istZeitsprung(true, true, 0L, 10_000_000L))
        assertFalse(LiveHaenger.istZeitsprung(true, true, 1_000_006_375_999L, 1_000_120_039_999L))
        assertFalse(LiveHaenger.istZeitsprung(true, true, 0L, 250_000L)) // üblicher Kleinsprung
    }

    @Test fun `Zeitsprung - Schalter aus oder haenger_sauber aus - nie`() {
        assertFalse(LiveHaenger.istZeitsprung(false, true, 1_000_120_039_999L, 1_000_006_375_999L))
        assertFalse(LiveHaenger.istZeitsprung(true, false, 1_000_120_039_999L, 1_000_006_375_999L))
    }

    // German4K 3.2.1 (t-13): Entscheidung über gerenderte Frames, Position nur ohne Video (mit Rücksprung-Korrektur).
    private fun messe(posStart: Long, framesStart: Int?, vararg proben: Pair<Long, Int?>) =
        LiveHaenger.ZeitsprungFortschritt(posStart, framesStart).also { m -> proben.forEach { (pos, fr) -> m.probe(pos, fr) } }

    /** 12 Proben à 250 ms = 3 s; Position und Frames je Probe. */
    private fun lauf(posStart: Long, posSchritt: Long, framesStart: Int?, framesSchritt: Int, posKnick: Pair<Int, Long>? = null) =
        messe(posStart, framesStart, *(1..12).map { i ->
            val pos = posStart + i * posSchritt + (if (posKnick != null && i >= posKnick.first) posKnick.second else 0L)
            pos to framesStart?.let { it + i * framesSchritt }
        }.toTypedArray())

    @Test fun `Zeitsprung - Video laeuft weiter obwohl Media3 die Position zuruecksetzt - kein Haenger`() {
        // Position springt nach der 1. Probe um −7 s, Bild läuft (25 fps ≈ 6 Frames je 250 ms)
        val m = lauf(50_000L, 250L, framesStart = 1_000, framesSchritt = 6, posKnick = 1 to -7_000L)
        assertEquals(72, m.frames)
        assertTrue(m.posRueckMs <= -6_000L)
        assertFalse(LiveHaenger.haengtNachSprung(true, false, m))
    }

    @Test fun `Zeitsprung - Video steht - Haenger, auch wenn die Position laeuft oder gepuffert wird`() {
        val steht = lauf(50_000L, 0L, framesStart = 1_000, framesSchritt = 0)
        assertTrue(LiveHaenger.haengtNachSprung(true, true, steht))
        // Position läuft (Ton), aber keine neuen Bilder: < 10 Frames in 3 s
        val tonOhneBild = messe(50_000L, 1_000, *(1..12).map { (50_000L + it * 250L) to (1_000 + if (it == 12) 9 else 0) }.toTypedArray())
        assertEquals(9, tonOhneBild.frames)
        assertTrue(LiveHaenger.haengtNachSprung(true, false, tonOhneBild))
    }

    @Test fun `Zeitsprung - Restbilder nach dem Sprung, dann Stillstand - Haenger (Emulator t-13)`() {
        // gemessen: 15 Frames in den ersten ~0,6 s, danach BUFFERING ohne neues Bild
        val m = messe(44_022L, 1_000, 44_272L to 1_008, 44_522L to 1_015, 44_624L to 1_015,
            *(4..12).map { 44_624L to 1_015 }.toTypedArray())
        assertEquals(15, m.frames)
        assertEquals(0, m.framesSpaet)
        assertTrue(LiveHaenger.haengtNachSprung(true, true, m))
        assertTrue(LiveHaenger.haengtNachSprung(true, false, m))
    }

    @Test fun `Zeitsprung - neuer Decoder setzt den Zaehler zurueck - neue Frames zaehlen`() {
        val m = messe(50_000L, 1_000, 50_250L to 1_006, 50_500L to null, 50_750L to 5, 51_000L to 11)
        assertEquals(6 + 5 + 6, m.frames)
        assertFalse(LiveHaenger.haengtNachSprung(true, false, m))
        val kleiner = messe(50_000L, 1_000, 50_250L to 3, 50_500L to 8)
        assertEquals(8, kleiner.frames)
    }

    @Test fun `Zeitsprung - Radio ohne Video - Position mit Ruecksprung-Korrektur`() {
        val laeuft = lauf(50_000L, 250L, framesStart = null, framesSchritt = 0, posKnick = 2 to -7_000L)
        assertFalse(laeuft.hatVideo)
        assertEquals(2_750L, laeuft.fortschrittMs)
        assertFalse(LiveHaenger.haengtNachSprung(true, false, laeuft))
        val steht = lauf(50_000L, 0L, framesStart = null, framesSchritt = 0, posKnick = 2 to -7_000L)
        assertTrue(LiveHaenger.haengtNachSprung(true, false, steht))
        // puffert ohne Video = Hänger
        assertTrue(LiveHaenger.haengtNachSprung(true, true, laeuft))
        // Vorwärtssprung der Position zählt höchstens 1 s je Probe
        val vor = messe(50_000L, null, 80_000L to null, 80_000L to null)
        assertEquals(1_000L, vor.fortschrittMs)
    }

    @Test fun `Zeitsprung - pausiert ist nie ein Haenger`() {
        assertFalse(LiveHaenger.haengtNachSprung(false, true, lauf(50_000L, 0L, 1_000, 0)))
        assertFalse(LiveHaenger.haengtNachSprung(false, true, lauf(50_000L, 0L, null, 0)))
    }

    @Test fun `Zeitsprung - Budget 2 je 10 Minuten`() {
        val min = 60_000L
        assertTrue(LiveHaenger.zeitsprungBudgetFrei(emptyList(), 0L))
        assertTrue(LiveHaenger.zeitsprungBudgetFrei(listOf(1 * min), 2 * min))
        assertFalse(LiveHaenger.zeitsprungBudgetFrei(listOf(1 * min, 2 * min), 3 * min))
        // der erste fällt nach 10 Minuten aus dem Fenster
        assertTrue(LiveHaenger.zeitsprungBudgetFrei(listOf(1 * min, 2 * min), 11 * min))
        assertFalse(LiveHaenger.zeitsprungBudgetFrei(listOf(1 * min, 2 * min), 11 * min - 1))
    }

    @Test fun `healthyReset gibt die eine 458-Wiederholung zurueck`() {
        val t = TuneState()
        t.haengerLeitungWiederholt = true
        t.haengerLeitungSeitMs = 13_500L
        LiveHaenger.nachGesund(t)
        assertFalse(t.haengerLeitungWiederholt)
        assertEquals(0L, t.haengerLeitungSeitMs)
        // ein neuer 458-Hänger eine Stunde später: wieder genau eine Wiederholung
        assertEquals(Belegt.WIEDERHOLEN, nach(schon = t.haengerLeitungWiederholt))
    }
}
