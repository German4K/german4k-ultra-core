package tv.own.owntv.core.german4k

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** German4K 3.1: „Uhr geht falsch" nur mit frischer Panel-Antwort — eine gespeicherte trägt eine alte server_time. */
class German4kUhrTest {
    private val t = Instant.parse("2026-10-04T09:00:00Z").toEpochMilli()

    @Test fun `fresh answer, device 6 min off`() = assertTrue(German4kUhr.falsch("2026-10-04T09:06:00Z", frisch = true, deviceNow = t))
    @Test fun `fresh answer, device 4 min off`() = assertFalse(German4kUhr.falsch("2026-10-04T09:04:00Z", frisch = true, deviceNow = t))
    @Test fun `cached answer never`() = assertFalse(German4kUhr.falsch("2026-10-03T22:00:00Z", frisch = false, deviceNow = t))
    @Test fun `no server time never`() = assertFalse(German4kUhr.falsch("", frisch = true, deviceNow = t))

    // German4K 3.2.1 (p-20): Antwort vom App-Start (09:00), Bewertung 27 min später nach dem 460-Dialog.
    private val spaeter = t + 27 * 60_000L
    @Test fun `old answer re-evaluated later is not a wrong clock`() =
        assertFalse(German4kUhr.falschBeiEmpfang("2026-10-04T09:00:00Z", frisch = true, empfangMs = t, uhrFrisch = true, jetztMs = spaeter))
    @Test fun `receive time still detects a wrong clock`() =
        assertTrue(German4kUhr.falschBeiEmpfang("2026-10-04T09:06:00Z", frisch = true, empfangMs = t, uhrFrisch = true, jetztMs = spaeter))
    @Test fun `no receive time no verdict`() =
        assertFalse(German4kUhr.falschBeiEmpfang("2026-10-04T09:06:00Z", frisch = true, empfangMs = 0L, uhrFrisch = true, jetztMs = t))
    @Test fun `switch off keeps old behaviour`() =
        assertTrue(German4kUhr.falschBeiEmpfang("2026-10-04T09:00:00Z", frisch = true, empfangMs = t, uhrFrisch = false, jetztMs = spaeter))
}
