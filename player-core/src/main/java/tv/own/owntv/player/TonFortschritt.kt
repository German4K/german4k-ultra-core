package tv.own.owntv.player

import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import tv.own.owntv.core.german4k.German4kFeatures
import tv.own.owntv.core.german4k.German4kSchalter

/**
 * German4K 3.2.1 (P4): „Ton läuft“ bei Bitstream-Ausgabe (Passthrough, AAC→AC3 über `g4k-ac3-transcode`).
 *
 * Der Ton-Wächter ([AudioWatchdog]) wartet auf `onAudioPositionAdvancing` — den Moment, in dem die
 * Wiedergabeposition des AudioTracks zu laufen beginnt. Bei PCM zuverlässig; bei Bitstream meldet der
 * Track seine Position auf manchen Geräten spät (Fire TV: einmal „no sound … after 6s“ unter T7, obwohl
 * der Receiver spielte) — und der Wächter schaltete die Sitzung auf Stereo.
 *
 * Darum zählt dort zusätzlich der Ausgabe-Zähler des Renderers (`renderedOutputBufferCount`: Puffer bzw.
 * AC3-Frames, die die Senke ANGENOMMEN hat). Er steigt nur, solange die Senke abnimmt — hängt sie, läuft
 * ihr Puffer voll und der Zähler steht.
 *
 * Review-Fix: Vor dem Abspielen füllt der Renderer den Senken-Puffer vor — der Zähler steigt dann, ohne
 * dass Ton herauskommt. Gezählt wird darum nur im Abspielen, und erst nach [NOETIGE_ANSTIEGE]
 * aufeinanderfolgenden Anstiegen. Bitstream heißt eindeutig: Senken-Encoding ≠ PCM oder Renderer
 * `g4k-ac3-transcode` — nie „kein Decoder gemeldet“ (wiederverwendeter PCM-Decoder meldet auch keinen).
 */
internal object TonFortschritt {
    /** Aufeinanderfolgende Anstiege im Abspielen, bis der Zähler als „Ton läuft“ gilt. */
    const val NOETIGE_ANSTIEGE = 2

    /** Schalter: AC3-Transcode nur unter ton_ac3; echter Passthrough unter ton_auto oder ton_ac3. */
    fun aktiv(transcode: Boolean, passthroughSenke: Boolean): Boolean = aktiv(
        transcode, passthroughSenke,
        tonAc3 = German4kFeatures.an(German4kSchalter.TON_AC3),
        tonAuto = German4kFeatures.an(German4kSchalter.TON_AUTO),
    )

    fun aktiv(transcode: Boolean, passthroughSenke: Boolean, tonAc3: Boolean, tonAuto: Boolean): Boolean =
        (transcode && tonAc3) || (passthroughSenke && (tonAc3 || tonAuto))

    /** Neue Zahl aufeinanderfolgender Anstiege. [vorher] < 0 = noch kein Messwert im Abspielen. */
    fun anstiege(vorher: Long, jetzt: Long, bisher: Int): Int =
        if (vorher >= 0 && jetzt > vorher) bisher + 1 else 0

    fun laeuft(anstiege: Int): Boolean = anstiege >= NOETIGE_ANSTIEGE

    /** Ausgabe-Zähler des Ton-Renderers, -1 = unbekannt. Auf dem Faden des Players aufrufen. */
    @UnstableApi
    fun zaehler(p: ExoPlayer): Long = runCatching {
        val c = p.audioDecoderCounters ?: return -1L
        c.ensureUpdated()
        c.renderedOutputBufferCount.toLong()
    }.getOrDefault(-1L)
}
