package tv.own.owntv.core.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** German4K 3.2.1 (U3): Sortierung „Erscheinungsjahr" darf keine Zahl aus dem Titel für ein Jahr halten. */
class German4kTitelJahrTest {

    private fun jahr(raw: String, providerYear: Int? = null) = ProviderCatalogMetadataParser.parse(raw, providerYear).parsedYear

    @Test
    fun zahlImTitelIstKeinJahr() {
        assertNull(jahr("Blade Runner 2049"))
        assertNull(jahr("DE - Blade Runner 2049"))
        assertNull(jahr("2001: Odyssee im Weltraum"))
        assertNull(jahr("1917"))
        assertNull(jahr("Wonder Woman 1984"))
    }

    @Test
    fun jahrInKlammernZaehlt() {
        assertEquals(2017, jahr("Film (2017)"))
        assertEquals(2017, jahr("DE - Blade Runner 2049 (2017)"))
        assertEquals(2019, jahr("1917 (2019)"))
        assertEquals(1968, jahr("2001: Odyssee im Weltraum (1968)"))
        assertEquals(2021, jahr("EN| The Movie Name [2021] HD"))
    }

    @Test
    fun jahrAmEndeHinterTrennerZaehlt() {
        assertEquals(2017, jahr("Film - 2017"))
        assertEquals(2017, jahr("Film | 2017"))
    }

    @Test
    fun unplausiblesJahrZaehltNicht() {
        assertNull(German4kTitelJahr.ausTitel("Film (2049)", maxJahr = 2027))
        assertNull(German4kTitelJahr.ausTitel("Film (1850)", maxJahr = 2027))
        assertEquals(2027, German4kTitelJahr.ausTitel("Film (2027)", maxJahr = 2027))
    }

    @Test
    fun anbieterJahrGewinntImmer() {
        assertEquals(2017, jahr("Blade Runner 2049", providerYear = 2017))
        assertEquals(2017, jahr("Film (1999)", providerYear = 2017))
    }
}
