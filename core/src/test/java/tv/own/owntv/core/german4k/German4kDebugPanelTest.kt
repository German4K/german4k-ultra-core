package tv.own.owntv.core.german4k

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Nur Debug-Builds dürfen das Panel auf einen lokalen Server umlenken (`adb shell setprop debug.g4k.panel …`). */
class German4kDebugPanelTest {
    @Test fun `release ignores the property`() = assertNull(German4kPanelClient.debugBasis("http://10.0.2.2:3000", debug = false))
    @Test fun `debug uses it without trailing slash`() = assertEquals("http://10.0.2.2:3000", German4kPanelClient.debugBasis(" http://10.0.2.2:3000/ ", debug = true))
    @Test fun `empty or garbage is ignored`() {
        assertNull(German4kPanelClient.debugBasis("", debug = true))
        assertNull(German4kPanelClient.debugBasis("10.0.2.2", debug = true))
        assertNull(German4kPanelClient.debugBasis(null, debug = true))
    }
}
