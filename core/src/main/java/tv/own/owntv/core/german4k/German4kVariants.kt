package tv.own.owntv.core.german4k

import java.util.Locale

/**
 * Derselbe Sender in einer anderen Qualität.
 *
 * Warum das hier steht: Von 16.172 Sendern tragen fast alle ein Qualitätskürzel am Namensende, und
 * **1.958 Basisnamen gibt es in mehreren Fassungen** (gemessen 19.09.2026 an der Kundenliste). Wenn
 * eine davon klemmt — der Anbieter hat gerade Aussetzer, die Bandbreite reicht nicht, oder das Gerät
 * kann den Codec nicht —, ist die nächstkleinere Fassung fast immer da. Bisher endete so ein Fall im
 * Fehlerbildschirm, obwohl zwei Zeilen weiter unten in der Liste dasselbe Programm lief.
 *
 * Besonders hart trifft es UHD: 363 Sender liegen nur in dieser Fassung vor, und ein Fire TV Stick
 * ohne 4K-Decoder scheitert daran jedes Mal — zuverlässig, dauerhaft, und für den Kunden sieht es aus,
 * als wäre der Sender kaputt.
 */
object German4kVariants {

    /** Qualitätsstufen, wie sie in den Namen stehen. Höher ist besser. */
    enum class Stufe(val rang: Int) {
        UHD(4), FHD(3), HD(2), SD(1), UNBEKANNT(0);

        /** Braucht diese Stufe einen 4K-fähigen Decoder? */
        val brauchtVierK: Boolean get() = this == UHD
    }

    data class Variante(val id: Long, val name: String, val stufe: Stufe)

    // "RTL UHD", "DAS ERSTE FHD", "SAT.1 SD" — das Kürzel steht als eigenes Wort, meist am Ende.
    private val KUERZEL = Regex("""\b(UHD|4K|FHD|HD|SD)\b""", RegexOption.IGNORE_CASE)

    /** Der Name ohne Qualitätskürzel und ohne doppelte Leerzeichen — die Klammer, die Geschwister eint. */
    fun basis(name: String): String =
        KUERZEL.replace(name.uppercase(Locale.ROOT), " ").replace(Regex("""\s+"""), " ").trim()

    fun stufe(name: String): Stufe {
        val treffer = KUERZEL.find(name.uppercase(Locale.ROOT))?.value?.uppercase(Locale.ROOT) ?: return Stufe.UNBEKANNT
        return when (treffer) {
            "UHD", "4K" -> Stufe.UHD
            "FHD" -> Stufe.FHD
            "HD" -> Stufe.HD
            "SD" -> Stufe.SD
            else -> Stufe.UNBEKANNT
        }
    }

    /** Gehören die beiden Namen zum selben Programm? */
    fun gleicheFamilie(a: String, b: String): Boolean = basis(a).isNotEmpty() && basis(a) == basis(b)

    /**
     * Die Ausweichfassungen für [aktuell], beste zuerst.
     *
     * Regeln, jede aus einem echten Fall:
     * - Nur Geschwister derselben Familie, nie ein anderes Programm.
     * - Die laufende Fassung fällt raus, ebenso jede gleichnamige Dublette (die Liste führt denselben
     *   Sender in mehreren Kategorien; ein Ausweich auf sich selbst wäre eine Endlosschleife).
     * - Ohne 4K-Decoder fliegt UHD ganz raus. Es hilft niemandem, eine Fassung anzubieten, an der das
     *   Gerät gerade erst gescheitert ist.
     * - Absteigend nach Qualität: Wer FHD nicht bekommt, nimmt HD, dann SD. Unbekannte Stufen zuletzt —
     *   sie könnten alles sein.
     */
    fun ausweich(aktuell: String, kandidaten: List<Variante>, vierKMoeglich: Boolean): List<Variante> {
        val familie = basis(aktuell)
        if (familie.isEmpty()) return emptyList()
        val jetzt = stufe(aktuell)
        return kandidaten
            .asSequence()
            .filter { basis(it.name) == familie }
            .filter { it.name.uppercase(Locale.ROOT) != aktuell.uppercase(Locale.ROOT) }
            .filter { it.stufe != jetzt }
            .filter { vierKMoeglich || !it.stufe.brauchtVierK }
            .distinctBy { it.stufe }
            .sortedByDescending { it.stufe.rang }
            .toList()
    }

    /**
     * Was die App **statt** [aktuell] starten sollte, bevor es überhaupt losgeht.
     *
     * Der Fall: ein UHD-Sender auf einem Gerät ohne 4K-Decoder. Erst zu scheitern und dann zu wechseln
     * kostet den Kunden zehn Sekunden schwarzes Bild — und das bei jedem einzelnen Versuch, denn das
     * Gerät wird morgen nicht plötzlich 4K können.
     */
    fun besserGleichSo(aktuell: String, kandidaten: List<Variante>, vierKMoeglich: Boolean): Variante? {
        if (vierKMoeglich || !stufe(aktuell).brauchtVierK) return null
        return ausweich(aktuell, kandidaten, vierKMoeglich = false).firstOrNull()
    }
}
