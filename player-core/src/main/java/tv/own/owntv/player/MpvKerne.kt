package tv.own.owntv.player

import java.util.concurrent.atomic.AtomicInteger

/**
 * German4K 3.2.1 (P2): wie viele libmpv-Kerne im Prozess leben.
 *
 * FFmpegs eigene Logzeilen (z. B. „https: HTTP error 403 Forbidden“) gehen über einen PROZESSWEITEN
 * av_log-Callback, den libmpv an genau einen Kern bindet: an den, der beim Erzeugen keinen lebenden
 * Vorgänger mit dieser Bindung vorfand (mpv `init_libav`). Stirbt dieser Kern, landen FFmpeg-Zeilen auf
 * stderr — also nirgends —, bis wieder ein Kern entsteht, während keiner gebunden ist.
 *
 * Seit A1 (3.2.1) entsteht der neue Kern, während der verbrauchte noch lebt, und der alte stirbt danach.
 * Gemessen (Emulator 21.10., logcat): ab dem ersten „Instanz verbraucht“ keine einzige `[ffmpeg/…]`-Zeile
 * mehr — und damit kein HTTP-Status im Fehlerbild und keine 458/460-Erkennung in der Retry-Leiter.
 */
internal object MpvKerne {
    private val lebend = AtomicInteger(0)

    /** Ein Kern ist entstanden. true = neben einem noch lebenden Kern — FFmpeg-Log hängt dann am älteren. */
    fun geboren(): Boolean = lebend.getAndIncrement() > 0

    /** Ein Kern ist zerstört (destroy() zurückgekehrt). */
    fun gestorben() {
        while (true) {
            val n = lebend.get()
            if (n <= 0 || lebend.compareAndSet(n, n - 1)) return
        }
    }

    fun anzahl(): Int = lebend.get()

    /**
     * Soll der Kern vor dem nächsten neuen Titel neu gebaut werden, damit er die FFmpeg-Logzeilen wieder
     * bekommt? Nur wenn er neben einem anderen entstand ([ohneFfmpegLog]) und inzwischen allein lebt.
     */
    fun neuBindenFaellig(ohneFfmpegLog: Boolean, lebendeKerne: Int): Boolean = ohneFfmpegLog && lebendeKerne == 1

    internal fun zuruecksetzenFuerTest() = lebend.set(0)
}

/** German4K 3.2.1 (P2): welcher mpv-Fehlertext ins Fehlerbild kommt. */
internal object MpvFehlerGrund {
    private val GENERISCH_RX = Regex(
        "failed to open|opening failed|could not open|loading failed|was aborted|finished playback",
        RegexOption.IGNORE_CASE,
    )

    /** Trägt die Zeile einen HTTP-Status (403, 404, 458, 460, 511 …)? */
    fun hatHttpStatus(zeile: String?): Boolean = zeile != null && PlayerErrors.httpStatusIn(zeile) != null

    /**
     * [letzte] = letzte Fehlerzeile DIESES Ladeversuchs, [httpZeileDesTitels] = letzte Zeile mit HTTP-Status
     * aus irgendeinem Versuch desselben Titels. Die Leiter probiert nacheinander .ts/.m3u8, Wiederholungen
     * und den Ersatz-User-Agent; scheitert der letzte Versuch nur noch generisch („Failed to open …“), ging
     * der Grund (HTTP 403) verloren. Dann gilt die HTTP-Zeile. Ein anderer konkreter Grund (Decoder, SSL …)
     * bleibt stehen.
     */
    fun fuerAnzeige(letzte: String?, httpZeileDesTitels: String?): String? {
        if (httpZeileDesTitels == null) return letzte
        if (letzte == null) return httpZeileDesTitels
        if (hatHttpStatus(letzte)) return letzte
        return if (GENERISCH_RX.containsMatchIn(letzte)) httpZeileDesTitels else letzte
    }
}
