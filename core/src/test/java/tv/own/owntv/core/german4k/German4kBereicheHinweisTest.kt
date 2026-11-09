package tv.own.owntv.core.german4k

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** German4K 3.2.1 (U48): kein doppelter (halb deutscher) Hinweis in „Länder & Bereiche" ohne Zugang. */
class German4kBereicheHinweisTest {
    private val server = "Dieses Gerät ist noch nicht mit einem Zugang verbunden."

    @Test fun `ohne Zugang und leere Liste - nur der App-Hinweis`() {
        assertNull(German4kBereicheHinweis.serverGrund(server, listeLeer = true, an = true))
    }

    @Test fun `andere Gruende bleiben stehen`() {
        val netz = "Das hat gerade nicht geklappt. Deine Liste ist unverändert."
        assertEquals(netz, German4kBereicheHinweis.serverGrund(netz, listeLeer = true, an = true))
    }

    @Test fun `mit Liste oder Schalter aus - Server-Grund wie bisher`() {
        assertEquals(server, German4kBereicheHinweis.serverGrund(server, listeLeer = false, an = true))
        assertEquals(server, German4kBereicheHinweis.serverGrund(server, listeLeer = true, an = false))
        assertNull(German4kBereicheHinweis.serverGrund("  ", listeLeer = true, an = true))
    }
}
