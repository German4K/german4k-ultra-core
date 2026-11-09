package tv.own.owntv.core.german4k

/**
 * Geht die Geräteuhr mehr als fünf Minuten falsch? Nur mit einer FRISCHEN Panel-Antwort entscheidbar: eine
 * gespeicherte (Panel gerade nicht erreichbar) trägt die `server_time` von damals und meldete sonst jedem
 * Kunden bei einem Ausfall „Die Uhr dieses Geräts geht falsch" (gefunden 04.10.2026 am Emulator).
 */
object German4kUhr {
    private const val GRENZE_MS = 300_000L

    fun falsch(serverTime: String, frisch: Boolean, deviceNow: Long = System.currentTimeMillis()): Boolean {
        if (!frisch || serverTime.isBlank()) return false
        val server = runCatching { java.time.Instant.parse(serverTime).toEpochMilli() }.getOrNull() ?: return false
        return kotlin.math.abs(deviceNow - server) >= GRENZE_MS
    }

    // German4K 3.2.1 (p-20)
    /**
     * Wie [falsch], aber verglichen mit der Geräteuhr beim EMPFANG der Antwort ([empfangMs]), nicht mit „jetzt".
     * Die Oberfläche hält die Panel-Antwort vom App-Start; wer sie eine halbe Stunde später (z. B. nach einem
     * 460-Dialog) neu bewertet, sah sonst 27 min „Abweichung" (Fire TV, Kandidat 16). Schalter `uhr_frisch`:
     * aus = altes Verhalten (Vergleich mit [jetztMs]). [empfangMs] fehlt (0) → kein Urteil.
     */
    fun falschBeiEmpfang(
        serverTime: String,
        frisch: Boolean,
        empfangMs: Long,
        uhrFrisch: Boolean,
        jetztMs: Long = System.currentTimeMillis(),
    ): Boolean {
        if (!uhrFrisch) return falsch(serverTime, frisch, jetztMs)
        if (empfangMs <= 0L) return false
        return falsch(serverTime, frisch, empfangMs)
    }
}
