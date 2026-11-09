package tv.own.owntv.player

import tv.own.owntv.core.german4k.German4kFeatures
import tv.own.owntv.core.german4k.German4kSchalter

/**
 * German4K 3.2.1 (p-20, Schalter `ton_waechter_stumm`): Darf der Ton-Wächter ([AudioWatchdog]) diese Wiedergabe beurteilen?
 *
 * Fire TV, Kandidat 16: In der Streaminfo stand „zurückgefallen: exo/live: no sound…", obwohl der Ton lief. Die
 * Live-Vorschau spielt bewusst stumm (Lautstärke 0, Tonspur abgewählt) — ihr AudioTrack läuft dann nicht oder spät an,
 * der Wächter hielt das für eine tote Ausgabe und schaltete die ganze Sitzung auf Stereo. Stumm ist kein Fehler.
 */
internal object TonWaechterStumm {
    fun zaehlt(stumm: Boolean, spurAbgewaehlt: Boolean): Boolean =
        zaehlt(stumm, spurAbgewaehlt, German4kFeatures.an(German4kSchalter.TON_WAECHTER_STUMM))

    /** [schalter] aus = altes Verhalten (zählt immer). */
    fun zaehlt(stumm: Boolean, spurAbgewaehlt: Boolean, schalter: Boolean): Boolean =
        !schalter || (!stumm && !spurAbgewaehlt)
}
