package tv.own.owntv.core.german4k

import java.util.Locale

/**
 * German4K: Vergleichsschlüssel für Sendernamen, damit ein Favorit einen Sendertausch überlebt.
 *
 * Tauscht der Server einen toten Sender gegen ein Geschwister, ändern sich stream_id und oft auch
 * der Name — „SKY CINEMA ACTION HD" wird „SKY CINEMA ACTION FHD", „DE: ARD" wird „DE | ARD".
 * Der UserDataResolver findet den Favoriten dann weder über die id noch über den exakten Namen, und
 * er bleibt unsichtbar liegen, bis der alte Sender zurückkommt. Dieser Schlüssel lässt Qualitäts-
 * zusätze, Ländervorsatz, Klammern und Satzzeichen weg; übrig bleibt, was den Sender ausmacht.
 */
object German4kSenderName {
    private val QUALITAET = setOf(
        "UHD", "FHD", "HD", "SD", "HQ", "LQ", "4K", "8K", "HEVC", "H264", "H265",
        "1080P", "1080", "720P", "720", "2160P", "50FPS", "60FPS", "RAW", "BACKUP", "ALT",
    )
    // Nur zwei Buchstaben (Ländercode) mit Trenner — „RTL - Plus" darf nicht zu „PLUS" werden.
    private val VORSATZ = Regex("^\\s*[|\\[(]?\\s*[A-Z]{2}\\s*[|\\]):]+\\s*")
    private val KLAMMER = Regex("[\\[(][^\\])]*[\\])]")
    private val TRENNER = Regex("[^A-Z0-9ÄÖÜ]+")

    /** Leerer Schlüssel = nichts Brauchbares übrig; der Aufrufer vergleicht dann nicht. */
    fun schluessel(name: String): String {
        var s = name.uppercase(Locale.ROOT)
        s = s.replace("ᴴᴰ", " HD ").replace("ᵁᴴᴰ", " UHD ").replace("ᶠᴴᴰ", " FHD ")
        s = VORSATZ.replace(s, " ")
        s = KLAMMER.replace(s, " ")
        return TRENNER.split(s)
            .filter { it.isNotEmpty() && it !in QUALITAET }
            .joinToString(" ")
    }
}
