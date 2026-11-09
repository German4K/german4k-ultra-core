package tv.own.owntv.core.german4k

import tv.own.owntv.core.live.EpgNowNext

/**
 * German4K 3.2.1 (U47, Schalter `epg_jetzt_uhr`): „Live jetzt" im Player folgt der Uhr. Die Daten kommen beim
 * Umschalten; lief eine Sendung währenddessen ab, blieb sie stehen („07:00–07:05" um 07:07). Aus dem, was schon
 * da ist (jetzt, danach, weitere), wird zu [jetztMs] neu bestimmt, was läuft und was danach kommt.
 */
object German4kEpgJetzt {
    fun zu(epg: EpgNowNext, jetztMs: Long): EpgNowNext {
        val now = epg.now
        if (now != null && jetztMs in now.startMs until now.stopMs) return epg
        if (now == null && (epg.next == null || jetztMs < epg.next.startMs)) return epg
        val alle = (listOfNotNull(epg.now, epg.next) + epg.upcoming)
            .filter { it.stopMs > it.startMs }
            .distinctBy { it.startMs }
            .sortedBy { it.startMs }
        val laeuft = alle.firstOrNull { jetztMs in it.startMs until it.stopMs }
        val danach = alle.firstOrNull { it.startMs >= (laeuft?.stopMs ?: (jetztMs + 1)) }
        val rest = if (danach == null) emptyList() else alle.filter { it.startMs > danach.startMs }
        return epg.copy(now = laeuft, next = danach, upcoming = rest, previous = alle.lastOrNull { it.stopMs <= jetztMs } ?: epg.previous)
    }

    /** Millisekunden bis zum nächsten Wechsel (Ende von „jetzt" bzw. Beginn von „danach"), null = keiner bekannt. */
    fun bisWechsel(epg: EpgNowNext, jetztMs: Long): Long? =
        listOfNotNull(epg.now?.stopMs, epg.next?.startMs).filter { it > jetztMs }.minOrNull()?.minus(jetztMs)
}
