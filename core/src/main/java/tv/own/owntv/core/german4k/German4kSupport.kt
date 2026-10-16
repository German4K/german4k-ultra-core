package tv.own.owntv.core.german4k

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether the help screen is on top of the shell.
 *
 * A flag rather than a navigation route on purpose: the help screen has to be reachable from places
 * that have no navigation of their own — the lock screen, the pairing board, a player error — and
 * each of those would otherwise need its own way through OwnTV's navigation graph. One flag read by
 * the overlay layer keeps every entry point to a single line and keeps the upstream merge clean.
 */
object German4kSupport {

    private val _sichtbar = MutableStateFlow(false)
    val sichtbar: StateFlow<Boolean> = _sichtbar.asStateFlow()

    fun oeffnen() { _sichtbar.value = true }
    fun schliessen() { _sichtbar.value = false }

    /** „Dein Zugang": Verlängern, Zweitgerät, Werben, Kontakt — dieselbe Bauart wie oben. */
    private val _kunde = MutableStateFlow(false)
    val kundeSichtbar: StateFlow<Boolean> = _kunde.asStateFlow()

    fun kundeOeffnen() { _kunde.value = true }
    fun kundeSchliessen() { _kunde.value = false }

    /** „Länder & Bereiche": die Senderliste kürzen, ohne uns zu fragen. */
    private val _bereiche = MutableStateFlow(false)
    val bereicheSichtbar: StateFlow<Boolean> = _bereiche.asStateFlow()

    fun bereicheOeffnen() { _bereiche.value = true }
    fun bereicheSchliessen() { _bereiche.value = false }

    /** German4K 3.2 (N7): „Meine Erinnerungen“ — gemerkte Sendungen und Spiele, löschen möglich. */
    private val _erinnerungen = MutableStateFlow(false)
    val erinnerungenSichtbar: StateFlow<Boolean> = _erinnerungen.asStateFlow()

    fun erinnerungenOeffnen() { _erinnerungen.value = true }
    fun erinnerungenSchliessen() { _erinnerungen.value = false }
}
