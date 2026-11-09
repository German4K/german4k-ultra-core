package tv.own.owntv.core.german4k

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.core.database.entity.ChannelEntity

class German4kEventFrischTest {
    private val e = German4kEventFrisch

    private fun ch(id: Long, rid: String, name: String, logo: String? = null, epg: String? = null) =
        ChannelEntity(id = id, sourceId = 1, name = name, streamUrl = "u$id", remoteId = rid, logoUrl = logo, epgChannelId = epg)

    @Test fun erkenntEventKategorien() {
        assertTrue(e.istEventKategorie("🇩🇪 DAZN PPV"))
        assertTrue(e.istEventKategorie("🇺🇸 PPV Events"))
        assertTrue(e.istEventKategorie("IT: Eventi"))
        assertTrue(e.istEventKategorie("Live Events"))
        assertTrue(e.istEventKategorie("UK | EVENT"))
        assertFalse(e.istEventKategorie("Eventino Kids"))
        assertFalse(e.istEventKategorie("🇩🇪 Sport"))
        assertFalse(e.istEventKategorie("Happv"))
        assertFalse(e.istEventKategorie(null))
    }

    @Test fun fristSechzigSekunden() {
        assertTrue(e.faellig(null, 0L))
        assertFalse(e.faellig(1_000L, 60_999L))
        assertTrue(e.faellig(1_000L, 61_000L))
        e.vergessenFuerTests()
        assertTrue(e.anmelden(7L, 100L))
        assertFalse(e.anmelden(7L, 200L))
        assertTrue(e.anmelden(8L, 200L))
        assertTrue(e.anmelden(7L, 100L + e.FRIST_MS))
    }

    @Test fun abgleichNurGeaenderteVorhandeneZeilen() {
        val alt = listOf(ch(1, "a", "DAZN PPV 03 · Volkanovski", "l1"), ch(2, "b", "DAZN PPV 04"), ch(3, "c", "PPV 05", epg = "x"))
        val frisch = listOf(
            German4kEventFrisch.Frisch("a", "DAZN PPV 03 · Napoli – Roma", null, null),
            German4kEventFrisch.Frisch("b", "DAZN PPV 04", null, null),
            German4kEventFrisch.Frisch("c", "  ", null, "y"),
            German4kEventFrisch.Frisch("neu", "PPV 99", null, null),
        )
        val g = e.abgleich(alt, frisch)
        assertEquals(listOf(1L, 3L), g.map { it.id })
        assertEquals("DAZN PPV 03 · Napoli – Roma", g[0].name)
        assertEquals("l1", g[0].logoUrl) // fehlendes Logo lässt das alte stehen
        assertEquals("PPV 05", g[1].name) // leerer Name wird nicht übernommen
        assertEquals("y", g[1].epgChannelId)
    }

    @Test fun fehlerBleibtStill() = runBlocking {
        var gespeichert = false
        val g = e.aktualisieren(laden = { throw java.io.IOException("x") }, vorhandene = { emptyList() }, speichern = { gespeichert = true })
        assertTrue(g.isEmpty())
        assertFalse(gespeichert)
        val g2 = e.aktualisieren(laden = { null }, vorhandene = { emptyList() }, speichern = { gespeichert = true })
        assertTrue(g2.isEmpty())
        assertFalse(gespeichert)
    }

    @Test fun speichertGeaenderte() = runBlocking {
        var gespeichert: List<ChannelEntity> = emptyList()
        val g = e.aktualisieren(
            laden = { listOf(German4kEventFrisch.Frisch("a", "Neu", null, null)) },
            vorhandene = { ids -> if (ids == listOf("a")) listOf(ch(1, "a", "Alt")) else emptyList() },
            speichern = { gespeichert = it },
        )
        assertEquals(1, g.size)
        assertEquals("Neu", gespeichert.single().name)
    }
}
