package tv.own.owntv.core.player

/**
 * How the user wants multichannel audio handled. Persisted as a string (see
 * `SettingsRepository.surroundMode`); the legacy `surround_sound` boolean maps onto it.
 *
 * The distinction that matters is **who decodes Dolby/DTS**:
 *
 *  - [STEREO] — we always decode in-app and hand the sink plain 2.0 PCM. Nothing is bitstreamed, so a
 *    TV/soundbar that lies about its capabilities never gets the chance to mis-play anything. Safest,
 *    lowest latency, and the only mode that is correct on 2.0/2.1 speakers (which is most TVs).
 *  - [SURROUND] — the sink's advertised capabilities are used as-is: Dolby/DTS may be passed through
 *    for the TV/receiver to decode, or decoded to multichannel PCM. Right for a real 5.1/7.1 receiver.
 *  - [AUTO] — German4K 3.1.1: „Automatisch" = Stereo auf allen Geräten (vorher: wie [SURROUND] mit
 *    Rückfall auf [STEREO]). Mehrkanal gibt es nur noch, wenn der Nutzer ausdrücklich [SURROUND] wählt —
 *    zu viele Fernseher/Soundbars/Boxen verloren mit Mehrkanal-PCM den Ton. The default.
 *
 * The demotion is **not** the mode's job — see [AudioOutputPolicy]. The watchdog runs in all three
 * modes and cannot be switched off, because "no sound at all" is never what the user asked for.
 *
 * German4K 3.2.1 (T2), Schalter `ton_auto` (an):
 *  - [AUTO] wird wirklich automatisch: der erkannte Ausgang ([TonAusgang]) entscheidet (siehe [TonWahl]).
 *  - [MULTICHANNEL] neu: Mehrkanal-PCM, nie Bitstream — für Fernseher, die bei Durchleitung stumm werden.
 *  - [SURROUND] heißt in der Oberfläche „Receiver“ (Durchleitung Dolby/DTS). Der gespeicherte Name bleibt
 *    "SURROUND", damit ein altes „Surround“ ohne Migration als Receiver weiterläuft.
 * Schalter `ton_auto` aus ⇒ genau 3.2: drei Stufen, [MULTICHANNEL] verhält sich wie das alte [SURROUND].
 */
enum class SurroundMode {
    AUTO, STEREO, SURROUND,
    /** German4K 3.2.1 (T2): Mehrkanal-PCM ohne Bitstream. Neu am Ende — Namen werden gespeichert, nie ordinal. */
    MULTICHANNEL;

    /**
     * German4K 3.2.1 (T2): nächste Stufe beim Durchschalten.
     * ton_auto an: Automatisch → Nur Stereo → Mehrkanal → Receiver → Automatisch.
     * ton_auto aus: Automatisch → Nur Stereo → Surround → Automatisch (wie 3.2).
     */
    fun naechste(tonAuto: Boolean): SurroundMode = if (tonAuto) {
        when (this) {
            AUTO -> STEREO
            STEREO -> MULTICHANNEL
            MULTICHANNEL -> SURROUND
            SURROUND -> AUTO
        }
    } else {
        when (this) {
            AUTO -> STEREO
            STEREO -> SURROUND
            SURROUND, MULTICHANNEL -> AUTO
        }
    }

    /** German4K 3.2.1 (T2): die Stufe, die bei ausgeschaltetem `ton_auto` gilt und angezeigt wird. */
    fun wirksam(tonAuto: Boolean): SurroundMode = if (!tonAuto && this == MULTICHANNEL) SURROUND else this

    companion object {
        /**
         * Read from the persisted string, falling back to the pre-4.1.7 boolean.
         *
         * A user who never touched the old switch gets [AUTO] (the new, better default); one who
         * explicitly turned it **on** clearly wants multichannel, so gets [SURROUND]; one who
         * explicitly turned it **off** was working around a broken output, so keeps [STEREO].
         */
        fun of(stored: String?, legacyBoolean: Boolean?): SurroundMode {
            stored?.let { s -> entries.firstOrNull { it.name == s }?.let { return it } }
            return when (legacyBoolean) {
                null -> AUTO
                true -> SURROUND
                false -> STEREO
            }
        }
    }
}
