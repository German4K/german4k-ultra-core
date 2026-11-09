package tv.own.owntv.core.german4k

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class German4kDatumTest {
    private val de = Locale.GERMANY

    @Test fun isoDatumWirdDeutsch() = assertEquals("27.10.2026", German4kDatum.anzeige("2026-10-27", de))

    @Test fun mitUhrzeit() = assertEquals("01.03.2027", German4kDatum.anzeige("2027-03-01 23:59:59", de))

    @Test fun isoMitT() = assertEquals("01.03.2027", German4kDatum.anzeige("2027-03-01T10:00:00Z", de))

    @Test fun englisch() = assertEquals("Oct 27, 2026", German4kDatum.anzeige("2026-10-27", Locale.US))

    @Test fun unlesbarBleibt() {
        assertEquals("unbegrenzt", German4kDatum.anzeige("unbegrenzt", de))
        assertEquals("27.10.2026", German4kDatum.anzeige("27.10.2026", de))
        assertEquals("2026-13-40", German4kDatum.anzeige("2026-13-40", de))
    }
}
