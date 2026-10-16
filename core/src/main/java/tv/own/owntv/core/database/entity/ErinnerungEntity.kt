package tv.own.owntv.core.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * German4K 3.2 (N7): eine gemerkte Sendung oder ein gemerktes Spiel („⏰ Erinnern“), je Profil.
 *
 * Rein lokal: kein Server, kein Abgleich, keine Sicherung — eine Erinnerung ist eine Bitte an genau
 * dieses Gerät, und ein Gerät, das sie nicht kennt, soll auch nicht klingeln.
 *
 * **[art]** ist `sendung` (Programmführer), `spiel` oder `konferenz` (Sport-Hub). **[schluessel]** macht
 * eine Erinnerung eindeutig je Profil: `sendung:<Sender>:<Beginn>` bzw. `spiel:<id>` — zweimal
 * „Erinnern“ legt nichts doppelt an (`INSERT OR IGNORE`, das kann auch das SQLite 3.19 von Android 9;
 * ein `ON CONFLICT … DO UPDATE` dagegen erst ab 3.24, Lehre 20.09.2026).
 *
 * **Sport hängt an der Spiel-Kennung [spielId], nie an einer streamId.** Der Sender wird erst beim
 * Auslösen über den Sport-Hub bestimmt — Plätze ändern sich bis zum Anpfiff. Verlegt der Spielplan
 * das Spiel, zieht die App [startMs] nach.
 *
 * **Sendungen merken sich den Sender doppelt:** [channelId] ist die lokale Zeile und kann nach einem
 * Neuabgleich ins Leere zeigen, [sourceId] + [remoteId] (die Stream-Kennung des Anbieters) finden ihn
 * dann wieder.
 */
@Entity(
    tableName = "erinnerungen",
    foreignKeys = [
        ForeignKey(entity = ProfileEntity::class, parentColumns = ["id"], childColumns = ["profileId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [
        Index("profileId"),
        Index("startMs"),
        Index(value = ["profileId", "schluessel"], unique = true),
    ],
)
data class ErinnerungEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val profileId: Long,
    val art: String,
    val schluessel: String,
    val titel: String,
    /** Sendername bzw. Wettbewerb — die zweite Zeile im Hinweis und in der Liste. */
    val zusatz: String? = null,
    val startMs: Long,
    val endeMs: Long? = null,
    val channelId: Long? = null,
    val sourceId: Long? = null,
    val remoteId: String? = null,
    val spielId: Long? = null,
    /** Minuten vor [startMs], zu denen erinnert wird (Sendung 5, Spiel 10). */
    val vorlaufMin: Int,
    /** Wann die Android-Benachrichtigung ging (App war zu) — null = noch nicht. */
    val benachrichtigtAt: Long? = null,
    /** Der Hinweis im Bild wurde gezeigt und beantwortet (Umschalten/Später) oder die Sendung ist vorbei. */
    val erledigt: Boolean = false,
    val createdAt: Long,
) {
    companion object {
        const val ART_SENDUNG = "sendung"
        const val ART_SPIEL = "spiel"
        const val ART_KONFERENZ = "konferenz"
    }
}
