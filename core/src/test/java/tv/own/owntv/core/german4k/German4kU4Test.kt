package tv.own.owntv.core.german4k

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.core.settings.SettingsRepository.SortMode
import java.io.File
import java.time.LocalDate

/** German4K 3.2.1 (u-4): Restfehler Oberfläche — U8 Störung, U9 Zugangstage, U10 Favoriten nach Nummer. */
class German4kU4Test {

    @After fun aufraeumen() = German4kFeatures.set(emptyMap())

    private val heute = LocalDate.parse("2026-10-21")

    private fun antwort(
        expire: String = "2027-10-21",
        quellen: List<String> = emptyList(),
        noteTyp: String = "",
        locked: Boolean = false,
    ) = German4kPanelAnswer(
        mac = "00:1a:79:00:00:01", deviceKey = "123456", registered = quellen.isNotEmpty(), expireDate = expire,
        locked = locked, noteTitle = "", noteContent = "", appVersion = "", apkLink = "", einrichtenUrl = "", loginHost = "",
        sources = quellen.mapIndexed { i, n -> German4kSource("q$i", n, "https://german4k.tv", "u", "p", i == 0) },
        noteTyp = noteTyp,
    )

    // --- U9 ---------------------------------------------------------------------------------

    @Test fun `ohne Zugang keine Tageszahl trotz Platzhalter heute plus 365`() {
        val a = antwort(noteTyp = "willkommen")
        assertNull(German4kZugangTage.tage(a, heute))
        assertFalse(German4kZugangTage.echt(a))
        assertFalse(German4kZugangTage.anzeigen(a))
    }

    @Test fun `gekoppelte German4K-Line zeigt ihre Tage`() {
        val a = antwort(expire = "2026-11-20", quellen = listOf("German4K"))
        assertEquals(30, German4kZugangTage.tage(a, heute))
        assertTrue(German4kZugangTage.anzeigen(a))
    }

    @Test fun `selbst eingetragener fremder Zugang traegt nur den Platzhalter`() {
        assertNull(German4kZugangTage.tage(antwort(quellen = listOf("Mein IPTV")), heute))
    }

    @Test fun `abgelaufener Zugang ohne Quellen bleibt sichtbar`() {
        assertEquals(-3, German4kZugangTage.tage(antwort(expire = "2026-10-18", noteTyp = "abgelaufen"), heute))
    }

    @Test fun `gesperrtes Geraet und unlesbares Datum zeigen nichts`() {
        assertNull(German4kZugangTage.tage(antwort(quellen = listOf("German4K"), locked = true), heute))
        assertNull(German4kZugangTage.tage(antwort(expire = "", quellen = listOf("German4K")), heute))
        assertNull(German4kZugangTage.tage(null, heute))
    }

    @Test fun `Schalter aus ist das alte Verhalten`() {
        German4kFeatures.set(mapOf(German4kSchalter.ZUGANG_TAGE_ECHT to "aus"))
        assertTrue(German4kZugangTage.anzeigen(antwort(noteTyp = "willkommen")))
        assertEquals(365, German4kZugangTage.tage(antwort(), heute, streng = false))
    }

    // --- U8 ---------------------------------------------------------------------------------

    private val m3uAusfall = "ExoPlayer error before first frame: ERROR_CODE_IO_NETWORK_CONNECTION_FAILED: java.io.IOException: " +
        "java.util.concurrent.ExecutionException: java.io.IOException: unexpected end of stream on http://127.0.0.1:8767/..."

    @Test fun `aufgegebener Sender mit Verbindungsfehler meldet keinen gelungenen Wechsel`() {
        // Gemessen abn2-a9/log-serverwechsel.txt 22:36:41 — bisher HOST_AUSFALL („Das Bild ist wieder da").
        assertEquals(German4kHealth.Klasse.HOST_AUSFALL, German4kHealth.klassifiziere(m3uAusfall))
        assertEquals(German4kHealth.Klasse.UNBEKANNT, German4kStoerung.einordnen(m3uAusfall))
        assertEquals(German4kHealth.Klasse.UNBEKANNT, German4kStoerung.einordnen("'X' — giving up: no picture within 30s of tuning"))
        assertEquals(German4kHealth.Klasse.HOST_AUSFALL, German4kStoerung.einordnen(m3uAusfall, ehrlich = false))
    }

    @Test fun `die uebrigen Einordnungen bleiben`() {
        assertEquals(German4kHealth.Klasse.LEITUNG_BELEGT, German4kStoerung.einordnen("Source error: response code: 458"))
        assertEquals(German4kHealth.Klasse.ABGELAUFEN, German4kStoerung.einordnen("HTTP 403"))
        assertEquals(German4kHealth.Klasse.LAND_GESPERRT, German4kStoerung.einordnen("response code: 456"))
    }

    @Test fun `melde zeigt bei Verbindungsfehler keinen Dialog`() {
        German4kStoerung.schliessen()
        German4kStoerung.melde("Lokal AAC51 Eins", m3uAusfall)
        assertNull(German4kStoerung.lage.value)
        German4kStoerung.melde("Sender", "response code: 458")
        assertEquals(German4kHealth.Klasse.LEITUNG_BELEGT, German4kStoerung.lage.value?.klasse)
        German4kStoerung.schliessen()
    }

    // --- U10 --------------------------------------------------------------------------------

    @Test fun `Live-Favoriten folgen dem Sortierknopf`() {
        assertEquals(German4kFavoritenLive.Abfrage.NUMMER, German4kFavoritenLive.abfrage(SortMode.PLAYLIST, true))
        assertEquals(German4kFavoritenLive.Abfrage.A_Z, German4kFavoritenLive.abfrage(SortMode.ALPHA, true))
        assertEquals(German4kFavoritenLive.Abfrage.HINZUGEFUEGT, German4kFavoritenLive.abfrage(SortMode.PLAYLIST, false))
        assertEquals(German4kFavoritenLive.Abfrage.HINZUGEFUEGT, German4kFavoritenLive.abfrage(SortMode.ALPHA, false))
    }

    private fun orderBy(src: String, fn: String): String {
        val i = src.indexOf("fun $fn(")
        assertTrue("$fn fehlt", i > 0)
        val query = src.substring(src.lastIndexOf("@Query(", i), i)
        return Regex("ORDER BY ([^\"]*)\"").find(query)!!.groupValues[1]
    }

    @Test fun `Favoriten-Abfragen der Sender sortieren nach Nummer bzw Name und laufen auf SQLite 3_19`() {
        val src = File("src/main/java/tv/own/owntv/core/database/dao/ChannelDao.kt").readText()
        val nummer = orderBy(src, "pagingFavoritesNummer")
        val name = orderBy(src, "pagingFavoritesNameManual")
        val snapshot = orderBy(src, "snapshotFavoritesNummer")
        // Eigene Reihenfolge zuerst, dann die Sendernummer — wie in der Kategorie (pagingByCategoryManual).
        assertTrue(nummer, nummer.startsWith("(CASE WHEN o.position IS NULL THEN 1 ELSE 0 END), o.position, c.sourceId, c.sortOrder"))
        assertFalse(nummer, "addedAt" in nummer)
        assertTrue(name, name.endsWith("o.position, c.name"))
        // Verschieben startet aus genau der Liste, die unter „Nummer“ zu sehen ist.
        assertEquals("$nummer LIMIT :limit", snapshot)
        listOf(nummer, name, snapshot).forEach { o ->
            assertFalse(o, Regex("(?i)NULLS (FIRST|LAST)| OVER ?\\(").containsMatchIn(o))
        }
    }
}
