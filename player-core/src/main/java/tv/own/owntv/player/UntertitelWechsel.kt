package tv.own.owntv.player

import tv.own.owntv.core.german4k.German4kFeatures
import tv.own.owntv.core.german4k.German4kSchalter

/**
 * German4K 3.2.1 (P7): what the viewer picked, engine-neutral — so a subtitle or audio choice survives
 * an engine switch in either direction (mpv ⇄ ExoPlayer) and the image-subtitle handoff.
 *
 * The two engines describe the same file differently: mpv says `ger`, ExoPlayer `de`; mpv lists a
 * placeholder CC track ExoPlayer never has; ExoPlayer may drop a track it cannot decode. A bare ordinal
 * (the 3.2.1 carry-over) or an exact language string therefore missed — the switch landed on "Off".
 * The wish carries everything that identifies the track and [UntertitelWechsel.finde] matches on it.
 */
data class SpurWunsch(
    /** Language as the source engine reported it (`ger`, `de`, `eng` …); null/`und` = unknown. */
    val lang: String?,
    /** Image subtitle (PGS/VobSub/DVB) — only meaningful for subtitles. */
    val image: Boolean,
    /** Ordinal among the source engine's tracks of this type (-1 = unknown). */
    val ordinal: Int,
    /** The container's track title ("Deutsch (PGS)"); both engines read the same MKV name. */
    val label: String?,
)

internal fun TrackOption.alsWunsch(): SpurWunsch = SpurWunsch(
    lang = lang?.trim()?.takeIf { it.isNotEmpty() && !it.equals("und", ignoreCase = true) },
    image = image,
    ordinal = typeIndex,
    label = label.trim().takeIf { it.isNotEmpty() },
)

internal object UntertitelWechsel {
    fun aktiv(): Boolean = German4kFeatures.an(German4kSchalter.UNTERTITEL_WECHSEL)

    /**
     * German4K 3.2.1 (p-13/Z): Was beim Engine-Wechsel als Untertitel-Wunsch weitergeht. Zeigt mpv auf dem Direktweg
     * nur ersatzweise eine Textspur für einen Bild-Wunsch (gleiche Sprache), bleibt der Bild-Wunsch der Wunsch —
     * zurück auf ExoPlayer kommt die Bild-Spur (z. B. „Deutsch (PGS)") wieder, nicht der Ersatz „Deutsch (Text)".
     * Eine echte Kundenwahl ersetzt [wunsch] vorher (selectSubtitle), dann gilt sie.
     */
    fun wunschBeiWechsel(gewaehlt: TrackOption?, wunsch: SpurWunsch?): SpurWunsch? {
        val g = gewaehlt?.alsWunsch() ?: return null
        if (wunsch != null && wunsch.image && !g.image &&
            (wunsch.lang == null || g.lang == null || sprachGleich(wunsch.lang, g.lang))
        ) return wunsch
        return g
    }

    /** Same language, whatever code family each engine uses (ger = deu = de, eng = en-US …). */
    fun sprachGleich(a: String?, b: String?): Boolean =
        !a.isNullOrBlank() && !b.isNullOrBlank() && languageIdentity(a) == languageIdentity(b)

    /**
     * The track in [spuren] that best matches [w], or null when none plausibly is the one the viewer
     * picked (then the caller leaves subtitles off and says so rather than guessing a language).
     *
     * [bildErlaubt] = the target engine can draw image subtitles; false filters them out (mpv on the
     * direct `mediacodec_embed` path) so a same-language text track can stand in.
     *
     * Order: same title + kind → same ordinal + kind + language → same language + kind → same language
     * (other kind) → without a known language only the ordinal, or the single candidate.
     */
    fun finde(spuren: List<TrackOption>, w: SpurWunsch, bildErlaubt: Boolean = true): TrackOption? {
        val kandidaten = if (bildErlaubt) spuren else spuren.filter { !it.image }
        if (kandidaten.isEmpty()) return null
        val gleicheArt = kandidaten.filter { it.image == w.image }
        fun sprachOk(t: TrackOption) = w.lang == null || t.lang.isNullOrBlank() || sprachGleich(w.lang, t.lang)
        w.label?.let { l -> gleicheArt.firstOrNull { it.label.trim() == l && sprachOk(it) }?.let { return it } }
        if (w.ordinal >= 0) {
            gleicheArt.firstOrNull { it.typeIndex == w.ordinal && (w.lang == null || sprachGleich(w.lang, it.lang)) }
                ?.let { return it }
        }
        if (w.lang != null) {
            return gleicheArt.firstOrNull { sprachGleich(w.lang, it.lang) }
                ?: kandidaten.firstOrNull { sprachGleich(w.lang, it.lang) }
        }
        if (w.ordinal >= 0) gleicheArt.firstOrNull { it.typeIndex == w.ordinal }?.let { return it }
        return gleicheArt.singleOrNull()
    }

    /** mpv's placeholder closed-caption track (`sub-create-cc-track`): codec `eia_608`, no title/lang. */
    fun istCcPlatzhalter(codec: String?): Boolean = codec?.lowercase()?.startsWith("eia_") == true

    /**
     * Whether to list mpv's CC placeholder at all. CEA-608/708 text only flows from the SOFTWARE decoder
     * ([OwnTVPlayer] switches to it while CC is selected), which is only offered up to 1080p with working
     * GL. Above that the row could never show anything — on a 4K film it was the nameless "Subtitle 5".
     */
    fun ccZeigen(videoHoehe: Int?, glKaputt: Boolean): Boolean =
        !glKaputt && (videoHoehe == null || videoHoehe <= 0 || videoHoehe <= 1080)

    /** What picking an image subtitle under mpv does. */
    enum class BildWeg {
        /** mpv renders itself (vo=gpu: software/copy rung) — it draws PGS/VobSub, just select it. */
        MPV_ZEICHNET,
        /** Direct path: hand playback to ExoPlayer, which draws image subtitles on its own layer. */
        AN_EXO,
        /** ExoPlayer already failed this item — say so instead of a handoff that bounces back. */
        HINWEIS_EXO_GEHT_NICHT,
        /** Live: there is no ExoPlayer handoff for live streams. */
        HINWEIS_LIVE,
    }

    fun bildWeg(direkt: Boolean, live: Boolean, exoGescheitert: Boolean): BildWeg = when {
        !direkt -> BildWeg.MPV_ZEICHNET
        live -> BildWeg.HINWEIS_LIVE
        exoGescheitert -> BildWeg.HINWEIS_EXO_GEHT_NICHT
        else -> BildWeg.AN_EXO
    }
}
