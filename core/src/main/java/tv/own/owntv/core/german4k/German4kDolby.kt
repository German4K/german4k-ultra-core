package tv.own.owntv.core.german4k

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/**
 * Dolby Vision auf einem Fernseher, der es nicht kann.
 *
 * Der Fall, aus dem das entstand (Traian, 13.09.2026): Unsere beste Serienfassung liegt in einer
 * eigenen Rubrik und ist in **Dolby Vision Profil 5** kodiert — und dieses Profil hat **keinen
 * Rückfall auf normales HDR**. Auf jedem Gerät ohne Dolby Vision läuft der Film dadurch grün-lila
 * und viel zu dunkel. Nichts davon ist ein Fehler, den man messen könnte: Der Stream ist heil, die
 * Leitung ist heil, der Kunde sieht trotzdem Matsch und meldet einen kaputten Titel.
 *
 * Wir zeigen deshalb einmal am Tag einen Satz, bevor es losgeht — und lassen den Kunden trotzdem
 * schauen, wenn er will. Sein Fernseher, seine Entscheidung.
 */
object German4kDolby {

    /** Läuft gerade ein Dolby-Vision-Titel auf einem Gerät ohne Dolby Vision? Dann der Titel, sonst null. */
    private val _hinweis = MutableStateFlow<String?>(null)
    val hinweis: StateFlow<String?> = _hinweis.asStateFlow()

    /** Der Tag, an dem der Hinweis zuletzt gezeigt wurde — einmal reicht, danach weiß es der Kunde. */
    @Volatile private var gesehenAm: String = ""

    /**
     * Trägt dieser Titel (oder seine Rubrik) Dolby Vision?
     *
     * Gemessen am Katalog (19.09.2026): genau **eine** Rubrik führt den Namen, bei den Filmen keine.
     * Der Titel wird trotzdem mitgeprüft, weil eine zweite Rubrik jederzeit dazukommen kann und eine
     * Namensprüfung nichts kostet.
     */
    fun istDolbyVision(rubrik: String?, titel: String?): Boolean {
        val text = "${rubrik.orEmpty()} ${titel.orEmpty()}".lowercase(Locale.ROOT)
        return "dolby vision" in text || Regex("""\bdv\b""").containsMatchIn(text)
    }

    /**
     * Vor dem Abspielen aufrufen. Zeigt den Hinweis nur, wenn er wirklich zutrifft: passender Titel,
     * Gerät ohne Dolby Vision, und heute noch nicht gezeigt.
     */
    fun pruefe(rubrik: String?, titel: String?, geraetKannDolby: Boolean, heute: String) {
        if (geraetKannDolby) return
        if (!istDolbyVision(rubrik, titel)) return
        if (gesehenAm == heute) return
        gesehenAm = heute
        _hinweis.value = titel?.takeIf { it.isNotBlank() } ?: ""
    }

    fun schliessen() { _hinweis.value = null }

    /** Nur für Tests: den Tagesspeicher zurücksetzen. */
    fun zuruecksetzen() { gesehenAm = ""; _hinweis.value = null }
}
