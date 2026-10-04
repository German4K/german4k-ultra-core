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
}
