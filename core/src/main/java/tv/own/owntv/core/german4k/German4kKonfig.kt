package tv.own.owntv.core.german4k

import org.json.JSONArray
import org.json.JSONObject

/**
 * German4K 3.2: Stellschrauben aus `/api/app/ultra` → `konfig` (optional). Warum: Vorlaufzeiten, Cache-Dauer,
 * die „Leitung belegt“-Codes und ein paar Überschriften sollen sich ohne App-Update nachziehen lassen. Fehlt
 * das Objekt oder ein Wert (oder ist er unbrauchbar), gilt der Standard — eine kaputte Server-Antwort darf
 * nie schlechter sein als ein Server, der das Feld gar nicht kennt.
 */
data class German4kKonfigWerte(
    val erinnernVorlaufSendungMin: Int = STD_VORLAUF_SENDUNG_MIN,
    val erinnernVorlaufSpielMin: Int = STD_VORLAUF_SPIEL_MIN,
    val angesagtFrischH: Int = STD_ANGESAGT_FRISCH_H,
    val leitungCodes: Set<Int> = STD_LEITUNG_CODES,
    /** Server-Texte, die einzelne App-Texte ersetzen (nur Schlüssel aus [TEXT_SCHLUESSEL], nie leer). */
    val texte: Map<String, String> = emptyMap(),
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("erinnern_vorlauf_sendung_min", erinnernVorlaufSendungMin)
        put("erinnern_vorlauf_spiel_min", erinnernVorlaufSpielMin)
        put("angesagt_frisch_h", angesagtFrischH)
        put("leitung_codes", JSONArray(leitungCodes.sorted()))
        put("texte", JSONObject().apply { texte.forEach { (k, v) -> put(k, v) } })
    }

    companion object {
        const val STD_VORLAUF_SENDUNG_MIN = 5
        const val STD_VORLAUF_SPIEL_MIN = 10
        const val STD_ANGESAGT_FRISCH_H = 6
        val STD_LEITUNG_CODES: Set<Int> = setOf(458, 460)

        /** Nur diese Texte darf der Server überschreiben — alles andere bleibt strings.xml. */
        val TEXT_SCHLUESSEL = setOf(
            "angesagt_titel", "angesagt_untertitel", "top10_filme_titel", "top10_serien_titel",
            "top10_untertitel", "konferenz_titel_bl", "leitung_belegt_text",
        )

        /** Ganzzahl in [min]..[max], sonst Standard (fehlend, Text, negativ, absurd groß). */
        private fun zahl(o: JSONObject, key: String, std: Int, min: Int, max: Int): Int {
            if (!o.has(key) || o.isNull(key)) return std
            val d = (o.opt(key) as? Number)?.toDouble() ?: return std
            if (d != Math.floor(d) || d < min || d > max) return std
            val n = d.toInt()
            return if (n in min..max) n else std
        }

        fun parse(o: JSONObject?): German4kKonfigWerte {
            if (o == null) return German4kKonfigWerte()
            // 200er-Codes als „belegt“ würden jeden Film abbrechen — nur echte Fehlercodes zulassen.
            val codes = o.optJSONArray("leitung_codes")?.let { a ->
                (0 until a.length()).mapNotNull { i -> (a.opt(i) as? Number)?.toInt()?.takeIf { it in 400..599 } }.toSet()
            }?.takeIf { it.isNotEmpty() } ?: STD_LEITUNG_CODES
            val texte = o.optJSONObject("texte")?.let { t ->
                t.keys().asSequence().filter { it in TEXT_SCHLUESSEL }
                    .mapNotNull { k -> (t.opt(k) as? String)?.trim()?.takeIf { it.isNotEmpty() }?.let { k to it } }
                    .toMap()
            } ?: emptyMap()
            return German4kKonfigWerte(
                erinnernVorlaufSendungMin = zahl(o, "erinnern_vorlauf_sendung_min", STD_VORLAUF_SENDUNG_MIN, 0, 120),
                erinnernVorlaufSpielMin = zahl(o, "erinnern_vorlauf_spiel_min", STD_VORLAUF_SPIEL_MIN, 0, 120),
                angesagtFrischH = zahl(o, "angesagt_frisch_h", STD_ANGESAGT_FRISCH_H, 1, 168),
                leitungCodes = codes,
                texte = texte,
            )
        }
    }
}

/** Halter wie [German4kFeatures]: der Provisioner setzt, die Stellen lesen. Vor der ersten Antwort = Standard. */
object German4kKonfig {
    @Volatile var werte: German4kKonfigWerte = German4kKonfigWerte()
        private set
    val flow = kotlinx.coroutines.flow.MutableStateFlow(German4kKonfigWerte())

    fun set(neu: German4kKonfigWerte?) { val w = neu ?: German4kKonfigWerte(); werte = w; flow.value = w }

    val erinnernVorlaufSendungMin: Int get() = werte.erinnernVorlaufSendungMin
    val erinnernVorlaufSpielMin: Int get() = werte.erinnernVorlaufSpielMin
    val angesagtFrischMs: Long get() = werte.angesagtFrischH * 60L * 60 * 1000
    val leitungCodes: Set<Int> get() = werte.leitungCodes

    /** Server-Text für [schluessel] oder [appText] (strings.xml), wenn der Server keinen liefert. */
    fun text(schluessel: String, appText: String): String = werte.texte[schluessel] ?: appText
}
