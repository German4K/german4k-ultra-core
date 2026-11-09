package tv.own.owntv.player

import androidx.annotation.StringRes
import java.util.Locale
import tv.own.owntv.core.R
import tv.own.owntv.core.german4k.German4kFeatures
import tv.own.owntv.core.german4k.German4kKonfig
import tv.own.owntv.core.german4k.German4kSchalter

/**
 * German4K 3.2.1 (t-7/1, Schalter `fehler_kundentext`): was der Kunde bei einem Wiedergabefehler liest.
 *
 * Bisher stand im Fehlerbild der rohe Server-Text („max connections") und darunter die Technikzeile
 * („Fehler: 'K Live Belegt': HTTP 460 — Leitung belegt, kein Rückfall"). Beides hilft uns beim Suchen und
 * dem Kunden gar nicht. Hier ist die eine Zuordnungstabelle: bekannter Code oder Text → ein Satz in
 * Du-Form. Roher Server-Text erscheint nie mehr direkt; die Technikzeile nur, wenn der Kunde
 * „Detaillierte Wiedergabeprotokollierung" eingeschaltet hat ([technik]).
 */
object German4kFehlerText {

    enum class Art(@StringRes val res: Int) {
        LEITUNG_BELEGT(R.string.t7_321_leitung_belegt),
        ABGELEHNT(R.string.t7_321_abgelehnt),
        NICHT_GEFUNDEN(R.string.t7_321_nicht_gefunden),
        LAND(R.string.t7_321_land),
        NETZ_ANMELDUNG(R.string.t7_321_anmeldung_netz),
        AUSGELASTET(R.string.t7_321_ausgelastet),
        ZEITUEBERSCHREITUNG(R.string.t7_321_zeitueberschreitung),
        FORMAT(R.string.t7_321_format),
        UNBEKANNT(0),
    }

    /** Folgt „Detaillierte Wiedergabeprotokollierung" (nur der Einstellung, nicht dem Debug-Build). */
    @Volatile var technik: Boolean = false

    fun an(): Boolean = German4kFeatures.an(German4kSchalter.FEHLER_KUNDENTEXT)

    /** Technikzeile/Medienzeile im Fehlerbild zeigen? */
    fun technikZeigen(an: Boolean = an()): Boolean = !an || technik

    /** Code und/oder Text (Anbieterantwort, Exo-/mpv-Fehlerzeile) → Kundensatz. */
    fun art(code: Int?, text: String?, leitungCodes: Set<Int> = leitungCodes()): Art {
        val status = code ?: text?.let { PlayerErrors.httpStatusIn(it) ?: German4kLeitung.status(it) }
        if (status != null) {
            when {
                status in leitungCodes || status == 458 || status == 460 -> return Art.LEITUNG_BELEGT
                status == 401 || status == 402 || status == 403 || status == PlayerErrors.HTTP_PROXY_AUTH -> return Art.ABGELEHNT
                status == 404 || status == 410 -> return Art.NICHT_GEFUNDEN
                status == 456 -> return Art.LAND
                status == 511 -> return Art.NETZ_ANMELDUNG
                status == 429 || status == 509 || status in 500..599 -> return Art.AUSGELASTET
            }
        }
        val t = text?.lowercase(Locale.ROOT) ?: return Art.UNBEKANNT
        return when {
            LEITUNG_WOERTER.any { it in t } -> Art.LEITUNG_BELEGT
            "expired" in t || "forbidden" in t || "unauthorized" in t -> Art.ABGELEHNT
            "not found" in t -> Art.NICHT_GEFUNDEN
            ZEIT_WOERTER.any { it in t } -> Art.ZEITUEBERSCHREITUNG
            FORMAT_WOERTER.any { it in t } -> Art.FORMAT
            else -> Art.UNBEKANNT
        }
    }

    /**
     * Welche Art der Fehler hat, den das Fehlerbild zeigen soll. Nur für die allgemeinen Fälle (Anbietertext,
     * „Sender konnte nicht wiedergegeben werden", Verbindung weg) — eine Engine, die sich selbst genau
     * beschrieben hat (Decoder, Ton, Bildformat), behält ihren eigenen, schon übersetzten Satz.
     */
    fun artFuer(failure: PlaybackFailure?, raw: String?, leitungCodes: Set<Int> = leitungCodes()): Art {
        val allgemein = failure == null || failure is PlaybackFailure.Raw || failure == PlaybackFailure.Channel ||
            failure is PlaybackFailure.StreamUnavailable || failure == PlaybackFailure.LostConnection ||
            failure == PlaybackFailure.MpvStreamNeverStarted
        if (!allgemein) return Art.UNBEKANNT
        if (failure is PlaybackFailure.Raw) {
            art(failure.httpCode, failure.message, leitungCodes).takeIf { it != Art.UNBEKANNT }?.let { return it }
        }
        return art(null, raw, leitungCodes)
    }

    /** Rohe Anbieterzeile → Kundensatz; Unbekanntes wird zum allgemeinen „konnte nicht wiedergegeben werden". */
    @StringRes
    fun rohText(raw: PlaybackFailure.Raw, leitungCodes: Set<Int> = leitungCodes()): Int =
        art(raw.httpCode, raw.message, leitungCodes).res.takeIf { it != 0 } ?: R.string.player_error_channel

    /** Die übersetzte Ursachenzeile (ohne Code) bleibt im Kundenbild nur, wo sie verständlich ist. */
    fun grundZeigen(reason: PlayerFailureReason?, art: Art): Boolean = art == Art.UNBEKANNT && reason in KUNDEN_GRUENDE

    private fun leitungCodes(): Set<Int> = runCatching { German4kKonfig.leitungCodes }.getOrDefault(setOf(458, 460))

    private val KUNDEN_GRUENDE = setOf(
        PlayerFailureReason.DECODER_BUSY, PlayerFailureReason.DECODER_TRANSIENT, PlayerFailureReason.UNSUPPORTED_VIDEO,
        PlayerFailureReason.DECODER_MEMORY, PlayerFailureReason.AUDIO,
    )
    private val LEITUNG_WOERTER = listOf(
        "max connections", "maximum connections", "connection limit", "too many connections", "max_connections", "leitung belegt",
    )
    private val ZEIT_WOERTER = listOf(
        "timed out", "timeout", "connection refused", "connection reset", "unable to resolve host",
        "unexpected end of stream", "no picture within", "never opened", "failed to connect",
    )
    private val FORMAT_WOERTER = listOf(
        "unrecognized file format", "invalid data found", "container_unsupported", "input does not start with",
        "parsing_container", "unsupported container",
    )
}
