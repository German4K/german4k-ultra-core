package tv.own.owntv.player

/**
 * German4K 3.2.1 (q-3, E): gemessene Bitrate über ein gleitendes Fenster.
 *
 * Die alte Messung teilte die Bytes seit der letzten Abfrage durch die Zeit seither. ExoPlayer lädt einen
 * Live-TS aber stoßweise (Puffer voll → Pause), und die erste Abfrage gab immer 0 zurück — die Info zeigte
 * „Bitrate 0 Mbit/s" für einen laufenden Sender. Hier: Bytes je Sekunde in [SLOTS] Eimern, Mittel über die
 * letzten [FENSTER_MS] (bzw. seit dem ersten Byte, wenn das kürzer her ist). null = noch nie gemessen.
 */
internal class BitrateFenster {
    private val sekunde = LongArray(SLOTS) { Long.MIN_VALUE }
    private val bytes = LongArray(SLOTS)
    private var erstesByteMs = -1L

    @Synchronized
    fun add(nowMs: Long, n: Int) {
        if (n <= 0) return
        if (erstesByteMs < 0) erstesByteMs = nowMs
        val s = nowMs / 1000
        val i = (s % SLOTS).toInt()
        if (sekunde[i] != s) { sekunde[i] = s; bytes[i] = 0 }
        bytes[i] += n.toLong()
    }

    /** Bits/s über das Fenster; null, solange nichts gemessen wurde. */
    @Synchronized
    fun bps(nowMs: Long): Long? {
        if (erstesByteMs < 0) return null
        val s = nowMs / 1000
        var summe = 0L
        for (i in 0 until SLOTS) {
            val alter = s - sekunde[i]
            if (sekunde[i] != Long.MIN_VALUE && alter in 0 until FENSTER_S) summe += bytes[i]
        }
        val spanneMs = (nowMs - maxOf(erstesByteMs, nowMs - FENSTER_MS)).coerceIn(MIN_SPANNE_MS, FENSTER_MS)
        return summe * 8_000 / spanneMs
    }

    @Synchronized
    fun reset() {
        sekunde.fill(Long.MIN_VALUE); bytes.fill(0); erstesByteMs = -1L
    }

    companion object {
        const val FENSTER_S = 10L
        const val FENSTER_MS = FENSTER_S * 1000
        /** Unter einer Sekunde Messzeit wäre der erste Stoß eine Fantasiezahl. */
        const val MIN_SPANNE_MS = 1000L
        private const val SLOTS = 12

        fun aktiv(): Boolean = tv.own.owntv.core.german4k.German4kFeatures.an(tv.own.owntv.core.german4k.German4kSchalter.BITRATE_FENSTER)
    }
}
