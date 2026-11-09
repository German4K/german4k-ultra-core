package tv.own.owntv.player

import tv.own.owntv.core.german4k.German4kFeatures
import tv.own.owntv.core.german4k.German4kSchalter

/**
 * German4K 3.2.1 (P10, Schalter `hud_playzustand`): Zustand des Pause/Abspielen-Knopfs.
 *
 * Fire-TV-Abnahme (s_17): Die Mitte zeigte „▶“, obwohl der Sender lief. ExoPlayer meldet `isPlaying` nur in
 * STATE_READY — beim Puffern (Live, Neuverbindung, Motorwechsel) ist es false, obwohl `playWhenReady` true ist
 * und das Bild gleich weiterläuft. Der Knopf folgt deshalb dem gewollten Zustand; OK pausiert dann wirklich
 * (vorher rief OK beim Puffern `play()` auf — ohne Wirkung).
 */
object PlayZustand {
    fun an(): Boolean = German4kFeatures.an(German4kSchalter.HUD_PLAYZUSTAND)

    /** Spielt oder puffert mit Spielwunsch. Ohne Schalter: nur [spielt] (Verhalten 3.2). */
    fun gewollt(spielt: Boolean, puffert: Boolean, willSpielen: Boolean, an: Boolean = an()): Boolean =
        spielt || (an && puffert && willSpielen)
}
