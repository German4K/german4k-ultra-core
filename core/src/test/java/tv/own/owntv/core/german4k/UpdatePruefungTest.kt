package tv.own.owntv.core.german4k

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** German4K 3.2.1 (u-14): Urteil und Build kommen aus EINER Lesung von `current`. */
class UpdatePruefungTest {
    private fun build(code: Int) = German4kUpdate("stable", "3.2.$code", code, "https://x/a.apk", "", false)

    @Test
    fun `NEUER liefert den Build gleich mit - paralleles offer(null) kann ihn nicht mehr wegnehmen`() {
        German4kUpdateSource.vergessen()
        German4kUpdateSource.offer(build(41))
        val p = German4kUpdateSource.pruefe(40, stillAn = true)
        German4kUpdateSource.offer(null) // läuft zwischen Urteil und Anzeige
        assertEquals(German4kUpdateSource.Urteil.NEUER, p.urteil)
        assertNotNull(p.neuer)
        assertEquals(41, p.neuer!!.versionCode)
        assertNull(German4kUpdateSource.newerThan(40))
        German4kUpdateSource.vergessen()
    }

    @Test
    fun `AKTUELL und UNBEKANNT tragen keinen Build`() {
        German4kUpdateSource.vergessen()
        assertEquals(German4kUpdateSource.Pruefung(German4kUpdateSource.Urteil.UNBEKANNT, null), German4kUpdateSource.pruefe(40, true))
        German4kUpdateSource.offer(build(40))
        assertEquals(German4kUpdateSource.Pruefung(German4kUpdateSource.Urteil.AKTUELL, null), German4kUpdateSource.pruefe(40, true))
        German4kUpdateSource.vergessen()
    }
}
