package tv.own.owntv.player

import dev.jdtech.mpv.MPVLib
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * German4K 3.2.1 (A1, p-5): der mpv-Befehlsfaden, der weiß, wie lange sein laufender Auftrag schon steckt.
 *
 * Befund 21.10. (TV-Emulator, Goldfish-Decoder): mit `hwdec=mediacodec` + `mediacodec_embed` dreht sich der mpv-Kern
 * gleich nach `file-loaded` endlos im FFmpeg-MediaCodec-Decoder (dequeueInputBuffer/-OutputBuffer, beide „später“).
 * Ab da beantwortet er keinen API-Aufruf mehr: der erste Lesezugriff (logAudioOut auf diesem Faden, queryTracks im
 * Ereignisfaden) hängt für immer, alles dahinter — stop, Barriere, Abkoppeln, destroy — läuft nie. Ein Auftrag, der
 * schon länger als [MpvDetachFrist.HAENGT_AB_MS] läuft, ist darum der Beleg „Kern hängt“: dann wartet niemand mehr
 * auf diesen Faden, weder die Engine-Übergabe noch der Main-Thread im surfaceDestroyed.
 */
internal class MpvFaden : ThreadPoolExecutor(
    1, 1, 0L, TimeUnit.MILLISECONDS, LinkedBlockingQueue(),
    { r -> Thread(r, "mpv-cmd").apply { isDaemon = true } },
) {
    @Volatile private var laeuft = false
    @Volatile private var seitNs = 0L

    override fun beforeExecute(t: Thread?, r: Runnable?) {
        seitNs = System.nanoTime()
        laeuft = true
    }

    override fun afterExecute(r: Runnable?, t: Throwable?) {
        laeuft = false
    }

    /** Wie lange der gerade laufende Auftrag schon läuft, 0 = der Faden ist frei. */
    fun laeuftSeitMs(jetztNs: Long = System.nanoTime()): Long =
        if (laeuft) TimeUnit.NANOSECONDS.toMillis(jetztNs - seitNs).coerceAtLeast(0) else 0L
}

/**
 * German4K 3.2.1 (A1, p-5): Ereignis-Tor je mpv-Kern. Der Spieler meldet sich nicht mehr selbst bei libmpv an,
 * sondern über dieses Tor. Wird ein hängender Kern verworfen, schließt das Tor ohne nativen Aufruf — vorher
 * meldete ein eigener Faden („owntv-mpv-abmelden“) den Kern ab und blieb dabei für immer stecken: `removeObserver`
 * wartet auf den Monitor, den der hängende Ereignisfaden hält (er steckt in unserem Rückruf). Ein Faden je Wechsel.
 */
internal class MpvKernTor(private val ziel: MPVLib.EventObserver) : MPVLib.EventObserver {
    @Volatile var offen = true
        private set

    fun schliessen() { offen = false }

    override fun eventProperty(property: String) { if (offen) ziel.eventProperty(property) }
    override fun eventProperty(property: String, value: Long) { if (offen) ziel.eventProperty(property, value) }
    override fun eventProperty(property: String, value: Double) { if (offen) ziel.eventProperty(property, value) }
    override fun eventProperty(property: String, value: Boolean) { if (offen) ziel.eventProperty(property, value) }
    override fun eventProperty(property: String, value: String) { if (offen) ziel.eventProperty(property, value) }
    override fun event(eventId: Int) { if (offen) ziel.event(eventId) }
}

/**
 * German4K 3.2.1 (A1, p-5): Log-Tor je mpv-Kern. Offen: alle Zeilen an [ziel]. Geschlossen (Kern verworfen): nur noch
 * FFmpegs Zeilen — die laufen prozessweit über den ältesten lebenden Kern, auch die des neuen (P2, siehe [MpvKerne]).
 */
internal class MpvKernLogTor(private val ziel: MPVLib.LogObserver) : MPVLib.LogObserver {
    @Volatile var offen = true
        private set

    fun schliessen() { offen = false }

    override fun logMessage(prefix: String, level: Int, text: String) {
        if (offen || prefix.trim().startsWith("ffmpeg", ignoreCase = true)) ziel.logMessage(prefix, level, text)
    }
}

/**
 * German4K 3.2.1 (A1, p-5): verworfene (hängende) Kerne, die noch nicht zerstört sind. Jeder bekommt genau einen
 * Abbau-Faden: `stop` (beendet Wiedergabe und Netz, sobald der Kern wieder antwortet), dann `destroy`. Hängt der Kern
 * für immer (Decoder-Verklemmung), bleibt er die eine Rest-Instanz — solange sie lebt, baut [OwnTVPlayer] neue Kerne
 * ohne den Direktweg (Software-Decoder), damit sich die Verklemmung nicht mit jedem Wechsel wiederholt.
 */
internal object MpvRestKerne {
    private val lebend = AtomicInteger(0)

    fun anzahl(): Int = lebend.get()

    /**
     * Ein hängender Kern wird verworfen: [stoppen] und [zerstoeren] laufen auf einem eigenen Faden (sie warten, bis der
     * Kern wieder antwortet — dann ist die Leitung zu), danach [gestorben]. Blockiert den Aufrufer nie.
     */
    fun abbauen(stoppen: () -> Unit, zerstoeren: () -> Unit, gestorben: () -> Unit): Thread {
        lebend.incrementAndGet()
        return Thread({
            try {
                runCatching { stoppen() }
                runCatching { zerstoeren() }
                runCatching { gestorben() }
            } finally {
                beendet()
            }
        }, "owntv-mpv-abbau").apply { isDaemon = true; start() }
    }

    private fun beendet() {
        while (true) {
            val n = lebend.get()
            if (n <= 0 || lebend.compareAndSet(n, n - 1)) return
        }
    }

    internal fun zuruecksetzenFuerTest() = lebend.set(0)
}

/**
 * German4K 3.2.1 (p-11, Schalter `kern_obergrenze`): Obergrenze für verworfene mpv-Kerne. Beim Heap-Profiling blieben
 * zwei hängende Kerne übrig — es gab keine Grenze. Jetzt: lebt schon einer ([MAX_REST]), laufen neue Kerne ohne
 * Direktweg (bestehende Sperre, Kopie/Software). Hängt trotzdem noch einer, geht die Wiedergabe VOR dem Verwerfen auf
 * ExoPlayer, und solange ein Rest lebt, baut der Player keinen weiteren mpv-Kern — es kann also kein dritter entstehen.
 */
internal object MpvKernGrenze {
    const val MAX_REST = 1

    /** Hängt der aktuelle Kern, während schon [lebend] Rest-Kerne leben: erst auf ExoPlayer ausweichen, dann verwerfen. */
    fun exoVorVerwerfen(an: Boolean, lebend: Int): Boolean = an && lebend >= MAX_REST

    /** Kein neuer mpv-Kern: die Sperre aus einem weiteren Hänger steht und mindestens ein Rest lebt noch. */
    fun mpvGesperrt(an: Boolean, sperre: Boolean, lebend: Int): Boolean = an && sperre && lebend > 0

    /** Die Sperre fällt, sobald kein Rest mehr lebt (der hängende Kern hat geantwortet und ist zerstört). */
    fun sperreLoesen(sperre: Boolean, lebend: Int): Boolean = sperre && lebend <= 0
}
