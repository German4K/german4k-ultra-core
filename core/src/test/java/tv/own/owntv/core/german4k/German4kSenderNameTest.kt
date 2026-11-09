package tv.own.owntv.core.german4k

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class German4kSenderNameTest {
    private fun k(n: String) = German4kSenderName.schluessel(n)

    @Test
    fun qualitaetszusatzZaehltNicht() {
        assertEquals(k("SKY CINEMA ACTION HD"), k("SKY CINEMA ACTION FHD"))
        assertEquals(k("Sky Cinema Action ᴴᴰ"), k("SKY CINEMA ACTION 4K"))
        assertEquals("SKY CINEMA ACTION", k("SKY CINEMA ACTION HEVC"))
    }

    @Test
    fun laendervorsatzUndKlammernZaehlenNicht() {
        assertEquals(k("DE: ARD"), k("DE | ARD HD"))
        assertEquals(k("|DE| ZDF"), k("ZDF (Backup)"))
    }

    @Test
    fun verschiedeneSenderBleibenVerschieden() {
        assertNotEquals(k("SKY SPORT 1 HD"), k("SKY SPORT 2 HD"))
        assertNotEquals(k("RTL"), k("RTL - Plus"))
        assertEquals("RTL PLUS", k("RTL - Plus"))
    }

    @Test
    fun nurZusatzErgibtLeerenSchluessel() {
        assertEquals("", k("HD"))
    }
}
