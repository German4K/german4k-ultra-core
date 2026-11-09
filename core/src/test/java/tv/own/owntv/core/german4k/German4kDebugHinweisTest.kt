package tv.own.owntv.core.german4k

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** German4K 3.3.1 (B5): `adb shell setprop debug.g4k.hinweis test` wirkt nur in Debug-Builds und nur mit bekanntem Typ. */
class German4kDebugHinweisTest {
    @Test fun `release ignoriert die Eigenschaft`() = assertNull(German4kDebugHinweis.typ("test", debug = false))

    @Test fun `debug nimmt bekannte Typen, auch mit Leerraum und Grossbuchstaben`() {
        assertEquals("test", German4kDebugHinweis.typ(" Test ", debug = true))
        assertEquals("verlaengern", German4kDebugHinweis.typ("verlaengern", debug = true))
        assertEquals("abgelaufen", German4kDebugHinweis.typ("abgelaufen", debug = true))
    }

    @Test fun `leer oder unbekannt ist aus`() {
        assertNull(German4kDebugHinweis.typ(null, debug = true))
        assertNull(German4kDebugHinweis.typ("", debug = true))
        assertNull(German4kDebugHinweis.typ("1", debug = true))
        assertNull(German4kDebugHinweis.typ("kaufen", debug = true))
    }
}
