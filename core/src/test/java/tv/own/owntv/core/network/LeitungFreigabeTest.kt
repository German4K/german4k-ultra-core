package tv.own.owntv.core.network

import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.core.german4k.German4kUpdate
import tv.own.owntv.core.german4k.German4kUpdateSource
import java.util.concurrent.Executors

/** German4K 3.2.1 (q-4/7, q-4/12). */
class LeitungFreigabeTest {

    @Test
    fun `schon frei - kein Warten`() = runBlocking {
        var fragen = 0
        assertTrue(LeitungFreigabe.warte(1_500) { fragen++; 0 })
        assertEquals(1, fragen)
    }

    @Test
    fun `wartet bis die Kachel-Leitungen zu sind`() = runBlocking {
        var offen = 3
        val frei = LeitungFreigabe.warte(1_500, schrittMs = 1) { offen.also { offen-- } }
        assertTrue(frei)
        assertEquals(-1, offen)
    }

    @Test
    fun `Frist abgelaufen - weiter statt ewig warten`() = runBlocking {
        var uhr = 0L
        val frei = LeitungFreigabe.warte(1_500, schrittMs = 1, jetzt = { uhr.also { uhr += 100 } }) { 1 }
        assertFalse(frei)
        assertTrue(uhr in 1_500L..1_800L)
    }

    @Test
    fun `Panel-Antwort ohne Build zaehlt als beantwortet`() {
        German4kUpdateSource.vergessen()
        German4kUpdateSource.offer(null)
        assertTrue(German4kUpdateSource.beantwortet.value)
        German4kUpdateSource.vergessen()
    }

    // --- p-14, Fund 9: Cache ist keine Antwort ---
    @Test
    fun `Cache-Antwort zaehlt nicht als beantwortet - manuelle Pruefung offline sagt nicht aktuell`() {
        German4kUpdateSource.vergessen()
        German4kUpdateSource.offer(null, frisch = false)
        assertFalse(German4kUpdateSource.beantwortet.value)
        assertEquals(German4kUpdateSource.Urteil.UNBEKANNT, German4kUpdateSource.urteil(40, stillAn = true))
        German4kUpdateSource.offer(build(40), frisch = false) // gleicher Build aus dem Cache
        assertEquals(German4kUpdateSource.Urteil.UNBEKANNT, German4kUpdateSource.urteil(40, stillAn = true))
        // Altes Verhalten (Schalter aus): bekannter Build = aktuell.
        assertEquals(German4kUpdateSource.Urteil.AKTUELL, German4kUpdateSource.urteil(40, stillAn = false))
        German4kUpdateSource.vergessen()
    }

    @Test
    fun `Pflicht-Update aus dem Cache wird trotzdem angeboten, frische Antwort heisst aktuell`() {
        German4kUpdateSource.vergessen()
        German4kUpdateSource.offer(build(41), frisch = false)
        assertEquals(German4kUpdateSource.Urteil.NEUER, German4kUpdateSource.urteil(40, stillAn = true))
        German4kUpdateSource.offer(null)
        assertEquals(German4kUpdateSource.Urteil.AKTUELL, German4kUpdateSource.urteil(40, stillAn = true))
        German4kUpdateSource.vergessen()
    }

    // --- p-14, Fund 2: evictAll nie auf dem Aufrufer-Faden (Main), nie ungefangen ---
    @Test
    fun `raeumen laeuft auf dem IO-Dispatcher und ein Fehler bricht nichts ab`() {
        val pool = Executors.newSingleThreadExecutor { r -> Thread(r, "g4k-io-test") }
        try {
            val io = pool.asCoroutineDispatcher()
            val faeden = mutableListOf<String>()
            var runde = 0
            val frei = runBlocking {
                LeitungFreigabe.warteAufIo(
                    1_500, io = io, schrittMs = 1,
                    raeumen = {
                        faeden += Thread.currentThread().name
                        if (runde++ == 0) throw RuntimeException("NetworkOnMainThreadException-Ersatz")
                    },
                    belegt = { if (runde < 2) 1 else 0 },
                )
            }
            assertTrue(frei)
            assertTrue(faeden.isNotEmpty())
            assertTrue(faeden.toString(), faeden.all { it.startsWith("g4k-io-test") }) // Debug-Modus hängt " @coroutine#n" an
        } finally {
            pool.shutdownNow()
        }
    }

    private fun build(code: Int) = German4kUpdate("stable", "3.2.$code", code, "https://x/a.apk", "", required = false)
}
