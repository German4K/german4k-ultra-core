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
}
