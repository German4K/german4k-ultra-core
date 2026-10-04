package tv.own.owntv.core.german4k

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class German4kVirtuellRepositoryTest {
    private var jetzt = VirtuellTestDaten.SERVER.toEpochMilli()
    private var aufrufe = 0
    private var antwort: German4kVirtuellAntwort? = German4kVirtuellAntwort.parse(VirtuellTestDaten.VERTRAG)
    private fun repo(warte: CompletableDeferred<Unit>? = null) = German4kVirtuellRepository(
        holen = { _, _ -> aufrufe++; warte?.await(); antwort },
        uhrJetzt = { jetzt },
    )

    @Test
    fun `second call within 60 s is served from the cache`() = runBlocking {
        val r = repo()
        r.plan(13750001)
        jetzt += 59_000
        val zweit = r.plan(13750001) as German4kVirtuellRepository.Ergebnis.Ok
        assertEquals(1, aufrufe)
        // Die Uhr stammt aus der ersten Antwort: 59 s später ist die Position 59 s weiter.
        assertEquals(710_000L + 59_000L, VirtuellPlan.stand(zweit.antwort.sender.single(), zweit.uhr, jetzt)!!.positionMs)
        jetzt += 2_000
        r.plan(13750001)
        assertEquals(2, aufrufe)
    }

    @Test
    fun `concurrent calls share one request`() = runBlocking {
        val tor = CompletableDeferred<Unit>()
        val r = repo(tor)
        val a = async { r.plan(13750001) }
        val b = async { r.plan(13750001) }
        tor.complete(Unit)
        listOf(a, b).awaitAll()
        assertEquals(1, aufrufe)
    }

    @Test
    fun `ok false is a refusal with the reason`() = runBlocking {
        antwort = German4kVirtuellAntwort.parse("""{"ok":false,"grund":"Dieses Gerät ist gesperrt.","vertrag":1,"server_time":"2026-10-04T18:00:00Z","stufe":null,"sender":[]}""")
        val e = repo().plan(1)
        assertEquals("Dieses Gerät ist gesperrt.", (e as German4kVirtuellRepository.Ergebnis.Abgelehnt).grund)
    }

    @Test
    fun `transport error is Transport and not cached`() = runBlocking {
        antwort = null
        val r = repo()
        assertTrue(r.plan(1) is German4kVirtuellRepository.Ergebnis.Transport)
        assertTrue(r.plan(1) is German4kVirtuellRepository.Ergebnis.Transport)
        assertEquals(2, aufrufe)
        assertNull(r.gecacht(1))
    }

    @Test
    fun `invalidate forces a fresh request`() = runBlocking {
        val r = repo()
        r.plan(13750001)
        r.invalidiere(13750001)
        r.plan(13750001)
        assertEquals(2, aufrufe)
    }

    @Test
    fun `a shorter window never replaces a longer plan`() = runBlocking {
        val voll = German4kVirtuellAntwort.parse(VirtuellTestDaten.VERTRAG)!!
        val kurz = voll.copy(sender = voll.sender.map { it.copy(plan = it.plan.take(1)) })
        var naechste = voll
        val r = German4kVirtuellRepository(holen = { _, _ -> naechste }, uhrJetzt = { jetzt })
        r.alle(6)
        naechste = kurz
        r.alle(1)
        assertEquals(3, r.plaene.value["g4k.v.himym"]!!.size)
    }

    @Test
    fun `plans are published per epg id`() = runBlocking {
        val r = repo()
        r.alle(1)
        assertEquals(3, r.plaene.value["g4k.v.himym"]!!.size)
        assertEquals(13750001L, r.gecacht(13750001)!!.antwort.sender.single().streamId)
    }
}
