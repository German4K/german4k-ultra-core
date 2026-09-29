package tv.own.owntv.core.german4k

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** German4K 3.0/32 (I2, L2): Titel ohne Vorsatz, Bündeln der Fassungen, Kategorienamen. */
class German4kTitelTest {

    @Test
    fun `prefix is stripped and becomes the badge`() {
        assertEquals(German4kTitelAnzeige("Blade Runner 2049", "DE"), German4kTitel.anzeige("DE - Blade Runner 2049"))
        assertEquals(German4kTitelAnzeige("Blade Runner 2049", "EN"), German4kTitel.anzeige("EN - Blade Runner 2049"))
        assertEquals(German4kTitelAnzeige("Death Race 2050", "NF"), German4kTitel.anzeige("NF - Death Race 2050"))
        assertEquals(German4kTitelAnzeige("Dune", "4K"), German4kTitel.anzeige("4K-AR - Dune"))
        assertEquals(German4kTitelAnzeige("Dune", "4K"), German4kTitel.anzeige("4K-A+ - Dune"))
        assertEquals(German4kTitelAnzeige("Sky Doku", "SC"), German4kTitel.anzeige("SC - Sky Doku"))
    }

    @Test
    fun `titles that only look like a prefix stay whole`() {
        assertNull(German4kTitel.anzeige("M - Eine Stadt sucht einen Mörder").kuerzel)
        assertNull(German4kTitel.anzeige("24 - Twenty Four").kuerzel)
        assertNull(German4kTitel.anzeige("Blade Runner").kuerzel)
        assertNull(German4kTitel.anzeige("Spider-Man - No Way Home").kuerzel)
        assertEquals("DE -", German4kTitel.anzeige("DE -").titel)
    }

    @Test
    fun `normal form ignores prefix, case, spacing and a trailing year`() {
        assertEquals("blade runner 2049", German4kTitel.normalform("DE - Blade  Runner 2049"))
        assertEquals("blade runner 2049", German4kTitel.normalform("EN - blade runner 2049 (2017)"))
        assertEquals(2017, German4kTitel.jahrAusTitel("EN - Blade Runner 2049 (2017)"))
    }

    @Test
    fun `preferred version is German, then 4K, then the first`() {
        assertEquals(2L, German4kTitel.bevorzugt(listOf(1L to "EN - X", 2L to "DE - X", 3L to "4K-AR - X")))
        assertEquals(3L, German4kTitel.bevorzugt(listOf(1L to "EN - X", 2L to "NF - X", 3L to "4K-AR - X")))
        assertEquals(1L, German4kTitel.bevorzugt(listOf(1L to "EN - X", 2L to "NF - X")))
        assertNull(German4kTitel.bevorzugt(emptyList()))
    }

    @Test
    fun `duplicates collapse onto the first loaded entry and count`() {
        val g = German4kFassungsGruppen()
        assertTrue(g.annehmen(10, "DE - Blade Runner 2049", 2017))
        assertFalse(g.annehmen(11, "EN - Blade Runner 2049", 2017))
        assertFalse(g.annehmen(12, "4K-AR - Blade Runner 2049", null))
        assertTrue(g.annehmen(20, "NF - Death Race 2050", null))
        assertEquals(mapOf(10L to 3), g.anzahl.value)
        // A reloaded page keeps every row in its role.
        assertTrue(g.annehmen(10, "DE - Blade Runner 2049", 2017))
        assertFalse(g.annehmen(11, "EN - Blade Runner 2049", 2017))
        assertEquals(10L, g.bevorzugt(10))
    }

    @Test
    fun `different known years stay apart`() {
        val g = German4kFassungsGruppen()
        assertTrue(g.annehmen(1, "DE - Dune", 1984))
        assertTrue(g.annehmen(2, "DE - Dune", 2021))
        assertFalse(g.annehmen(3, "EN - Dune (2021)", null))
        assertEquals(mapOf(2L to 2), g.anzahl.value)
        assertEquals(1L, g.bevorzugt(1))
    }

    @Test
    fun `preferred picks German among collapsed versions`() {
        val g = German4kFassungsGruppen()
        assertTrue(g.annehmen(1, "EN - Heat", 1995))
        assertFalse(g.annehmen(2, "DE - Heat", 1995))
        assertEquals(2L, g.bevorzugt(1))
    }

    @Test
    fun `category names lose leading decoration but keep the flag`() {
        assertEquals("Neu", German4kKategorie.anzeige("▶️ Neu"))
        assertEquals("Fußball", German4kKategorie.anzeige("⚽ Fußball"))
        assertEquals("🇩🇪 Kürzlich hinzugefügt", German4kKategorie.anzeige("🇩🇪 Kürzlich hinzugefügt"))
        assertEquals("🇩🇪 Bundesliga", German4kKategorie.anzeige("🇩🇪 ⚽ Bundesliga"))
        assertEquals("Sport", German4kKategorie.anzeige("⚽ | Sport"))
        assertEquals("Action", German4kKategorie.anzeige("Action"))
        assertEquals("⚽", German4kKategorie.anzeige("⚽"))
    }

    @Test
    fun `provider all-folders are recognised`() {
        assertTrue(German4kKategorie.istAlleOrdner("🇩🇪 Alle Filme"))
        assertTrue(German4kKategorie.istAlleOrdner("Alle Serien"))
        assertTrue(German4kKategorie.istAlleOrdner("▶️ Alle  Sender"))
        assertFalse(German4kKategorie.istAlleOrdner("🇩🇪 Alle Filme 2026"))
        assertFalse(German4kKategorie.istAlleOrdner("Action"))
    }
}
