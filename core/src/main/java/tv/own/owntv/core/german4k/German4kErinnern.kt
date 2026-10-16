package tv.own.owntv.core.german4k

import tv.own.owntv.core.database.entity.ErinnerungEntity

/**
 * German4K 3.2 (N7): die Regeln von „⏰ Erinnern“ — rein, ohne Android, damit sie vollständig
 * getestet sind (German4kErinnernTest).
 *
 * - Sendung: 5 min vorher, Spiel/Konferenz: 10 min vor Anpfiff.
 * - „Gleich“ gilt vom Auslösen bis 2 min nach Beginn; danach heißt der Hinweis „Verpasst? Läuft seit
 *   … min“, solange die Sendung noch läuft (Ende aus dem EPG, sonst 60 min; Spiele 2 h).
 * - Nur künftige Sendungen sind erinnerbar.
 */
object German4kErinnern {
    const val VORLAUF_SENDUNG_MIN = 5
    const val VORLAUF_SPIEL_MIN = 10

    private const val MINUTE_MS = 60_000L
    /** Bis hierhin nach Beginn ist es noch „Gleich“, kein „Verpasst“. */
    const val GLEICH_NACH_START_MS = 2 * MINUTE_MS
    const val SENDUNG_OHNE_ENDE_MS = 60 * MINUTE_MS
    const val SPIEL_DAUER_MS = 120 * MINUTE_MS

    /** Erledigte bleiben einen Tag in der Liste, dann fallen sie weg. */
    const val AUFRAEUMEN_NACH_MS = 24 * 60 * MINUTE_MS

    /**
     * Ob die App gerade sichtbar ist. Die Shell setzt es in onStart/onStop. Ist sie sichtbar, zeigt
     * sie den Hinweis selbst im Bild — dann soll nicht zusätzlich eine Benachrichtigung kommen.
     */
    @Volatile var vordergrund: Boolean = false

    /**
     * Ab Android 13 braucht eine Benachrichtigung die Erlaubnis des Nutzers. Wird die erste Erinnerung
     * gesetzt, fragt die Oberfläche einmal danach (Bestversuch — ohne Erlaubnis bleibt der Hinweis im Bild).
     */
    val erlaubnisGewuenscht = kotlinx.coroutines.flow.MutableStateFlow(false)

    /** Sender doppelt im Schlüssel: Quelle + Anbieter-Kennung überlebt einen Neuabgleich, die lokale id nicht. */
    fun sendungSchluessel(sourceId: Long, senderKennung: String, startMs: Long): String =
        "${ErinnerungEntity.ART_SENDUNG}:$sourceId:$senderKennung:$startMs"

    /** Sport hängt an der Spiel-/Konferenz-Kennung, nie an einer streamId. */
    fun spielSchluessel(spielId: Long): String = "${ErinnerungEntity.ART_SPIEL}:$spielId"

    fun ausloeseZeit(e: ErinnerungEntity): Long = e.startMs - e.vorlaufMin * MINUTE_MS

    fun endeVon(e: ErinnerungEntity): Long =
        e.endeMs?.takeIf { it > e.startMs }
            ?: (e.startMs + if (e.art == ErinnerungEntity.ART_SENDUNG) SENDUNG_OHNE_ENDE_MS else SPIEL_DAUER_MS)

    sealed interface Zustand {
        data object Wartet : Zustand
        data object Gleich : Zustand
        /** Seit [minuten] Minuten läuft es schon. */
        data class Verpasst(val minuten: Int) : Zustand
        data object Vorbei : Zustand
    }

    fun zustand(e: ErinnerungEntity, jetzt: Long): Zustand = when {
        e.erledigt -> Zustand.Vorbei
        jetzt < ausloeseZeit(e) -> Zustand.Wartet
        jetzt <= e.startMs + GLEICH_NACH_START_MS -> Zustand.Gleich
        jetzt < endeVon(e) -> Zustand.Verpasst(((jetzt - e.startMs) / MINUTE_MS).toInt().coerceAtLeast(1))
        else -> Zustand.Vorbei
    }

    /** Der nächste Hinweis, der jetzt ins Bild gehört: die früheste offene, die „Gleich“ oder „Verpasst“ ist. */
    fun naechsterHinweis(offene: List<ErinnerungEntity>, jetzt: Long): ErinnerungEntity? =
        offene.filter { !it.erledigt }
            .filter { zustand(it, jetzt).let { z -> z is Zustand.Gleich || z is Zustand.Verpasst } }
            .minByOrNull { it.startMs }

    /** Offene, die vorbei sind, ohne dass jemand sie gesehen hat — still als erledigt markieren. */
    fun vorbei(offene: List<ErinnerungEntity>, jetzt: Long): List<ErinnerungEntity> =
        offene.filter { !it.erledigt && zustand(it, jetzt) == Zustand.Vorbei }

    /** Nur künftige Sendungen: was schon begonnen hat, schaut man an, statt daran erinnert zu werden. */
    fun erinnerbar(startMs: Long, jetzt: Long): Boolean = startMs > jetzt

    /** Ein verlegtes Spiel: neuer Beginn laut Sport-Hub, oder null, wenn alles beim Alten ist. */
    fun verlegt(e: ErinnerungEntity, neuerStartMs: Long?): Long? =
        neuerStartMs?.takeIf { it != e.startMs && e.art != ErinnerungEntity.ART_SENDUNG }
}
