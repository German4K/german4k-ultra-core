package tv.own.owntv.core.content

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import tv.own.owntv.core.german4k.German4kFeatures
import tv.own.owntv.core.german4k.German4kSchalter
import tv.own.owntv.core.util.Pin

/**
 * German4K 3.3: Erwachsenen-PIN (Schalter `erwachsenen_pin`, fehlt = an).
 *
 * Kunden fragten nach „PIN 0000 ändern" — bisher öffnete „Adult Movies" ohne jede Abfrage, nur das Kinderprofil blendete
 * Erwachsenes aus. Jetzt: Erwachsenen-Rubriken öffnen und spielen erst nach dem PIN; bis dahin fehlen ihre Titel auch in
 * „Alle", Favoriten, Verlauf, Suche und Programmführer (die Listen bekommen dieselbe Ausblendung wie im Kinderprofil).
 *
 * **Erkennung** ([istErwachsen]): allein am Rubrik-Namen über [AdultCategoryClassifier.isAdult] — Namensmuster in vielen
 * Sprachen plus die Rubriken, die das Panel ausdrücklich als „für Erwachsene" nennt. Xtream/Stalker/M3U liefern kein
 * verlässliches Erwachsenen-Feld, die Datenbank hat keins (keine Room-Migration nötig).
 *
 * **Sitzung**: nur im Speicher. Nach richtiger Eingabe frei, bis die App neu startet ODER [FREI_MS] (4 h) vergangen sind —
 * auf dem Fernseher lebt der App-Prozess oft tagelang (Standby beendet ihn nicht), „bis zum Neustart" allein hieße dort
 * „für immer". Profilwechsel sperrt wieder ([sperren]).
 *
 * **PIN**: gerätelokal in DataStore als Salt-Hash ([Pin]); fehlt = [STANDARD_PIN]. Bewusst NICHT im Zugangs-Abgleich
 * (German4kAbgleich) und nicht im Backup — jedes Gerät hat seinen eigenen.
 */
object German4kErwachsenPin {
    const val STANDARD_PIN = "0000"
    const val FREI_MS: Long = 4L * 60L * 60L * 1000L
    /** Genau 4 Ziffern: so kann der Dialog nach der vierten Ziffer selbst prüfen (Fernbedienung: 0-0-0-0, fertig). */
    const val LAENGE = 4

    /** Reine Erkennung: ist [kategorieName] eine Erwachsenen-Rubrik? Unbekannt/leer = nein. */
    fun istErwachsen(kategorieName: String?): Boolean = AdultCategoryClassifier.isAdult(kategorieName)

    /** Stimmt [eingabe] mit dem gespeicherten Hash? Kein gespeicherter PIN = Standard [STANDARD_PIN]. */
    fun pinStimmt(eingabe: String, gespeichert: String?): Boolean =
        if (gespeichert.isNullOrBlank()) eingabe == STANDARD_PIN else Pin.verify(eingabe, gespeichert)

    /** Genau [LAENGE] Ziffern. */
    fun pinGueltig(pin: String): Boolean = pin.length == LAENGE && pin.all { it in '0'..'9' }

    // --- Schalter ---------------------------------------------------------------------------------------------------

    /** Für Tests austauschbar. */
    @Volatile internal var schalterAn: () -> Boolean = { German4kFeatures.an(German4kSchalter.ERWACHSENEN_PIN) }

    /** Schalter an? Aus = Verhalten exakt wie vor 3.3. */
    fun aktiv(): Boolean = schalterAn()

    // --- Sitzung ----------------------------------------------------------------------------------------------------

    /** Monotone Uhr in ms (für Tests austauschbar). */
    @Volatile internal var uhr: () -> Long = { System.nanoTime() / 1_000_000L }

    @Volatile private var freiBis: Long = 0L
    private val _frei = MutableStateFlow(false)

    /** PIN in dieser Sitzung eingegeben und noch nicht abgelaufen. Der Ablauf kippt erst bei [istFrei]/[ablaufPruefen]. */
    val frei: StateFlow<Boolean> = _frei.asStateFlow()

    fun istFrei(): Boolean {
        ablaufPruefen()
        return _frei.value
    }

    /** Nach 4 h wieder sperren. Ruft der PIN-Wirt mit einem Zeitgeber auf, damit auch offene Listen umschalten. */
    fun ablaufPruefen() {
        if (_frei.value && uhr() >= freiBis) sperren()
    }

    /** Restzeit der Freigabe in ms (0 = gesperrt). */
    fun restMs(): Long = if (_frei.value) (freiBis - uhr()).coerceAtLeast(0L) else 0L

    /**
     * German4K 3.3.1 (B3): Dauer einer Freigabe in ms — [FREI_MS], nur in Debug-Builds per
     * `adb shell setprop debug.g4k.pin_dauer_s 60` kürzer (Abnahme des Ablaufs am Emulator). Für Tests austauschbar.
     */
    @Volatile internal var freiDauer: () -> Long = { freiMs(debugDauerProp(), tv.own.owntv.core.CoreBuildInfo.debug) }

    /** German4K 3.3.1 (B3): Regel für den Debug-Umweg — Release immer [FREI_MS]; Debug 1 s … 4 h, sonst [FREI_MS]. */
    fun freiMs(prop: String?, debug: Boolean): Long {
        if (!debug) return FREI_MS
        val sekunden = prop?.trim()?.toLongOrNull() ?: return FREI_MS
        return if (sekunden in 1L..FREI_MS / 1000L) sekunden * 1000L else FREI_MS
    }

    /** Android-Systemeigenschaft `debug.g4k.pin_dauer_s` (Muster wie `debug.g4k.panel`); in Release nie gelesen. */
    private fun debugDauerProp(): String? {
        if (!tv.own.owntv.core.CoreBuildInfo.debug) return null
        return runCatching {
            Class.forName("android.os.SystemProperties").getMethod("get", String::class.java)
                .invoke(null, "debug.g4k.pin_dauer_s") as String
        }.getOrNull()
    }

    /** Richtiger PIN: für [FREI_MS] frei. Gilt auch als PIN-Eingabe für die globale Suche (3.2 F1). */
    fun freigeben() {
        freiBis = uhr() + freiDauer() // German4K 3.3.1 (B3): Debug-Builds ggf. kürzer
        _frei.value = true
        German4kErwachsenSitzung.pinEingegeben()
    }

    /** Profilwechsel, Ablauf: wieder sperren. */
    fun sperren() {
        freiBis = 0L
        _frei.value = false
    }

    /** Muss vor dem Öffnen/Spielen von [kategorieName] gefragt werden? */
    fun mussFragen(kategorieName: String?): Boolean = aktiv() && istErwachsen(kategorieName) && !istFrei()

    /** Ist Erwachsenes gerade hinter dem PIN (Schalter an, nicht frei)? Für Listen, die sich selbst ausblenden. */
    fun gesperrt(): Boolean = aktiv() && !istFrei()

    /**
     * Wie [gesperrt] als Flow: Schalteränderung oder PIN-Eingabe baut die Listen neu. Liest den Schalter aus
     * [German4kFeatures.flow] (fehlt = an).
     */
    val gesperrtFlow: Flow<Boolean> = combine(German4kFeatures.flow, _frei) { schalter, frei ->
        istGesperrt(schalter[German4kSchalter.ERWACHSENEN_PIN], frei)
    }.distinctUntilChanged()

    /** Reine Regel hinter [gesperrtFlow]: Schalter fehlt = an, nur exakt „aus" schaltet ab. */
    fun istGesperrt(schalterWert: String?, frei: Boolean): Boolean = schalterWert != "aus" && !frei

    // --- Tor: Abfrage aus ViewModels, beantwortet vom PIN-Dialog in der Oberfläche ----------------------------------

    private val _anfrage = MutableStateFlow<CompletableDeferred<Boolean>?>(null)

    /** Offene PIN-Abfrage (null = keine). Der Wirt in der Oberfläche zeigt dazu den PIN-Dialog. */
    val anfrage: StateFlow<CompletableDeferred<Boolean>?> = _anfrage.asStateFlow()

    /**
     * Darf [kategorieName] jetzt geöffnet/gespielt werden? Erwachsen + gesperrt ⇒ PIN-Dialog anfordern und auf die Antwort
     * warten (true = richtiger PIN, false = abgebrochen). Gleichzeitige Anfragen teilen sich einen Dialog.
     */
    suspend fun durchlassen(kategorieName: String?): Boolean {
        if (!mussFragen(kategorieName)) return true
        return anfordern().await()
    }

    private fun anfordern(): CompletableDeferred<Boolean> = synchronized(this) {
        _anfrage.value ?: CompletableDeferred<Boolean>().also { _anfrage.value = it }
    }

    // German4K 3.3.1 (B3): Abbruch vor dem Abspielen ------------------------------------------------------------------

    private val _abspielAbbruch = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /**
     * German4K 3.3.1 (B3): die PIN-Abfrage vor dem ABSPIELEN wurde abgebrochen. Die Shell schließt darauf den Player
     * (Vollbild, Mini, Audio). Vorher blieb im Vollbild ein schwarzes Bild stehen, bis der Kunde Zurück drückte: Freigabe
     * nach 4 h abgelaufen, im Vollbild weitergezappt → Abfrage → „Abbrechen" → der neue Sender startete nicht, der alte war
     * schon zu. Ordner-Abfragen ([durchlassen]) melden hier nichts — dort springt der Ordner zurück, das Bild bleibt.
     */
    val abspielAbbruch: SharedFlow<Unit> = _abspielAbbruch.asSharedFlow()

    /** German4K 3.3.1 (B3): wie [durchlassen], meldet „Abbrechen" aber über [abspielAbbruch]. Für Abspielen/Tunen. */
    suspend fun durchlassenZumAbspielen(kategorieName: String?): Boolean =
        durchlassen(kategorieName).also { ok -> if (!ok) _abspielAbbruch.tryEmit(Unit) }

    /** Antwort des Dialogs. true setzt die Freigabe NICHT selbst — das macht der Dialog über [freigeben]. */
    fun beantworten(ok: Boolean) {
        val offen = synchronized(this) { _anfrage.value.also { _anfrage.value = null } } ?: return
        offen.complete(ok)
    }

    /** Nur für Tests: Zustand wie nach dem Prozessstart. */
    internal fun vergessenFuerTests() {
        sperren()
        beantworten(false)
        schalterAn = { German4kFeatures.an(German4kSchalter.ERWACHSENEN_PIN) }
        uhr = { System.nanoTime() / 1_000_000L }
        freiDauer = { freiMs(debugDauerProp(), tv.own.owntv.core.CoreBuildInfo.debug) } // German4K 3.3.1 (B3)
    }
}
