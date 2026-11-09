package tv.own.owntv.core.german4k

import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.core.database.entity.EpgProgrammeEntity

/** Eine Karte der Startseiten-Reihe „Jetzt im TV": Sender + was gerade läuft, Zeiten in Gerätezeit. */
data class JetztImTvEintrag(
    val channel: ChannelEntity,
    val titel: String,
    val startMs: Long,
    val stopMs: Long,
    val virtuell: Boolean,
)

/**
 * „Wie beim normalen Fernsehen sehen, was gerade läuft, ohne durchzuzappen" (Kundenwunsch, 02.10.2026).
 * Virtuelle 24/7-Sender zuerst (dort steht immer etwas Bestimmtes), dann echte Sender des Kunden, die
 * laut Guide gerade etwas zeigen. Jeder Sender einmal; höchstens [max] Karten.
 */
object JetztImTv {
    fun zusammenstellen(
        virtuell: List<Pair<ChannelEntity, VirtuellStand>>,
        echt: List<ChannelEntity>,
        echtJetzt: Map<Long, EpgProgrammeEntity>,
        jetztMs: Long,
        max: Int = 12,
    ): List<JetztImTvEintrag> {
        val out = LinkedHashMap<Long, JetztImTvEintrag>()
        for ((ch, st) in virtuell) {
            if (ch.id in out) continue
            val e = st.eintrag
            out[ch.id] = JetztImTvEintrag(
                ch, e.titel, st.uhr.geraetMs(e.start.toEpochMilli()), st.uhr.geraetMs(e.ende.toEpochMilli()), virtuell = true,
            )
        }
        for (ch in echt) {
            if (ch.id in out) continue
            val p = echtJetzt[ch.id]?.takeIf { it.startMs <= jetztMs && jetztMs < it.stopMs } ?: continue
            out[ch.id] = JetztImTvEintrag(ch, p.title, p.startMs, p.stopMs, virtuell = false)
        }
        return out.values.take(max)
    }

    /** German4K 3.2.1 (p-15): weniger Karten als das, und „Jetzt im TV“ bleibt wie bisher (mit den Weiterschauen-Sendern). */
    const val MIN_OHNE_WEITER = 2

    /**
     * German4K 3.2.1 (p-15, Schalter `jetzt_tv_ohne_weiter`): „Jetzt im TV“ ohne die Sender, die schon unter
     * „Weiterschauen“ stehen ([weiterIds]) — sonst zeigten beide Reihen nach dem Abspielen dieselben Karten
     * (k8/emu/tv43). Aufgefüllt wird aus [zusatz] (z. B. zuletzt gesehene Sender jenseits von Weiterschauen). Bleiben
     * weniger als [min] Karten übrig, gilt die bisherige Liste aus [virtuell] + [echt] — die Reihe wird nie leer.
     */
    fun ohneWeiterschauen(
        virtuell: List<Pair<ChannelEntity, VirtuellStand>>,
        echt: List<ChannelEntity>,
        zusatz: List<ChannelEntity>,
        echtJetzt: Map<Long, EpgProgrammeEntity>,
        jetztMs: Long,
        weiterIds: Set<Long>,
        max: Int = 12,
        min: Int = MIN_OHNE_WEITER,
    ): List<JetztImTvEintrag> {
        val alt = zusammenstellen(virtuell, echt, echtJetzt, jetztMs, max)
        if (weiterIds.isEmpty()) return alt
        val ohne = zusammenstellen(
            virtuell.filter { it.first.id !in weiterIds },
            (echt + zusatz).filter { it.id !in weiterIds },
            echtJetzt, jetztMs, max,
        )
        return if (ohne.size >= min) ohne else alt
    }

    /**
     * German4K 3.2.1 (t-8): Rubrik-Nachbarn ohne einzeln ausgeblendete Sender ([hiddenItems] = Schlüssel aus
     * [tv.own.owntv.core.customize.CustomizeKeys.channel]) — die übrigen Reihen filtert schon HomeFeed.
     */
    fun ohneAusgeblendete(
        sender: List<tv.own.owntv.core.database.entity.ChannelEntity>,
        hiddenItems: Set<String>,
    ): List<tv.own.owntv.core.database.entity.ChannelEntity> =
        if (hiddenItems.isEmpty()) sender
        else sender.filterNot { tv.own.owntv.core.customize.CustomizeKeys.channel(it) in hiddenItems }

    /**
     * German4K 3.2.1 (t-8): Laufnummer für „Jetzt im TV“ — Minutenschleife und Collector bauen parallel; nur der
     * zuletzt GESTARTETE Lauf darf schreiben, ein älterer überschreibt nie den neueren.
     */
    class Laeufe {
        private val zaehler = java.util.concurrent.atomic.AtomicLong()
        fun start(): Long = zaehler.incrementAndGet()
        fun aktuell(lauf: Long): Boolean = zaehler.get() == lauf
    }
}
