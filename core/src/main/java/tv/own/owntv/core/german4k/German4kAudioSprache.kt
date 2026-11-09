package tv.own.owntv.core.german4k

import java.util.Locale

/**
 * German4K 3.2.1 (AS): Bevorzugte Audiosprache — Standard Deutsch, damit Filme mit mehreren Tonspuren direkt
 * deutsch starten. Schalter [German4kSchalter.AUDIO_DEUTSCH] (fehlt = an, "aus" = 3.2-Verhalten).
 *
 * Rein (kein Android), damit Auswahl, Normalisierung und der mpv-`alang`-Text unit-testbar sind.
 */
object German4kAudioSprache {
    /** Rückfall, wenn weder Kunde noch Server noch Gerätesprache etwas Brauchbares liefern. */
    const val STANDARD = "de"

    /** Gerätesprachen, bei denen trotzdem Deutsch gilt (unser Publikum schaut deutsch, auch mit englischem Gerät). */
    private val GERAET_IGNORIERT = setOf("de", "en")

    /** ISO 639-2/B → 639-2/T (nur wo sie sich unterscheiden; Locale kennt nur /T). */
    private val B_ZU_T = mapOf(
        "alb" to "sqi", "arm" to "hye", "baq" to "eus", "bur" to "mya", "chi" to "zho", "cze" to "ces",
        "dut" to "nld", "fre" to "fra", "geo" to "kat", "ger" to "deu", "gre" to "ell", "ice" to "isl",
        "mac" to "mkd", "mao" to "mri", "may" to "msa", "per" to "fas", "rum" to "ron", "slo" to "slk",
        "tib" to "bod", "wel" to "cym",
    )
    private val T_ZU_B: Map<String, String> = B_ZU_T.entries.associate { (b, t) -> t to b }

    /** 639-2-Sondercodes ohne Sprache: undefiniert, mehrere, keine, fehlend, „lokal". */
    private val KEINE_SPRACHE = setOf("und", "mul", "zxx", "mis", "qaa")

    /** Veraltete 639-1-Codes, die Java/Streams noch liefern. */
    private val ALT_ZU_NEU = mapOf("iw" to "he", "in" to "id", "ji" to "yi", "jw" to "jv", "mo" to "ro")

    /** 639-2/T (dreistellig) → 639-1, aus Locale gebaut. */
    private val DREI_ZU_ZWEI: Map<String, String> by lazy {
        val m = HashMap<String, String>()
        // Android (ICU) listet hier auch dreistellige Codes wie „und" — nur echte 639-1-Paare zählen.
        for (zwei in Locale.getISOLanguages()) {
            if (zwei.length != 2) continue
            val drei = runCatching { Locale(zwei).isO3Language }.getOrNull()?.lowercase(Locale.ROOT) ?: continue
            if (drei.length == 3) m.putIfAbsent(drei, zwei)
        }
        m
    }

    /**
     * Sprachnamen in Spurtiteln („Deutsch", „German 5.1", „Türkçe") → 639-1. Nur ganze Wörter, klein geschrieben.
     * Technische Erkennungswörter, keine Oberflächentexte.
     */
    private val NAMEN: Map<String, String> = mapOf(
        "deutsch" to "de", "german" to "de", "deutsche" to "de", "deutscher" to "de",
        "english" to "en", "englisch" to "en",
        "türkçe" to "tr", "turkce" to "tr", "turkish" to "tr", "türkisch" to "tr", "tuerkisch" to "tr",
        "shqip" to "sq", "albanian" to "sq", "albanisch" to "sq",
        "polski" to "pl", "polish" to "pl", "polnisch" to "pl",
        "română" to "ro", "romana" to "ro", "romanian" to "ro", "rumänisch" to "ro",
        "русский" to "ru", "russian" to "ru", "russisch" to "ru",
        "العربية" to "ar", "arabic" to "ar", "arabisch" to "ar",
        "hrvatski" to "hr", "croatian" to "hr", "kroatisch" to "hr",
        "srpski" to "sr", "serbian" to "sr", "serbisch" to "sr",
        "bosanski" to "bs", "bosnian" to "bs", "bosnisch" to "bs",
        "italiano" to "it", "italian" to "it", "italienisch" to "it",
        "español" to "es", "espanol" to "es", "spanish" to "es", "spanisch" to "es",
        "français" to "fr", "francais" to "fr", "french" to "fr", "französisch" to "fr",
    )

    /**
     * Sprachcode (639-1, 639-2/T, 639-2/B, BCP-47 wie „de-DE") → 639-1 klein, oder null wenn leer/unbekannt
     * („und", „mul", „qaa"). de, deu, ger, DE, de-AT → "de".
     */
    fun normalisiere(code: String?): String? {
        val roh = code?.trim()?.lowercase(Locale.ROOT)?.substringBefore('-')?.substringBefore('_') ?: return null
        if (roh.isEmpty() || roh.any { it !in 'a'..'z' } || roh in KEINE_SPRACHE) return null
        return when (roh.length) {
            2 -> ALT_ZU_NEU[roh] ?: roh.takeIf { it in Locale.getISOLanguages() }
            3 -> DREI_ZU_ZWEI[B_ZU_T[roh] ?: roh]
            else -> null
        }
    }

    /** Alle Schreibweisen derselben Sprache, 639-1 zuerst: "de" → [de, deu, ger]. Unbekannt → nur der Code selbst. */
    fun varianten(code: String?): List<String> {
        val zwei = normalisiere(code) ?: return listOfNotNull(code?.trim()?.takeIf { it.isNotEmpty() })
        val t = runCatching { Locale(zwei).isO3Language }.getOrNull()?.lowercase(Locale.ROOT)?.takeIf { it.length == 3 }
        return listOfNotNull(zwei, t, t?.let { T_ZU_B[it] }).distinct()
    }

    /** mpv `alang`: alle Schreibweisen mit Komma ("de,deu,ger"); leer = keine Vorgabe. */
    fun mpvAlang(code: String?): String = varianten(code).joinToString(",")

    /** Zwei Codes meinen dieselbe Sprache (de = deu = ger). */
    fun gleich(a: String?, b: String?): Boolean {
        val x = normalisiere(a) ?: return false
        return x == normalisiere(b)
    }

    /**
     * Sprache aus einem Spurtitel ohne Sprachcode: „Deutsch", „German 5.1", „DE AC3", „[GER]". Zweistellige
     * Kürzel zählen nur in Großbuchstaben (sonst wäre „de" in „Audio de …" ein Treffer). null = nichts erkannt.
     */
    fun ausTitel(titel: String?): String? {
        if (titel.isNullOrBlank()) return null
        val woerter = titel.split(Regex("[^\\p{L}]+")).filter { it.isNotEmpty() }
        for (w in woerter) {
            val klein = w.lowercase(Locale.ROOT)
            NAMEN[klein]?.let { return it }
            when (w.length) {
                3 -> if (klein in B_ZU_T || klein in T_ZU_B || (w == w.uppercase(Locale.ROOT) && DREI_ZU_ZWEI.containsKey(klein))) {
                    normalisiere(klein)?.let { return it }
                }
                2 -> if (w == w.uppercase(Locale.ROOT)) normalisiere(klein)?.let { return it }
            }
        }
        return null
    }

    /**
     * Wirksame Audiosprache (Reihenfolge fest):
     * (a) [gesetzt] = DataStore-Wert, null = nie gesetzt; auch "" („Keine" bewusst gewählt) wird respektiert;
     * (b) [server] = `konfig.audio_standard` (je Gerät überschreibbar);
     * (c) [geraet] = Gerätesprache, wenn sie weder Deutsch noch Englisch ist (tr, sq, pl, ro, ru, ar …);
     * (d) "de".
     * Ergebnis 639-1, oder bei (a) der Kundenwert (normalisiert, sonst unverändert; "" bleibt "").
     */
    fun wirksam(gesetzt: String?, server: String?, geraet: String?): String {
        if (gesetzt != null) return if (gesetzt.isBlank()) "" else normalisiere(gesetzt) ?: gesetzt.trim()
        normalisiere(server)?.let { return it }
        normalisiere(geraet)?.takeIf { it !in GERAET_IGNORIERT }?.let { return it }
        return STANDARD
    }

    /** Woher der wirksame Wert kommt — für Logzeile und Einstellungen. */
    enum class Quelle { KUNDE, SERVER, GERAET, STANDARD }

    fun quelle(gesetzt: String?, server: String?, geraet: String?): Quelle = when {
        gesetzt != null -> Quelle.KUNDE
        normalisiere(server) != null -> Quelle.SERVER
        normalisiere(geraet)?.let { it !in GERAET_IGNORIERT } == true -> Quelle.GERAET
        else -> Quelle.STANDARD
    }

    /** Eine Spur (Code + Titel), wie sie Exo/mpv melden. */
    data class Spur(val id: Int, val lang: String?, val titel: String?, val gewaehlt: Boolean)

    /**
     * Spur, die nur über ihren Titel zur Wunschsprache passt — oder null, wenn nichts zu tun ist:
     * Wunsch leer, schon eine Spur mit passendem Sprachcode da (das wählt Exo/mpv selbst), oder die gewählte
     * Spur passt schon. Gibt es keine Treffer, bleibt die Standardspur des Streams.
     */
    fun titelTreffer(spuren: List<Spur>, wunsch: String?): Spur? {
        val ziel = normalisiere(wunsch) ?: return null
        if (spuren.any { normalisiere(it.lang) == ziel }) return null
        val treffer = spuren.filter { normalisiere(it.lang) == null && ausTitel(it.titel) == ziel }
        if (treffer.isEmpty() || treffer.any { it.gewaehlt }) return null
        return treffer.first()
    }
}
