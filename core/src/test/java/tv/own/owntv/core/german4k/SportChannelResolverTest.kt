package tv.own.owntv.core.german4k

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tv.own.owntv.core.database.entity.ChannelEntity

/** German4K: Auswahl des Kanals aus den streamIds eines Senders. */
class SportChannelResolverTest {
    private fun ch(id: Long, source: Long, remote: String) =
        ChannelEntity(id = id, sourceId = source, name = "k$id", streamUrl = "", remoteId = remote)

    @Test
    fun `streamId order wins`() {
        val treffer = listOf(ch(1, 1, "12"), ch(2, 1, "11"))
        assertEquals(2L, SportChannelResolver.waehleErsten(listOf(11, 12), treffer, listOf(1))!!.id)
        assertEquals(1L, SportChannelResolver.waehleErsten(listOf(12, 11), treffer, listOf(1))!!.id)
    }

    @Test
    fun `tie goes to first source`() {
        val treffer = listOf(ch(1, 7, "11"), ch(2, 3, "11"))
        assertEquals(2L, SportChannelResolver.waehleErsten(listOf(11), treffer, listOf(3, 7))!!.id)
        assertEquals(1L, SportChannelResolver.waehleErsten(listOf(11), treffer, listOf(7, 3))!!.id)
        assertEquals(1L, SportChannelResolver.waehleErsten(listOf(11), treffer)!!.id)
    }

    @Test
    fun `empty is null`() {
        assertNull(SportChannelResolver.waehleErsten(listOf(11), emptyList()))
        assertNull(SportChannelResolver.waehleErsten(emptyList(), listOf(ch(1, 1, "11"))))
        assertNull(SportChannelResolver.waehleErsten(listOf(99), listOf(ch(1, 1, "11"))))
    }
}
