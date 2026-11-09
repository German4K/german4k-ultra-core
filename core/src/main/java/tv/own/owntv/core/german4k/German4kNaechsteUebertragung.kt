package tv.own.owntv.core.german4k

/**
 * German4K 3.3: „Läuft gerade nicht? Nächste Übertragung“ — die reinen Regeln (German4kNaechsteUebertragungTest).
 *
 * - Kandidaten sind die Fassungen aus `sender` eines Spiels, die im Paket nicht aus sind und streamIds haben.
 * - Gleiche Sprache wie die gescheiterte Fassung zuerst (Sprache aus der Flagge), dann die anderen; innerhalb
 *   davon eingeschaltete vor unbekannten, sonst die Reihenfolge des Servers (Eventplatz zuerst, …).
 * - Jede Fassung höchstens einmal je Sitzung ([versucht]); danach gibt es keine nächste — ehrliche Meldung.
 * - Meldung an den Server: Aktion [AKTION_PLATZ_TOT], Detail `<stream_id>|<spiel_id>|auto` bzw. `|knopf`.
 */
object German4kNaechsteUebertragung {
    const val AKTION_PLATZ_TOT = "platz_tot"
    const val ART_AUTO = "auto"
    const val ART_KNOPF = "knopf"

    /** Ohne Bild nach so langer Zeit gilt eine Hub-Fassung als gescheitert, auch wenn „Aufgeben nach“ auf Nie steht. */
    const val BILD_FRIST_MS = 35_000L

    /** Eine Hub-Sitzung gilt höchstens so lange (danach ist das Spiel vorbei; ein späterer Tune ist kein Hub-Tune). */
    const val SITZUNG_MAX_MS = 4L * 60 * 60 * 1000

    /** Kennung einer Fassung innerhalb eines Spiels — Name + Eventplatz, wie [SportViewModel.kanalFuer] sie wiederfindet. */
    fun schluessel(s: German4kSportSender): String = "${s.name}|${s.platz}"

    /** Die Fassung dieses Spiels, zu der die Anbieter-Kennung [remoteId] gehört, oder null. */
    fun fassungZu(spiel: German4kSpiel, remoteId: String?): German4kSportSender? {
        val id = remoteId?.trim()?.toLongOrNull() ?: return null
        return spiel.sender.firstOrNull { id in it.streamIds }
    }

    /** Reihenfolge der übrigen Fassungen nach [aktuell] (ohne [aktuell] selbst und ohne ausgeschaltete). */
    fun reihenfolge(sender: List<German4kSportSender>, aktuell: German4kSportSender?): List<German4kSportSender> {
        val aktuellKey = aktuell?.let { schluessel(it) }
        val sprache = aktuell?.let { senderAnzeige(it).sprache }
        return sender
            .filter { it.an != false && it.streamIds.isNotEmpty() && schluessel(it) != aktuellKey }
            .withIndex()
            .sortedWith(
                compareBy<IndexedValue<German4kSportSender>> { if (sprache != null && senderAnzeige(it.value).sprache == sprache) 0 else 1 }
                    .thenBy { if (it.value.an == true) 0 else 1 }
                    .thenBy { it.index },
            )
            .map { it.value }
    }

    /** Die nächste noch nicht versuchte Fassung oder null (alle durch). */
    fun naechste(sender: List<German4kSportSender>, aktuell: German4kSportSender?, versucht: Set<String>): German4kSportSender? =
        reihenfolge(sender, aktuell).firstOrNull { schluessel(it) !in versucht }

    fun meldungDetail(streamId: String, spielId: Long, art: String): String = "$streamId|$spielId|$art"

    /**
     * Nur wenn der Fehler am Stream liegt, hilft eine andere Fassung. Kein Netz, Leitung belegt, Zugang abgelaufen,
     * Sperren, Uhrzeit: die nächste Fassung scheitert genauso — dann das Fehlerbild wie bisher und keine Meldung.
     */
    fun darfWechseln(klasse: German4kHealth.Klasse): Boolean =
        klasse == German4kHealth.Klasse.UNBEKANNT || klasse == German4kHealth.Klasse.HOST_AUSFALL ||
            klasse == German4kHealth.Klasse.ALLES_GUT
}
