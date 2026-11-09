package tv.own.owntv.core.german4k

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * German4K 3.2.1 (U9): Restlaufzeit für „Mehr ▸ Mein Zugang".
 *
 * Der Server schickt `expire_date` IMMER mit. Ohne gekoppelte Line ist es ein Platzhalter — heute + 365 Tage —,
 * damit ältere Apps bei leerem Feld nicht „abgelaufen" zeigen (lib/app-panel/panel.ts, Schritt 3/3a). Die Ultra
 * las ihn als echte Laufzeit und schrieb „noch 365 Tage" unter „Mein Zugang", auch ohne jeden Zugang
 * (abn2-tv/13-mehr.png). Eine Tageszahl gibt es deshalb nur mit echtem German4K-Zugang:
 *  - eine von uns gekoppelte Line liefert Quellen namens „German4K" (der Server faltet alle geschützten Kacheln
 *    unter diesem Namen); selbst eingetragene Zugänge tragen ihren eigenen Namen und den Platzhalter;
 *  - ein wirklich abgelaufener Zugang hat keine Quellen mehr, aber `note_typ = abgelaufen` und das echte Datum.
 */
object German4kZugangTage {

    /** Tage bis zum Ablauf (negativ = abgelaufen), oder null, wenn es keinen echten Zugang gibt. */
    fun tage(a: German4kPanelAnswer?, heute: LocalDate = LocalDate.now(), streng: Boolean = true): Int? {
        if (a == null) return null
        val ablauf = runCatching { LocalDate.parse(a.expireDate) }.getOrNull() ?: return null
        val tage = ChronoUnit.DAYS.between(heute, ablauf).toInt()
        return if (!streng || echt(a)) tage else null
    }

    /** Ob [a] einen echten German4K-Zugang beschreibt — nur dann sind `expire_date`/`kunde.tage_offen` eine Auskunft. */
    fun echt(a: German4kPanelAnswer?): Boolean {
        if (a == null) return false
        if (a.noteTyp == "abgelaufen") return true
        if (a.locked) return false
        return a.sources.any { it.name.startsWith(QUELLE_GERMAN4K) }
    }

    /** [echt] hinter dem Notschalter: aus = 3.2-Verhalten (jedes Datum gilt). */
    fun anzeigen(a: German4kPanelAnswer?): Boolean =
        !German4kFeatures.an(German4kSchalter.ZUGANG_TAGE_ECHT) || echt(a)

    private const val QUELLE_GERMAN4K = "German4K"
}
