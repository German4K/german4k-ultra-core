package tv.own.owntv.core.german4k

import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** German4K 3.2.1 (U4, Review): die Kopplungs-Abfrage sendet "still": true, der normale Abruf nicht. */
class German4kPanelAnfrageTest {
    private val client = German4kPanelClient(OkHttpClient())

    @Test fun still_nur_bei_kopplungsabfrage() {
        val still = client.anfrage("dev", "3.2.1", 40, null, null, still = true)
        assertTrue(still.getBoolean("still"))
        assertEquals(40, still.getInt("build"))
        val normal = client.anfrage("dev", "3.2.1", 40, "u", "p", still = false)
        assertFalse(normal.has("still"))
        assertEquals("u", normal.getString("username"))
    }
}
