package tv.own.owntv.core.german4k

/**
 * German4K 3.2.1 (U48): „Länder & Bereiche" ohne Zugang. Der Server (`lib/app-panel/bereiche.ts`) antwortet dann
 * `ok:false` mit dem deutschen Grund „Dieses Gerät ist noch nicht mit einem Zugang verbunden." — darunter steht der
 * App-Hinweis für die leere Liste in der Sprache der Oberfläche. Beides zusammen war doppelt und in englischer
 * Oberfläche halb deutsch (final-a9/36-bereiche.png). Bei leerer Liste gilt der App-Hinweis; andere Gründe
 * (Netz, Riegel) bleiben stehen.
 */
object German4kBereicheHinweis {
    private val NICHT_VERBUNDEN = listOf(
        "nicht mit einem zugang verbunden",
        "not connected to an access",
        "not connected to a subscription",
    )

    /** Der Server-Grund, den die Seite zeigen soll — `null`, wenn der App-Hinweis der leeren Liste dasselbe sagt. */
    fun serverGrund(grund: String?, listeLeer: Boolean, an: Boolean = German4kFeatures.an(German4kSchalter.BEREICHE_EIN_HINWEIS)): String? {
        if (grund.isNullOrBlank()) return null
        if (!an || !listeLeer) return grund
        val klein = grund.lowercase()
        return if (NICHT_VERBUNDEN.any { it in klein }) null else grund
    }
}
