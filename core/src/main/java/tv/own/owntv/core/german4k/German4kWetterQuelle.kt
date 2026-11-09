package tv.own.owntv.core.german4k

/**
 * German4K 3.2.1 (Q4): Woher der Wetter-Chip seinen Ort nimmt. Vorrang: Ort, den der Kunde selbst eingetragen hat
 * (Name → Open-Meteo-Geocoding, „lat,lon“ direkt) → grober Ort unseres Servers → nichts (Chip aus). Die IP-Ortung
 * über ipapi.co (Kunden-IP an Dritte) gibt es nur noch mit Schalter [German4kSchalter.WETTER_SERVER_ORT] = "aus".
 * Rein, damit es ohne Android testbar ist.
 */
sealed class German4kWetterQuelle {
    /** Kunde hat einen Ort eingetragen (Text wie eingegeben, getrimmt). */
    data class Eingetragen(val text: String) : German4kWetterQuelle()
    /** Grober Ort aus der Panel-Antwort. */
    data class Server(val ort: German4kWetterOrt) : German4kWetterQuelle()
    /** Altes Verhalten (Schalter aus): IP-Ortung beim Fremddienst. */
    object IpOrtung : German4kWetterQuelle()
    /** Kein Ort bekannt → kein Abruf, Chip bleibt aus. */
    object Keine : German4kWetterQuelle()

    companion object {
        fun waehle(eingetragen: String, serverOrt: German4kWetterOrt?, serverOrtAn: Boolean): German4kWetterQuelle {
            val t = eingetragen.trim()
            if (t.isNotEmpty()) return Eingetragen(t)
            if (!serverOrtAn) return IpOrtung
            return serverOrt?.let { Server(it) } ?: Keine
        }

        /** Aktueller Stand aus Schalter + Panel-Antwort. */
        fun aktuell(eingetragen: String): German4kWetterQuelle = waehle(
            eingetragen, German4kKonfig.werte.wetterOrt, German4kFeatures.an(German4kSchalter.WETTER_SERVER_ORT),
        )
    }
}
