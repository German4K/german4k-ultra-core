package tv.own.owntv.player

import tv.own.owntv.core.german4k.German4kFeatures
import tv.own.owntv.core.german4k.German4kSchalter
import tv.own.owntv.core.player.TonAusgang

/**
 * German4K 3.2.1 (P12, Schalter `exo_uhr`): ExoPlayer zeigt das erste Bild, aber die Wiedergabeuhr läuft nie.
 *
 * Fire-TV-Endabnahme 22.10. (Ausgang „lautsprecher 2ch pcm“, also ohne erkannten HDMI-Ton): jeder Exo-Start blieb
 * nach dem ersten Bild stehen — AudioFlinger nahm den AudioTrack wegen „BUFFER TIMEOUT“ aus der Liste, Media3
 * meldete nach 10 s `stuck playing with no progress`, die Engine verband neu und stand wieder. Erster N-TV-Start
 * dadurch 13 s, danach Standbild-Schleife alle ~12 s. mpv spielte dieselben Sender auf demselben Gerät flüssig.
 *
 * Deshalb: steht die Uhr seit dem ersten Bild, geht der Sender sofort an mpv ([LiveExoWatchdog]); passiert das
 * zweimal am selben Tonausgang, startet Live für den Rest der Sitzung gleich auf mpv ([sitzungMpv]). Wechselt der
 * Ausgang (HDMI/Receiver wieder da), gilt die Sperre nicht mehr. Aus = Verhalten 3.2 (Neuverbindungs-Schleife).
 */
object ExoUhr {
    fun an(): Boolean = German4kFeatures.an(German4kSchalter.EXO_UHR)

    /** Ab so vielen Treffern am selben Ausgang startet Live gleich auf mpv. */
    const val SITZUNG_AB = 2

    @Volatile private var treffer = 0
    @Volatile private var ausgang: String? = null

    private fun ausgangJetzt(): String? = runCatching { TonAusgang.aktuell.value?.technisch() }.getOrNull()

    /** Uhr seit dem ersten Bild nie gelaufen (und lange genug gewartet)? */
    fun stand(erstesBild: Boolean, fortschritte: Int, seitStartMs: Long, fristMs: Long): Boolean =
        erstesBild && fortschritte == 0 && seitStartMs >= fristMs

    /**
     * German4K 3.2.1 (p-13/8): Ab so viel Puffer bei stehender Uhr kam das Netz nachweislich durch — dann steht die
     * Tonausgabe, nicht der Sender. Ein toter Sender (Netz) füllt keinen Puffer.
     */
    const val PUFFER_BELEG_MS = 2_000L

    /** Ab so vielen Uhr-Fortschritten (Messungen) gilt ein Exo-Lauf als erfolgreich und setzt die Zählung zurück. */
    const val ERFOLG_AB = 5

    /**
     * German4K 3.2.1 (p-13/8): Stillstand der Tonausgabe belegt? Der Sender hat Ton, die Tonposition lief seit dem
     * Formatwechsel nie an (AudioTrack nimmt nichts ab) bzw. es gab Underruns, und das Netz lieferte trotzdem
     * genug Daten nach. Nur dann zählt [melde] — ein toter Sender darf Live nicht für die Sitzung auf mpv legen.
     */
    fun tonStillstandBelegt(hatTon: Boolean, tonPositionLief: Boolean, underruns: Int, pufferMs: Long): Boolean =
        hatTon && (!tonPositionLief || underruns > 0) && pufferMs >= PUFFER_BELEG_MS

    /** Zählt nur mit Beleg (siehe [tonStillstandBelegt]); ohne Beleg (oder Schalter-alt: immer) wie 3.2.1 bis Build 40. */
    fun melde(belegt: Boolean = true) {
        if (!belegt) return
        val jetzt = ausgangJetzt()
        if (jetzt != ausgang) { ausgang = jetzt; treffer = 0 }
        treffer++
    }

    /** German4K 3.2.1 (p-13/8): ExoPlayer lief an diesem Ausgang ([ERFOLG_AB] Fortschritte) → Zählung zurück. */
    fun erfolg() {
        if (treffer == 0) return
        if (ausgangJetzt() == ausgang) treffer = 0
    }

    /** Live für diese Sitzung zuerst auf mpv? */
    fun sitzungMpv(): Boolean = an() && sitzungMpv(treffer, ausgang, ausgangJetzt())

    internal fun sitzungMpv(treffer: Int, ausgangBeiTreffer: String?, ausgangJetzt: String?): Boolean =
        treffer >= SITZUNG_AB && ausgangBeiTreffer == ausgangJetzt

    internal fun zuruecksetzen() { treffer = 0; ausgang = null }
    internal fun trefferStand(): Int = treffer
}
