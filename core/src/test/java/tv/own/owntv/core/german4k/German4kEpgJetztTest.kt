package tv.own.owntv.core.german4k

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import tv.own.owntv.core.live.EpgNowNext
import tv.own.owntv.core.parser.XtEpgEntry

class German4kEpgJetztTest {
    private val min = 60_000L
    private fun e(t: String, a: Int, b: Int) = XtEpgEntry(t, null, a * min, b * min)
    // 07:00–07:05, 07:05–09:00, 09:00–09:30 (Minuten ab Mitternacht)
    private val a = e("Kurz", 420, 425)
    private val b = e("Morgenmagazin", 425, 540)
    private val c = e("Danach", 540, 570)

    @Test fun laufendBleibt() {
        val epg = EpgNowNext(a, b, listOf(c))
        assertSame(epg, German4kEpgJetzt.zu(epg, 422 * min))
    }

    @Test fun rolltWeiter() {
        val r = German4kEpgJetzt.zu(EpgNowNext(a, b, listOf(c)), 427 * min)
        assertEquals("Morgenmagazin", r.now?.title)
        assertEquals("Danach", r.next?.title)
    }

    @Test fun upcomingOhneNextDoppelt() {
        val r = German4kEpgJetzt.zu(EpgNowNext(a, b, listOf(b, c)), 545 * min)
        assertEquals("Danach", r.now?.title)
        assertNull(r.next)
    }

    @Test fun nichtsMehrBekannt() {
        val r = German4kEpgJetzt.zu(EpgNowNext(a, null), 430 * min)
        assertNull(r.now)
        assertNull(r.next)
    }

    @Test fun wechselZeit() {
        assertEquals(3 * min, German4kEpgJetzt.bisWechsel(EpgNowNext(a, b), 422 * min))
        assertNull(German4kEpgJetzt.bisWechsel(EpgNowNext(null, null), 0))
    }
}
