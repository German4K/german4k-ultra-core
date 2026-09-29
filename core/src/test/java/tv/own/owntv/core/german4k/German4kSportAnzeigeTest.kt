package tv.own.owntv.core.german4k

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

/** German4K 3.0/32: Karten-Fußzeile, Senderzeilen, Spielminute, Tagesgruppen. */
class German4kSportAnzeigeTest {
    private fun sender(name: String, kategorie: String? = null, an: Boolean? = true, gruppe: String? = null) =
        German4kSportSender(name, kategorie, listOf(1L), an, gruppe, null, true, null)

    private fun spiel(
        sender: List<German4kSportSender> = emptyList(),
        rechte: German4kSportRechte? = null,
        bereich: German4kSportBereich? = null,
        start: String = "2026-10-01T18:45:00Z",
        id: Long = 1,
    ) = German4kSpiel(id, "t", "h", "g", null, Instant.parse(start), "", "geplant", 0.0, "spiel", sender, false, bereich, rechte)

    @Test
    fun `flag extraction`() {
        assertEquals("🇩🇪", fuehrendeFlagge("🇩🇪 DAZN PPV 12"))
        assertEquals("DE", flaggenLand("🇩🇪"))
        assertNull(fuehrendeFlagge("DAZN PPV 12"))
        assertNull(fuehrendeFlagge(""))
        assertNull(fuehrendeFlagge(null))
        assertEquals("DAZN PPV 12", ohneFlagge("🇩🇪 DAZN PPV 12"))
        assertEquals("DAZN", ohneFlagge("DAZN"))
    }

    @Test
    fun `language from flag`() {
        assertEquals(SportSprache.DEUTSCH, spracheVonFlagge("🇦🇹"))
        assertEquals(SportSprache.DEUTSCH, spracheVonFlagge("🇨🇭"))
        assertEquals(SportSprache.ENGLISCH, spracheVonFlagge("🇬🇧"))
        assertEquals(SportSprache.ENGLISCH, spracheVonFlagge("🇨🇦"))
        assertEquals(SportSprache.ENGLISCH, spracheVonFlagge("🇮🇪"))
        assertEquals(SportSprache.SPANISCH, spracheVonFlagge("🇪🇸"))
        assertEquals(SportSprache.FRANZOESISCH, spracheVonFlagge("🇫🇷"))
        assertEquals(SportSprache.ITALIENISCH, spracheVonFlagge("🇮🇹"))
        assertEquals(SportSprache.TUERKISCH, spracheVonFlagge("🇹🇷"))
        assertNull(spracheVonFlagge("🇳🇴"))
        assertNull(spracheVonFlagge(null))
    }

    @Test
    fun `brand from category or name`() {
        assertEquals("DAZN", senderMarke(sender("🇩🇪 DAZN PPV 12", "🇩🇪 DAZN")))
        assertEquals("Soccer", senderMarke(sender("🇩🇪 Soccer PPV 19", "🇩🇪 Soccer PPV")))
        assertEquals("Sky Sport", senderMarke(sender("🇩🇪 Sky Sport 1", "🇩🇪 Sky Sport")))
        assertEquals("Dazn", senderMarke(sender("🇪🇸 Dazn PPV 10", "🇪🇸 Dazn PPV")))
        assertEquals("DAZN", senderMarke(sender("🇩🇪 DAZN PPV 12")))
        assertEquals("Sky Sport", senderMarke(sender("🇩🇪 Sky Sport 2")))
        assertEquals("ServusTV", senderMarke(sender("ServusTV", "  ")))
    }

    @Test
    fun `sender row display`() {
        val a = senderAnzeige(sender("🇩🇪 DAZN PPV 12", "🇩🇪 DAZN"))
        assertEquals(SportSenderAnzeige("🇩🇪", "DAZN PPV 12", SportSprache.DEUTSCH, "DAZN"), a)
        val b = senderAnzeige(sender("Viaplay PPV 03", "🇸🇪 Viaplay PPV"))
        assertEquals("🇸🇪", b.flagge)
        assertNull(b.sprache)
        assertEquals("Viaplay PPV 03", b.titel)
    }

    @Test
    fun `card footer cascade`() {
        val s = spiel(
            sender = listOf(sender("🇩🇪 DAZN PPV 12", "🇩🇪 DAZN")) + List(10) { sender("🇳🇴 Tv2 Play PPV 0$it", "🇳🇴 Tv2 Play PPV", an = false) },
        )
        assertEquals(SportFusszeile.Sender("🇩🇪", SportSprache.DEUTSCH, "DAZN", 10), sportFusszeile(s))
        assertEquals(SportFusszeile.Sender("🇩🇪", SportSprache.DEUTSCH, "Sky Sport", 0), sportFusszeile(spiel(listOf(sender("🇩🇪 Sky Sport 1", "🇩🇪 Sky Sport")))))
        assertEquals(SportFusszeile.Rechte("Sky"), sportFusszeile(spiel(rechte = German4kSportRechte("Sky", null), bereich = German4kSportBereich("DE", null))))
        assertEquals(SportFusszeile.Bereich("Deutschland"), sportFusszeile(spiel(bereich = German4kSportBereich("Deutschland", null))))
        assertEquals(SportFusszeile.Keine, sportFusszeile(spiel()))
    }

    @Test
    fun `match minute`() {
        val start = Instant.parse("2026-10-01T18:00:00Z")
        fun m(min: Long) = sportMinute(start, start.plusSeconds(min * 60))
        assertEquals(SportMinute.Minute(1), sportMinute(start, start.minusSeconds(120)))
        assertEquals(SportMinute.Minute(1), m(0))
        assertEquals(SportMinute.Minute(34), m(34))
        assertEquals(SportMinute.Minute(45), m(45))
        assertEquals(SportMinute.Halbzeit, m(46))
        assertEquals(SportMinute.Halbzeit, m(60))
        assertEquals(SportMinute.Minute(46), m(61))
        assertEquals(SportMinute.Minute(90), m(105))
        assertEquals(SportMinute.Nachspielzeit, m(106))
        assertEquals(SportMinute.Nachspielzeit, m(200))
    }

    @Test
    fun `later grouped by berlin day`() {
        val g = nachTagen(
            listOf(
                spiel(start = "2026-10-02T18:00:00Z", id = 1),
                spiel(start = "2026-10-02T22:30:00Z", id = 2), // 00:30 Berlin am 03.10.
                spiel(start = "2026-10-03T13:30:00Z", id = 3),
            ),
        )
        assertEquals(listOf(LocalDate.of(2026, 10, 2), LocalDate.of(2026, 10, 3)), g.map { it.first })
        assertEquals(listOf(listOf(1L), listOf(2L, 3L)), g.map { p -> p.second.map { it.id } })
    }

    @Test
    fun `later expanded by default`() {
        assertTrue(spaeterStandardOffen(0))
        assertTrue(spaeterStandardOffen(8))
        assertFalse(spaeterStandardOffen(9))
    }

    @Test
    fun `split off senders and count countries`() {
        val t = senderTeilen(
            listOf(
                sender("🇩🇪 DAZN PPV 12", "🇩🇪 DAZN"),
                sender("🇳🇴 Tv2 Play PPV 02", an = false),
                sender("🇸🇪 Viaplay PPV 03", an = false),
                sender("🇳🇴 Viaplay PPV 02", an = false),
                sender("🇪🇸 Dazn PPV 10", an = null),
            ),
        )
        assertEquals(listOf("🇩🇪 DAZN PPV 12", "🇪🇸 Dazn PPV 10"), t.an.map { it.name })
        assertEquals(3, t.aus.size)
        assertEquals(2, t.ausLaender)
    }
}
