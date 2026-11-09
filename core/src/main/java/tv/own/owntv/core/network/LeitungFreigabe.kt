package tv.own.owntv.core.network

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * German4K 3.2.1 (q-4/7): kleines Warten auf „Leitungen zu" mit fester Frist — getrennt von OkHttp, damit es ohne
 * Netz prüfbar ist ([StreamingHttpClient.warteBisFrei]).
 */
object LeitungFreigabe {
    /** Wie lange Multiview → Vollbild höchstens auf die Kachel-Leitungen wartet (wie die mpv-Frist A1). */
    const val FRIST_MS = 1_500L
    private const val SCHRITT_MS = 40L

    /**
     * German4K 3.2.1 (p-14, Fund 2): wie [warte], aber [raeumen] (OkHttp `evictAll`) und [belegt] laufen auf [io] und
     * werfen nie. Der Aufrufer sitzt im viewModelScope (Main): das Schließen leerer TLS-Sockets schreibt ein close_notify
     * aufs Netz → NetworkOnMainThreadException, ungefangen = Absturz. Ein Fehler beim Räumen zählt nicht als belegt.
     */
    suspend fun warteAufIo(
        fristMs: Long,
        io: CoroutineDispatcher = Dispatchers.IO,
        schrittMs: Long = SCHRITT_MS,
        jetzt: () -> Long = System::currentTimeMillis,
        raeumen: () -> Unit,
        belegt: () -> Int,
    ): Boolean = warte(fristMs, schrittMs, jetzt) {
        withContext(io) {
            runCatching { raeumen() }
            runCatching { belegt() }.getOrDefault(0)
        }
    }

    /** Fragt [belegt] bis 0 oder bis [fristMs] um ist. true = frei geworden. */
    suspend fun warte(fristMs: Long, schrittMs: Long = SCHRITT_MS, jetzt: () -> Long = System::currentTimeMillis, belegt: suspend () -> Int): Boolean {
        val ende = jetzt() + fristMs
        while (true) {
            if (belegt() <= 0) return true
            if (jetzt() >= ende) return false
            delay(schrittMs)
        }
    }
}
