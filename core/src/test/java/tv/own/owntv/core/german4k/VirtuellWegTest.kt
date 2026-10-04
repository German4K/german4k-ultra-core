package tv.own.owntv.core.german4k

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Weg B nur, wenn alles stimmt; sonst spielt der Sender normal (Weg A). Review Focus 5. */
class VirtuellWegTest {
    private val server = VirtuellTestDaten.SERVER.toEpochMilli()
    private val antwort = German4kVirtuellAntwort.parse(VirtuellTestDaten.VERTRAG)!!
    private val ok = German4kVirtuellRepository.Ergebnis.Ok(antwort, VirtuellUhr(0))

    @Test fun `feature off means normal channel`() = assertFalse(VirtuellWeg.darf(istVirtuell = true, featureSichtbar = false, gesperrtBis = 0, jetzt = server))
    @Test fun `ordinary channel never`() = assertFalse(VirtuellWeg.darf(false, true, 0, server))
    @Test fun `blocked channel waits`() = assertFalse(VirtuellWeg.darf(true, true, server + 1, server))
    @Test fun `virtual channel with feature on`() = assertTrue(VirtuellWeg.darf(true, true, server - 1, server))

    @Test
    fun `plan answer plays at the position`() {
        val a = VirtuellWeg.ausgang(ok, 13750001, server)
        assertEquals(710_000L, (a as VirtuellWeg.Ausgang.Spielen).stand.positionMs)
    }

    @Test fun `no answer is normal channel`() = assertEquals(VirtuellWeg.Ausgang.WegA, VirtuellWeg.ausgang(null, 13750001, server))
    @Test fun `transport error is normal channel`() =
        assertEquals(VirtuellWeg.Ausgang.WegA, VirtuellWeg.ausgang(German4kVirtuellRepository.Ergebnis.Transport, 13750001, server))

    @Test fun `server stage off - empty sender list - normal channel`() {
        val leer = German4kVirtuellRepository.Ergebnis.Ok(antwort.copy(stufe = "aus", sender = emptyList()), VirtuellUhr(0))
        assertEquals(VirtuellWeg.Ausgang.WegA, VirtuellWeg.ausgang(leer, 13750001, server))
    }

    @Test fun `other channel in the answer - normal channel`() = assertEquals(VirtuellWeg.Ausgang.WegA, VirtuellWeg.ausgang(ok, 999, server))
    @Test fun `clock outside plan - normal channel`() = assertEquals(VirtuellWeg.Ausgang.WegA, VirtuellWeg.ausgang(ok, 13750001, server + 6 * 3_600_000))

    @Test fun `refusal carries the reason`() =
        assertEquals(VirtuellWeg.Ausgang.Hinweis("Gesperrt."), VirtuellWeg.ausgang(German4kVirtuellRepository.Ergebnis.Abgelehnt("Gesperrt."), 1, server))

    @Test fun `blank refusal is just the normal channel`() =
        assertEquals(VirtuellWeg.Ausgang.WegA, VirtuellWeg.ausgang(German4kVirtuellRepository.Ergebnis.Abgelehnt(""), 1, server))
}
