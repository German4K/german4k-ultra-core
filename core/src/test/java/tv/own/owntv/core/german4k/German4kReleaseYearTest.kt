package tv.own.owntv.core.german4k

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tv.own.owntv.core.catalog.ReleaseYear
import java.util.Calendar

// German4K: Sortierung „Erscheinungsjahr" (Kundenwunsch Aleks959) — Jahr aus Anbieterfeld oder Titel.
class German4kReleaseYearTest {
    private val nextYear = Calendar.getInstance().get(Calendar.YEAR) + 1

    @Test
    fun trailingYearFromTitle() {
        assertEquals(2026, ReleaseYear.fromTitle("DE - Apex (2026)"))
        assertEquals(1999, ReleaseYear.fromTitle("DE - Matrix (1999) 4K"))
        assertEquals(2017, ReleaseYear.fromTitle("Blade Runner (2049) (2017)"))
    }

    @Test
    fun noOrImplausibleYear() {
        assertNull(ReleaseYear.fromTitle("DE - Apex"))
        assertNull(ReleaseYear.fromTitle("Film 2049"))
        assertNull(ReleaseYear.fromTitle("Film (1899)"))
        assertNull(ReleaseYear.fromTitle("Film (${nextYear + 1})"))
        assertNull(ReleaseYear.fromTitle("Film (20a4)"))
        assertNull(ReleaseYear.fromTitle("Film (2024"))
        assertNull(ReleaseYear.fromTitle("("))
        assertNull(ReleaseYear.fromTitle(""))
    }

    @Test
    fun providerYearWins() {
        assertEquals(2020, ReleaseYear.resolve(2020, "2019-01-01", "DE - X (2018)"))
        assertEquals(2019, ReleaseYear.resolve(null, "2019-01-01", "DE - X (2018)"))
        assertEquals(2018, ReleaseYear.resolve(0, "", "DE - X (2018)"))
        assertEquals(2018, ReleaseYear.resolve(null, "N/A", "DE - X (2018)"))
        assertEquals(nextYear, ReleaseYear.resolve(nextYear, null, "X"))
        assertNull(ReleaseYear.resolve(null, "201905", "X"))
        assertNull(ReleaseYear.resolve(null, null, "X"))
    }
}
