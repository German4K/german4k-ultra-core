package tv.own.owntv.core.german4k

/**
 * German4K 3.3.1 (B4, Schalter `vod_bremse`): Wiederholbremse für Filme und Folgen.
 *
 * Feldfall Amin (Fire TV 4K Max, 04.11.): jeder Filmstart scheiterte am Hardware-Decoder, der Player wechselte die
 * Engine und lud den Film alle 10–20 s neu — ohne Ende und ohne einen Satz an den Kunden. Hier wird gezählt: jedes
 * automatische Neuladen, jeder Engine-Wechsel und jede Rettungsstufe desselben Titels ist ein Fehlschlag. Sind es
 * [GRENZE] innerhalb von [FENSTER_MS], hält der Player an und zeigt einen Hinweis statt weiter neu zu laden.
 *
 * Zählt nicht: Live (eigene Wächter), Überlast 502/503/504 (eigener stiller Neuversuch `ueberlast_neuversuch`) und
 * „Leitung belegt“ (eigener Ablauf mit „Leitung freigeben“). Ein manueller Neuversuch des Kunden setzt alles zurück.
 *
 * Reiner Zähler ohne Android-Abhängigkeit; die Zeit kommt vom Aufrufer (elapsedRealtime), damit JVM-Tests sie setzen.
 */
object German4kVodBremse {
    /** So viele Fehlschläge desselben Titels im Fenster → bremsen. */
    const val GRENZE = 3

    /** Zeitfenster für [GRENZE]. */
    const val FENSTER_MS = 120_000L

    /** Überlast: hat den eigenen Neuversuch (German4kUeberlast) — darf hier nie mitzählen. */
    val UEBERLAST: Set<Int> = setOf(502, 503, 504)

    /** Titel → Zeitpunkte seiner Fehlschläge (älteste zuerst). Klein: nur Titel mit Fehlschlägen im Fenster. */
    private val fehlschlaege = HashMap<String, ArrayDeque<Long>>()

    /** Zählt dieser Fehlschlag für die Bremse? Live nie, Überlast nie, „Leitung belegt“ nie. */
    fun zaehlt(live: Boolean, httpStatus: Int?, belegt: Boolean): Boolean =
        !live && !belegt && httpStatus !in UEBERLAST

    /**
     * Schlüssel des Titels: die Inhaltskennung (Film-/Folgen-ID), sonst die URL ohne Schema und Host — ein
     * Hostwechsel (Multi-DNS) macht aus demselben Film keinen neuen.
     */
    fun schluessel(contentKey: String?, url: String): String =
        contentKey?.takeIf { it.isNotBlank() } ?: url.substringAfter("://").substringAfter('/')

    /**
     * Ein Fehlschlag von [titel] um [jetzt]. true = es sind jetzt [GRENZE] oder mehr im Fenster, der Aufrufer
     * bremst (lädt NICHT neu, hält an, zeigt den Hinweis).
     */
    @Synchronized
    fun fehlschlag(titel: String, jetzt: Long): Boolean {
        aufraeumen(jetzt)
        val zeiten = fehlschlaege.getOrPut(titel) { ArrayDeque() }
        zeiten.addLast(jetzt)
        return zeiten.size >= GRENZE
    }

    /** Wie viele Fehlschläge von [titel] liegen im Fenster? (Diagnose/Tests) */
    @Synchronized
    fun anzahl(titel: String, jetzt: Long): Int {
        aufraeumen(jetzt)
        return fehlschlaege[titel]?.size ?: 0
    }

    /** Manueller Neuversuch / Engine-Wahl des Kunden: alles vergessen. */
    @Synchronized
    fun zuruecksetzen() {
        fehlschlaege.clear()
    }

    private fun aufraeumen(jetzt: Long) {
        val it = fehlschlaege.values.iterator()
        while (it.hasNext()) {
            val zeiten = it.next()
            while (zeiten.isNotEmpty() && jetzt - zeiten.first() >= FENSTER_MS) zeiten.removeFirst()
            if (zeiten.isEmpty()) it.remove()
        }
    }
}
