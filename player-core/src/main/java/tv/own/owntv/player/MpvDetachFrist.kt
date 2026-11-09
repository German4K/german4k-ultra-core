package tv.own.owntv.player

import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * German4K 3.2.1 (A1): einen libmpv-Aufruf auf dem mpv-Faden ausführen und höchstens [fristMs] darauf warten.
 * Rückgabe true = fertig (oder nichts mehr zu tun, Faden schon beendet), false = Frist abgelaufen.
 */
internal object MpvDetachFrist {
    const val FRIST_MS = 1_500L

    /**
     * German4K 3.2.1 (A1, p-5): ab hier gilt ein laufender mpv-Auftrag als Hänger (siehe [MpvFaden]). Kein normaler
     * Aufruf dauert so lange — loadfile/stop kehren sofort zurück, Lesezugriffe in Millisekunden.
     */
    const val HAENGT_AB_MS = 1_000L

    /** Hängt der Kern schon, bevor wir überhaupt etwas einreihen? Dann wartet niemand mehr auf ihn. */
    fun haengtSchon(laeuftSeitMs: Long): Boolean = laeuftSeitMs >= HAENGT_AB_MS

    fun ausfuehren(executor: ExecutorService, fristMs: Long, block: () -> Unit): Boolean {
        val future = try {
            executor.submit { block() }
        } catch (_: RejectedExecutionException) {
            return true
        }
        return try {
            future.get(fristMs, TimeUnit.MILLISECONDS)
            true
        } catch (_: TimeoutException) {
            false
        } catch (_: ExecutionException) {
            true // Aufruf ist gescheitert, aber zurückgekehrt — der Kern hängt nicht
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }
}
