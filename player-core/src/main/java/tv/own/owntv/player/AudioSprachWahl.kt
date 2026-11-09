package tv.own.owntv.player

import tv.own.owntv.core.german4k.German4kAudioSprache
import tv.own.owntv.core.german4k.German4kFeatures
import tv.own.owntv.core.german4k.German4kSchalter

/**
 * German4K 3.2.1 (AS): bevorzugte Audiosprache für alle Engines (mpv, Exo-VOD, Live/Vorschau/Multiview).
 * Schalter `audio_deutsch` aus = 3.2-Verhalten (Wert unverändert an alang / setPreferredAudioLanguage).
 */
object AudioSprachWahl {
    const val TAG = "German4kAudio"

    fun an(): Boolean = German4kFeatures.an(German4kSchalter.AUDIO_DEUTSCH)

    /** mpv `alang`: "de" → "de,deu,ger" (mpv vergleicht je nach Build nur wörtlich). Leer = keine Vorgabe. */
    fun mpvAlang(lang: String, an: Boolean = an()): String =
        if (!an || lang.isBlank()) lang else German4kAudioSprache.mpvAlang(lang)

    /** Exo `setPreferredAudioLanguages`: alle Schreibweisen; leer = keine Vorgabe. */
    fun exoSprachen(lang: String, an: Boolean = an()): Array<String> = when {
        lang.isBlank() -> emptyArray()
        !an -> arrayOf(lang)
        else -> German4kAudioSprache.varianten(lang).toTypedArray()
    }

    /**
     * Spur, die nur über ihren Titel („Deutsch", „German") passt, während keine Spur einen passenden Sprachcode
     * trägt — die wählt Exo/mpv nicht selbst. null = nichts zu tun (Standardspur bleibt).
     */
    fun titelTreffer(spuren: List<TrackOption>, lang: String, an: Boolean = an()): TrackOption? {
        if (!an || lang.isBlank()) return null
        val t = German4kAudioSprache.titelTreffer(
            spuren.map { German4kAudioSprache.Spur(it.mpvId, it.lang, it.label, it.selected) }, lang,
        ) ?: return null
        return spuren.firstOrNull { it.mpvId == t.id }
    }
}
