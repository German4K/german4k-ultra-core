package tv.own.owntv.core.german4k

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.core.german4k.German4kVariants.Stufe
import tv.own.owntv.core.german4k.German4kVariants.Variante

/**
 * Die Ausweich-Regeln. Kunstnamen statt echter Sender — die Regel ist die Namensform, nicht der
 * Sender, und ein Test, der echte Namen führt, wandert früher oder später in einen Screenshot.
 */
class German4kVariantsTest {

    private fun v(id: Long, name: String) = Variante(id, name, German4kVariants.stufe(name))

    private val familie = listOf(
        v(1, "Beispiel Eins UHD"),
        v(2, "Beispiel Eins FHD"),
        v(3, "Beispiel Eins HD"),
        v(4, "Beispiel Eins SD"),
        v(5, "Beispiel Zwei FHD"),
    )

    @Test
    fun `Kuerzel wird erkannt, auch in Kleinschreibung`() {
        assertEquals(Stufe.UHD, German4kVariants.stufe("Beispiel uhd"))
        assertEquals(Stufe.UHD, German4kVariants.stufe("Beispiel 4K"))
        assertEquals(Stufe.FHD, German4kVariants.stufe("Beispiel FHD"))
        assertEquals(Stufe.HD, German4kVariants.stufe("Beispiel HD"))
        assertEquals(Stufe.SD, German4kVariants.stufe("Beispiel SD"))
        assertEquals(Stufe.UNBEKANNT, German4kVariants.stufe("Beispiel ohne Angabe"))
    }

    @Test
    fun `Basis ist der Name ohne Kuerzel`() {
        assertEquals("BEISPIEL EINS", German4kVariants.basis("Beispiel Eins FHD"))
        assertEquals("BEISPIEL EINS", German4kVariants.basis("Beispiel Eins HD"))
        // Punkte und Ziffern im Namen dürfen nicht verloren gehen.
        assertEquals("KANAL 7.1", German4kVariants.basis("Kanal 7.1 SD"))
    }

    @Test
    fun `gleiche Familie erkennt Geschwister und trennt Fremde`() {
        assertTrue(German4kVariants.gleicheFamilie("Beispiel Eins FHD", "Beispiel Eins HD"))
        assertTrue(!German4kVariants.gleicheFamilie("Beispiel Eins FHD", "Beispiel Zwei FHD"))
    }

    @Test
    fun `Ausweich zaehlt abwaerts und laesst die laufende Fassung weg`() {
        val a = German4kVariants.ausweich("Beispiel Eins FHD", familie, vierKMoeglich = true)
        assertEquals(listOf("Beispiel Eins UHD", "Beispiel Eins HD", "Beispiel Eins SD"), a.map { it.name })
    }

    @Test
    fun `ohne 4K-Decoder faellt UHD ganz weg`() {
        val a = German4kVariants.ausweich("Beispiel Eins FHD", familie, vierKMoeglich = false)
        assertEquals(listOf("Beispiel Eins HD", "Beispiel Eins SD"), a.map { it.name })
    }

    @Test
    fun `ein anderes Programm ist nie ein Ausweich`() {
        val a = German4kVariants.ausweich("Beispiel Zwei FHD", familie, vierKMoeglich = true)
        assertTrue(a.isEmpty())
    }

    @Test
    fun `Dubletten derselben Stufe erscheinen nur einmal`() {
        val mitDublette = familie + v(6, "Beispiel Eins HD")
        val a = German4kVariants.ausweich("Beispiel Eins FHD", mitDublette, vierKMoeglich = false)
        assertEquals(listOf("Beispiel Eins HD", "Beispiel Eins SD"), a.map { it.name })
    }

    @Test
    fun `die laufende Fassung kommt nie zurueck, auch nicht als Dublette`() {
        val a = German4kVariants.ausweich("Beispiel Eins HD", familie + v(7, "BEISPIEL EINS HD"), vierKMoeglich = true)
        assertTrue(a.none { German4kVariants.stufe(it.name) == Stufe.HD })
    }

    @Test
    fun `ein UHD-Sender wird auf einem Geraet ohne 4K gar nicht erst gestartet`() {
        val statt = German4kVariants.besserGleichSo("Beispiel Eins UHD", familie, vierKMoeglich = false)
        assertEquals("Beispiel Eins FHD", statt?.name)
    }

    @Test
    fun `mit 4K-Decoder bleibt UHD unangetastet`() {
        assertNull(German4kVariants.besserGleichSo("Beispiel Eins UHD", familie, vierKMoeglich = true))
    }

    @Test
    fun `ein Sender ohne Kuerzel wird nicht umgeleitet`() {
        assertNull(German4kVariants.besserGleichSo("Beispiel ohne Angabe", familie, vierKMoeglich = false))
        assertTrue(German4kVariants.ausweich("Beispiel ohne Angabe", familie, vierKMoeglich = true).isEmpty())
    }

    @Test
    fun `steht nur UHD zur Verfuegung, gibt es ohne 4K keinen Ausweich`() {
        val nurUhd = listOf(v(1, "Beispiel Drei UHD"))
        assertTrue(German4kVariants.ausweich("Beispiel Drei UHD", nurUhd, vierKMoeglich = false).isEmpty())
        assertNull(German4kVariants.besserGleichSo("Beispiel Drei UHD", nurUhd, vierKMoeglich = false))
    }
}
