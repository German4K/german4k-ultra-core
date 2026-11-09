package tv.own.owntv.player

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** German4K 3.2.1 (AS): was die Engines bekommen — Schalter an/aus. */
class AudioSprachWahlTest {
    @Test fun mpvAn() {
        assertEquals("de,deu,ger", AudioSprachWahl.mpvAlang("de", an = true))
        assertEquals("en,eng", AudioSprachWahl.mpvAlang("eng", an = true))
        assertEquals("", AudioSprachWahl.mpvAlang("", an = true))
    }

    @Test fun mpvAusWieFrueher() = assertEquals("de", AudioSprachWahl.mpvAlang("de", an = false))

    @Test fun exo() {
        assertArrayEquals(arrayOf("de", "deu", "ger"), AudioSprachWahl.exoSprachen("de", an = true))
        assertArrayEquals(arrayOf("de"), AudioSprachWahl.exoSprachen("de", an = false))
        assertArrayEquals(emptyArray<String>(), AudioSprachWahl.exoSprachen("", an = true))
    }

    @Test fun titelTreffer() {
        val spuren = listOf(
            TrackOption(label = "English", mpvId = 1, selected = true),
            TrackOption(label = "Deutsch", mpvId = 2, selected = false),
        )
        assertEquals(2, AudioSprachWahl.titelTreffer(spuren, "de", an = true)?.mpvId)
        assertNull(AudioSprachWahl.titelTreffer(spuren, "de", an = false))
        assertNull(AudioSprachWahl.titelTreffer(spuren.map { it.copy(lang = if (it.mpvId == 1) "eng" else "ger") }, "de", an = true))
    }
}
