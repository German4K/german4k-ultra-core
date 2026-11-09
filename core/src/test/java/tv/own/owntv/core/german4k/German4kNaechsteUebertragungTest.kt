package tv.own.owntv.core.german4k

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** German4K 3.3: „Läuft gerade nicht? Nächste Übertragung“ — Wahl der nächsten Fassung. */
class German4kNaechsteUebertragungTest {
    private fun s(name: String, vararg ids: Long, an: Boolean? = true, platz: Boolean = false) =
        German4kSportSender(name, null, ids.toList(), an, null, null, platz, null)

    private val de1 = s("🇩🇪 Soccer PPV 1", 11, platz = true)
    private val en1 = s("🇬🇧 Sky Sports", 21)
    private val de2 = s("🇩🇪 DAZN 1", 12, 13)
    private val es1 = s("🇪🇸 DAZN ES", 31)
    private val deAus = s("🇦🇹 Sky AT", 14, an = false)
    private val deLeer = s("🇩🇪 Sport1")
    private val deUnbekannt = s("🇨🇭 Blue Sport", 15, an = null)

    @Test
    fun `gleiche Sprache zuerst, dann die anderen in Server-Reihenfolge`() {
        val sender = listOf(de1, en1, de2, es1, deAus, deLeer, deUnbekannt)
        assertEquals(
            listOf(de2, deUnbekannt, en1, es1),
            German4kNaechsteUebertragung.reihenfolge(sender, de1),
        )
    }

    @Test
    fun `naechste ueberspringt versuchte und ist am Ende null`() {
        val sender = listOf(de1, en1, de2)
        val k = German4kNaechsteUebertragung::schluessel
        assertEquals(de2, German4kNaechsteUebertragung.naechste(sender, de1, setOf(k(de1))))
        assertEquals(en1, German4kNaechsteUebertragung.naechste(sender, de2, setOf(k(de1), k(de2))))
        assertNull(German4kNaechsteUebertragung.naechste(sender, en1, setOf(k(de1), k(de2), k(en1))))
    }

    @Test
    fun `ohne erkennbare Sprache Server-Reihenfolge`() {
        val ohne = s("Event 7", 7, platz = true)
        assertEquals(listOf(de1, en1), German4kNaechsteUebertragung.reihenfolge(listOf(ohne, de1, en1), ohne))
    }

    @Test
    fun `Fassung zur Anbieter-Kennung`() {
        val spiel = German4kSpiel(1, "A – B", "A", "B", null, Instant.EPOCH, "", "laeuft", 0.0, "spiel", listOf(de1, de2), false, null, null)
        assertEquals(de2, German4kNaechsteUebertragung.fassungZu(spiel, "13"))
        assertNull(German4kNaechsteUebertragung.fassungZu(spiel, "99"))
        assertNull(German4kNaechsteUebertragung.fassungZu(spiel, null))
    }

    @Test
    fun `Meldung und Wechselgruende`() {
        assertEquals("4711|99|auto", German4kNaechsteUebertragung.meldungDetail("4711", 99, German4kNaechsteUebertragung.ART_AUTO))
        assertTrue(German4kNaechsteUebertragung.darfWechseln(German4kHealth.Klasse.UNBEKANNT))
        assertFalse(German4kNaechsteUebertragung.darfWechseln(German4kHealth.Klasse.LEITUNG_BELEGT))
        assertFalse(German4kNaechsteUebertragung.darfWechseln(German4kHealth.Klasse.KEIN_NETZ))
        assertFalse(German4kNaechsteUebertragung.darfWechseln(German4kHealth.Klasse.ABGELAUFEN))
    }
}
