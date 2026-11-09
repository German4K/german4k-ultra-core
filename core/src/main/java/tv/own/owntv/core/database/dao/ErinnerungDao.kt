package tv.own.owntv.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import tv.own.owntv.core.database.entity.ErinnerungEntity

/**
 * German4K 3.2 (N7): „⏰ Erinnern“ — gemerkte Sendungen und Spiele je Profil.
 *
 * Kein `@Upsert`: Room schreibt dafür `ON CONFLICT … DO UPDATE`, das Android 9 (SQLite 3.19) nicht
 * kennt. `INSERT OR IGNORE` reicht, denn eine zweite gleiche Erinnerung soll nichts ändern.
 */
@Dao
interface ErinnerungDao {

    /** -1, wenn es diese Erinnerung im Profil schon gibt. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun einfuegen(e: ErinnerungEntity): Long

    @Query("SELECT * FROM erinnerungen WHERE id = :id")
    suspend fun getById(id: Long): ErinnerungEntity?

    @Query("SELECT * FROM erinnerungen WHERE profileId = :profileId AND schluessel = :schluessel")
    suspend fun finde(profileId: Long, schluessel: String): ErinnerungEntity?

    @Query("DELETE FROM erinnerungen WHERE id = :id")
    suspend fun loeschen(id: Long)

    /** Die Liste „Meine Erinnerungen“: offene zuerst, dann nach Beginn. */
    @Query("SELECT * FROM erinnerungen WHERE profileId = :profileId ORDER BY erledigt ASC, startMs ASC")
    fun beobachte(profileId: Long): Flow<List<ErinnerungEntity>>

    /** Für die ⏰-Markierung im Programmführer und im Sport-Hub. */
    @Query("SELECT schluessel FROM erinnerungen WHERE profileId = :profileId AND erledigt = 0")
    fun beobachteSchluessel(profileId: Long): Flow<List<String>>

    /** Alles, was noch klingeln kann — über alle Profile, für die Wecker nach einem Start. */
    @Query("SELECT * FROM erinnerungen WHERE erledigt = 0 ORDER BY startMs ASC")
    suspend fun offene(): List<ErinnerungEntity>

    @Query("SELECT * FROM erinnerungen WHERE profileId = :profileId AND erledigt = 0 ORDER BY startMs ASC")
    suspend fun offeneFuer(profileId: Long): List<ErinnerungEntity>

    @Query("UPDATE erinnerungen SET erledigt = 1 WHERE id = :id")
    suspend fun erledigen(id: Long)

    @Query("UPDATE erinnerungen SET benachrichtigtAt = :zeit WHERE id = :id")
    suspend fun benachrichtigt(id: Long, zeit: Long)

    /** Ein verlegtes Spiel: die Erinnerung wandert mit. */
    @Query("UPDATE erinnerungen SET startMs = :startMs, benachrichtigtAt = NULL WHERE id = :id")
    suspend fun verschieben(id: Long, startMs: Long)

    /** Erledigte, die länger als einen Tag vorbei sind, fallen aus der Liste. */
    @Query("DELETE FROM erinnerungen WHERE erledigt = 1 AND startMs < :vorMs")
    suspend fun aufraeumen(vorMs: Long)
}
