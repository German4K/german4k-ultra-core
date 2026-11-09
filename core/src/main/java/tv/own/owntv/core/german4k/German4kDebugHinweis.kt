package tv.own.owntv.core.german4k

/**
 * German4K 3.3.1 (B5): Prüfauslöser für die Hinweis-Dialoge am Emulator — nur Debug-Builds, Muster wie `debug.g4k.panel`
 * ([German4kPanelClient.debugBasis]). `adb shell setprop debug.g4k.hinweis test` zeigt beim App-Start den Testzugang-Hinweis
 * mit QR (Kundenfall), `verlaengern`/`info`/`wartung` die anderen Hinweise, `abgelaufen`/`gesperrt` die Sperrseite.
 * Leer oder unbekannt = aus. Die Beispieldaten setzt [German4kProvisioner.debugTestzugang] bzw. [German4kProvisioner.debugTestNote].
 */
object German4kDebugHinweis {
    private val TYPEN = setOf("test", "verlaengern", "abgelaufen", "gesperrt", "wartung", "info")

    /** Regel: Release immer null; Debug ein bekannter Typ (Groß/Klein und Leerraum egal), sonst null. */
    fun typ(prop: String?, debug: Boolean): String? =
        if (!debug) null else prop?.trim()?.lowercase()?.takeIf { it in TYPEN }

    /** Aus der Android-Systemeigenschaft `debug.g4k.hinweis`; in Release nie gelesen. */
    fun typ(): String? {
        if (!tv.own.owntv.core.CoreBuildInfo.debug) return null
        val prop = runCatching {
            Class.forName("android.os.SystemProperties").getMethod("get", String::class.java)
                .invoke(null, "debug.g4k.hinweis") as String
        }.getOrNull()
        return typ(prop, debug = true)
    }
}
