package tv.own.owntv.core.german4k

import org.json.JSONArray
import org.json.JSONObject
import tv.own.owntv.core.database.entity.ChannelEntity
import java.time.Instant
import java.time.OffsetDateTime

/**
 * Virtuelle 24/7-Sender (Weg B): Antwort von `POST https://german4k.com/api/app/virtuell`.
 * Vertrag 1 — docs/superpowers/specs/2026-10-04-virtuelle-24-7-sender.md §3 im Website-Repo.
 *
 * Immer HTTP 200; `ok=false` + `grund` ist eine Antwort, kein Fehler. Ein Transportfehler ist `null` beim
 * Aufrufer — dann spielt der Sender ganz normal (Weg A). Plan-Einträge ohne Zeiten oder ohne `url` fallen weg.
 */
data class German4kVirtuellAntwort(
    val ok: Boolean,
    val grund: String?,
    val vertrag: Int,
    val serverTime: Instant?,
    val stufe: String?,
    val sender: List<German4kVirtuellSender>,
) {
    companion object {
        fun parse(text: String): German4kVirtuellAntwort? {
            val o = runCatching { JSONObject(text) }.getOrNull() ?: return null
            val arr = o.optJSONArray("sender")
            return German4kVirtuellAntwort(
                ok = o.optBoolean("ok", false),
                grund = textOderNull(o, "grund"),
                vertrag = o.optInt("vertrag", 0),
                serverTime = zeitpunkt(o.optString("server_time")),
                stufe = textOderNull(o, "stufe"),
                sender = buildList {
                    if (arr != null) for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { senderAus(it)?.let(::add) }
                },
            )
        }

        private fun textOderNull(o: JSONObject, feld: String): String? =
            if (!o.has(feld) || o.isNull(feld)) null else o.optString(feld).takeIf { it.isNotBlank() }

        private fun zahlOderNull(o: JSONObject, feld: String): Int? =
            if (!o.has(feld) || o.isNull(feld)) null else o.optInt(feld)

        private fun zeitpunkt(text: String?): Instant? {
            if (text.isNullOrBlank()) return null
            return runCatching { Instant.parse(text) }.getOrNull()
                ?: runCatching { OffsetDateTime.parse(text).toInstant() }.getOrNull()
        }

        private fun eintragAus(e: JSONObject): VirtuellEintrag? {
            val start = zeitpunkt(e.optString("start")) ?: return null
            val ende = zeitpunkt(e.optString("ende")) ?: return null
            val url = e.optString("url").takeIf { it.isNotBlank() } ?: return null
            if (!ende.isAfter(start)) return null
            return VirtuellEintrag(
                start = start, ende = ende,
                dauerSek = e.optInt("dauer_sek", ((ende.toEpochMilli() - start.toEpochMilli()) / 1000).toInt()),
                art = e.optString("art", "series"),
                zielId = e.optLong("ziel_id"),
                titel = e.optString("titel"),
                serie = textOderNull(e, "serie"),
                staffel = zahlOderNull(e, "staffel"),
                folge = zahlOderNull(e, "folge"),
                folgentitel = textOderNull(e, "folgentitel"),
                url = url,
            )
        }

        private fun senderAus(s: JSONObject): German4kVirtuellSender? {
            val id = s.optLong("stream_id").takeIf { it > 0 } ?: return null
            val j = s.optJSONObject("jetzt")
            val planArr = s.optJSONArray("plan")
            return German4kVirtuellSender(
                streamId = id,
                slug = s.optString("slug"),
                name = s.optString("name"),
                kurzname = textOderNull(s, "kurzname"),
                kategorie = s.optString("kategorie"),
                categoryId = s.optInt("category_id"),
                nummer = zahlOderNull(s, "nummer"),
                logo = textOderNull(s, "logo"),
                epgId = s.optString("epg_id"),
                jetzt = j?.let(::eintragAus),
                positionSek = j?.optInt("position_sek", 0) ?: 0,
                plan = buildList {
                    if (planArr != null) for (i in 0 until planArr.length()) planArr.optJSONObject(i)?.let { eintragAus(it)?.let(::add) }
                },
            )
        }

        private fun eintragJson(e: VirtuellEintrag) = JSONObject().apply {
            put("start", e.start.toString()); put("ende", e.ende.toString()); put("dauer_sek", e.dauerSek)
            put("art", e.art); put("ziel_id", e.zielId); put("titel", e.titel)
            put("serie", e.serie ?: JSONObject.NULL); put("staffel", e.staffel ?: JSONObject.NULL)
            put("folge", e.folge ?: JSONObject.NULL); put("folgentitel", e.folgentitel ?: JSONObject.NULL)
            put("url", e.url)
        }
    }

    fun toJson(): String = JSONObject().apply {
        put("ok", ok)
        put("grund", grund ?: JSONObject.NULL)
        put("vertrag", vertrag)
        put("server_time", serverTime?.toString() ?: "")
        put("stufe", stufe ?: JSONObject.NULL)
        put("sender", JSONArray().apply {
            sender.forEach { s ->
                put(JSONObject().apply {
                    put("stream_id", s.streamId); put("slug", s.slug); put("name", s.name)
                    put("kurzname", s.kurzname ?: JSONObject.NULL); put("kategorie", s.kategorie)
                    put("category_id", s.categoryId); put("nummer", s.nummer ?: JSONObject.NULL)
                    put("logo", s.logo ?: JSONObject.NULL); put("epg_id", s.epgId); put("virtuell", true)
                    put("jetzt", s.jetzt?.let { eintragJson(it).put("position_sek", s.positionSek) } ?: JSONObject.NULL)
                    put("plan", JSONArray().apply { s.plan.forEach { put(eintragJson(it)) } })
                })
            }
        })
    }.toString()
}

data class German4kVirtuellSender(
    val streamId: Long,
    val slug: String,
    val name: String,
    val kurzname: String?,
    val kategorie: String,
    val categoryId: Int,
    val nummer: Int?,
    val logo: String?,
    val epgId: String,
    val jetzt: VirtuellEintrag?,
    val positionSek: Int,
    val plan: List<VirtuellEintrag>,
)

data class VirtuellEintrag(
    val start: Instant,
    val ende: Instant,
    val dauerSek: Int,
    /** `series` oder `movie`. */
    val art: String,
    val zielId: Long,
    val titel: String,
    val serie: String?,
    val staffel: Int?,
    val folge: Int?,
    val folgentitel: String?,
    val url: String,
)

/** Präfix der EPG-Kennung virtueller Sender — daran (und nur daran) erkennt die App sie in Xtream/M3U. */
const val VIRTUELL_EPG_PRAEFIX = "g4k.v."

val ChannelEntity.istVirtuell: Boolean get() = epgChannelId?.startsWith(VIRTUELL_EPG_PRAEFIX) == true

fun ChannelEntity.virtuellStreamId(): Long? = remoteId?.toLongOrNull()
