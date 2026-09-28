package tv.own.owntv.core.german4k

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId

/**
 * Sport-Hub (Bereich „Fußball"): Antwort von `POST https://german4k.com/api/app/sport`.
 *
 * Der Server antwortet immer mit 200. `ok=false` + `grund` heißt: nicht gekoppelt, Bereich aus oder
 * gesperrt — das ist eine Antwort, kein Fehler. Ein Transportfehler dagegen ist `null` beim Aufrufer.
 *
 * Kaskade (verbindlich, die App erfindet keine fünfte Stufe): Senderliste (Eventplatz zuerst, dann
 * bestätigte Sender) → [German4kSportRechte] (Marke, mit Kategorie zum Öffnen) → [German4kSportBereich].
 * Eine `streamId` wird nie zwischengespeichert: vor jedem Abspielen neu holen.
 */
data class German4kSportAntwort(
    val ok: Boolean,
    val grund: String?,
    /** Stand des Spielplans (UTC, ISO) oder null. */
    val stand: String?,
    val serverTime: String,
    val spiele: List<German4kSpiel>,
) {
    companion object {
        /** Tolerant wie [German4kPanelAnswer.parse]; kaputtes JSON → null. `events` wird ignoriert. */
        fun parse(text: String): German4kSportAntwort? {
            val o = runCatching { JSONObject(text) }.getOrNull() ?: return null
            val arr = o.optJSONArray("spiele")
            val spiele = buildList {
                if (arr != null) for (i in 0 until arr.length()) {
                    arr.optJSONObject(i)?.let { s -> spielAus(s)?.let { add(it) } }
                }
            }
            return German4kSportAntwort(
                ok = o.optBoolean("ok", false),
                grund = textOderNull(o, "grund"),
                stand = textOderNull(o, "stand"),
                serverTime = o.optString("server_time"),
                spiele = spiele,
            )
        }

        private fun textOderNull(o: JSONObject, feld: String): String? =
            if (!o.has(feld) || o.isNull(feld)) null else o.optString(feld).takeIf { it.isNotBlank() }

        private fun zeitpunkt(text: String): Instant? =
            runCatching { Instant.parse(text) }.getOrNull()
                ?: runCatching { OffsetDateTime.parse(text).toInstant() }.getOrNull()

        private fun spielAus(s: JSONObject): German4kSpiel? {
            val start = zeitpunkt(s.optString("start")) ?: return null
            val senderArr = s.optJSONArray("sender")
            val sender = buildList {
                if (senderArr != null) for (i in 0 until senderArr.length()) {
                    val x = senderArr.optJSONObject(i) ?: continue
                    val ids = x.optJSONArray("streamIds")
                    add(
                        German4kSportSender(
                            name = x.optString("name"),
                            kategorie = textOderNull(x, "kategorie"),
                            streamIds = if (ids == null) emptyList() else (0 until ids.length()).map { ids.optLong(it) }.filter { it > 0 },
                            an = if (!x.has("an") || x.isNull("an")) null else x.optBoolean("an"),
                            gruppe = textOderNull(x, "gruppe"),
                            gruppeName = textOderNull(x, "gruppeName"),
                            platz = x.optBoolean("platz", false),
                            platzZustand = textOderNull(x, "platzZustand"),
                        ),
                    )
                }
            }
            return German4kSpiel(
                id = s.optLong("id"),
                titel = s.optString("titel"),
                heim = s.optString("heim"),
                gast = s.optString("gast"),
                wettbewerb = textOderNull(s, "wettbewerb"),
                start = start,
                startDE = s.optString("startDE"),
                zustand = s.optString("zustand"),
                relevanz = s.optDouble("relevanz", 0.0).takeUnless { it.isNaN() } ?: 0.0,
                art = s.optString("art"),
                sender = sender,
                senderUnbekannt = s.optBoolean("senderUnbekannt", false),
                bereich = s.optJSONObject("bereich")?.let { b -> German4kSportBereich(b.optString("name"), textOderNull(b, "hinweis")) },
                rechte = s.optJSONObject("rechte")?.let { r ->
                    German4kSportRechte(r.optString("sender"), textOderNull(r, "hinweis"), textOderNull(r, "kategorie"))
                },
            )
        }
    }

    fun toJson(): String = JSONObject().apply {
        put("ok", ok)
        put("grund", grund ?: JSONObject.NULL)
        put("stand", stand ?: JSONObject.NULL)
        put("server_time", serverTime)
        put("spiele", JSONArray().apply {
            spiele.forEach { s ->
                put(JSONObject().apply {
                    put("id", s.id); put("titel", s.titel); put("heim", s.heim); put("gast", s.gast)
                    put("wettbewerb", s.wettbewerb ?: JSONObject.NULL)
                    put("start", s.start.toString()); put("startDE", s.startDE); put("zustand", s.zustand)
                    put("relevanz", s.relevanz); put("art", s.art); put("senderUnbekannt", s.senderUnbekannt)
                    put("sender", JSONArray().apply {
                        s.sender.forEach { x ->
                            put(JSONObject().apply {
                                put("name", x.name); put("kategorie", x.kategorie ?: JSONObject.NULL)
                                put("streamIds", JSONArray(x.streamIds))
                                put("an", x.an ?: JSONObject.NULL)
                                put("gruppe", x.gruppe ?: JSONObject.NULL); put("gruppeName", x.gruppeName ?: JSONObject.NULL)
                                put("platz", x.platz); put("platzZustand", x.platzZustand ?: JSONObject.NULL)
                            })
                        }
                    })
                    put("bereich", s.bereich?.let { b -> JSONObject().put("name", b.name).put("hinweis", b.hinweis ?: JSONObject.NULL) } ?: JSONObject.NULL)
                    put("rechte", s.rechte?.let { r ->
                        JSONObject().put("sender", r.sender).put("hinweis", r.hinweis ?: JSONObject.NULL).put("kategorie", r.kategorie ?: JSONObject.NULL)
                    } ?: JSONObject.NULL)
                })
            }
        })
    }.toString()
}

/** Ein Spiel im Plan. [start] ist UTC; [startDE] der fertige deutsche Text vom Server. */
data class German4kSpiel(
    val id: Long,
    val titel: String,
    val heim: String,
    val gast: String,
    val wettbewerb: String?,
    val start: Instant,
    val startDE: String,
    /** geplant | laeuft | beendet */
    val zustand: String,
    val relevanz: Double,
    val art: String,
    /** Eventplatz zuerst, dann bestätigte Sender; streamIds je Sender beste Stufe zuerst. */
    val sender: List<German4kSportSender>,
    val senderUnbekannt: Boolean,
    val bereich: German4kSportBereich?,
    val rechte: German4kSportRechte?,
) {
    val laeuft: Boolean get() = zustand == ZUSTAND_LAEUFT
    val beendet: Boolean get() = zustand == ZUSTAND_BEENDET

    companion object {
        const val ZUSTAND_GEPLANT = "geplant"
        const val ZUSTAND_LAEUFT = "laeuft"
        const val ZUSTAND_BEENDET = "beendet"
    }
}

/** [an] = im Paket des Kunden eingeschaltet (null = unbekannt). [platz] = Eventplatz statt fester Sender. */
data class German4kSportSender(
    val name: String,
    val kategorie: String?,
    val streamIds: List<Long>,
    val an: Boolean?,
    val gruppe: String?,
    val gruppeName: String?,
    val platz: Boolean,
    val platzZustand: String?,
)

data class German4kSportBereich(val name: String, val hinweis: String?)

data class German4kSportRechte(val sender: String, val hinweis: String?, val kategorie: String? = null)

/** Filterchips über der Liste. */
enum class SportChip { ALLE, BUNDESLIGA, CL, POKAL, INTERNATIONAL }

private val CHIP_WOERTER = mapOf(
    SportChip.BUNDESLIGA to "bundesliga",
    SportChip.CL to "champions",
    SportChip.POKAL to "pokal",
)

/** Spiele zum gewählten Chip. INTERNATIONAL = Wettbewerb bekannt, aber keiner der drei deutschen/CL-Töpfe. */
fun chipFilter(spiele: List<German4kSpiel>, chip: SportChip): List<German4kSpiel> = when (chip) {
    SportChip.ALLE -> spiele
    SportChip.INTERNATIONAL -> spiele.filter { s ->
        val w = s.wettbewerb?.lowercase() ?: return@filter false
        CHIP_WOERTER.values.none { w.contains(it) }
    }
    else -> {
        val wort = CHIP_WOERTER.getValue(chip)
        spiele.filter { it.wettbewerb?.lowercase()?.contains(wort) == true }
    }
}

/** Die vier Regale der Übersicht. */
data class SportRegale(
    val live: List<German4kSpiel> = emptyList(),
    val heute: List<German4kSpiel> = emptyList(),
    val spaeter: List<German4kSpiel> = emptyList(),
    val beendet: List<German4kSpiel> = emptyList(),
) {
    val leer: Boolean get() = live.isEmpty() && heute.isEmpty() && spaeter.isEmpty() && beendet.isEmpty()
}

/** Zeitzone, in der „heute" gilt. */
val SPORT_ZONE: ZoneId = ZoneId.of("Europe/Berlin")

/**
 * Einsortieren: live nach Relevanz; heute (geplant, Starttag in [zone] == [heute]) nach Anstoß, dann
 * Relevanz; später = übrige geplante nach Anstoß; beendet für sich, jüngste zuerst.
 */
fun regale(spiele: List<German4kSpiel>, heute: LocalDate, zone: ZoneId = SPORT_ZONE): SportRegale {
    val live = spiele.filter { it.laeuft }.sortedByDescending { it.relevanz }
    val beendet = spiele.filter { it.beendet }.sortedByDescending { it.start }
    val geplant = spiele.filter { !it.laeuft && !it.beendet }
    val (heuteListe, rest) = geplant.partition { it.start.atZone(zone).toLocalDate() == heute }
    return SportRegale(
        live = live,
        heute = heuteListe.sortedWith(compareBy<German4kSpiel> { it.start }.thenByDescending { it.relevanz }),
        spaeter = rest.sortedBy { it.start },
        beendet = beendet,
    )
}
