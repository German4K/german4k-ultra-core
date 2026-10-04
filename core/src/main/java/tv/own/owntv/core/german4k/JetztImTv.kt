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
 * „Wie beim normalen Fernsehen sehen, was gerade läuft, ohne durchzuzappen" (Wunsch Rabe330, 02.10.2026).
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
}
