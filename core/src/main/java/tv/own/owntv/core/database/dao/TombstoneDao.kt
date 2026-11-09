package tv.own.owntv.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import tv.own.owntv.core.database.entity.UserDataTombstoneEntity

/**
 * Deleted-user-data markers (v36) — see [UserDataTombstoneEntity]. Written when the user removes a
 * favorite, a history entry, a resume position or a custom-category membership, read by local sync
 * so the deletion travels to the other device instead of being undone by it.
 */
@Dao
interface TombstoneDao {

    /**
     * Records a deletion, keeping the LATER moment when one is already there. An older timestamp
     * must never win: it would lose to a re-add that happened in between, and two devices can
     * exchange tombstones out of order.
     *
     * German4K: two statements in one transaction rather than an `ON CONFLICT … DO UPDATE` upsert.
     * SQLite only learned that syntax in 3.24 (API 30) and minSdk here is 26 — Room prepares the
     * statement on first use, so the app crashed with `near "ON": syntax error` the first time a
     * customer removed a favourite on anything older. Seen in the field on 2026-09-20, Amazon AFTR
     * (Android 9), German4K Ultra 2.2 (17), twice within 20 seconds. Verified against a real
     * SQLite 3.19.3 build (the one Android 9 ships): the upsert is rejected, these two statements
     * run and keep the same meaning — later wins, earlier does not roll back, one row, same id.
     *
     * `INSERT OR IGNORE` leans on the unique (profileId, kind, identity) index, and unlike
     * `INSERT OR REPLACE` it keeps the existing row's autoGenerate id instead of minting a new one.
     * Same approach as [SeriesSortOrderDao.setOrder], which hit this exact wall earlier.
     */
    @Transaction
    suspend fun record(profileId: Long, kind: String, identity: String, deletedAt: Long) {
        insertIgnore(profileId, kind, identity, deletedAt)
        bumpDeletedAt(profileId, kind, identity, deletedAt)
    }

    @Query(
        "INSERT OR IGNORE INTO user_data_tombstones (profileId, kind, identity, deletedAt) " +
            "VALUES (:profileId, :kind, :identity, :deletedAt)",
    )
    suspend fun insertIgnore(profileId: Long, kind: String, identity: String, deletedAt: Long)

    @Query(
        "UPDATE user_data_tombstones SET deletedAt = MAX(deletedAt, :deletedAt) " +
            "WHERE profileId = :profileId AND kind = :kind AND identity = :identity",
    )
    suspend fun bumpDeletedAt(profileId: Long, kind: String, identity: String, deletedAt: Long)

    /** When this row was deleted, or null if it never was. Gates a merge insert of the same record. */
    @Query("SELECT deletedAt FROM user_data_tombstones WHERE profileId = :profileId AND kind = :kind AND identity = :identity")
    suspend fun deletedAt(profileId: Long, kind: String, identity: String): Long?

    /** Everything, for the sync payload. */
    @Query("SELECT * FROM user_data_tombstones")
    suspend fun getAllOnce(): List<UserDataTombstoneEntity>

    @Query("SELECT COUNT(*) FROM user_data_tombstones")
    suspend fun count(): Int

    /**
     * Drops all but the [keep] newest. A tombstone is only useful until every device has seen it, and
     * "Clear watch history" on a big library writes one per row — without a cap the table would grow
     * for ever to remember deletions nothing will ever ask about again.
     */
    @Query(
        "DELETE FROM user_data_tombstones WHERE id NOT IN " +
            "(SELECT id FROM user_data_tombstones ORDER BY deletedAt DESC LIMIT :keep)",
    )
    suspend fun prune(keep: Int)
}
