package tv.own.owntv.core.database

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.core.database.entity.EpgProgrammeEntity
import tv.own.owntv.core.database.entity.SourceEntity
import tv.own.owntv.core.model.SourceType

/**
 * German4K 3.2 (F3): der Programmführer lädt seine Sender seitenweise. Mit einer Liste, die als
 * `SELECT *` mehrere CursorWindows braucht (25.000 Sender mit langen Adressen und Kopfzeilen), muss
 * [tv.own.owntv.core.database.dao.ChannelDao.channelsWithGuidePaged] dasselbe liefern wie die alte
 * Abfrage — gleiche Menge, gleiche Reihenfolge, Grenze eingehalten — und Sender ohne Programm auslassen.
 */
@RunWith(AndroidJUnit4::class)
class ChannelGuidePagedTest {
    private lateinit var db: OwnTVDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, OwnTVDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun pagedGuideChannels_matchSingleQuery_onAListLargerThanOneCursorWindow() = runBlocking {
        val sourceId = db.sourceDao().insert(SourceEntity(name = "G4K", type = SourceType.XTREAM, url = "http://x"))
        val lang = "x".repeat(300) // ~1 KB je Zeile → 25.000 Zeilen ≈ 25 MB, weit über 2 MB
        val anzahl = 25_000
        val channels = (1..anzahl).map { i ->
            ChannelEntity(
                sourceId = sourceId, categoryId = null,
                // Absichtlich gemischt, damit ORDER BY number, name wirklich sortiert.
                name = "Sender ${(i * 7919) % anzahl} $lang",
                streamUrl = "http://host/live/u/p/$i.ts?$lang",
                remoteId = "$i",
                epgChannelId = if (i % 10 == 0) null else "ch$i",
                httpHeaders = "User-Agent: $lang",
            )
        }
        channels.chunked(1_000).forEach { db.channelDao().insertAll(it) }
        val programmes = (1..anzahl).filter { it % 10 != 0 && it % 3 != 0 }.map { i ->
            EpgProgrammeEntity(sourceId = sourceId, epgChannelId = "ch$i", startMs = 1_000L, stopMs = 2_000L, title = "P$i")
        }
        programmes.chunked(1_000).forEach { db.epgDao().upsertProgrammes(it) }

        val limit = 20_000
        val paged = db.channelDao().channelsWithGuidePaged(listOf(sourceId), "", limit)
        val ids = db.channelDao().channelIdsWithGuide(listOf(sourceId), "", limit)

        assertEquals(programmes.size.coerceAtMost(limit), paged.size)
        assertEquals(ids, paged.map { it.id })
        // Nur Sender mit Programm, und die Spalten kommen vollständig an.
        assertEquals(true, paged.all { it.epgChannelId != null && it.remoteId!!.toInt() % 3 != 0 })
        assertEquals(true, paged.all { it.httpHeaders?.length == 312 })

        // Kleine Grenze: dieselben ersten Zeilen wie die alte Ein-Abfrage-Variante.
        val alt = db.channelDao().channelsWithGuide(listOf(sourceId), "", 300).map { it.id }
        val neu = db.channelDao().channelsWithGuidePaged(listOf(sourceId), "", 300).map { it.id }
        assertEquals(alt, neu)
    }
}
