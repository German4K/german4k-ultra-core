package tv.own.owntv.core.german4k

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import tv.own.owntv.core.content.AdultCategoryClassifier

/**
 * German4K 3.2 (N1/N2): „🔥 Angesagt" und „🏆 Top 10 bei German4K".
 *
 * Bisher holte das Trend-Regal seine Liste beim fremden Worker des OwnTV-Autors (tmdb.owntv.me) und
 * suchte die Titel dann über Namensvergleich im Katalog — bei unseren Namen („DE - …", „4K-DE - …")
 * ging das oft daneben, und der Dienst gehört nicht uns. Jetzt rechnet unser Nachtlauf
 * (german4k-website, scripts/tv/angesagt.mjs) einmal täglich eine kleine Datei `angesagt.json`
 * (Schnittstelle C1, docs/app-rebrand/ULTRA-3.2-PLAN.md §2) und legt sie auf R2. Jede Fassung trägt
 * dort die `remoteId`, unter der die App den Titel speichert — der Abgleich ist damit eine reine
 * Nachschlage-Arbeit: angezeigt wird nur, was im Katalog DIESES Geräts liegt (der Katalog spiegelt schon
 * Länder/Bereiche/Erotik-Schalter des Kunden). Der Server braucht so keine Liste je Line.
 *
 * Alles hier ist reine Logik ohne Android, damit es als JVM-Test läuft.
 */
enum class AngesagtArt { FILM, SERIE }

/** Höchstens EIN Abzeichen oben links (Entwurf): NEUE FOLGE vor NEU vor 4K. */
enum class AngesagtBadge { NEUE_FOLGE, NEU, VIERK }

/**
 * Eine Fassung laut Datei. [id] = `xtreamId(streams.id, 0)` = die Kennung der Hauptzeile in player_api;
 * [sid] = die rohe `streams.id` (ab Server-Stand 17.10.), siehe [German4kAngesagt.kandidaten].
 */
data class AngesagtFassung(val id: String, val label: String, val codec: String?, val res: String?, val sid: Long? = null)

data class AngesagtEintrag(
    val tmdb: Int,
    val art: AngesagtArt,
    val titel: String,
    val jahr: Int?,
    val rang: Int,
    /** Nur NEUE_FOLGE oder NEU — „4K" entscheidet die App, weil nur sie das Gerät kennt. */
    val badge: AngesagtBadge?,
    val fassungen: List<AngesagtFassung>,
)

data class AngesagtDatei(
    val v: Int,
    val stand: String,
    val angesagt: List<AngesagtEintrag>,
    val topFilme: List<AngesagtEintrag>,
    val topSerien: List<AngesagtEintrag>,
    val neuFilme: List<AngesagtEintrag>,
    val neuSerien: List<AngesagtEintrag>,
) {
    companion object {
        /** Größte Fassung der Schnittstelle, die diese App versteht. Eine neuere wird trotzdem gelesen,
         *  solange die Felder passen — der Server erweitert nur, er bricht nichts. */
        const val VERSION = 1

        /** Top-Listen tragen höchstens zehn Plätze, egal was in der Datei steht. */
        const val TOP_MAX = 10

        /**
         * Liest die Datei. Ungültig (kein JSON, keine Version) → null, dann bleibt das Regal aus.
         * Einzelne kaputte Einträge fallen still heraus: ein Eintrag ohne TMDB-ID, ohne Art oder ohne
         * Fassung kann nichts anzeigen und darf die übrigen nicht mitreißen.
         */
        fun parse(json: String): AngesagtDatei? = runCatching {
            val o = JSONObject(json)
            val v = o.optInt("v", 0)
            if (v < 1) return null
            AngesagtDatei(
                v = v,
                stand = o.optString("stand"),
                angesagt = liste(o.optJSONArray("angesagt")),
                topFilme = liste(o.optJSONArray("top_filme")).filter { it.art == AngesagtArt.FILM }.take(TOP_MAX),
                topSerien = liste(o.optJSONArray("top_serien")).filter { it.art == AngesagtArt.SERIE }.take(TOP_MAX),
                neuFilme = liste(o.optJSONArray("neu_filme")).filter { it.art == AngesagtArt.FILM },
                neuSerien = liste(o.optJSONArray("neu_serien")).filter { it.art == AngesagtArt.SERIE },
            )
        }.getOrNull()

        private fun liste(a: JSONArray?): List<AngesagtEintrag> {
            if (a == null) return emptyList()
            val roh = (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let(::eintrag) }
            // Reihenfolge = Rang (Vertrag C1); bei gleichem Rang bleibt die Dateireihenfolge.
            return roh.withIndex().sortedWith(compareBy({ it.value.rang }, { it.index })).map { it.value }
                .distinctBy { it.art to it.tmdb }
        }

        private fun eintrag(o: JSONObject): AngesagtEintrag? {
            val tmdb = o.optInt("tmdb", 0).takeIf { it > 0 } ?: return null
            val art = when (o.optString("art")) {
                "movie" -> AngesagtArt.FILM
                "series" -> AngesagtArt.SERIE
                else -> return null
            }
            val fa = o.optJSONArray("fassungen") ?: return null
            val fassungen = (0 until fa.length()).mapNotNull { i ->
                val f = fa.optJSONObject(i) ?: return@mapNotNull null
                // Die Kennung kommt als Zeichenkette; eine Zahl wird genauso akzeptiert.
                val id = f.opt("id")?.toString()?.trim()?.takeIf { it.isNotEmpty() && it != "null" } ?: return@mapNotNull null
                AngesagtFassung(id, f.optString("label").trim(), f.wert("codec"), f.wert("res"), f.optLong("sid", -1L).takeIf { it >= 0 })
            }.distinctBy { it.id }
            if (fassungen.isEmpty()) return null
            return AngesagtEintrag(
                tmdb = tmdb,
                art = art,
                titel = o.optString("titel").trim(),
                jahr = o.optInt("jahr", 0).takeIf { it in 1900..2100 },
                rang = o.optInt("rang", Int.MAX_VALUE).takeIf { it > 0 } ?: Int.MAX_VALUE,
                badge = when (o.optString("badge")) {
                    "neue_folge" -> AngesagtBadge.NEUE_FOLGE
                    "neu" -> AngesagtBadge.NEU
                    else -> null
                },
                fassungen = fassungen,
            )
        }

        private fun JSONObject.wert(name: String): String? =
            if (isNull(name)) null else optString(name).trim().lowercase(Locale.ROOT).takeIf { it.isNotEmpty() }
    }
}

/** Eine Fassung, wie sie im Katalog dieses Geräts liegt — nur das, was der Abgleich braucht. */
data class AngesagtLokal(val itemId: Long, val remoteId: String, val name: String, val kategorie: String?)

/** Ein Eintrag, den dieses Gerät zeigen darf, mit der Fassung, die ein Klick öffnet. */
data class AngesagtTreffer(
    val eintrag: AngesagtEintrag,
    val gewaehlt: AngesagtLokal,
    val lokaleFassungen: Int,
    val badge: AngesagtBadge?,
)

object German4kAngesagt {

    /** Weniger als vier Titel sind kein Regal (gleiche Schwelle wie bisher `TrendingDao.MIN_ELIGIBLE_ITEMS`). */
    const val MIN_EINTRAEGE = 4

    /**
     * Welche Einträge dieses Gerät zeigt — in Rangfolge.
     *
     * Regeln, jede mit Grund:
     * - Nur Fassungen, die im lokalen Katalog liegen (= Zuschnitt des Kunden).
     * - Nie eine Fassung aus einer Erwachsenen-Rubrik (F1, Traian 16.10.: auch nicht nach PIN). Der
     *   Server lässt Erwachsenes schon weg; doppelt hält besser, falls eine Rubrik umbenannt wurde.
     * - Kinderprofil: nur Fassungen aus einer Kinder-Rubrik ([German4kKinder]); sonst fällt der Eintrag.
     * - Ausgeblendete Rubriken des Profils ([verborgen]) zählen wie nicht vorhanden.
     */
    fun abgleichen(
        eintraege: List<AngesagtEintrag>,
        lokal: Map<String, AngesagtLokal>,
        kinderprofil: Boolean,
        caps: German4kDeviceCaps.Caps?,
        verborgen: (AngesagtLokal) -> Boolean = { false },
    ): List<AngesagtTreffer> = eintraege.mapNotNull { e ->
        // Erst die Hauptzeile (exakte id), dann jede andere Zeile desselben Werks (Regalplätze, alte Kennung).
        val vorhanden = e.fassungen.mapNotNull { f -> kandidaten(f).firstNotNullOfOrNull { lokal[it] }?.let { f to it } }
            .distinctBy { it.second.itemId }
            .filterNot { (_, l) -> AdultCategoryClassifier.isAdult(l.kategorie) }
            .filterNot { (_, l) -> verborgen(l) }
            .filter { (_, l) -> !kinderprofil || German4kKinder.istKinderKategorie(l.kategorie) }
        if (vorhanden.isEmpty()) return@mapNotNull null
        val wahl = waehleFassung(vorhanden.map { it.first }, caps) ?: return@mapNotNull null
        val gewaehlt = vorhanden.first { it.first.id == wahl.id }.second
        val vierK = caps?.hevc4k == true && vorhanden.any { (f, _) -> istUhd(f) && geht(f, caps) }
        AngesagtTreffer(
            eintrag = e,
            gewaehlt = gewaehlt,
            lokaleFassungen = vorhanden.size,
            badge = e.badge ?: if (vierK) AngesagtBadge.VIERK else null,
        )
    }.distinctBy { it.eintrag.art to it.eintrag.tmdb }

    /**
     * Unter welchen `remoteId`s die Fassung im lokalen Katalog liegen kann — beste zuerst.
     *
     * Der TV-Server vergibt je Katalogzeile eine eigene Kennung `XID_BASIS + streams.id * XID_SLOTS + platz`
     * (german4k-website lib/tv/serve.ts `xtreamId`): Platz 0 ist die Hauptkategorie, die übrigen sind
     * Regale wie „🆕 Neu" oder „🏆 Top 10". Derselbe Titel liegt also unter mehreren Kennungen, und je nach
     * Zuschnitt des Kunden fehlt vielleicht gerade die Hauptzeile. Ältere Kataloge kennen noch die blanke
     * `streams.id`. Reihenfolge: exakte id (Hauptzeile — ihre Rubrik sagt verlässlich „Kinder"/„Erwachsene"),
     * dann die Regalplätze, zuletzt die alte blanke Kennung.
     */
    fun kandidaten(f: AngesagtFassung): List<String> {
        val sid = f.sid ?: f.id.toLongOrNull()?.let { streamIdVon(it) } ?: return listOf(f.id)
        return buildList {
            add(f.id)
            for (platz in 0 until XID_SLOTS) add((XID_BASIS + sid * XID_SLOTS + platz).toString())
            add(sid.toString())
        }.distinct()
    }

    /** Umkehrung von `xtreamId` (Server): Kennung → streams.id; alte blanke Kennungen bleiben, wie sie sind. */
    fun streamIdVon(xid: Long): Long = if (xid >= XID_BASIS) (xid - XID_BASIS) / XID_SLOTS else xid

    /** Muss zu german4k-website lib/tv/serve.ts (XID_BASIS) und lib/tv/vod-regale.ts (XID_SLOTS) passen. */
    const val XID_BASIS = 100_000_000L
    const val XID_SLOTS = 32

    /** „🔥 Angesagt" unter der Top 10: ohne die Titel, die schon in der Top 10 stehen (im Entwurf noch doppelt). */
    fun ohneTop(angesagt: List<AngesagtTreffer>, top: List<AngesagtTreffer>): List<AngesagtTreffer> {
        val schon = top.map { it.eintrag.art to it.eintrag.tmdb }.toSet()
        return angesagt.filterNot { (it.eintrag.art to it.eintrag.tmdb) in schon }
    }

    /** Ein Regal mit zu wenig Titeln bleibt aus, statt halbleer dazustehen. */
    fun <T> regal(liste: List<T>): List<T> = if (liste.size >= MIN_EINTRAEGE) liste else emptyList()

    /**
     * Die Standard-Fassung für „Abspielen" (Plan §2.4, gleiche Regel wie die Bild-Garantie):
     * 1. was das Gerät kann — kein HEVC ohne HEVC-Decoder (Codec-Weiche: ein h264-Gerät bekommt nie
     *    HEVC), kein 4K ohne 4K-Decoder, kein Dolby Vision ohne DV-Bildschirm (Profil 5 ist sonst grün-lila);
     * 2. deutsche Tonspur vor anderen;
     * 3. höchste passende Qualität: 4K DV > 4K > FHD > HD > SD.
     * Wissen wir nichts über das Gerät ([caps] == null), darf der Kunde es versuchen — wie überall in
     * [German4kDeviceCaps]. Passt GAR keine Fassung, nehmen wir die gutmütigste (kein HEVC, kleinste
     * Auflösung): ein Bild mit Ruckeln ist besser als ein schwarzer Schirm.
     */
    fun waehleFassung(fassungen: List<AngesagtFassung>, caps: German4kDeviceCaps.Caps?): AngesagtFassung? {
        if (fassungen.isEmpty()) return null
        val passend = fassungen.filter { geht(it, caps) }
        if (passend.isEmpty()) {
            return fassungen.withIndex().minWith(
                compareBy<IndexedValue<AngesagtFassung>>({ if (istHevc(it.value)) 1 else 0 })
                    .thenBy { stufe(it.value).rang.let { r -> if (r == 0) Int.MAX_VALUE else r } }
                    .thenBy { it.index },
            ).value
        }
        return passend.withIndex().minWith(
            compareBy<IndexedValue<AngesagtFassung>>({ if (istDeutsch(it.value)) 0 else 1 })
                .thenByDescending { qualitaet(it.value, caps) }
                .thenBy { it.index },
        ).value
    }

    /** Kann dieses Gerät die Fassung zeigen? */
    fun geht(f: AngesagtFassung, caps: German4kDeviceCaps.Caps?): Boolean {
        if (caps == null) return true
        if (istHevc(f) && !caps.hevc) return false
        if (istUhd(f) && !caps.hevc4k) return false
        if (istDv(f) && !caps.dolbyVision) return false
        return true
    }

    fun istDeutsch(f: AngesagtFassung): Boolean {
        val l = f.label.uppercase(Locale.ROOT)
        return DEUTSCH.containsMatchIn(l) || German4kTitel.anzeige(f.label).kuerzel == "DE"
    }

    /** Codec aus der Datei; fehlt er, verrät das Etikett HEVC (4K/UHD liefern wir nur in HEVC). */
    fun istHevc(f: AngesagtFassung): Boolean = when (f.codec) {
        "hevc", "h265", "x265" -> true
        "h264", "avc", "x264" -> false
        else -> istUhd(f) || HEVC.containsMatchIn(f.label.uppercase(Locale.ROOT))
    }

    fun istUhd(f: AngesagtFassung): Boolean = stufe(f) == German4kVariants.Stufe.UHD

    fun istDv(f: AngesagtFassung): Boolean = DV.containsMatchIn(f.label.uppercase(Locale.ROOT))

    private fun stufe(f: AngesagtFassung): German4kVariants.Stufe = when (f.res) {
        "uhd", "4k" -> German4kVariants.Stufe.UHD
        "fhd" -> German4kVariants.Stufe.FHD
        "hd" -> German4kVariants.Stufe.HD
        "sd" -> German4kVariants.Stufe.SD
        else -> German4kVariants.stufe(f.label)
    }

    /** Rang der Qualität: DV zählt nur, wenn das Gerät es kann (sonst wäre es oben schon rausgefallen). */
    private fun qualitaet(f: AngesagtFassung, caps: German4kDeviceCaps.Caps?): Int {
        val basis = stufe(f).rang * 10
        return if (istDv(f) && caps?.dolbyVision == true) basis + 5 else basis
    }

    /**
     * Welche Folge „Abspielen" bei einer Serie startet: die zuletzt geschaute (Weiterschauen), sonst
     * S1E1. Ist die zuletzt geschaute fertig ([zuletztFertig]), die nächste — wer Folge 3 zu Ende
     * gesehen hat, will Folge 4, nicht den Abspann von 3. Staffel 0 sind Specials — die nimmt nur, wer
     * sonst nichts hat.
     */
    fun standardFolge(folgen: List<Folge>, zuletzt: Long?, zuletztFertig: Boolean = false): Long? {
        if (folgen.isEmpty()) return null
        val ordnung = compareBy<Folge>({ if (it.staffel > 0) 0 else 1 }, { it.staffel }, { it.folge }, { it.id })
        val sortiert = folgen.sortedWith(ordnung)
        zuletzt?.let { z ->
            val i = sortiert.indexOfFirst { it.id == z }
            if (i >= 0) {
                if (!zuletztFertig) return sortiert[i].id
                // Nach der letzten regulären Folge nicht in die Specials springen — dann bleibt es bei ihr.
                val naechste = sortiert.getOrNull(i + 1)?.takeIf { it.staffel > 0 || sortiert[i].staffel == 0 }
                return (naechste ?: sortiert[i]).id
            }
        }
        return sortiert.first().id
    }

    data class Folge(val id: Long, val staffel: Int, val folge: Int)

    private val DEUTSCH = Regex("""(^|[^A-Z])(DE|GER|DEU|DEUTSCH|GERMAN)([^A-Z]|$)""")
    private val HEVC = Regex("""\b(HEVC|H\.?265|X265)\b""")
    private val DV = Regex("""\b(DV|DOVI|DOLBY VISION)\b""")
}

/**
 * Kinder-Rubriken am Namen erkennen — für das Kinderprofil (Plan §2.2: Angesagt/Top 10 nur mit Titeln
 * aus Kinder-Rubriken). Bewusst eng: lieber ein Regal zu wenig im Kinderprofil als ein falscher Titel.
 * „Animation" zählt, „Anime" nicht (viel davon ist nicht für Kinder).
 */
object German4kKinder {
    private val MARKER = Regex(
        """\b(kinder\w*|kids?|kinderfilme?|family|familie\w*|zeichentrick\w*|cartoons?|animation|animationsfilme?|junior)\b""",
    )

    fun istKinderKategorie(name: String?): Boolean {
        if (name.isNullOrBlank()) return false
        if (AdultCategoryClassifier.isAdult(name)) return false
        return MARKER.containsMatchIn(name.lowercase(Locale.ROOT))
    }
}
