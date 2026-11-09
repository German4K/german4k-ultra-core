package tv.own.owntv.player

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MpvDetachFristTest {
    @Test
    fun freierFadenKommtRechtzeitig() {
        val ex = Executors.newSingleThreadExecutor()
        var lief = false
        assertTrue(MpvDetachFrist.ausfuehren(ex, 1_500) { lief = true })
        assertTrue(lief)
        ex.shutdownNow()
    }

    @Test
    fun haengenderFadenLaeuftInDieFristOhneMainZuBlockieren() {
        val ex = Executors.newSingleThreadExecutor()
        val haengt = CountDownLatch(1)
        ex.execute { haengt.await() } // hängender Netz-Read im Kern
        val start = System.nanoTime()
        assertFalse(MpvDetachFrist.ausfuehren(ex, 200) { })
        val ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
        assertTrue("wartete $ms ms", ms in 150..1_000)
        haengt.countDown()
        ex.shutdownNow()
    }

    @Test
    fun beendeterFadenIstKeinHaenger() {
        val ex = Executors.newSingleThreadExecutor()
        ex.shutdown()
        assertTrue(MpvDetachFrist.ausfuehren(ex, 200) { error("darf nicht laufen") })
    }

    @Test
    fun fehlerImAufrufIstKeinHaenger() {
        val ex = Executors.newSingleThreadExecutor()
        assertTrue(MpvDetachFrist.ausfuehren(ex, 1_500) { error("native Ausnahme") })
        ex.shutdownNow()
    }

    @Test
    fun zwanzigWechselHintereinanderBleibenUnterDerFrist() {
        val ex = Executors.newSingleThreadExecutor()
        repeat(20) { assertTrue(MpvDetachFrist.ausfuehren(ex, 1_500) { Thread.sleep(5) }) }
        ex.shutdownNow()
    }
}
