package tv.own.owntv.core.german4k

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/**
 * German4K 3.0/32 (I2): Wie ein Film- oder Serientitel angezeigt wird.
 *
 * Die Liste schreibt Anbieter- und Sprachvorsätze in den Namen („DE - Blade Runner 2049",
 * „NF - Death Race 2050", „4K-AR - Dune"). Gespeichert bleibt der Name, wie er kommt — gezeigt wird
 * der Titel ohne Vorsatz, der Vorsatz wandert als kleines Kürzel aufs Plakat ([kuerzel]).
 */
data class German4kTitelAnzeige(val titel: String, val kuerzel: String?)

object German4kTitel {

    // "DE - …", "4K-AR - …", "4K-A+ - …": ein bis drei Großbuchstaben/Ziffern/+, optional mit
    // Bindestrich-Zusatz, dann " - ". Der Kopf braucht mindestens zwei Zeichen und einen Buchstaben —
    // "M - Eine Stadt sucht einen Mörder" und "24 - …" sind Titel, keine Vorsätze.
    private val VORSATZ = Regex("""^\s*([A-Z0-9+]{1,3}(?:-[A-Z0-9+]{1,4})?)\s+-\s+""")
    private val JAHR_AM_ENDE = Regex("""\s*[(\[]((?:19|20)\d{2})[)\]]\s*$""")
    private val LEERRAUM = Regex("""\s+""")

    /** Titel ohne Vorsatz plus Kürzel (Teil vor dem Bindestrich: "4K-AR" → "4K"). */
    fun anzeige(name: String): German4kTitelAnzeige {
        val roh = name.trim()
        val treffer = VORSATZ.find(name) ?: return German4kTitelAnzeige(roh, null)
        val kopf = treffer.groupValues[1].substringBefore('-')
        if (kopf.length < 2 || kopf.none { it.isLetter() }) return German4kTitelAnzeige(roh, null)
        val rest = name.substring(treffer.range.last + 1).trim()
        if (rest.isEmpty()) return German4kTitelAnzeige(roh, null)
        return German4kTitelAnzeige(rest, kopf)
    }

    /** Nur der Titel — für Stellen ohne Platz für ein Kürzel (Detailseite, Suchzeile). */
    fun titel(name: String): String = anzeige(name).titel

    /** Vergleichsform: ohne Vorsatz, ohne Jahr in Klammern am Ende, klein, Leerraum vereinheitlicht. */
    fun normalform(name: String): String =
        JAHR_AM_ENDE.replace(anzeige(name).titel, "").lowercase(Locale.ROOT).replace(LEERRAUM, " ").trim()

    /** Jahr aus "(2017)" am Titelende, falls der Eintrag selbst keins trägt. */
    fun jahrAusTitel(name: String): Int? =
        JAHR_AM_ENDE.find(anzeige(name).titel)?.groupValues?.get(1)?.toIntOrNull()

    /**
     * Welche Fassung ein Klick auf eine gebündelte Kachel öffnet: deutsch zuerst, dann 4K, dann die
     * erste. [kandidaten] in Listenreihenfolge (id → Name).
     */
    fun bevorzugt(kandidaten: List<Pair<Long, String>>): Long? {
        if (kandidaten.isEmpty()) return null
        kandidaten.firstOrNull { anzeige(it.second).kuerzel == KUERZEL_DE }?.let { return it.first }
        kandidaten.firstOrNull { istVierK(it.second) }?.let { return it.first }
        return kandidaten.first().first
    }

    private fun istVierK(name: String): Boolean {
        val gross = name.uppercase(Locale.ROOT)
        return anzeige(name).kuerzel == KUERZEL_4K || VIERK.containsMatchIn(gross)
    }

    private val VIERK = Regex("""\b(4K|UHD)\b""")
    private const val KUERZEL_DE = "DE"
    private const val KUERZEL_4K = "4K"
}

/**
 * German4K 3.0/32 (I2): Doppelte Titel in einem Raster zu einer Kachel bündeln.
 *
 * Läuft als zustandsbehafteter Filter über die Seiten EINER Paging-Generation (je Kategorie, Suche
 * und Sortierung ein neues Objekt). Die zuerst geladene Fassung eines Titels bleibt stehen und ist
 * der Vertreter; jede weitere wird ausgeblendet und zählt nur mit. Wird eine Seite verworfen und neu
 * geladen, bleibt jede Fassung bei ihrer Rolle — ein Vertreter ist nie „Dublette seiner selbst".
 *
 * Gleich heißt: gleiche [German4kTitel.normalform] und, wo beide ein Jahr kennen, gleiches Jahr.
 */
class German4kFassungsGruppen {
    private class Gruppe(val vertreter: Long, var jahr: Int?, val mitglieder: LinkedHashMap<Long, String>)

    private val nachTitel = HashMap<String, MutableList<Gruppe>>()
    private val nachId = HashMap<Long, Gruppe>()
    private val _anzahl = MutableStateFlow<Map<Long, Int>>(emptyMap())

    /** Vertreter-id → Zahl der Fassungen, nur für Gruppen mit mehr als einer. */
    val anzahl: StateFlow<Map<Long, Int>> = _anzahl.asStateFlow()

    /** true = diese Zeile wird gezeigt (Vertreter oder Einzelstück), false = gebündelt. */
    @Synchronized
    fun annehmen(id: Long, name: String, jahr: Int?): Boolean {
        nachId[id]?.let { return it.vertreter == id }
        val schluessel = German4kTitel.normalform(name)
        if (schluessel.isEmpty()) return true
        val j = jahr ?: German4kTitel.jahrAusTitel(name)
        val liste = nachTitel.getOrPut(schluessel) { mutableListOf() }
        val passend = liste.firstOrNull { it.jahr == null || j == null || it.jahr == j }
        if (passend == null) {
            val neu = Gruppe(id, j, linkedMapOf(id to name))
            liste += neu
            nachId[id] = neu
            return true
        }
        if (passend.jahr == null) passend.jahr = j
        passend.mitglieder[id] = name
        nachId[id] = passend
        _anzahl.value = _anzahl.value + (passend.vertreter to passend.mitglieder.size)
        return false
    }

    /** Die Fassung, die ein Klick auf [vertreter] öffnen soll (deutsch, dann 4K, dann erste). */
    @Synchronized
    fun bevorzugt(vertreter: Long): Long {
        val g = nachId[vertreter] ?: return vertreter
        return German4kTitel.bevorzugt(g.mitglieder.entries.map { it.key to it.value }) ?: vertreter
    }
}
