package tv.own.owntv.core.database

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.core.database.dao.ChannelDao
import tv.own.owntv.core.database.entity.ChannelEntity

/** German4K 3.2 (F3): paketweises Nachladen der Programmführer-Sender — Reihenfolge, Paketgröße, Lücken. */
class ChannelGuidePagingTest {
    private fun ch(id: Long) = ChannelEntity(id = id, sourceId = 1, name = "C$id", streamUrl = "u$id")

    @Test
    fun `keeps the id order, never asks more than one page, skips rows that vanished`() = runBlocking {
        val ids = (20_000L downTo 1L).toList()
        val pakete = mutableListOf<Int>()
        val res = ChannelDao.loadInOrder(ids) { chunk ->
            pakete += chunk.size
            chunk.filter { it % 1000L != 0L }.map { ch(it) } // jede tausendste Zeile ist „weg“
        }
        assertTrue(pakete.all { it <= ChannelDao.CHANNEL_PAGE })
        assertEquals(20_000 / ChannelDao.CHANNEL_PAGE, pakete.size)
        assertEquals(ids.filter { it % 1000L != 0L }, res.map { it.id })
    }

    @Test
    fun `empty input asks nothing`() = runBlocking {
        var gefragt = false
        assertTrue(ChannelDao.loadInOrder(emptyList()) { gefragt = true; emptyList() }.isEmpty())
        assertEquals(false, gefragt)
    }
}
