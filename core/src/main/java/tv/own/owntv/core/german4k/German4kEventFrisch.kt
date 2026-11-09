package tv.own.owntv.core.german4k

import tv.own.owntv.core.database.entity.ChannelEntity
import java.util.concurrent.ConcurrentHashMap

/**
 * German4K 3.3 (Schalter `event_frisch`, Kunde): Event-/PPV-Kategorien beim Öffnen frisch vom Server.
 *
 * Der Server benennt Eventplätze alle 5 min um („DAZN PPV 03 · Napoli – Roma“), Events wandern beim Anbieter oft den
 * Platz. Die App zeigte den Katalog-Cache vom letzten Abgleich — alte Namen. Unser Server liefert in
 * `get_live_categories` kein eigenes Feld (`categories.ppv` bleibt serverseitig), also erkennen wir die Kategorie wie
 * der Server selbst am Namen (`lib/tv/mapping/auto.ts` EVENT_WORTE: Eventi, Live Events, PPV; dazu „Event(s)“).
 *
 * Beim Öffnen: `get_live_streams&category_id=…` und die vorhandenen Zeilen (gleiche stream_id) mit Name, Logo und
 * EPG-Kennung aktualisieren — kein ganzer Abgleich, keine neuen oder gelöschten Zeilen (das bleibt dem Sync).
 * Höchstens alle [FRIST_MS] je Kategorie; jeder Fehler ist still, der Cache bleibt.
 */
object German4kEventFrisch {
    const val FRIST_MS = 60_000L

    private val MUSTER = Regex("""(?i)(^|[^\p{L}\p{N}])(eventi|events?|ppv)([^\p{L}\p{N}]|$)""")

    /** Eventkategorie am Namen (Wortgrenzen: „Eventino“ ist keine). */
    fun istEventKategorie(name: String?): Boolean = name != null && MUSTER.containsMatchIn(name)

    /** Eine Zeile aus `get_live_streams`. */
    data class Frisch(val remoteId: String, val name: String, val logoUrl: String?, val epgChannelId: String?)

    private val zuletzt = ConcurrentHashMap<Long, Long>()

    /** Reine Regel: frisch holen, wenn noch nie oder vor mindestens [FRIST_MS]. */
    fun faellig(letzter: Long?, jetzt: Long): Boolean = letzter == null || jetzt - letzter >= FRIST_MS || jetzt < letzter

    /** Merkt den Abruf vor dem Laden — zwei schnelle Öffnungen lösen nur einen Abruf aus. */
    fun anmelden(kategorieId: Long, jetzt: Long): Boolean {
        if (!faellig(zuletzt[kategorieId], jetzt)) return false
        zuletzt[kategorieId] = jetzt
        return true
    }

    /** Nur für Tests. */
    fun vergessenFuerTests() = zuletzt.clear()

    /**
     * Die vorhandenen Zeilen, die sich geändert haben (gleiche stream_id, anderer Name/Logo/EPG-Kennung).
     * Leere Namen vom Server übernehmen wir nicht; ein fehlendes Logo lässt das alte stehen.
     */
    fun abgleich(alt: List<ChannelEntity>, frisch: List<Frisch>): List<ChannelEntity> {
        val neu = frisch.associateBy { it.remoteId }
        return alt.mapNotNull { ch ->
            val f = ch.remoteId?.let(neu::get) ?: return@mapNotNull null
            val name = f.name.trim().takeIf { it.isNotEmpty() } ?: ch.name
            val logo = f.logoUrl?.takeIf { it.isNotBlank() } ?: ch.logoUrl
            val epg = f.epgChannelId?.takeIf { it.isNotBlank() } ?: ch.epgChannelId
            if (name == ch.name && logo == ch.logoUrl && epg == ch.epgChannelId) null
            else ch.copy(name = name, logoUrl = logo, epgChannelId = epg)
        }
    }

    /**
     * Holt [laden] (null = Fehler) und schreibt geänderte Zeilen über [speichern]. Gibt die geänderten Zeilen zurück
     * (leer bei Fehler oder ohne Änderung). Wirft nie außer bei Abbruch der Coroutine.
     */
    suspend fun aktualisieren(
        laden: suspend () -> List<Frisch>?,
        vorhandene: suspend (List<String>) -> List<ChannelEntity>,
        speichern: suspend (List<ChannelEntity>) -> Unit,
    ): List<ChannelEntity> {
        val frisch = try {
            laden()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        } ?: return emptyList()
        if (frisch.isEmpty()) return emptyList()
        return try {
            val geaendert = abgleich(vorhandene(frisch.map { it.remoteId }.distinct()), frisch)
            if (geaendert.isNotEmpty()) speichern(geaendert)
            geaendert
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        }
    }
}
