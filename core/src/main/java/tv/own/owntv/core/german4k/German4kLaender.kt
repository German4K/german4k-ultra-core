package tv.own.owntv.core.german4k

import org.json.JSONObject

/**
 * German4K 3.2 (N8/C3): „Länder & Bereiche“ in der Länder-Ansicht — `POST /api/app/ultra/bereiche` mit
 * `ansicht: "laender"`.
 *
 * Je Land drei Schalter (Live · Filme · Serien), darunter die einzelnen Bereiche. Der Server liefert
 * in `typen` nur die Typen, die es in diesem Land gibt; das Sonderland `weitere` (★) hat gar keine und
 * lässt sich nur bereichsweise schalten. Erwachsenes steht hier nie — das regelt der Server.
 *
 * `ok=false` mit `grund` ist eine Antwort, kein Fehler: der alte Stand kommt trotzdem mit, und die
 * Oberfläche zeigt den Grund (auch „hat sich nicht geändert“). Ein Transportfehler ist `ok=false`
 * ohne Länder und ohne Grund — dann zeigt die App ihren eigenen Text.
 */
data class German4kLaenderAntwort(
    val ok: Boolean,
    val grund: String?,
    /** Konfigurator-Fassung des Kunden (1 oder 2) — nur zur Einordnung, die App schaltet gleich. */
    val version: Int?,
    val laender: List<German4kLand>,
    /** Nach dem Schalten: „Fertig – 🇬🇧 Live: 312 Sender kommen dazu“. */
    val meldung: String?,
    /** Nach dem Schalten, optional: z. B. Hinweis auf eine sehr große Liste. */
    val warnung: String?,
    val bereicheStand: String?,
) {
    companion object {
        /** Kaputtes JSON → null (= Transportfehler beim Aufrufer). */
        fun parse(text: String): German4kLaenderAntwort? {
            val o = runCatching { JSONObject(text) }.getOrNull() ?: return null
            val arr = o.optJSONArray("laender")
            val laender = buildList {
                if (arr != null) for (i in 0 until arr.length()) {
                    val l = arr.optJSONObject(i) ?: continue
                    val code = l.optString("land").trim()
                    if (code.isEmpty()) continue
                    add(landAus(code, l))
                }
            }
            return German4kLaenderAntwort(
                ok = o.optBoolean("ok", false),
                grund = text(o, "grund"),
                version = if (o.has("version") && !o.isNull("version")) o.optInt("version") else null,
                laender = laender,
                meldung = text(o, "meldung"),
                warnung = text(o, "warnung"),
                bereicheStand = text(o, "bereiche_stand"),
            )
        }

        private fun landAus(code: String, l: JSONObject): German4kLand {
            val typenObj = l.optJSONObject("typen")
            val typen = buildMap {
                LAND_TYPEN.forEach { t ->
                    val x = typenObj?.optJSONObject(t) ?: return@forEach
                    put(t, German4kLandTyp(an = x.optBoolean("an", false), sender = x.optInt("sender", 0)))
                }
            }
            val barr = l.optJSONArray("bereiche")
            val bereiche = buildList {
                if (barr != null) for (i in 0 until barr.length()) {
                    val b = barr.optJSONObject(i) ?: continue
                    add(
                        German4kLandBereich(
                            id = b.optInt("id"),
                            name = b.optString("name"),
                            sender = b.optInt("sender"),
                            an = b.optBoolean("an"),
                            standard = b.optBoolean("standard"),
                            typ = b.optString("typ").ifBlank { TYP_LIVE },
                        ),
                    )
                }
            }
            return German4kLand(
                land = code,
                name = l.optString("name").ifBlank { code.uppercase() },
                flagge = text(l, "flagge"),
                typen = typen,
                bereiche = bereiche,
            )
        }

        private fun text(o: JSONObject, feld: String): String? =
            if (!o.has(feld) || o.isNull(feld)) null else o.optString(feld).trim().takeIf { it.isNotEmpty() }

        const val TYP_LIVE = "live"
        const val TYP_MOVIE = "movie"
        const val TYP_SERIES = "series"

        /** Reihenfolge der Schalter auf der Karte: Live · Filme · Serien. */
        val LAND_TYPEN = listOf(TYP_LIVE, TYP_MOVIE, TYP_SERIES)

        /** Das Sonderland ohne Länderschalter (★). */
        const val LAND_WEITERE = "weitere"
    }
}

data class German4kLand(
    val land: String,
    val name: String,
    val flagge: String?,
    /** Nur die Typen, die es in diesem Land gibt (Schlüssel `live`/`movie`/`series`). */
    val typen: Map<String, German4kLandTyp>,
    val bereiche: List<German4kLandBereich>,
) {
    /** Länderschalter gibt es nicht für `weitere` und nicht für Länder ohne Typen. */
    val hatLandSchalter: Boolean get() = land != German4kLaenderAntwort.LAND_WEITERE && typen.isNotEmpty()

    /** Die Bereiche eines Typs, für die aufgeklappte Karte. */
    fun bereicheVon(typ: String): List<German4kLandBereich> = bereiche.filter { it.typ == typ }
}

data class German4kLandTyp(val an: Boolean, val sender: Int)

data class German4kLandBereich(
    val id: Int,
    val name: String,
    val sender: Int,
    val an: Boolean,
    val standard: Boolean,
    val typ: String,
)

/** Was die App schalten will: ein ganzes Land je Typ oder einen einzelnen Bereich. */
sealed interface German4kLaenderSchalter {
    val typ: String
    val an: Boolean

    data class Land(val land: String, override val typ: String, override val an: Boolean) : German4kLaenderSchalter
    data class Bereich(val id: Int, override val typ: String, override val an: Boolean) : German4kLaenderSchalter
}

/** Anfragekörper — rein, damit er ohne Netz geprüft werden kann. */
fun laenderAnfrage(deviceId: String, schalter: German4kLaenderSchalter?): JSONObject = JSONObject().apply {
    put("app_device_id", deviceId)
    put("ansicht", "laender")
    when (schalter) {
        null -> put("typ", German4kLaenderAntwort.TYP_LIVE)
        is German4kLaenderSchalter.Land -> { put("land", schalter.land); put("typ", schalter.typ); put("an", schalter.an) }
        is German4kLaenderSchalter.Bereich -> { put("id", schalter.id); put("typ", schalter.typ); put("an", schalter.an) }
    }
}
