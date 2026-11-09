package tv.own.owntv.core.german4k

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.core.content.AdultCategoryClassifier
import tv.own.owntv.core.database.entity.CategoryEntity
import tv.own.owntv.core.model.MediaType

/** German4K 3.2: Angesagt / Top 10 — Parser, Abgleich, Erwachsene, Kinderprofil, Fassungswahl, Entdoppeln. */
class German4kAngesagtTest {

    private fun caps(hevc: Boolean = true, vierK: Boolean = true, dv: Boolean = false) =
        German4kDeviceCaps.Caps(hevc = hevc, hevc4k = vierK, hdr10 = false, dolbyVision = dv, fireTv = false, api = 28, model = "test", tv = true)

    private val beispiel = """
        { "v": 1, "stand": "2026-10-17T02:00:00Z",
          "angesagt": [
            { "tmdb": 20, "art": "series", "titel": "VisionQuest", "jahr": 2026, "rang": 2, "badge": "neue_folge",
              "fassungen": [ { "id": "s1", "label": "DE", "codec": "h264", "res": "fhd" } ] },
            { "tmdb": 10, "art": "movie", "titel": "Spider-Man", "jahr": 2026, "rang": 1, "badge": null,
              "fassungen": [ { "id": "40563", "label": "DE 4K DV", "codec": "hevc", "res": "uhd" },
                             { "id": "40564", "label": "DE", "codec": "h264", "res": "fhd" } ] },
            { "tmdb": 0, "art": "movie", "titel": "kaputt", "rang": 3, "fassungen": [ { "id": "x" } ] },
            { "tmdb": 30, "art": "podcast", "titel": "falsche Art", "rang": 4, "fassungen": [ { "id": "y" } ] },
            { "tmdb": 40, "art": "movie", "titel": "ohne Fassung", "rang": 5, "fassungen": [] },
            { "tmdb": 10, "art": "movie", "titel": "Spider-Man doppelt", "rang": 6, "fassungen": [ { "id": 777 } ] }
          ],
          "top_filme": [ ${(1..12).joinToString(",") { """{ "tmdb": ${100 + it}, "art": "movie", "titel": "T$it", "rang": $it, "fassungen": [ { "id": "t$it" } ] }""" }} ],
          "top_serien": [ { "tmdb": 20, "art": "series", "titel": "VisionQuest", "rang": 1, "fassungen": [ { "id": "s1" } ] } ],
          "neu_filme": [], "neu_serien": [], "unbekannt": 5 }
    """.trimIndent()

    @Test
    fun `parser liest Vertrag C1 sortiert nach Rang und wirft Kaputtes still raus`() {
        val d = AngesagtDatei.parse(beispiel)!!
        assertEquals(1, d.v)
        assertEquals(listOf(10, 20), d.angesagt.map { it.tmdb }) // Rang 1 vor 2; tmdb 0, falsche Art, leer, Dublette weg
        assertEquals(AngesagtArt.FILM, d.angesagt[0].art)
        assertEquals(AngesagtBadge.NEUE_FOLGE, d.angesagt[1].badge)
        assertNull(d.angesagt[0].badge)
        assertEquals("hevc", d.angesagt[0].fassungen[0].codec)
        assertEquals("uhd", d.angesagt[0].fassungen[0].res)
        assertEquals(10, d.topFilme.size) // höchstens zehn
        assertEquals(1, d.topSerien.size)
    }

    @Test
    fun `parser lehnt Unsinn ab`() {
        assertNull(AngesagtDatei.parse("kein json"))
        assertNull(AngesagtDatei.parse("""{ "angesagt": [] }""")) // ohne Version
        assertNotNull(AngesagtDatei.parse("""{ "v": 2, "angesagt": [] }""")) // neuere Fassung wird gelesen
        // Fassungs-id als Zahl wird akzeptiert
        val d = AngesagtDatei.parse("""{ "v": 1, "angesagt": [ { "tmdb": 1, "art": "movie", "rang": 1, "fassungen": [ { "id": 5 } ] } ] }""")!!
        assertEquals("5", d.angesagt.single().fassungen.single().id)
    }

    private fun lokal(vararg l: AngesagtLokal) = l.associateBy { it.remoteId }

    @Test
    fun `abgleich zeigt nur lokal vorhandene Titel`() {
        val d = AngesagtDatei.parse(beispiel)!!
        val t = German4kAngesagt.abgleichen(d.angesagt, lokal(AngesagtLokal(1, "40564", "DE - Spider-Man", "DE Action")), false, caps())
        assertEquals(listOf(10), t.map { it.eintrag.tmdb })
        assertEquals("40564", t.single().gewaehlt.remoteId)
    }

    @Test
    fun `abgleich nimmt nie eine Fassung aus einer Erwachsenen-Rubrik`() {
        val d = AngesagtDatei.parse(beispiel)!!
        val t = German4kAngesagt.abgleichen(
            d.angesagt,
            lokal(AngesagtLokal(1, "40564", "DE - Spider-Man", "XXX Erotik"), AngesagtLokal(2, "s1", "VisionQuest", "Für Erwachsene 18+")),
            kinderprofil = false,
            caps = caps(),
        )
        assertTrue(t.isEmpty())
        // Vom Panel genannte Rubrik zählt auch, wenn der Name harmlos aussieht.
        AdultCategoryClassifier.setzeErwachsenRubriken(listOf("Nachtprogramm"))
        try {
            val t2 = German4kAngesagt.abgleichen(d.angesagt, lokal(AngesagtLokal(1, "40564", "x", "Nachtprogramm")), false, caps())
            assertTrue(t2.isEmpty())
        } finally {
            AdultCategoryClassifier.setzeErwachsenRubriken(emptyList())
        }
    }

    @Test
    fun `kinderprofil nur mit Titeln aus Kinder-Rubriken`() {
        val d = AngesagtDatei.parse(beispiel)!!
        val l = lokal(AngesagtLokal(1, "40564", "DE - Spider-Man", "DE Action"), AngesagtLokal(2, "s1", "VisionQuest", "DE Kinder Serien"))
        val t = German4kAngesagt.abgleichen(d.angesagt, l, kinderprofil = true, caps = caps())
        assertEquals(listOf(20), t.map { it.eintrag.tmdb })
        assertTrue(German4kKinder.istKinderKategorie("DE - KINDER FILME"))
        assertTrue(German4kKinder.istKinderKategorie("Animation"))
        assertTrue(German4kKinder.istKinderKategorie("Familie & Kids"))
        assertFalse(German4kKinder.istKinderKategorie("Anime"))
        assertFalse(German4kKinder.istKinderKategorie("Action"))
        assertFalse(German4kKinder.istKinderKategorie(null))
    }

    @Test
    fun `ausgeblendete Rubriken des Profils zaehlen wie nicht vorhanden`() {
        val d = AngesagtDatei.parse(beispiel)!!
        val t = German4kAngesagt.abgleichen(d.angesagt, lokal(AngesagtLokal(1, "40564", "x", "Action")), false, caps()) { true }
        assertTrue(t.isEmpty())
    }

    @Test
    fun `fassungswahl deutsch dann was das Geraet kann dann hoechste Qualitaet`() {
        val dv = AngesagtFassung("a", "DE 4K DV", "hevc", "uhd")
        val uhd = AngesagtFassung("b", "DE 4K", "hevc", "uhd")
        val fhdHevc = AngesagtFassung("c", "DE FHD HEVC", "hevc", "fhd")
        val fhd = AngesagtFassung("d", "DE", "h264", "fhd")
        val en = AngesagtFassung("e", "EN 4K", "hevc", "uhd")
        val alle = listOf(en, fhd, fhdHevc, uhd, dv)
        // DV-Bildschirm + 4K: Dolby Vision zuerst
        assertEquals("a", German4kAngesagt.waehleFassung(alle, caps(dv = true))?.id)
        // ohne DV: 4K ohne DV, deutsch vor der englischen 4K
        assertEquals("b", German4kAngesagt.waehleFassung(alle, caps(dv = false))?.id)
        // HEVC ja, 4K nein: FHD HEVC und FHD h264 sind gleich gut, Dateireihenfolge entscheidet (fhd zuerst)
        assertEquals("d", German4kAngesagt.waehleFassung(alle, caps(vierK = false))?.id)
        // Codec-Weiche: h264-Gerät bekommt nie HEVC
        assertEquals("d", German4kAngesagt.waehleFassung(alle, caps(hevc = false, vierK = false))?.id)
        // Unbekanntes Gerät: der Kunde darf es versuchen — beste deutsche Fassung, aber DV nicht bevorzugt
        assertEquals("b", German4kAngesagt.waehleFassung(alle, null)?.id)
    }

    @Test
    fun `fassungswahl ohne Codec-Angabe liest das Etikett und faellt gutmuetig zurueck`() {
        val uhdOhneCodec = AngesagtFassung("u", "DE 4K", null, null)
        val hdOhneCodec = AngesagtFassung("h", "DE HD", null, null)
        assertTrue(German4kAngesagt.istHevc(uhdOhneCodec))
        assertFalse(German4kAngesagt.istHevc(hdOhneCodec))
        assertEquals("h", German4kAngesagt.waehleFassung(listOf(uhdOhneCodec, hdOhneCodec), caps(hevc = false, vierK = false))?.id)
        // Nichts passt: die gutmütigste (kein HEVC zuerst, sonst kleinste Auflösung)
        val nurHevc = listOf(AngesagtFassung("x", "DE 4K", "hevc", "uhd"), AngesagtFassung("y", "DE FHD", "hevc", "fhd"))
        assertEquals("y", German4kAngesagt.waehleFassung(nurHevc, caps(hevc = false, vierK = false))?.id)
        assertNull(German4kAngesagt.waehleFassung(emptyList(), caps()))
        assertTrue(German4kAngesagt.istDeutsch(AngesagtFassung("z", "DE - Dune", null, null)))
        assertFalse(German4kAngesagt.istDeutsch(AngesagtFassung("z", "EN 4K", null, null)))
    }

    @Test
    fun `badge 4K nur wenn Geraet 4K kann und 4K lokal liegt, Server-Badge hat Vorrang`() {
        val d = AngesagtDatei.parse(beispiel)!!
        val beide = lokal(AngesagtLokal(1, "40563", "4K", "Action"), AngesagtLokal(2, "40564", "DE", "Action"), AngesagtLokal(3, "s1", "VQ", "Serien"))
        // 40563 ist „DE 4K DV" — ohne DV-Bildschirm keine 4K-Fassung, die das Gerät zeigen kann
        assertNull(German4kAngesagt.abgleichen(d.angesagt, beide, false, caps(vierK = true, dv = false)).first { it.eintrag.tmdb == 10 }.badge)
        val mit4k = German4kAngesagt.abgleichen(d.angesagt, beide, false, caps(vierK = true, dv = true))
        assertEquals(AngesagtBadge.VIERK, mit4k.first { it.eintrag.tmdb == 10 }.badge)
        assertEquals(AngesagtBadge.NEUE_FOLGE, mit4k.first { it.eintrag.tmdb == 20 }.badge)
        val ohne4k = German4kAngesagt.abgleichen(d.angesagt, beide, false, caps(vierK = false))
        assertNull(ohne4k.first { it.eintrag.tmdb == 10 }.badge)
        assertEquals("40564", ohne4k.first { it.eintrag.tmdb == 10 }.gewaehlt.remoteId)
        val nurFhd = German4kAngesagt.abgleichen(d.angesagt, lokal(AngesagtLokal(2, "40564", "DE", "Action")), false, caps(vierK = true))
        assertNull(nurFhd.single().badge)
    }

    @Test
    fun `angesagt unter der Top 10 ohne Titel aus der Top 10 und Regal erst ab vier`() {
        val d = AngesagtDatei.parse(beispiel)!!
        val l = lokal(AngesagtLokal(3, "s1", "VQ", "Serien"))
        val top = German4kAngesagt.abgleichen(d.topSerien, l, false, caps())
        val ang = German4kAngesagt.abgleichen(d.angesagt.filter { it.art == AngesagtArt.SERIE }, l, false, caps())
        assertEquals(1, ang.size)
        assertTrue(German4kAngesagt.ohneTop(ang, top).isEmpty())
        assertTrue(German4kAngesagt.regal(listOf(1, 2, 3)).isEmpty())
        assertEquals(4, German4kAngesagt.regal(listOf(1, 2, 3, 4)).size)
    }

    @Test
    fun `standardfolge weiterschauen sonst S1E1, nach fertiger Folge die naechste`() {
        val f = listOf(
            German4kAngesagt.Folge(9, 0, 1), // Special
            German4kAngesagt.Folge(3, 2, 1),
            German4kAngesagt.Folge(1, 1, 1),
            German4kAngesagt.Folge(2, 1, 2),
        )
        assertEquals(1L, German4kAngesagt.standardFolge(f, null))
        assertEquals(2L, German4kAngesagt.standardFolge(f, 2))
        assertEquals(3L, German4kAngesagt.standardFolge(f, 2, zuletztFertig = true))
        assertEquals(3L, German4kAngesagt.standardFolge(f, 3, zuletztFertig = true)) // letzte Folge bleibt
        assertEquals(1L, German4kAngesagt.standardFolge(f, 999)) // unbekannte Folge → S1E1
        assertEquals(9L, German4kAngesagt.standardFolge(listOf(German4kAngesagt.Folge(9, 0, 1)), null))
        assertNull(German4kAngesagt.standardFolge(emptyList(), null))
    }

    @Test
    fun `erwachsenen-filter blendet fuer Startseite und Suche aus, Kinderprofil wie bisher`() {
        val cats = listOf(
            CategoryEntity(id = 1, sourceId = 1, mediaType = MediaType.MOVIE, name = "DE Action"),
            CategoryEntity(id = 2, sourceId = 1, mediaType = MediaType.MOVIE, name = "XXX Erotik"),
        )
        // altes Verhalten: Erwachsenenprofil sieht alles
        assertEquals(emptySet<Long>(), AdultCategoryClassifier.hiddenCategoryIds(cats, emptySet(), isKidsProfile = false))
        // Startseite (F1): immer aus
        assertEquals(setOf(2L), AdultCategoryClassifier.hiddenCategoryIds(cats, emptySet(), isKidsProfile = false, hideAdult = true))
        // Kinderprofil: aus, auch wenn jemand hideAdult = false übergäbe
        assertEquals(setOf(2L), AdultCategoryClassifier.hiddenCategoryIds(cats, emptySet(), isKidsProfile = true, hideAdult = false))
    }

    @Test
    fun `suche gibt Erwachsenes erst nach PIN frei`() {
        tv.own.owntv.core.content.German4kErwachsenSitzung.sperren()
        assertFalse(tv.own.owntv.core.content.German4kErwachsenSitzung.frei)
        tv.own.owntv.core.content.German4kErwachsenSitzung.pinEingegeben()
        assertTrue(tv.own.owntv.core.content.German4kErwachsenSitzung.frei)
        tv.own.owntv.core.content.German4kErwachsenSitzung.sperren()
        assertFalse(tv.own.owntv.core.content.German4kErwachsenSitzung.frei)
    }

    @Test
    fun `panel-antwort traegt angesagt_url durch parse und toJson`() {
        val a = German4kPanelAnswer.parse("""{ "mac": "m", "angesagt_url": "https://r2.example/app/angesagt.json" }""")
        assertEquals("https://r2.example/app/angesagt.json", a.angesagtUrl)
        assertEquals(a.angesagtUrl, German4kPanelAnswer.parse(a.toJson()).angesagtUrl)
        assertNull(German4kPanelAnswer.parse("""{ "mac": "m" }""").angesagtUrl)
        assertNull(German4kPanelAnswer.parse("""{ "mac": "m", "angesagt_url": null }""").angesagtUrl)
        assertNull(German4kPanelAnswer.parse("""{ "mac": "m", "angesagt_url": "ftp://x" }""").angesagtUrl)
    }

    @Test
    fun `abgleich ueber sid findet Regalzeilen und alte Kennungen, Hauptzeile hat Vorrang`() {
        // streams.id 846 → Hauptzeile 100000000 + 846*32 = 100027072, Regalplatz 3 = 100027075
        val haupt = (100_000_000L + 846 * 32).toString()
        val regal = (100_000_000L + 846 * 32 + 3).toString()
        assertEquals(846L, German4kAngesagt.streamIdVon(regal.toLong()))
        assertEquals(12345L, German4kAngesagt.streamIdVon(12345L)) // alte Kennung bleibt
        val d = AngesagtDatei.parse("""{ "v": 1, "angesagt": [ { "tmdb": 1, "art": "movie", "rang": 1,
            "fassungen": [ { "id": "$haupt", "sid": 846, "label": "DE", "codec": null, "res": null } ] } ] }""")!!
        val f = d.angesagt.single().fassungen.single()
        assertEquals(846L, f.sid)
        val k = German4kAngesagt.kandidaten(f)
        assertEquals(haupt, k.first())
        assertTrue(regal in k && "846" in k)
        // nur Regalzeile lokal → gefunden
        val nurRegal = German4kAngesagt.abgleichen(d.angesagt, lokal(AngesagtLokal(7, regal, "DE - X", "🆕 Neu diese Woche")), false, caps())
        assertEquals(regal, nurRegal.single().gewaehlt.remoteId)
        // Haupt- und Regalzeile lokal → Hauptzeile, als EINE Fassung gezählt
        val beide = German4kAngesagt.abgleichen(d.angesagt, lokal(AngesagtLokal(7, regal, "X", "Neu"), AngesagtLokal(8, haupt, "X", "DE Action")), false, caps())
        assertEquals(haupt, beide.single().gewaehlt.remoteId)
        assertEquals(1, beide.single().lokaleFassungen)
        // alte blanke Kennung im Katalog
        val alt = German4kAngesagt.abgleichen(d.angesagt, lokal(AngesagtLokal(9, "846", "X", "DE Action")), false, caps())
        assertEquals("846", alt.single().gewaehlt.remoteId)
        // ohne sid: aus der id zurückgerechnet
        assertTrue(regal in German4kAngesagt.kandidaten(AngesagtFassung(haupt, "DE", null, null)))
    }
}
