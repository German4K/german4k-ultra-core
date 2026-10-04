package tv.own.owntv.core.german4k

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * German4K 3.1: alte Stände (Fauli/wohnzimmer79666, Rohrkolben053). `stand` = letzte Konfig- ODER
 * Laufzeitänderung der Line; fehlt das Feld (Server noch ohne), gilt `bereiche_stand`. Zusätzlich erkennt
 * die App einen Renew selbst: neues Ablaufdatum oder neues Passwort gegenüber der letzten Antwort.
 */
class German4kStandTest {
    private fun antwort(extra: String = "", expire: String = "2026-11-03", pw: String = "p1") = German4kPanelAnswer.parse(
        """{"mac":"aa","device_key":"1","mac_registered":true,"expire_date":"$expire","lock":0,
            "sources":[{"id":"k0","name":"German4K","server":"https://german4k.tv","username":"u","password":"$pw","is_default":true}]
            $extra}""",
    )

    @Test fun `stand is read`() = assertEquals("2026-10-02T21:49:23.130Z", antwort(""","stand":"2026-10-02T21:49:23.130Z"""").stand)
    @Test fun `stand null when missing`() = assertNull(antwort().stand)

    @Test fun `effective stand falls back to bereiche_stand`() {
        assertEquals("2026-10-01T00:00:00Z", antwort(""","bereiche_stand":"2026-10-01T00:00:00Z"""").standEffektiv)
        assertEquals("2026-10-02T00:00:00Z", antwort(""","bereiche_stand":"2026-10-01T00:00:00Z","stand":"2026-10-02T00:00:00Z"""").standEffektiv)
    }

    @Test fun `stand survives the cache round trip`() {
        val a = antwort(""","stand":"2026-10-02T00:00:00Z"""")
        assertEquals(a.stand, German4kPanelAnswer.parse(a.toJson()).stand)
    }

    @Test fun `renewal detected by new expiry`() =
        assertTrue(German4kPanelAnswer.laufzeitGeaendert(antwort(expire = "2026-10-02"), antwort(expire = "2026-12-03")))

    @Test fun `renewal detected by new password`() =
        assertTrue(German4kPanelAnswer.laufzeitGeaendert(antwort(pw = "alt"), antwort(pw = "neu")))

    @Test fun `same answer is no renewal`() = assertFalse(German4kPanelAnswer.laufzeitGeaendert(antwort(), antwort()))
    @Test fun `no previous answer is no renewal`() = assertFalse(German4kPanelAnswer.laufzeitGeaendert(null, antwort()))
}
