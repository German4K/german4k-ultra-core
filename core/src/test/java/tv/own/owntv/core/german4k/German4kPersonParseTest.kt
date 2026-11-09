package tv.own.owntv.core.german4k

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class German4kPersonParseTest {
    @Test fun liestNameUndKennungen() {
        val a = German4kPersonAntwort.parse("""{"person_id":287,"name":"Brad Pitt","movies":[{"stream_id":100000032,"name":"x"},{"stream_id":"100000064"}],"series":[{"series_id":100000096}]}""")!!
        assertEquals("Brad Pitt", a.name)
        assertEquals(listOf("100000032", "100000064"), a.filmIds)
        assertEquals(listOf("100000096"), a.serienIds)
    }
    @Test fun leerOderKaputt() {
        assertEquals(0, German4kPersonAntwort.parse("""{"person_id":0,"name":"","movies":[],"series":[]}""")!!.filmIds.size)
        assertNull(German4kPersonAntwort.parse("nein"))
    }
}
