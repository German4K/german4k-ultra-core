package tv.own.owntv.player

/**
 * German4K 3.2.1 (A1, p-6): früher Wächter für den Hardware-Decoder von mpv (Schalter `mpv_hw_waechter`).
 *
 * Befund 21.10. (TV-Emulator): mit `hwdec=mediacodec` + `mediacodec_embed` dreht sich der mpv-Kern gleich nach
 * `file-loaded` endlos im FFmpeg-MediaCodec-Decoder und beantwortet keinen API-Aufruf mehr — schwarzes Bild, bis der
 * Kunde aufgibt. Der Wächter läuft ab dem Laden im Takt [TAKT_MS] (Main-Looper, liest nie selbst nativ: ein Lesezugriff
 * läuft als Probe auf dem mpv-Faden, der Wächter schaut nur, ob und wann sie zurückkommt) und urteilt hier, rein und
 * unit-getestet:
 *
 * - [Urteil.HAENGT]: nach `file-loaded` steckt der mpv-Faden ≥ [HAENGT_MS] in einem Auftrag — der Kern antwortet nicht.
 * - [Urteil.KEIN_BILD]: der Kern antwortet, der Hardware-Decoder ist aktiv, seit `file-loaded` ≥ [BILD_FRIST_MS]
 *   kein einziges Bild (Höhe 0), obwohl Daten da sind: Zeit/Ton laufen ([ZEIT_LAEUFT_MS]) oder der Puffer hält schon
 *   ≥ [PUFFER_VOLL_MS] (mpv startet den Ton erst mit dem ersten Bild — dann steht die Zeit, der Puffer füllt sich).
 * - [Urteil.FERTIG]: ein Bild ist da (Höhe > 0) — Wächter beendet. Ebenso nach [MAX_MS] ohne Befund.
 *
 * Nicht sein Fall (bleibt bei den vorhandenen Wächtern): Öffnen dauert (kein `file-loaded`), Netz stockt (Zeit steht,
 * Kern antwortet), Decoder gar nicht erst angesprungen (`hwdec-current` leer/„no“ → Decode-Check), reine Tonspur.
 */
internal object MpvHwWaechter {
    /** Ab hier gilt ein steckender mpv-Auftrag nach `file-loaded` als Hänger. Normale Lesezugriffe: Millisekunden. */
    const val HAENGT_MS = 2_000L
    /** So lange nach `file-loaded` darf das erste Bild bei laufender Zeit dauern (Live-Einstieg mitten in der GOP). */
    const val BILD_FRIST_MS = 4_000L
    /** Mindestens so viel Medienzeit muss seit `file-loaded` gelaufen sein, damit „Ton läuft“ gilt. */
    const val ZEIT_LAEUFT_MS = 1_500L
    /** So viel gepufferte Medienzeit heißt „das Netz liefert“ — fehlt dann das Bild, liegt es am Decoder. */
    const val PUFFER_VOLL_MS = 2_000L
    const val TAKT_MS = 500L
    /** Danach gibt der Wächter auf (die übrigen Wächter übernehmen). */
    const val MAX_MS = 20_000L

    enum class Urteil { WEITER, FERTIG, HAENGT, KEIN_BILD }

    /** Ergebnis einer Probe auf dem mpv-Faden. [zeitMs] = `time-pos`, [pufferMs] = `demuxer-cache-duration` (null = unbekannt). */
    data class Probe(
        val hoehe: Int,
        val zeitMs: Long?,
        val hwdec: String?,
        val videoSpur: Boolean,
        val pufferMs: Long? = null,
    )

    /**
     * @param seitLadenMs seit dem Laden (Wächterstart)
     * @param seitFileLoadedMs seit `file-loaded`, null = noch nicht geladen
     * @param stecktMs wie lange der laufende Auftrag des mpv-Fadens schon steckt (0 = frei)
     * @param probe jüngste zurückgekommene Probe nach `file-loaded` (null = keine)
     * @param zeitBeimLadenMs erste nach `file-loaded` gelesene `time-pos` (Bezug für „Zeit läuft“)
     * @param tonModus Kunde hat bewusst nur Ton (Audio-Modus) — kein Bild zu erwarten
     */
    fun urteil(
        seitLadenMs: Long,
        seitFileLoadedMs: Long?,
        stecktMs: Long,
        probe: Probe?,
        zeitBeimLadenMs: Long?,
        tonModus: Boolean,
    ): Urteil {
        if (probe != null && probe.hoehe > 0) return Urteil.FERTIG
        if (seitFileLoadedMs != null && stecktMs >= HAENGT_MS) return Urteil.HAENGT
        if (seitLadenMs >= MAX_MS) return Urteil.FERTIG
        if (seitFileLoadedMs == null || probe == null) return Urteil.WEITER
        if (tonModus || !probe.videoSpur) return Urteil.FERTIG
        val hwAktiv = !probe.hwdec.isNullOrEmpty() && probe.hwdec != "no"
        if (!hwAktiv) return Urteil.WEITER // Decoder (noch) nicht angesprungen: Sache des Decode-Checks
        if (seitFileLoadedMs < BILD_FRIST_MS) return Urteil.WEITER
        val t = probe.zeitMs
        val zeitLaeuft = t != null && zeitBeimLadenMs != null && t - zeitBeimLadenMs >= ZEIT_LAEUFT_MS
        val pufferVoll = (probe.pufferMs ?: 0L) >= PUFFER_VOLL_MS
        return if (zeitLaeuft || pufferVoll) Urteil.KEIN_BILD else Urteil.WEITER
    }

    /**
     * German4K 3.2.1 (A1, p-9): was der Wächter mit seinem Befund tut. Software nur dort, wo sie trägt — wie
     * `canTrySoftwareRescue()`: GL vorhanden (Software zeichnet über `vo=gpu`) und Quelle ≤ 1080p. Die Höhe kommt aus
     * `demux-h` (bei `file-loaded` bekannt; `height` bleibt bis zum ersten Bild 0), 0 = unbekannt = nicht belegt.
     * Vorher heilte KEIN_BILD immer auf Software: 4K-Live mit langsamem erstem Bild (Realtek-Aushandlung) und vollem
     * Puffer endete nach 4 s im Decode-Guard (Abbruch mit Meldung statt etwas später Bild), ohne GL blieb es schwarz.
     *
     * - [Heilung.SOFTWARE]: gleicher Titel im Software-Decoder (bisheriger Weg).
     * - [Heilung.EXO_VOD]: Film/Serie über 1080p bzw. ohne GL → gleich ExoPlayer.
     * - [Heilung.WEITER]: Live, Kern antwortet → weiter warten (spätes Bild; sonst übernehmen Leiter/Wächter).
     * - [Heilung.NEU_OHNE_DIREKTWEG]: Live, Kern hängt → verwerfen und neu laden, ohne Software zu erzwingen; der neue
     *   Kern baut wegen der Rest-Instanz ohne Direktweg ([MpvRenderWeg]: mit GL `mediacodec-copy`).
     */
    enum class Heilung { SOFTWARE, EXO_VOD, WEITER, NEU_OHNE_DIREKTWEG }

    fun heilung(haengt: Boolean, live: Boolean, glOk: Boolean, quellHoehe: Int): Heilung = when {
        glOk && quellHoehe in 1..1080 -> Heilung.SOFTWARE
        !live -> Heilung.EXO_VOD
        haengt -> Heilung.NEU_OHNE_DIREKTWEG
        else -> Heilung.WEITER
    }

    /** VOD setzt nach der Heilung dort fort, wo er stand (bzw. wohin er springen sollte); Live/Archiv von vorn. */
    fun fortsetzenBei(live: Boolean, archiv: Boolean, positionMs: Long, geplantMs: Long): Long = when {
        live || archiv -> 0L
        positionMs > 0L -> positionMs
        else -> geplantMs.coerceAtLeast(0L)
    }
}
