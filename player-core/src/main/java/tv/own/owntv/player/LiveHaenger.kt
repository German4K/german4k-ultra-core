package tv.own.owntv.player

/**
 * German4K 3.2.1 (p-17, Schalter `haenger_sauber` / `live_zeitsprung`): Live-Hänger auf 1-Verbindungs-Zugängen.
 *
 * Abnahme Kandidat 10 (k10/tv-lc3.txt): Sender hängt (Zeitstempelsprung im Strom), das Engine verbindet nach ~13,5 s neu,
 * während die alte Verbindung noch offen ist → 458. Das Engine plante den nächsten Neuversuch (+3 s), der Wächter gab bei
 * 15 s auf („Wiedergabefehler“) — und 1 s später lief der geplante Neuversuch doch noch: Ton hinter dem Fehlerbild, die
 * Leitung belegt. Drei Regeln, rein und testbar:
 *
 * 1. Vor jedem Neuversuch die alte Verbindung schließen ([vorherSchliessen]).
 * 2. „Leitung belegt“ auf einen Neuversuch nach dem Hänger → genau EINE Wiederholung nach [LEITUNG_WARTE_MS]
 *    ([nachBelegt]); der Wächter wartet diese eine Wiederholung ab ([waechterNachfristMs]).
 * 3. Ein großer Zeitsprung im Strom ist kein Netzproblem → sofort sauber neu verbinden ([istZeitsprung]).
 */
object LiveHaenger {
    /** Wartezeit vor der einen Wiederholung nach „Leitung belegt“ — der Server gibt die alte Sitzung frei. */
    const val LEITUNG_WARTE_MS = 4_000L

    /** Ab diesem Sprung (µs) im Ton-Zeitstempel gilt der Strom als neu begonnen (Schleife, Encoder-Neustart). */
    const val ZEITSPRUNG_US = 10_000_000L

    /** Was nach „Leitung belegt“ auf einen Neuversuch passiert. */
    enum class Belegt {
        /** Altes Verhalten: die Neuverbindungs-Leiter des Engines läuft weiter. */
        LEITER,
        /** Einmal nach [LEITUNG_WARTE_MS] wiederholen. */
        WIEDERHOLEN,
        /** Die eine Wiederholung ist verbraucht → endgültiger Fehler, kein weiterer Versuch. */
        AUFGEBEN,
    }

    fun vorherSchliessen(schalterAn: Boolean): Boolean = schalterAn

    /**
     * [status] = HTTP-Status des gescheiterten Neuversuchs, [belegt] = „Leitung belegt“-Prüfung (458/460 bzw.
     * konfig.leitung_codes), [hatGespielt] = der Sender lief in dieser Abstimmung schon, [schonWiederholt] = die eine
     * Wiederholung ist verbraucht.
     */
    fun nachBelegt(
        schalterAn: Boolean,
        status: Int?,
        belegt: (Int) -> Boolean,
        hatGespielt: Boolean,
        schonWiederholt: Boolean,
    ): Belegt {
        if (!schalterAn || !hatGespielt || status == null || !belegt(status)) return Belegt.LEITER
        return if (schonWiederholt) Belegt.AUFGEBEN else Belegt.WIEDERHOLEN
    }

    /**
     * Wie lange der Wächter über seine Hänger-Frist hinaus noch wartet: nur solange die eine Wiederholung nach
     * „Leitung belegt“ geplant ist bzw. gerade öffnet ([wiederholungSeitMs] > 0 = Zeitpunkt der Planung).
     */
    fun waechterNachfristMs(schalterAn: Boolean, wiederholungSeitMs: Long, jetztMs: Long): Long {
        if (!schalterAn || wiederholungSeitMs <= 0L) return 0L
        val rest = wiederholungSeitMs + LEITUNG_WARTE_MS + OEFFNEN_MS - jetztMs
        return rest.coerceIn(0L, LEITUNG_WARTE_MS + OEFFNEN_MS)
    }

    /** Zeit, die die Wiederholung zum Öffnen bekommt (erstes Bild nach dem Neuaufbau). */
    const val OEFFNEN_MS = 8_000L

    /**
     * German4K 3.2.1 (t-12): nur ein RÜCKWÄRTS-Sprung ≥ [ZEITSPRUNG_US] (Schleife, Encoder-Neustart) ist ein Kandidat.
     * Vorwärtssprünge (Werbeeinschnitt, Sky/DAZN-Restream) gleicht Media3 selbst aus — das Bild läuft weiter. Wirkt nur
     * mit `haenger_sauber` zusammen: ohne diesen Schalter schließt [vorherSchliessen] die alte Verbindung nicht, und ein
     * Neuversuch öffnete auf 1-Verbindungs-Zugängen eine zweite (458).
     */
    fun istZeitsprung(schalterAn: Boolean, haengerSauberAn: Boolean, erwartetUs: Long, tatsaechlichUs: Long): Boolean =
        schalterAn && haengerSauberAn && erwartetUs - tatsaechlichUs >= ZEITSPRUNG_US

    /** German4K 3.2.1 (t-12): so lange nach dem Rückwärtssprung wird geprüft, ob die Wiedergabe wirklich hängt. */
    const val ZEITSPRUNG_PRUEF_MS = 3_000L

    /** German4K 3.2.1 (t-12): weniger Fortschritt als das in [ZEITSPRUNG_PRUEF_MS] = das Bild steht. */
    const val ZEITSPRUNG_FORTSCHRITT_MS = 1_000L

    /** German4K 3.2.1 (t-12): höchstens so viele Zeitsprung-Neuverbindungen je Abstimmung im [ZEITSPRUNG_FENSTER_MS]. */
    const val ZEITSPRUNG_BUDGET = 2
    const val ZEITSPRUNG_FENSTER_MS = 10 * 60_000L

    // German4K 3.2.1 (t-13): Fortschritt nach dem Sprung über gerenderte Frames, nicht über die Position. Media3 setzt
    // die Position nach einer Diskontinuität ggf. selbst zurück; wurde sie vorher gelesen, ergab sich z. B. −7 s und
    // ein laufendes Bild galt als Hänger.

    /** German4K 3.2.1 (t-13): weniger gerenderte Frames als das in [ZEITSPRUNG_PRUEF_MS] = das Bild steht. */
    const val ZEITSPRUNG_MIN_FRAMES = 10

    /**
     * German4K 3.2.1 (t-13): so viele Frames muss auch die letzte Sekunde der Prüfung ([ZEITSPRUNG_SPAET_PROBEN] Proben)
     * bringen. Gemessen am Emulator (t-13): nach dem Rücksprung rendert ExoPlayer noch ~0,6 s die schon dekodierten Bilder
     * (15 Frames), dann steht es in BUFFERING — die Summe allein hielt das für „läuft“.
     */
    const val ZEITSPRUNG_MIN_FRAMES_SPAET = 3
    const val ZEITSPRUNG_SPAET_PROBEN = 4

    /** German4K 3.2.1 (t-13): Abtastabstand während der Prüfung (Frames, Position). */
    const val ZEITSPRUNG_PROBE_MS = 250L

    /** German4K 3.2.1 (t-13): größter Positionsfortschritt, der je Abtastung zählt — ein Vorwärtssprung ist kein Spielen. */
    const val ZEITSPRUNG_PROBE_MAX_MS = 1_000L

    /**
     * German4K 3.2.1 (t-13): Fortschritt seit dem Sprung, abgetastet alle [ZEITSPRUNG_PROBE_MS].
     * - Frames: Summe der Zuwächse von `DecoderCounters.renderedOutputBufferCount`; fällt der Zähler (neuer Decoder)
     *   oder war er weg, zählt der neue Stand ganz. `null` = kein Video-Decoder (Radio).
     * - Position (nur ohne Video): Summe der Vorwärtsschritte; geht sie zurück, ist das der neue Bezugspunkt
     *   (Rücksprung-Korrektur), nie ein negativer Fortschritt.
     */
    class ZeitsprungFortschritt(posMs: Long, frames: Int?) {
        private var letztePosMs = posMs
        private var letzteFrames: Int? = frames
        var frames = 0; private set
        private val letzteZuwaechse = ArrayDeque<Int>()
        /** Frames der letzten [ZEITSPRUNG_SPAET_PROBEN] Proben (≈ letzte Sekunde). */
        val framesSpaet: Int get() = letzteZuwaechse.sum()
        var hatVideo = frames != null; private set
        var fortschrittMs = 0L; private set
        /** Position ging während der Prüfung zurück (Media3 hat nach der Diskontinuität nachgezogen). */
        var posRueckMs = 0L; private set

        fun probe(posMs: Long, framesJetzt: Int?) {
            val d = posMs - letztePosMs
            if (d < 0) posRueckMs = minOf(posRueckMs, d) else fortschrittMs += minOf(d, ZEITSPRUNG_PROBE_MAX_MS)
            letztePosMs = posMs
            if (framesJetzt != null) {
                hatVideo = true
                val vorher = letzteFrames
                val zuwachs = if (vorher == null || framesJetzt < vorher) framesJetzt else framesJetzt - vorher
                frames += zuwachs
                letzteZuwaechse.addLast(zuwachs)
            } else {
                letzteZuwaechse.addLast(0)
            }
            while (letzteZuwaechse.size > ZEITSPRUNG_SPAET_PROBEN) letzteZuwaechse.removeFirst()
            letzteFrames = framesJetzt
        }
    }

    /**
     * German4K 3.2.1 (t-12, t-13): hängt die Wiedergabe [ZEITSPRUNG_PRUEF_MS] nach dem Sprung? Mit Video: weniger als
     * [ZEITSPRUNG_MIN_FRAMES] gerenderte Frames, oder in der letzten Sekunde weniger als [ZEITSPRUNG_MIN_FRAMES_SPAET]. Ohne Video (Radio): Puffern oder weniger als [ZEITSPRUNG_FORTSCHRITT_MS]
     * Positionsfortschritt. Pausiert ([spielenGewollt] = false) ist nie ein Hänger.
     */
    fun haengtNachSprung(spielenGewollt: Boolean, puffert: Boolean, f: ZeitsprungFortschritt): Boolean {
        if (!spielenGewollt) return false
        if (f.hatVideo) return f.frames < ZEITSPRUNG_MIN_FRAMES || f.framesSpaet < ZEITSPRUNG_MIN_FRAMES_SPAET
        return puffert || f.fortschrittMs < ZEITSPRUNG_FORTSCHRITT_MS
    }

    /** German4K 3.2.1 (t-12): noch Budget für eine Zeitsprung-Neuverbindung? Sonst altes Verhalten (Hänger-Wächter). */
    fun zeitsprungBudgetFrei(verbrauchtMs: List<Long>, jetztMs: Long): Boolean =
        verbrauchtMs.count { jetztMs - it in 0 until ZEITSPRUNG_FENSTER_MS } < ZEITSPRUNG_BUDGET

    /**
     * German4K 3.2.1 (t-12): die Wiedergabe hält wieder (healthyReset) — die eine Wiederholung nach „Leitung belegt“
     * steht einem späteren, neuen Hänger wieder zu. Bisher galt sie nur bis zum nächsten play().
     */
    internal fun nachGesund(tune: TuneState) {
        tune.haengerLeitungWiederholt = false
        tune.haengerLeitungSeitMs = 0L
    }
}
