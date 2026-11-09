package tv.own.owntv.core.german4k

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper

/**
 * German4K 3.2.1 (q-6, Schalter [German4kSchalter.NETZ_ZURUECK_NEUSTART]): kurze Netzlücke während Live.
 *
 * Befund t-8: Netz weg → die Leiter wechselt zu mpv, mpv meldet „geöffnet“ und gleich danach „loading failed“; die
 * Leiter wertete das als Erfolg und blieb stehen. Nach Netz zurück kam der Sender erst über „Erneut versuchen“ wieder.
 *
 * Hier nur die Entscheidungen (rein, getestet). Wer wartet und wer neu startet, steht im LiveViewModel.
 */
object German4kNetzZurueck {
    /** mpv gilt erst als geöffnet, wenn in dieser Zeit danach kein Fehler kommt. */
    const val NACH_OEFFNEN_MS = 5_000L

    /** Netz ist zurück, aber noch kein Fehlerbild (Leiter lädt noch): so lange darf ein Fehler noch den Neustart auslösen. */
    const val NACHLAUF_MS = 20_000L

    /**
     * Neustart erst so lange nach „Netz validiert“: die Sitzung von vor der Lücke hält beim Anbieter noch die Leitung,
     * bis seine Sendewiederholung auf das zurückgekehrte Gerät trifft (A9-Emulator, 1-Verbindungs-Mock, 40–50 s Lücke:
     * alte Sitzung zu nach +8,2 s / +11,3 s). Ein Neustart nach 4 s lief zweimal in „Leitung belegt“ (458). Mit 10 s
     * plus der einen Wiederholung bei 458 (t-7, ~2 s) ist die alte Sitzung in der Regel weg.
     * Startet die Engine in der Zeit selbst wieder (ExoPlayer „network restored“), entfällt der Neustart.
     */
    const val NEUSTART_VERZUG_MS = 10_000L

    enum class Schritt { NEUSTART, WARTEN, ABBRECHEN }

    /**
     * Ein Live-Fehler (Leiterstufe oder Fehlerbild) für [sender]: auf ein validiertes Netz warten?
     * Nur ohne validiertes Netz, nie bei „Leitung belegt“ (458/460), nie in Multiview, nur wenn Live zu sehen ist und
     * dieser Sender seit dem letzten Bild noch keinen automatischen Neustart hatte ([schonVersucht]).
     */
    fun warten(
        schalterAn: Boolean,
        sender: String?,
        online: Boolean,
        belegt: Boolean,
        multiview: Boolean,
        sichtbar: Boolean,
        schonVersucht: String?,
    ): Boolean = schalterAn && sender != null && !online && !belegt && !multiview && sichtbar && schonVersucht != sender

    /**
     * Netz ist validiert ([seitNetzMs] her). Neu starten nur, wenn derselbe Sender noch auf dem Schirm steht und ein
     * Fehler zu sehen ist; ohne Fehlerbild (lädt noch) im [NACHLAUF_MS] weiter warten; sonst abbrechen.
     */
    fun beiNetz(
        schalterAn: Boolean,
        gewartetAuf: String?,
        sender: String?,
        fehler: Boolean,
        belegt: Boolean,
        multiview: Boolean,
        sichtbar: Boolean,
        vorn: Boolean,
        seitNetzMs: Long,
    ): Schritt {
        if (!schalterAn || gewartetAuf == null) return Schritt.ABBRECHEN
        if (sender != gewartetAuf || belegt || multiview || !sichtbar || !vorn) return Schritt.ABBRECHEN
        if (fehler) return Schritt.NEUSTART
        return if (seitNetzMs < NACHLAUF_MS) Schritt.WARTEN else Schritt.ABBRECHEN
    }

    /**
     * German4K 3.2.1 (t-10): mpv meldete „geöffnet“ — sofort als gelungen werten (Störungsdialoge schließen, „schon
     * versucht“ zurücksetzen)? Mit Schalter erst nach [NACH_OEFFNEN_MS] ohne Fehler: scheitert mpv in der Zeit, wären
     * Dialog und Neustart-Sperre sonst schon weg (Neustart-Schleife bei jeder Netzlücke). Schalter aus = sofort (alt).
     */
    fun sofortGelungen(schalterAn: Boolean): Boolean = !schalterAn

    /**
     * German4K 3.2.1 (t-10): mpv scheiterte kurz nach „geöffnet“ — der „Aufgeben nach“-Wecker war beim Öffnen schon
     * gestoppt. Wieder scharf schalten, solange die Frist der Abstimmung noch läuft; ist sie schon um, entscheidet die
     * Leiter selbst (keine Stufe mehr → Fehlerbild), ein sofort feuernder Wecker würde ihr dazwischenfunken.
     */
    fun weckerNeuSetzen(schalterAn: Boolean, abgelaufen: Boolean): Boolean = schalterAn && !abgelaufen

    /** mpv meldete „geöffnet“, [fehlerNachMs] später einen Fehler: als Fehlschlag werten? */
    fun oeffnenGescheitert(schalterAn: Boolean, fehlerNachMs: Long?): Boolean =
        schalterAn && fehlerNachMs != null && fehlerNachMs <= NACH_OEFFNEN_MS
}

/**
 * German4K 3.2.1 (q-6): wartet per `registerDefaultNetworkCallback` auf ein validiertes Standardnetz und meldet das
 * auf dem Hauptfaden. Höchstens ein Rückruf ist angemeldet; [abmelden] ist jederzeit erlaubt (auch doppelt).
 */
class German4kNetzWache(private val context: Context) {
    private val haupt = Handler(Looper.getMainLooper())
    private var rueckruf: ConnectivityManager.NetworkCallback? = null

    val angemeldet: Boolean get() = rueckruf != null

    fun anmelden(beiValidiert: () -> Unit) {
        abmelden()
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val eigen = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                ) {
                    haupt.post { if (rueckruf === this) beiValidiert() }
                }
            }
        }
        rueckruf = eigen
        runCatching { cm.registerDefaultNetworkCallback(eigen) }.onFailure { if (rueckruf === eigen) rueckruf = null }
    }

    fun abmelden() {
        val alt = rueckruf ?: return
        rueckruf = null
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        runCatching { cm.unregisterNetworkCallback(alt) }
    }
}
