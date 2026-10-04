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
}
