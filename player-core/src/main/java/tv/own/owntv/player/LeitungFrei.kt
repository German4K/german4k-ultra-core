package tv.own.owntv.player

import java.util.concurrent.ConcurrentHashMap

/**
 * German4K 3.2.1 (t-14, Schalter `zap_erst_schliessen`): Senderwechsel und Neuverbindung auf 1-Verbindungs-Zugängen.
 *
 * Abnahme Kandidat 11 (k11/lc-5680.txt, Mock mit 1-Verbindungs-Regel):
 * - Jeder Wechsel fragte den neuen Sender an, bevor der alte zu war → erst einmal 458, ~2 s verloren; bei trägem
 *   Anbieter blieb jede zweite Vorschau schwarz, Zappen im Vollbild öffnete sofort den Dialog „anderes Gerät“.
 * - Nach einem Hänger/Zeitsprung gab es genau EINE 458-Wiederholung je Abstimmung (zurück erst nach 60 s READY) — beim
 *   zweiten Hänger kam sofort das Fehlerbild.
 *
 * Drei Regeln, rein und testbar:
 * 1. [erstSchliessen]: bei einem 1-Verbindungs-Zugang erst die alte Live-Leitung schließen, dann anfragen.
 * 2. [warteMs]: „Leitung belegt“ (458/460) → Wiederholungen nach 1 s, 2 s, 3 s, 4 s — das Budget gilt je EREIGNIS (Zap,
 *    Hänger, Zeitsprung, erste Abstimmung), nicht je Abstimmung. Erst danach Belegt-Dialog/Fehlerbild (echte Belegung
 *    durch ein anderes Gerät bleibt so nach ~10 s erkennbar).
 *
 * German4K 3.2.1 (t-16): die je Host gelernte Freigabe-Pause (t-14/t-15) ist entfernt — sie erzeugte drei Review-Runden
 * lang neue Fehler. Es bleiben „erst schließen“ (Socket zu, höchstens [tv.own.owntv.core.network.LeitungFreigabe.FRIST_MS]),
 * der Backoff 1/2/3/4 s nach 458/460 (t-16/5) und: eine Kachel wartet nur auf die Vollbild-Leitung ([offen]).
 */
object LeitungFrei {
    /**
     * Wartezeiten vor den Wiederholungen nach „Leitung belegt“, je Ereignis.
     * German4K 3.2.1 (t-16/5): 1/2/3/4 s (kumuliert 1/3/6/10 s) statt 1,5/3/5 s — ohne gelernte Pause trifft die erste
     * Wiederholung eine Freigabe von 0–1 s, 2 s und 5 s je eine Stufe früher; Belegt-Dialog weiter nach ~10 s.
     */
    val WARTE_MS = longArrayOf(1_000L, 2_000L, 3_000L, 4_000L)

    /** So viele Wiederholungen je Ereignis. */
    val BUDGET: Int get() = WARTE_MS.size

    /** Zeit, die eine Wiederholung zum Öffnen bekommt (erstes Bild), bevor ein Wächter sie aufgibt. */
    const val OEFFNEN_MS = 8_000L

    /**
     * 1-Verbindungs-Zugang? [schonBelegt] = für den Host schon „nur eine Sitzung“ bekannt (max_connections 1 beim Abgleich
     * oder ein 458 außerhalb einer Kachel in diesem Lauf), [belegtGesehen] = auf diesem Host in diesem Lauf schon ein
     * 458/460 außerhalb einer Kachel ([belegtGesehen]).
     *
     * German4K 3.2.1 (t-15): der German4K-Host gilt NICHT mehr pauschal als 1-Verbindungs-Zugang. Unser Server meldet
     * `max_connections` 0, und Multiview läuft dort mit einem Konto und zwei Kacheln (DAZN 1+2 gemessen) — das pauschale
     * „erst schließen“ ließ jede Kachel 1,5 s warten und sperrte Live-Vorschau neben mpv und Hero-Vorschau. Ohne 458 bleibt
     * es beim Verhalten vor t-14 (F31, [LiveStreamQuirks.merktEineSitzung]).
     */
    fun einzelLeitung(schalterAn: Boolean, schonBelegt: Boolean, belegtGesehen: Boolean): Boolean =
        schalterAn && (schonBelegt || belegtGesehen)

    /** German4K 3.2.1 (t-15): [einzelLeitung] für den Host von [url] aus dem, was dieser App-Lauf gesehen hat. */
    fun einzelLeitungFuer(schalterAn: Boolean, url: String?): Boolean = url != null && einzelLeitung(
        schalterAn, LiveStreamQuirks.isSingleSession(url), belegtGesehen(url),
    )

    private val belegtHosts: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /**
     * German4K 3.2.1 (t-15): auf dem Host von [url] kam außerhalb einer Multiview-Kachel ein „Leitung belegt“ (458/460) —
     * gelernt nur für diesen App-Lauf, nie gespeichert. Kacheln über dem Konto-Limit sagen nichts über Vollbild-Wechsel.
     */
    fun belegtGesehen(url: String, kachel: Boolean) { if (!kachel) belegtHosts += LiveStreamQuirks.hostKey(url) }

    fun belegtGesehen(url: String): Boolean = LiveStreamQuirks.hostKey(url) in belegtHosts

    /** Vor der Anfrage erst die alte Live-Leitung dieses Players schließen? Aus = altes Verhalten (paralleles Umschalten). */
    fun erstSchliessen(schalterAn: Boolean, einzelLeitung: Boolean, alterStromOffen: Boolean): Boolean =
        schalterAn && einzelLeitung && alterStromOffen

    /** Wartezeit vor der Wiederholung Nr. [schonWiederholt] + 1 dieses Ereignisses; null = Budget aufgebraucht. */
    fun warteMs(schonWiederholt: Int): Long? = WARTE_MS.getOrNull(schonWiederholt)

    /** Was nach „Leitung belegt“ passiert. */
    enum class Nach {
        /** Schalter aus oder kein „belegt“: der bisherige Weg entscheidet. */
        ALT,
        /** Nach [warteMs] dieselbe Adresse noch einmal. */
        WIEDERHOLEN,
        /** Budget dieses Ereignisses verbraucht → Belegt-Dialog / Fehlerbild. */
        AUFGEBEN,
        /** Kunde hat weggezappt oder die App ist im Hintergrund → nichts mehr anfragen. */
        LASSEN,
    }

    fun nachBelegt(
        schalterAn: Boolean,
        status: Int?,
        belegt: (Int) -> Boolean,
        schonWiederholt: Int,
        nochGewaehlt: Boolean,
        imVordergrund: Boolean,
    ): Nach {
        if (!schalterAn || status == null || !belegt(status)) return Nach.ALT
        if (!nochGewaehlt || !imVordergrund) return Nach.LASSEN
        return if (warteMs(schonWiederholt) != null) Nach.WIEDERHOLEN else Nach.AUFGEBEN
    }

    /**
     * Wie lange ein Wächter (Hänger, Öffnen, Multiview-Kachel) über seine Frist hinaus noch wartet: solange eine
     * Wiederholung nach „Leitung belegt“ geplant ist ([seitMs] > 0 = Zeitpunkt der Planung, [warteMs] ihre Wartezeit)
     * bzw. gerade öffnet.
     */
    fun waechterNachfristMs(schalterAn: Boolean, seitMs: Long, warteMs: Long, jetztMs: Long): Long {
        if (!schalterAn || seitMs <= 0L) return 0L
        val voll = warteMs.coerceAtLeast(0L) + OEFFNEN_MS
        return (seitMs + voll - jetztMs).coerceIn(0L, voll)
    }

    private val offen = ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicInteger>()
    private val offenVollbild = ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicInteger>() // German4K 3.2.1 (t-15)

    /** Ein Live-Strom zum Host von [url] wurde angenommen (2xx) — von irgendeinem Engine; [kachel] = Multiview-Kachel. */
    fun auf(url: String, kachel: Boolean = false) {
        val k = LiveStreamQuirks.hostKey(url)
        offen.getOrPut(k) { java.util.concurrent.atomic.AtomicInteger() }.incrementAndGet()
        if (!kachel) offenVollbild.getOrPut(k) { java.util.concurrent.atomic.AtomicInteger() }.incrementAndGet()
    }

    /**
     * Angenommene, noch offene Live-Ströme zum Host von [url] über alle Engines (Vorschau, Vollbild, Kacheln).
     * German4K 3.2.1 (t-15): [ohneKacheln] = nur die der Vollbild-/Vorschau-Engine — eine Kachel wartet nur auf die
     * Vollbild-Leitung, nicht auf ihre Nachbar-Kacheln (die laufen auf einem Mehrfach-Konto gleichzeitig).
     */
    fun offen(url: String, ohneKacheln: Boolean = false): Int =
        (if (ohneKacheln) offenVollbild else offen)[LiveStreamQuirks.hostKey(url)]?.get()?.coerceAtLeast(0) ?: 0

    /** Ein angenommener Live-Strom zum Host von [url] wurde (von irgendeinem Engine) geschlossen. */
    fun zu(url: String, kachel: Boolean = false) {
        val k = LiveStreamQuirks.hostKey(url)
        offen[k]?.let { if (it.decrementAndGet() < 0) it.set(0) }
        if (!kachel) offenVollbild[k]?.let { if (it.decrementAndGet() < 0) it.set(0) }
    }

    /** Nur für Tests. */
    internal fun vergessen() { offen.clear(); offenVollbild.clear(); belegtHosts.clear() }

    /**
     * German4K 3.2.1 (t-15): Wahl-Marke für Senderwahlen mit Wartepunkten (mpv freigeben, Leitung zu). Jede neue
     * Wahl macht alle älteren ungültig — ein Start, der nach dem Warten aufwacht, prüft [gilt] und lässt den Sender
     * fallen, wenn der Kunde inzwischen etwas anderes gewählt hat (sonst spielte B und danach A).
     */
    class Wahl {
        @Volatile private var aktuell = 0L
        @Synchronized fun neu(): Long = ++aktuell
        fun gilt(marke: Long): Boolean = marke == aktuell
    }
}
