package tv.own.owntv.player

import tv.own.owntv.core.network.HttpClient

/**
 * German4K 3.2.1 (P1): Zugangsdaten aus mpv-Logzeilen entfernen, bevor sie irgendwo landen (Fehlerbild,
 * LiveDiagnosticsLog, Wiedergabe-Fehlerprotokoll → German4kDiagnose).
 *
 * [HttpClient.redactUrl] deckt Query-Parameter, `user:pass@host` und Xtream-Pfade MIT Schema/Host ab
 * (`https://host/live/<user>/<pass>/…`). mpv und FFmpeg schreiben die Pfade aber auch ohne Host
 * („Opening /live/<user>/<pass>/364.ts“, HLS-Segmente relativ) — diese Form maskiert der zweite Schritt.
 * Idempotent: schon maskierte Zeilen bleiben gleich.
 */
internal object MpvLogMaske {
    private val PFAD_RX = Regex(
        """(?i)(/(?:live|movie|series|vod|timeshift)/)([^/\s<>]+)/([^/\s<>]+)/""",
    )

    fun maskiere(text: String): String =
        HttpClient.redactUrl(text).replace(PFAD_RX) { m ->
            if (m.groupValues[2] == MASKE && m.groupValues[3] == MASKE) m.value else "${m.groupValues[1]}$MASKE/$MASKE/"
        }

    private const val MASKE = "•••"
}
