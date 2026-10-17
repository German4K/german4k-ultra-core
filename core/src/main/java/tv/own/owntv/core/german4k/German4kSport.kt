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
    /**
     * German4K 3.2: Reiter vom Server (nur Wettbewerbe mit Spielen HEUTE, schon sortiert) oder null bei einem
     * alten Server — dann bleiben die festen Chips. „Alle“ setzt immer die App davor.
     */
    val reiter: List<German4kSportReiter>? = null,
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
                reiter = reiterAus(o),
            )
        }

        /** Fehlt `reiter` (oder ist es kein Array) → null. Einträge ohne id/titel/wettbewerbe und „alle“ fallen weg. */
        internal fun reiterAus(o: JSONObject): List<German4kSportReiter>? {
            val arr = o.optJSONArray("reiter") ?: return null
            val gesehen = HashSet<String>()
            return buildList {
                for (i in 0 until arr.length()) {
                    val r = arr.optJSONObject(i) ?: continue
                    val id = r.optString("id").trim()
                    val titel = r.optString("titel").trim()
                    if (id.isEmpty() || titel.isEmpty() || id.equals(SportTab.ALLE_ID, ignoreCase = true) || !gesehen.add(id)) continue
                    val w = r.optJSONArray("wettbewerbe") ?: continue
                    val wettbewerbe = (0 until w.length()).mapNotNull { j -> (w.opt(j) as? String)?.trim()?.takeIf { it.isNotEmpty() } }
                    if (wettbewerbe.isEmpty()) continue
                    add(German4kSportReiter(id, titel, wettbewerbe))
                }
            }
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
                // German4K 3.2 (N4/C2): nur bei `art = "konferenz"` gefüllt; fehlt bei alten Servern → leer/null.
                paarungen = paarungenAus(s.optJSONArray("paarungen")),
                bestaetigt = if (!s.has("bestaetigt") || s.isNull("bestaetigt")) null else s.optBoolean("bestaetigt"),
                // German4K 3.2 (C5): Vereinswappen; fehlen bei alten Servern → null, die App zeigt dann Initialen.
                heimLogo = logoUrl(s, "heim_logo"),
                gastLogo = logoUrl(s, "gast_logo"),
                // German4K 3.2 (C6): Wettbewerbslogo (Spiel und Konferenz); fehlt bei alten Servern → null, dann kein Logo.
                wettbewerbLogo = logoUrl(s, "wettbewerb_logo"),
            )
        }

        /**
         * German4K 3.2 (C5): Wappen-URL nur, wenn sie wirklich ein Bild laden kann — http(s) mit Host. Leer,
         * „null“ als Text oder Unsinn → null, damit die Karte Initialen zeigt statt eines kaputten Bildes.
         */
        internal fun logoUrl(o: JSONObject, feld: String): String? {
            val roh = textOderNull(o, feld)?.trim() ?: return null
            val uri = runCatching { java.net.URI(roh) }.getOrNull() ?: return null
            val schema = uri.scheme?.lowercase()
            if (schema != "https" && schema != "http") return null
            if (uri.host.isNullOrBlank()) return null
            return roh
        }

        private fun paarungenAus(arr: JSONArray?): List<German4kPaarung> = buildList {
            if (arr != null) for (i in 0 until arr.length()) {
                val p = arr.optJSONObject(i) ?: continue
                val heim = p.optString("heim").trim()
                val gast = p.optString("gast").trim()
                if (heim.isEmpty() && gast.isEmpty()) continue
                add(German4kPaarung(heim, gast, zeitpunkt(p.optString("start")), logoUrl(p, "heim_logo"), logoUrl(p, "gast_logo")))
            }
        }
    }

    fun toJson(): String = JSONObject().apply {
        put("ok", ok)
        put("grund", grund ?: JSONObject.NULL)
        put("stand", stand ?: JSONObject.NULL)
        put("server_time", serverTime)
        reiter?.let { l ->
            put("reiter", JSONArray().apply {
                l.forEach { r -> put(JSONObject().put("id", r.id).put("titel", r.titel).put("wettbewerbe", JSONArray(r.wettbewerbe))) }
            })
        }
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
                    if (s.paarungen.isNotEmpty()) put("paarungen", JSONArray().apply {
                        s.paarungen.forEach { p ->
                            put(
                                JSONObject().put("heim", p.heim).put("gast", p.gast).put("start", p.start?.toString() ?: JSONObject.NULL)
                                    .put("heim_logo", p.heimLogo ?: JSONObject.NULL).put("gast_logo", p.gastLogo ?: JSONObject.NULL),
                            )
                        }
                    })
                    put("bestaetigt", s.bestaetigt ?: JSONObject.NULL)
                    put("heim_logo", s.heimLogo ?: JSONObject.NULL)
                    put("gast_logo", s.gastLogo ?: JSONObject.NULL)
                    put("wettbewerb_logo", s.wettbewerbLogo ?: JSONObject.NULL)
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
    /** German4K 3.2 (N4): die Spiele einer Konferenz, klein unter der Karte. Sonst leer. */
    val paarungen: List<German4kPaarung> = emptyList(),
    /** Konferenz: true = EPG des Senders sagt „Konferenz“, false = laut Spielplan, null = unbekannt. */
    val bestaetigt: Boolean? = null,
    /** German4K 3.2 (C5): Wappen (quadratische PNG) oder null → Initialen-Platzhalter. */
    val heimLogo: String? = null,
    val gastLogo: String? = null,
    /**
     * German4K 3.2 (C6): Logo des Wettbewerbs (quadratische, transparente PNG) oder null. Anders als beim Wappen
     * gibt es KEINEN Ersatz: ohne Logo bleibt die Stelle leer, damit die Karte genauso aussieht wie in 3.1.
     */
    val wettbewerbLogo: String? = null,
) {
    val laeuft: Boolean get() = zustand == ZUSTAND_LAEUFT
    val beendet: Boolean get() = zustand == ZUSTAND_BEENDET

    /** German4K 3.2 (N4/C2): Konferenz-Eintrag — die App zeigt dafür eine eigene Karte ganz oben. */
    val istKonferenz: Boolean get() = art == ART_KONFERENZ

    companion object {
        const val ART_KONFERENZ = "konferenz"
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

/** German4K 3.2 (N4): eine Paarung einer Konferenz. [start] fehlt, wenn der Server sie nicht mitgibt. */
data class German4kPaarung(
    val heim: String,
    val gast: String,
    val start: Instant?,
    /** German4K 3.2 (C5): Wappen je Paarung, null → Initialen. */
    val heimLogo: String? = null,
    val gastLogo: String? = null,
)

// ---------------------------------------------------------------------------------------------
// German4K 3.2 (C5): Initialen als Ersatz, wenn ein Verein (noch) kein Wappen hat.
// ---------------------------------------------------------------------------------------------

/**
 * Rechtsform-/Vereinskürzel, die in fast jedem Namen stehen und deshalb nichts unterscheiden
 * („1. FC Köln“ und „1. FC Union Berlin“ wären sonst beide „FC…“). Kleingeschrieben, ohne Punkt.
 */
private val VEREINS_KUERZEL: Set<String> =
    "fc sv vfb tsg vfl sc tsv fsv ssv spvgg bv bsc sg fk nk sk ac as afc cf cd ssc us ev club".split(' ').toSet()

/** Kleine Füllwörter in Vereinsnamen („Atlético de Madrid“ → „AM“). */
private val FUELLWOERTER: Set<String> = "de del la di da van von der le les y e".split(' ').toSet()

/** Trenner zwischen Wörtern eines Vereinsnamens: Leerraum, Bindestrich, Schrägstrich, Punkt. */
private val NAMENS_TRENNER = Regex("[\\s\\-/.]+")

/**
 * 1–3 Initialen eines Vereinsnamens für den runden Platzhalter: „Eintracht Frankfurt“ → „EF“,
 * „1. FC Köln“ → „K“, „Paris Saint-Germain“ → „PSG“, „Bayer 04 Leverkusen“ → „BL“. Zahlen, Kürzel und
 * Füllwörter fallen weg; bleibt nichts übrig, zählen doch die Kürzel („FC“ → „F“). Leer → "".
 */
fun vereinsInitialen(name: String): String {
    val woerter = name.split(NAMENS_TRENNER).map { it.trim('\'', '(', ')', ',') }.filter { it.isNotEmpty() }
    val mitBuchstabe = woerter.filter { w -> w.any { it.isLetter() } }
    val kern = mitBuchstabe.filter { it.lowercase() !in VEREINS_KUERZEL && it.lowercase() !in FUELLWOERTER }
    val basis = kern.ifEmpty { mitBuchstabe }
    return buildString { basis.mapNotNull { w -> w.firstOrNull { it.isLetter() }?.uppercaseChar() }.take(3).forEach { append(it) } }
}

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

/** German4K 3.2: ein Reiter vom Server — Filter = Spiele, deren `wettbewerb` in [wettbewerbe] steht. */
data class German4kSportReiter(val id: String, val titel: String, val wettbewerbe: List<String>)

/**
 * German4K 3.2: ein Reiter über der Liste — entweder ein fester Chip (alter Server, [chip] gesetzt, Text aus
 * strings.xml) oder ein Server-Reiter ([titel] + [wettbewerbe]). „Alle“ ist immer der feste Chip ALLE.
 */
data class SportTab(val id: String, val chip: SportChip?, val titel: String?, val wettbewerbe: List<String>?) {
    companion object {
        const val ALLE_ID = "alle"
        val ALLE = SportTab(ALLE_ID, SportChip.ALLE, null, null)
    }
}

/** Reiter zur Antwort: mit `reiter` „Alle“ + genau diese, sonst die festen Chips wie bisher. */
fun sportTabs(reiter: List<German4kSportReiter>?): List<SportTab> =
    if (reiter == null) SportChip.entries.map { if (it == SportChip.ALLE) SportTab.ALLE else SportTab(it.name.lowercase(), it, null, null) }
    else listOf(SportTab.ALLE) + reiter.map { SportTab(it.id, null, it.titel, it.wettbewerbe) }

/** Spiele zum gewählten Reiter. Server-Reiter: Wettbewerb exakt (ohne Groß/Klein, getrimmt) in der Liste. */
fun tabFilter(spiele: List<German4kSpiel>, tab: SportTab): List<German4kSpiel> {
    val liste = tab.wettbewerbe ?: return chipFilter(spiele, tab.chip ?: SportChip.ALLE)
    val namen = liste.map { it.trim().lowercase() }.toSet()
    return spiele.filter { s -> s.wettbewerb?.trim()?.lowercase()?.let { it in namen } == true }
}

/** Die vier Regale der Übersicht. */
data class SportRegale(
    /** German4K 3.2 (N4): Konferenz-Karten ganz oben — laufende zuerst, dann nach Anstoß; beendete fallen weg. */
    val konferenzen: List<German4kSpiel> = emptyList(),
    val live: List<German4kSpiel> = emptyList(),
    val heute: List<German4kSpiel> = emptyList(),
    val spaeter: List<German4kSpiel> = emptyList(),
    val beendet: List<German4kSpiel> = emptyList(),
) {
    val leer: Boolean get() = konferenzen.isEmpty() && live.isEmpty() && heute.isEmpty() && spaeter.isEmpty() && beendet.isEmpty()
}

/** Zeitzone, in der „heute" gilt. */
val SPORT_ZONE: ZoneId = ZoneId.of("Europe/Berlin")

/**
 * Einsortieren: live nach Relevanz; heute (geplant, Starttag in [zone] == [heute]) nach Anstoß, dann
 * Relevanz; später = übrige geplante nach Anstoß; beendet für sich, jüngste zuerst.
 */
fun regale(
    alle: List<German4kSpiel>,
    heute: LocalDate,
    zone: ZoneId = SPORT_ZONE,
    /** German4K 3.2: Notschalter `konferenz_karte` — aus ⇒ Konferenz steht wie ein Spiel in den Regalen (3.1.1-Zeile). */
    konferenzKarte: Boolean = German4kFeatures.an(German4kSchalter.KONFERENZ_KARTE),
): SportRegale {
    // German4K 3.2 (N4): Konferenzen bekommen ihre eigene Karte und stehen nicht noch einmal in den Regalen.
    val (konf, spiele) = if (konferenzKarte) alle.partition { it.istKonferenz } else emptyList<German4kSpiel>() to alle
    val konferenzen = konf.filter { !it.beendet }.sortedWith(compareByDescending<German4kSpiel> { it.laeuft }.thenBy { it.start })
    val live = spiele.filter { it.laeuft }.sortedByDescending { it.relevanz }
    val beendet = spiele.filter { it.beendet }.sortedByDescending { it.start }
    val geplant = spiele.filter { !it.laeuft && !it.beendet }
    val (heuteListe, rest) = geplant.partition { it.start.atZone(zone).toLocalDate() == heute }
    return SportRegale(
        konferenzen = konferenzen,
        live = live,
        heute = heuteListe.sortedWith(compareBy<German4kSpiel> { it.start }.thenByDescending { it.relevanz }),
        spaeter = rest.sortedBy { it.start },
        beendet = beendet,
    )
}

// ---------------------------------------------------------------------------------------------
// German4K 3.0/32: Karten-Fußzeile, Senderzeilen, Spielminute, Tagesgruppen (reine Helfer).
// ---------------------------------------------------------------------------------------------

/** Sprache, die eine Senderflagge verspricht. Unbekannte Flaggen → keine Sprache (nur Flagge). */
enum class SportSprache { DEUTSCH, ENGLISCH, SPANISCH, FRANZOESISCH, ITALIENISCH, TUERKISCH }

private const val REGIONAL_A = 0x1F1E6
private const val REGIONAL_Z = 0x1F1FF

private fun istRegional(cp: Int) = cp in REGIONAL_A..REGIONAL_Z

/** Führendes Flaggen-Emoji (Paar Regional-Indicator) von [text] oder null. */
fun fuehrendeFlagge(text: String?): String? {
    val t = text?.trimStart() ?: return null
    if (t.isEmpty()) return null
    val a = t.codePointAt(0)
    if (!istRegional(a)) return null
    val i = Character.charCount(a)
    if (i >= t.length) return null
    val b = t.codePointAt(i)
    if (!istRegional(b)) return null
    return t.substring(0, i + Character.charCount(b))
}

/** Ländercode einer Flagge ("🇩🇪" → "DE") oder null. */
fun flaggenLand(flagge: String?): String? {
    val f = fuehrendeFlagge(flagge) ?: return null
    val a = f.codePointAt(0)
    val b = f.codePointAt(Character.charCount(a))
    return buildString {
        append('A' + (a - REGIONAL_A))
        append('A' + (b - REGIONAL_A))
    }
}

private val SPRACHE_JE_LAND: Map<String, SportSprache> = buildMap {
    listOf("DE", "AT", "CH").forEach { put(it, SportSprache.DEUTSCH) }
    listOf("GB", "US", "CA", "IE").forEach { put(it, SportSprache.ENGLISCH) }
    put("ES", SportSprache.SPANISCH)
    put("FR", SportSprache.FRANZOESISCH)
    put("IT", SportSprache.ITALIENISCH)
    put("TR", SportSprache.TUERKISCH)
}

/** Sprache aus der Flagge: DE/AT/CH Deutsch, GB/US/CA/IE Englisch, ES, FR, IT, TR; sonst null. */
fun spracheVonFlagge(flagge: String?): SportSprache? = flaggenLand(flagge)?.let { SPRACHE_JE_LAND[it] }

/** [text] ohne führende Flagge, getrimmt. */
fun ohneFlagge(text: String): String {
    val f = fuehrendeFlagge(text) ?: return text.trim()
    return text.trimStart().substring(f.length).trim()
}

private val ENDE_NUMMER = Regex("\\s+\\d+$")
private val ENDE_PPV = Regex("\\s+PPV$", RegexOption.IGNORE_CASE)

/**
 * Marke eines Senders: Kategorie ohne Flagge und ohne „ PPV" am Ende ("🇩🇪 Soccer PPV" → "Soccer").
 * Ohne Kategorie: Name ohne Flagge, ohne Nummer und ohne „ PPV" am Ende ("🇩🇪 DAZN PPV 12" → "DAZN").
 */
fun senderMarke(sender: German4kSportSender): String {
    val aus = sender.kategorie?.let { ENDE_PPV.replace(ohneFlagge(it), "").trim() }?.takeIf { it.isNotEmpty() }
    if (aus != null) return aus
    val name = ohneFlagge(sender.name)
    val ohneNr = ENDE_NUMMER.replace(name, "").trim()
    return ENDE_PPV.replace(ohneNr, "").trim().ifEmpty { ohneNr.ifEmpty { name } }
}

/** Was eine Senderzeile zeigt: große Flagge, Titel (Name ohne Flagge), Sprache, Marke. */
data class SportSenderAnzeige(
    val flagge: String?,
    val titel: String,
    val sprache: SportSprache?,
    val marke: String,
)

fun senderAnzeige(sender: German4kSportSender): SportSenderAnzeige {
    val flagge = fuehrendeFlagge(sender.name) ?: fuehrendeFlagge(sender.kategorie)
    return SportSenderAnzeige(
        flagge = flagge,
        titel = ohneFlagge(sender.name).ifEmpty { sender.name },
        sprache = spracheVonFlagge(flagge),
        marke = senderMarke(sender),
    )
}

/** Fußzeile einer Spielkarte nach der Kaskade Sender → Rechte → Bereich. */
sealed interface SportFusszeile {
    /** „🇩🇪 Deutsch auf DAZN · 10 weitere" — [weitere] = Sender minus eins. */
    data class Sender(val flagge: String?, val sprache: SportSprache?, val marke: String, val weitere: Int) : SportFusszeile
    data class Rechte(val sender: String) : SportFusszeile
    data class Bereich(val name: String) : SportFusszeile
    data object Keine : SportFusszeile
}

fun sportFusszeile(spiel: German4kSpiel): SportFusszeile {
    val erster = spiel.sender.firstOrNull()
    return when {
        erster != null -> senderAnzeige(erster).let { SportFusszeile.Sender(it.flagge, it.sprache, it.marke, spiel.sender.size - 1) }
        spiel.rechte != null -> SportFusszeile.Rechte(spiel.rechte.sender)
        spiel.bereich != null -> SportFusszeile.Bereich(spiel.bereich.name)
        else -> SportFusszeile.Keine
    }
}

/** Anzeige der Spielminute im LIVE-Chip. */
sealed interface SportMinute {
    data class Minute(val n: Int) : SportMinute
    data object Halbzeit : SportMinute
    data object Nachspielzeit : SportMinute
}

/**
 * Grobe Spielminute aus der Zeit seit Anstoß (kein Live-Ticker): 1–45 → n′, 46–60 → Halbzeit,
 * danach n−15, ab 90 → „90+′". Vor dem Anstoß → 1′.
 */
fun sportMinute(start: Instant, jetzt: Instant): SportMinute {
    val n = maxOf(1L, java.time.Duration.between(start, jetzt).toMinutes()).toInt()
    return when {
        n <= 45 -> SportMinute.Minute(n)
        n <= 60 -> SportMinute.Halbzeit
        n - 15 <= 90 -> SportMinute.Minute(n - 15)
        else -> SportMinute.Nachspielzeit
    }
}

/** „Morgen & später" nach Kalendertag in [zone] gruppiert, Tage aufsteigend, Reihenfolge je Tag bleibt. */
fun nachTagen(spiele: List<German4kSpiel>, zone: ZoneId = SPORT_ZONE): List<Pair<LocalDate, List<German4kSpiel>>> =
    spiele.groupBy { it.start.atZone(zone).toLocalDate() }.toList().sortedBy { it.first }

/** „Morgen & später" ist offen, wenn heute höchstens so viele Spiele laufen. */
const val SPAETER_OFFEN_BIS = 8

fun spaeterStandardOffen(heuteAnzahl: Int): Boolean = heuteAnzahl <= SPAETER_OFFEN_BIS

/** Sender der Spielseite: eingeschaltete/unbekannte oben, ausgeschaltete ([an] == false) für den Aufklapper. */
data class SportSenderTeilung(
    val an: List<German4kSportSender>,
    val aus: List<German4kSportSender>,
) {
    /** Anzahl Länder unter [aus] — nach Flagge, ersatzweise Gruppe/Kategorie/Name. */
    val ausLaender: Int
        get() = aus.map { flaggenLand(fuehrendeFlagge(it.name) ?: fuehrendeFlagge(it.kategorie)) ?: it.gruppe ?: it.gruppeName ?: it.kategorie ?: it.name }
            .distinct().size
}

fun senderTeilen(sender: List<German4kSportSender>): SportSenderTeilung {
    val (aus, an) = sender.partition { it.an == false }
    return SportSenderTeilung(an = an, aus = aus)
}
