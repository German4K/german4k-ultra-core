package tv.own.owntv.core.setup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.core.database.entity.ProfileEntity

/** German4K 3.2.1 (t-12): Übernahme eines angefangenen Assistenten-Profils. */
class G4kEinrichtenProfilTest {
    private val kind = ProfileEntity(id = 7, name = "Kinder", avatarColor = 0, avatarId = 3, isKids = true, pinHash = "h1234")

    @Test fun `gleicher Lauf - neue Eingabe gilt ganz`() {
        val neu = G4kEinrichtenProfil.uebernehmen(kind, gleicherLauf = true, name = "Papa", avatarId = 1, isKids = false, pinHash = null)
        assertEquals(7L, neu.id)
        assertEquals("Papa", neu.name)
        assertEquals(1, neu.avatarId)
        assertFalse(neu.isKids)
        assertNull(neu.pinHash)
    }

    @Test fun `Provisioner bzw fremder Lauf - Kinder-Kennzeichen und PIN bleiben`() {
        val neu = G4kEinrichtenProfil.uebernehmen(kind, gleicherLauf = false, name = "German4K", avatarId = 0, isKids = false, pinHash = null)
        assertEquals(7L, neu.id)
        assertEquals("German4K", neu.name)
        assertTrue(neu.isKids)
        assertEquals("h1234", neu.pinHash)
    }

    @Test fun `fremder Lauf - neue PIN und Kinder-Kennzeichen werden gesetzt`() {
        val leer = kind.copy(isKids = false, pinHash = null)
        val neu = G4kEinrichtenProfil.uebernehmen(leer, gleicherLauf = false, name = "X", avatarId = 2, isKids = true, pinHash = "h9")
        assertTrue(neu.isKids)
        assertEquals("h9", neu.pinHash)
    }
}
