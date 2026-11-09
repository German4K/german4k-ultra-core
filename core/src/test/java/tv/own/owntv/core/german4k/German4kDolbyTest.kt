package tv.own.owntv.core.german4k

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class German4kDolbyTest {

    @Before
    fun setUp() = German4kDolby.zuruecksetzen()

    @Test
    fun `die Rubrik mit Dolby Vision wird erkannt`() {
        assertTrue(German4kDolby.istDolbyVision("4K Dolby Vision", "Irgendeine Serie"))
        assertTrue(German4kDolby.istDolbyVision(null, "Beispielfilm (DV)"))
        assertTrue(German4kDolby.istDolbyVision("Filme DV", null))
    }

    @Test
    fun `gewoehnliche Titel loesen nichts aus`() {
        assertFalse(German4kDolby.istDolbyVision("Filme", "Beispielfilm"))
        // „dv" mitten im Wort ist kein Dolby Vision — sonst trifft es Namen wie „Advent".
        assertFalse(German4kDolby.istDolbyVision("Advent", "Dvorak"))
        assertFalse(German4kDolby.istDolbyVision(null, null))
    }

    @Test
    fun `ein Geraet mit Dolby Vision bekommt nie einen Hinweis`() {
        German4kDolby.pruefe("4K Dolby Vision", "Serie", geraetKannDolby = true, heute = "2026-09-19")
        assertNull(German4kDolby.hinweis.value)
    }

    @Test
    fun `ohne Dolby Vision erscheint der Hinweis mit dem Titel`() {
        German4kDolby.pruefe("4K Dolby Vision", "Beispielserie", geraetKannDolby = false, heute = "2026-09-19")
        assertEquals("Beispielserie", German4kDolby.hinweis.value)
    }

    @Test
    fun `der Hinweis kommt nur einmal am Tag, danach wieder`() {
        German4kDolby.pruefe("4K Dolby Vision", "Erste", geraetKannDolby = false, heute = "2026-09-19")
        German4kDolby.schliessen()
        German4kDolby.pruefe("4K Dolby Vision", "Zweite", geraetKannDolby = false, heute = "2026-09-19")
        assertNull("am selben Tag nur einmal", German4kDolby.hinweis.value)

        German4kDolby.pruefe("4K Dolby Vision", "Dritte", geraetKannDolby = false, heute = "2026-09-20")
        assertEquals("Dritte", German4kDolby.hinweis.value)
    }

    @Test
    fun `ein Titel ohne Dolby Vision loest auch ohne DV-Geraet nichts aus`() {
        German4kDolby.pruefe("Filme", "Beispielfilm", geraetKannDolby = false, heute = "2026-09-19")
        assertNull(German4kDolby.hinweis.value)
    }
}
