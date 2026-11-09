package tv.own.owntv.core.sync

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

/** German4K 3.2.1 (p-17, Schalter zahl_voll): Einrichtungsabschluss zeigt die volle Zahl („3.600 Filme“). */
class VollZahlTest {
    @Test fun deutsch_mit_tausenderpunkt() = assertEquals("3.600", vollZahl(Locale.GERMANY, 3_600))
    @Test fun englisch_mit_komma() = assertEquals("3,600", vollZahl(Locale.US, 3_600))
    @Test fun kleine_zahl_unveraendert() = assertEquals("999", vollZahl(Locale.GERMANY, 999))
    @Test fun grosse_zahl() = assertEquals("125.000", vollZahl(Locale.GERMANY, 125_000))
}
