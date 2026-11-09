package tv.own.owntv.player

import tv.own.owntv.core.german4k.German4kFeatures
import tv.own.owntv.core.german4k.German4kSchalter

/**
 * German4K 3.2.1 (P13, Schalter `speicher_live`): Obergrenze für mpvs Rückpuffer bei Live.
 *
 * Fire-TV-Endabnahme: 10 Motorwechsel → PSS 216 → 256 MB, am Ende 369 MB + 72 MB Swap. Gemessen im Emulator
 * (30/60 Wechsel, Heap-Dumps, heapprofd): kein Java-Leck (ExoPlayer/LivePreviewEngine je 1 Instanz), der Zuwachs
 * sitzt im nativen Heap und flacht ab — mpvs Demuxer-Puffer plus Allokator-Rückhalt. Bei Live sucht mpv nie
 * rückwärts (Zeitversatz läuft über Archiv-Adressen), der Rückpuffer (16–64 MiB je Gerät) war reiner Ballast.
 */
object LiveSpeicher {
    /** Rückpuffer für Live — wie bei onTrimMemory. */
    const val LIVE_RUECK = "8MiB"

    fun an(): Boolean = German4kFeatures.an(German4kSchalter.SPEICHER_LIVE)

    /**
     * Wert für `demuxer-max-back-bytes` beim Laden, null = nichts ändern (Schalter aus → Verhalten 3.2).
     * Film/Serie bekommt das Geräte-Budget zurück, außer das System hat schon Speicher zurückverlangt.
     */
    fun rueckpuffer(live: Boolean, budget: String?, getrimmt: Boolean, an: Boolean = an()): String? = when {
        !an -> null
        live || getrimmt -> LIVE_RUECK
        else -> budget
    }
}
