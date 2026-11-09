package tv.own.owntv.core.german4k

import kotlinx.coroutines.flow.first
import tv.own.owntv.core.database.dao.ChannelDao
import tv.own.owntv.core.database.dao.SourceDao
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.core.model.MediaType
import tv.own.owntv.core.repository.activeSourceIds
import tv.own.owntv.core.settings.SettingsRepository

/**
 * Macht aus den streamIds eines Senders (beste Stufe zuerst) einen Kanal der eigenen Liste.
 * Erst die aktiven Live-Quellen, dann alle Quellen des Profils. Nur vorhandene DAO-Abfragen
 * (Android 9 SQLite) — keine neue Query.
 */
class SportChannelResolver(
    private val settings: SettingsRepository,
    private val sourceDao: SourceDao,
    private val channelDao: ChannelDao,
) {
    suspend fun aufloesen(streamIds: List<Long>): ChannelEntity? {
        if (streamIds.isEmpty()) return null
        val remote = streamIds.map { it.toString() }
        val profileId = settings.activeProfileIdNow()
        if (profileId < 0) return null
        val aktiv = activeSourceIds(settings, sourceDao, profileId, MediaType.LIVE)
        if (aktiv.isNotEmpty()) {
            waehleErsten(streamIds, channelDao.findByRemoteIds(aktiv, remote), aktiv)?.let { return it }
        }
        val alle = sourceDao.observeForProfile(profileId).first().map { it.id }
        if (alle.isEmpty() || alle == aktiv) return null
        return waehleErsten(streamIds, channelDao.findByRemoteIds(alle, remote), alle)
    }

    companion object {
        /**
         * Reihenfolge der [streamIds] gewinnt; gibt es dieselbe Kennung in mehreren Quellen, gewinnt die
         * zuerst in [quellen] genannte (ohne [quellen]: Trefferreihenfolge).
         */
        fun waehleErsten(streamIds: List<Long>, treffer: List<ChannelEntity>, quellen: List<Long> = emptyList()): ChannelEntity? {
            if (treffer.isEmpty()) return null
            val rang = quellen.withIndex().associate { (i, id) -> id to i }
            for (id in streamIds) {
                val key = id.toString()
                val passend = treffer.filter { it.remoteId == key }
                if (passend.isNotEmpty()) return passend.minByOrNull { rang[it.sourceId] ?: Int.MAX_VALUE }
            }
            return null
        }
    }
}
