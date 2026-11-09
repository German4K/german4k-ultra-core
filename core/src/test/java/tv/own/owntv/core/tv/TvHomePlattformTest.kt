package tv.own.owntv.core.tv

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** German4K 3.2.1 (Q3): TV-Startbildschirm nur mit Leanback UND TV-Provider. */
class TvHomePlattformTest {
    @Test fun `Handy ohne Leanback - nie`() = assertFalse(TvHomePlattform.entscheide(leanback = false, tvProvider = false))
    @Test fun `Leanback ohne Provider - nie`() = assertFalse(TvHomePlattform.entscheide(leanback = true, tvProvider = false))
    @Test fun `Provider ohne Leanback - nie`() = assertFalse(TvHomePlattform.entscheide(leanback = false, tvProvider = true))
    @Test fun `Fernseher - ja`() = assertTrue(TvHomePlattform.entscheide(leanback = true, tvProvider = true))
}
