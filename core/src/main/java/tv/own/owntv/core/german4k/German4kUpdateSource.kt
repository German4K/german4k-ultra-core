package tv.own.owntv.core.german4k

/**
 * Panel-driven updates: the German4K panel tells each device which build it should run (stable or
 * beta channel, see `/mac beta`), the provisioner hands it here, and [tv.own.owntv.core.update.UpdateManager]
 * offers it instead of asking GitHub. No panel answer yet → the GitHub release stays the fallback.
 */
object German4kUpdateSource {
    @Volatile var current: German4kUpdate? = null
        private set

    /**
     * [frisch] = Antwort kam gerade vom Panel. German4K 3.2.1 (p-14, Fund 9): eine Cache-Antwort (Start ohne Netz) setzt
     * nur [current] (Pflicht-Update A7), zählt aber NICHT als beantwortet — sonst meldete die manuelle Prüfung offline
     * „aktuell" statt „konnte nicht prüfen".
     */
    fun offer(update: German4kUpdate?, frisch: Boolean = true) { current = update; if (frisch) _beantwortet.value = true }

    // German4K 3.2.1 (q-4/12, p-14): ob das Panel in diesem Lauf FRISCH etwas zum Update gesagt hat — auch „kein Update"
    // (offer(null)). Die Start-Prüfung wartet darauf still, statt nach 5 s „konnte nicht prüfen" zu zeigen.
    private val _beantwortet = kotlinx.coroutines.flow.MutableStateFlow(false)
    val beantwortet: kotlinx.coroutines.flow.StateFlow<Boolean> = _beantwortet

    /** German4K 3.2.1 (p-14): was die Panel-Seite der Update-Prüfung sagt. */
    enum class Urteil { NEUER, AKTUELL, UNBEKANNT }

    /**
     * German4K 3.2.1 (p-14, Fund 9): NEUER = Panel nennt einen neueren Build (auch aus dem Cache — Pflicht-Update A7);
     * AKTUELL nur nach frischer Antwort ([beantwortet]) bzw. mit [stillAn]=false (altes Verhalten: jeder bekannte Build);
     * sonst UNBEKANNT → die Prüfung sagt ehrlich „konnte nicht prüfen".
     */
    fun urteil(versionCode: Int, stillAn: Boolean): Urteil = pruefe(versionCode, stillAn).urteil

    /** German4K 3.2.1 (u-14): Urteil samt dem Build, auf dem es beruht — bei NEUER nie null. */
    data class Pruefung(val urteil: Urteil, val neuer: German4kUpdate?)

    /**
     * German4K 3.2.1 (u-14): liest [current] genau einmal (Schnappschuss). Früher las der Aufrufer nach [urteil] ein
     * zweites Mal mit `newerThan(...)!!` — ein paralleles `offer(null)` dazwischen gab eine NPE im IO-Scope (Absturz).
     */
    fun pruefe(versionCode: Int, stillAn: Boolean): Pruefung {
        val snap = current
        val neuer = neuerAls(snap, versionCode)
        return when {
            neuer != null -> Pruefung(Urteil.NEUER, neuer)
            stillAn && beantwortet.value -> Pruefung(Urteil.AKTUELL, null)
            !stillAn && snap != null -> Pruefung(Urteil.AKTUELL, null)
            else -> Pruefung(Urteil.UNBEKANNT, null)
        }
    }

    private fun neuerAls(u: German4kUpdate?, versionCode: Int): German4kUpdate? =
        u?.takeIf { it.versionCode > versionCode && it.url.isNotBlank() }

    /** Nur für Tests: Zustand wie nach dem Prozessstart. */
    internal fun vergessen() { current = null; _beantwortet.value = false }

    /** The panel update when it is newer than the running build (by versionCode), else null. */
    fun newerThan(versionCode: Int): German4kUpdate? = neuerAls(current, versionCode) // German4K 3.2.1 (u-14)
}

/** Feature switches from the panel: aus | entwicklung | beta | an. */
object German4kFeatures {
    @Volatile private var map: Map<String, String> = emptyMap()
    /** Dieselben Schalter als Flow — NavVisibility blendet damit Bereiche wie `sport` ein/aus. */
    val flow = kotlinx.coroutines.flow.MutableStateFlow<Map<String, String>>(emptyMap())
    /**
     * [frisch] = Antwort des Panels in diesem Lauf (false = gespeicherte Antwort aus dem Cache).
     * German4K 3.2.1 (t-15): merkt sich, ob überhaupt schon Schalter geladen sind ([geladen]) bzw. eine frische Antwort da
     * ist ([frischGeladen]).
     */
    fun set(features: Map<String, String>, frisch: Boolean = true) {
        map = features; flow.value = features
        geladen = true
        if (frisch) frischGeladen.value = true
    }

    /** German4K 3.2.1 (t-15): schon Schalter gesetzt (Cache oder Panel) in diesem Prozess? */
    @Volatile var geladen: Boolean = false
        private set

    /** German4K 3.2.1 (t-15): eine frische Panel-Antwort (nicht nur der Cache) ist in diesem Prozess angekommen. */
    val frischGeladen = kotlinx.coroutines.flow.MutableStateFlow(false)

    /**
     * German4K 3.2.1 (t-15): wie [an], aber bevor der Server etwas sagen konnte, gilt „aus“ (altes Verhalten). Für
     * Neuerungen, die beim Start nicht schon mit dem Standard „an“ greifen dürfen — bisher nur `zap_erst_schliessen`.
     * German4K 3.2.1 (t-16): „gesagt“ = eine frische Panel-Antwort in diesem Lauf ([frischGeladen]) oder der Schalter steht
     * ausdrücklich in den gespeicherten Schaltern. [geladen] allein reichte nicht: der 3.2-Cache (ohne 3.2.1-Schalter)
     * machte ihn schon wahr, und „fehlt = an“ schaltete die Neuerung vor der ersten Antwort ein.
     */
    fun anGeladen(name: String): Boolean = an(name) && (frischGeladen.value || name in map)

    /** Nur für Tests: Zustand wie nach dem Prozessstart. */
    fun vergessenFuerTests() { map = emptyMap(); flow.value = emptyMap(); geladen = false; frischGeladen.value = false }
    fun state(name: String): String = map[name] ?: "aus"
    fun visible(name: String): Boolean = state(name) != "aus"
    /** Show the "still in development — view anyway?" hint before opening. */
    fun inDevelopment(name: String): Boolean = state(name) == "entwicklung"

    /**
     * German4K 3.2: Notschalter für Neuerungen, die schon im Build stecken. Anders als [visible] gilt hier:
     * Schlüssel fehlt ⇒ AN (das 3.2-Verhalten), nur exakt "aus" schaltet auf das alte Verhalten zurück.
     * Warum: ein Server, der den Schalter (noch) nicht kennt, darf die Neuerung nicht abschalten.
     */
    fun an(name: String): Boolean = map[name] != "aus"
}

/** German4K 3.2: Namen der Notschalter (siehe [German4kFeatures.an]). */
object German4kSchalter {
    /** German4K 3.3: „Was ist neu“ einmal nach einem Update. */
    const val WAS_IST_NEU = "was_ist_neu"
    /** German4K 3.3: „Problem melden“ mit Kurznummer (/problem). aus = „Fehler senden“ wie 3.2. */
    const val PROBLEM_MELDEN = "problem_melden"
    /** German4K 3.3: Beta-Kanal in den Einstellungen selbst wählen (mit Rückweg-Build). */
    const val BETA_WAHL = "beta_wahl"
    const val TOP10 = "top10"
    const val ANGESAGT_HERO = "angesagt_hero"
    const val VEREINSLOGOS = "vereinslogos"
    const val WETTBEWERB_LOGO = "wettbewerb_logo"
    const val KONFERENZ_KARTE = "konferenz_karte"
    const val ERINNERN = "erinnern"
    const val LEITUNG_BELEGT = "leitung_belegt"
    const val ZAPP_LISTE = "zapp_liste"
    const val LAENDER_V2 = "laender_v2"
    /** German4K 3.2.1 (AS): Audiosprache standardmäßig Deutsch (bzw. Server/Gerätesprache), wenn der Kunde nichts gewählt hat. aus = 3.2. */
    const val AUDIO_DEUTSCH = "audio_deutsch"

    // German4K 3.2.1 — jede Neuerung einzeln abschaltbar (Server: ULTRA_FEATURES in lib/app-panel/ultra.ts).
    /** B1: Multiview standardmäßig an + „Zweiten Sender dazu“ im Vollbild. aus = Standard Aus wie 3.2. */
    const val MULTIVIEW = "multiview"
    /** A3: Aufnahmen halten bei German4K (max_connections 0) die Leitung fürs Zuschauen frei. */
    const val AUFNAHMEN = "aufnahmen"
    /** B4: nach „Leitung freigeben“ startet der Sender selbst neu. */
    const val LEITUNG_FREIGEBEN = "leitung_freigeben"
    /** B3: Umschalter „Alle · Filme · Serien“ am Angesagt-Regal. */
    const val ANGESAGT_FILTER = "angesagt_filter"
    /** D2: Startregale Fußball/Jetzt im TV/Neu bei Filme ein-/ausblendbar. aus = feste Plätze wie 3.2. */
    const val STARTREGALE_V2 = "startregale_v2"
    /** A1: mpv-Surface mit Frist abkoppeln (kein ANR beim Wechsel mpv → Exo). */
    const val MPV_DETACH_FRIST = "mpv_detach_frist"
    /** A5: nach Auflösungswechsel beim Zappen Surface einmal neu aufbauen. */
    const val ZAPP_SURFACE = "zapp_surface"
    /** A7: Pflicht-Update-Dialog unabhängig vom Startschalter. */
    const val PFLICHT_DIALOG = "pflicht_dialog"
    /** T1/T2/T4: echtes „Automatisch“ (HDMI-Erkennung) und neue Tonstufen. aus = 3.2-Verhalten. */
    const val TON_AUTO = "ton_auto"
    /** T5: Dialog beim Downmix in die Mitte. */
    const val TON_DIALOG = "ton_dialog"
    /** T3: FFmpeg-Tondecoder in ExoPlayer. */
    const val TON_FFMPEG = "ton_ffmpeg"

    // German4K 3.2.1 / Build 40
    /** T7: AAC-Mehrkanal → AC3 neu kodieren und als Dolby durchreichen (Fire TV kann kein Mehrkanal-PCM). Server: stable aus. */
    const val TON_AC3 = "ton_ac3"
    /** B7: Live-Sender mit gemessenen ≥ 50 fps starten beim nächsten Mal auf ExoPlayer, auch wenn sie auf mpv stehen. */
    const val LIVE_FPS_EXO = "live_fps_exo"

    // German4K 3.2.1 (u-3) — Restpunkte Oberfläche aus der Abnahme.
    /** U1: Hochformat einspaltig (Startbildschirm-Bereiche, Video-Player-Einstellungen, EPG-Quellen). aus = 3.2-Layout. */
    const val HOCHFORMAT_EINSPALTIG = "hochformat_einspaltig"
    /** U2: Angesagt rechnet nach jedem abgeschlossenen Katalog-Abgleich neu (nicht erst nach Neustart). */
    const val ANGESAGT_NACH_SYNC = "angesagt_nach_sync"
    /** U3: Jahr aus dem Titel nur, wenn es als Jahr plausibel ist („Blade Runner 2049" ist keins). */
    const val JAHR_PLAUSIBEL = "jahr_plausibel"
    /** U4: Anmeldebildschirm fragt still alle 5 s, ob das Gerät inzwischen gekoppelt ist (max. 10 min). */
    const val KOPPLUNG_ABFRAGE = "kopplung_abfrage"
    /** U6: Trend-Ordner (Top 10, Angesagt, Neu diese Woche) bleiben bei A–Z oben. */
    const val TREND_ORDNER_OBEN = "trend_ordner_oben"

    // German4K 3.2.1 (u-4) — Restfehler Oberfläche aus der zweiten Abnahme.
    /** U7: Hochformat — Quellen-Karte und Einrichtungs-Knöpfe brechen um statt abzuschneiden. aus = 3.2-Layout. */
    const val HOCHFORMAT_KNOEPFE = "hochformat_knoepfe"
    /** U8: „Auf zweite Verbindung gewechselt“ nur, wenn wirklich gewechselt wurde und Bild kam. aus = 3.2-Verhalten. */
    const val STOERUNG_EHRLICH = "stoerung_ehrlich"
    /** U9: „Mein Zugang · noch N Tage“ nur mit echtem German4K-Zugang und echtem Ablaufdatum. */
    const val ZUGANG_TAGE_ECHT = "zugang_tage_echt"
    /** U10: Favoriten-Sortierung „Nummer“ sortiert nach Sendernummer wie in der Kategorie. */
    const val FAVORITEN_NUMMER = "favoriten_nummer"
    /** U11: Serienfolge aus einer Start-Detailseite → nach dem Player zurück zur Startseite (wie Filme, B2). */
    const val SERIE_VON_START = "serie_von_start"

    // German4K 3.2.1 (u-5) — Bedienfehler am Fernseher (D-Pad).
    /** U12: Zurück schließt Tastatur und Bearbeiten in einem; ↑/↓ verlassen ein Feld ohne offene Tastatur. aus = 3.2-Verhalten. */
    const val TASTATUR_ZURUECK = "tastatur_zurueck"
    /** U13: Fokussierte Knöpfe (auch gefüllte) mit Ring in Textfarbe und Vergrößerung. aus = 3.2-Darstellung. */
    const val KNOPF_FOKUS = "knopf_fokus"
    /** U14: Kopfleiste ↔ Seitenleiste immer per D-Pad erreichbar, auch wenn der Inhalt nichts Fokussierbares hat. */
    const val SHELL_FOKUS = "shell_fokus"
    /** German4K 3.2.1 (L1): Panel-Licht als kleines Bild statt Vollflächen-Verläufen (aus = 3.2-Zeichnung). */
    const val PANEL_LICHT_BILD = "panel_licht_bild"
    /**
     * German4K 3.2.1 (L2): Panel-Grundfarbe ins Lichtbild eingebacken — ein deckendes Bild statt Fläche + halbtransparentem
     * Bild je Panel (GPU spart das Überblenden ganzer Panelflächen). aus = L1 (Fläche, dann Lichtbild darüber).
     */
    const val PANEL_LICHT_OPAK = "panel_licht_opak"
    /**
     * German4K 3.2.1 (L3): Kacheltitel (U32) als ein Layout-Knoten ohne Unterkomposition; alle Wörter in EINEM Textlayout
     * gemessen und app-weit gemerkt (Raster-Zeilen bauen sich beim Blättern schneller auf). aus = U32 (BoxWithConstraints).
     */
    const val TITEL_MESSUNG = "titel_messung"
    /**
     * German4K 3.2.1 (L6): Glaseffekt — ruhende Glasflächen zeichnen Tönung + Glaslicht als ein kleines gebackenes Bild statt
     * zwei Verläufen über die ganze Fläche (größter GPU-Posten mit Glaseffekt). aus = Verläufe live wie in 3.2.
     */
    const val GLAS_LICHT_BILD = "glas_licht_bild"
    /**
     * German4K 3.2.1 (L7): Die Shell liest den Fokus-Sender der Live-Liste nur dort, wo er gebraucht wird (Player/Verlauf),
     * statt bei jedem ↓/↑ ganz neu zusammengesetzt zu werden. aus = 3.2-Verhalten.
     */
    const val SHELL_RUHIG = "shell_ruhig"

    // German4K 3.2.1 (A1, p-6)
    /**
     * Früher Wächter für mpvs Hardware-Decoder: hängt der Kern nach file-loaded ≥ 2 s oder kommt bei laufendem Ton
     * kein Bild, wird er verworfen und derselbe Titel sofort im Software-Decoder neu gestartet (einmal je Titel);
     * zwei Hänger derselben Codec-/Auflösungsklasse → Klasse startet auf diesem Gerät künftig gleich in Software
     * (über 1080p als VOD auf ExoPlayer). aus = 3.2.1-Verhalten (schwarz, bis Kunde zurückgeht).
     */
    const val MPV_HW_WAECHTER = "mpv_hw_waechter"

    // German4K 3.2.1 (P7, p-7)
    /**
     * Untertitel/Ton über Engine-Wechsel: gewählte Spur (Sprache + Art + Titel) bleibt bei mpv ⇄ Exo erhalten,
     * Bild-Untertitel (PGS/VobSub) gehen auf dem Direktweg zuverlässig an ExoPlayer oder melden sich mit Hinweis,
     * Sprachcodes ger/deu/de gelten als gleich, die Platzhalter-CC-Spur von mpv erscheint nur, wo CC gehen kann.
     * aus = 3.2.1-Verhalten (Wechsel setzt Untertitel auf „Aus", Bild-Untertitel unter mpv still unsichtbar).
     */
    const val UNTERTITEL_WECHSEL = "untertitel_wechsel"

    // German4K 3.2.1 (q-3) — Player-Funde der TV-Abnahme (Build 40).
    /** A: Live mpv → ExoPlayer (HUD-Engine-Knopf) nimmt Untertitel und Tonspur mit (vorher „Aus"). */
    const val LIVE_SPUR_WECHSEL = "live_spur_wechsel"
    /** B: mpv — Wechsel auf eine Text-Untertitelspur mitten im Film liest Untertitel bis 60 s zurück nach (laufende Zeile erscheint sofort). */
    const val MPV_TEXT_NACHLESEN = "mpv_text_nachlesen"
    /** C: Film/Serie — gewählte Ton- und Untertitelspur je Titel merken und beim Fortsetzen wieder setzen. */
    const val VOD_SPUR_MERKEN = "vod_spur_merken"
    /** D: Live auf ExoPlayer — Bild-Untertitel (PGS/DVB/VobSub) tragen im Dialog „· Bild" wie bei mpv. */
    const val LIVE_BILD_KENNUNG = "live_bild_kennung"
    /** E: Info-Bitrate auf ExoPlayer gemessen über ein 10-s-Fenster; unbekannt → Zeile fehlt statt „0 Mbit/s". */
    const val BITRATE_FENSTER = "bitrate_fenster"
    /** F: Multiview-Kachel, die nie lief, meldet „ließ sich nicht öffnen" statt Decoder-Grenze des Fernsehers. */
    const val MULTIVIEW_FEHLERGRUND = "multiview_fehlergrund"
    /** G: Aufnahme-Dienst (Vordergrund-Benachrichtigung) startet nur, wenn eine Aufnahme läuft oder fällig ist. */
    const val AUFNAHME_NUR_FAELLIG = "aufnahme_nur_faellig"

    // German4K 3.2.1 (u-6) — weitere Bedienfehler am Fernseher (D-Pad).
    /** U15: Einstellungen → in die rechte Spalte landet auf dem ersten (bzw. zuletzt gewählten) Eintrag der Gruppe. */
    const val EINSTELLUNGEN_FOKUS = "einstellungen_fokus"
    /** U16: Profil-Dialog — Einstieg in die Avatar-Reihe auf dem gewählten Avatar („kein Avatar" bleibt sichtbar). */
    const val AVATAR_FOKUS = "avatar_fokus"
    /** U17/U18: Einstellungen-Suche — Zurück leert die Suche statt die Einstellungen zu verlassen; Treffer-Pfad zweizeilig. */
    const val EINSTELLUNGEN_SUCHE = "einstellungen_suche"
    /** U20: Farbwahl-Dialoge (Akzentfarbe, Fokus-Hervorhebung) öffnen mit Fokus auf der aktuellen Farbe. */
    const val AUSWAHL_FOKUS = "auswahl_fokus"
    /** U21: Kategorien und Elemente anpassen — Knopfleiste zweizeilig, damit „PIN festlegen" erreichbar bleibt. */
    const val LEISTE_UMBRUCH = "leiste_umbruch"
    /** U22: Live-TV — → aus den Kategorien auf den zuletzt markierten Sender (sonst den ersten), nie auf die Suchleiste. */
    const val LIVE_FOKUS = "live_fokus"
    /** U22: Fokus-Rückfallketten werten ein stilles false von requestFocus() als Fehlschlag (Compose 1.12 wirft nicht mehr). */
    const val FOKUS_RUECKFALL = "fokus_rueckfall"
    /** U24: Detailseite — nach dem Abruf ohne Ergebnis „Keine weiteren Angaben“ statt ewig „Wird geladen…“. */
    const val DETAIL_LADEN = "detail_laden"
    /** U25: Live-TV/Filme/Serien — Zurück mit aktiver Suche leert erst die Suche (Fokus ins Suchfeld). */
    const val SUCHE_ZURUECK = "suche_zurueck"
    /** U26: „Zurück"-Knöpfe am Fernseher mit Links-Pfeil statt Chevron nach rechts (Folgenansicht). */
    const val ZURUECK_PFEIL = "zurueck_pfeil"
    /** U27: Folgenansicht — ← aus der Vorschau zurück auf die markierte Folge (nicht Folge 1). */
    const val FOLGEN_FOKUS = "folgen_fokus"
    /** U28: „EPG hinzufügen" aus dem TV-Guide — Zurück/Abbrechen führt in den TV-Guide statt in die Einstellungen. */
    const val EPG_ZURUECK = "epg_zurueck"
    /** U29: Mehr — „Mein Zugang" ohne Laufzeit erklärt, was OK öffnet (statt leerer Fläche). */
    const val MEHR_TEXTE = "mehr_texte"

    // German4K 3.2.1 (u-7) — letzte benannte Bedienfehler.
    /** U30: Quellen — Zurück aus „Quelle hinzufügen" setzt den Fokus wieder auf „Quelle hinzufügen" (nicht „Bearbeiten"). */
    const val QUELLEN_FOKUS = "quellen_fokus"
    /** U31: Folgenansicht aus der Serien-Detailseite geöffnet → Zurück führt auf die Detailseite (Fokus „Folgen"). */
    const val FOLGEN_ZURUECK_DETAIL = "folgen_zurueck_detail"
    /** U32: Kacheltitel ohne Plakat brechen nicht mitten im Wort (Schrift wird kleiner, sonst Ellipse). */
    const val TITEL_UMBRUCH = "titel_umbruch"
    /** U33: Startseite — ↑ aus dem obersten Element geht in die Kopfleiste (Suche/Letzter Sender). */
    const val START_KOPFLEISTE = "start_kopfleiste"

    // German4K 3.2.1 (u-8) — Funde aus der Fire-TV-Durchsicht.
    /** U34: Popup-/Langdruck-Menüs mit deckendem Grund — die Liste dahinter scheint nicht mehr durch. */
    const val MENUE_DECKEND = "menue_deckend"
    /** U34: Langdruck-Menüs (Live/Filme/Serien/Folgen) — jede Aktion mit Symbol (Umbenennen, Verschieben, Ausblenden …). */
    const val MENUE_SYMBOLE = "menue_symbole"
    /** U35: Live-TV mit ausgeschalteter Live-Vorschau — leere Vorschauspalte sagt das, statt „Vorschau startet …“. */
    const val LIVE_VORSCHAU_AUS_TEXT = "live_vorschau_aus_text"
    /** U36: Live-TV — nach dem Leeren der Suche steht die Liste am zuletzt markierten Sender (sonst oben), nicht mittendrin. */
    const val LIVE_SUCHE_LEEREN = "live_suche_leeren"
    /** U37: Live-TV — Sendername rechts bricht nur an Wortgrenzen (wie U32). */
    const val LIVE_TITEL_UMBRUCH = "live_titel_umbruch"
    /** U38: Fußball/Startseite — Spielplan beim Öffnen neu, wenn älter als 10 min; nur solange sichtbar; „Stand" = Abrufzeit. */
    const val SPORT_FRISCH = "sport_frisch"
    /** U39: Kopfleisten-Chips — Schrift mit Kontrast ≥ 4,5:1 (fokussiert deckend, Ablauf-Chip). */
    const val KOPF_KONTRAST = "kopf_kontrast"
    /** P8: Player-Leiste — Fokuskette ←/→, längeres Ausblenden mit Fokus in der Leiste, Fokus zurück aufs Element, OK kurz nach dem Ausblenden löst noch aus. */
    const val HUD_BEDIENUNG = "hud_bedienung"
    /** Q1: keine fremden Dienste aus der Ursprungs-App (OwnTV-Worker für TMDB/OpenSubtitles, GitHub-Update-Rückfall).
     *  aus = altes Verhalten (Worker + GitHub werden wieder gefragt). */
    const val FREMDE_DIENSTE_AUS = "fremde_dienste_aus"
    /** Q3: Android-TV-Startbildschirm (Kanäle/Watch-Next) nur auf Geräten mit Leanback + TV-Provider. */
    const val TV_STARTSEITE_NUR_TV = "tv_startseite_nur_tv"

    // German4K 3.2.1 (p-10) — Player-Funde aus der finalen Fire-TV-Abnahme.
    /**
     * D: Live erkennt „Leitung belegt" (konfig.leitung_codes, Standard 458/460) bei ExoPlayer und mpv: ein Warten auf die
     * Freigabe der eigenen Sitzung, dann kein Engine-Rückfall und keine andere Qualitätsstufe, sondern der Störungsdialog
     * mit „Leitung freigeben". aus = 3.2.1-Verhalten (Leiter läuft durch, 460 kann auf eine andere Fassung springen).
     */
    const val LEITUNG_LIVE = "leitung_live"
    /** B: Multiview-Kacheln auf schwarzem Grund, Bild im echten Seitenverhältnis (kein grüner Decoder-Rand bei 720p). */
    const val MULTIVIEW_RAND = "multiview_rand"
    /** Multiview — ZURÜCK zeigt die Ton-Kachel im Vollbild statt in die Senderliste zu springen. */
    const val MULTIVIEW_ZURUECK = "multiview_zurueck"
    /** Player-Kopf: Senderlogo im eigenen Seitenverhältnis (ContentScale.Fit in fester Box) statt verzerrt. */
    const val KOPF_LOGO_FIT = "kopf_logo_fit"
    /** Q4: Wetter-Ort ohne eingetragenen Ort grob von unserem Server (`konfig.wetter_ort`), nie mehr ipapi.co.
     *  aus = altes Verhalten (IP-Ortung über ipapi.co). */
    const val WETTER_SERVER_ORT = "wetter_server_ort"

    // German4K 3.2.1 (u-10) — Funde aus der finalen Fire-TV-Abnahme.
    /** U40: Startseite — ↑ aus dem obersten Element erreicht die Kopfleiste auch, wenn die Liste ein paar Pixel gerollt ist. */
    const val KOPFLEISTE_OBEN = "kopfleiste_oben"
    /**
     * U41: Filme-/Serien-Raster — ↓ bleibt im Raster. Paging behält 6000 statt 300 Zeilen und die Kacheln tragen
     * einen Schlüssel aus der Kennung statt id@Index (Abwerfen einer Seite verschob mit Bündelung/Filter alle Indizes,
     * die fokussierte Kachel wurde verworfen, der Fokus fiel in die Kategorien). aus = 3.2-Verhalten.
     */
    const val RASTER_FOKUS = "raster_fokus"
    /** U42: Update-Hinweis beim Start („neueste Version", Prüfen, Fehler) unten rechts statt über der Kopfleiste. */
    const val HINWEIS_UNTEN = "hinweis_unten"
    /** U43: Datumsangaben vom Server („2026-10-27") in Landesform („27.10.2026") — Mein Zugang, Über, Dialoge. */
    const val DATUM_ANZEIGE = "datum_anzeige"
    /** U44: Abgeschnittenes ganz zeigen — CC-Untertitel kurz benannt, Wettbewerbs-Chip bis 9 sp, Vorschau-Plaketten brechen als Ganzes um. */
    const val TEXTE_GANZ = "texte_ganz"
    /** U45: Suche — gleiche Werke (Titel ohne Vorsatz + Jahr) einmal mit „N Fassungen", OK öffnet die bevorzugte Fassung. */
    const val SUCHE_BUENDELN = "suche_buendeln"
    /** U46: TV-Guide — Titel laufender Sendungen (Beginn links außerhalb) stehen am sichtbaren Rand statt draußen. */
    const val EPG_TITEL_SICHTBAR = "epg_titel_sichtbar"
    /** U47: Player „Live jetzt" wechselt zur Uhrzeit auf die nächste Sendung; Zeile „07:05–09:00 · noch 127 Min.". */
    const val EPG_JETZT_UHR = "epg_jetzt_uhr"

    // German4K 3.2.1 (t-5) — Player-Funde der Fire-TV-Endabnahme.
    /** P9: Player-Leiste — eine Ausblendzeit, solange der Fokus irgendwo im HUD steht (auch Pause); der erste Pfeil nach dem Ausblenden weckt nur (kein Zappen, keine Senderliste). */
    const val HUD_AUSBLENDEN = "hud_ausblenden"
    /** P10: Pause/Abspielen-Knopf folgt dem gewollten Zustand (playWhenReady) — beim Puffern „Pause“ statt „▶“, OK pausiert dann wirklich. */
    const val HUD_PLAYZUSTAND = "hud_playzustand"
    /** P11: Live — kein Tempo-Knopf, ein Tempo ≠ 1,0 aus Film/Serie wird beim Live-Start auf 1,0 gesetzt (mpv hing bei 1,25x alle 16 s). */
    const val LIVE_TEMPO_FEST = "live_tempo_fest"
    /** P12: Live — steht ExoPlayers Uhr nach dem ersten Bild, sofort an mpv; zweimal am selben Tonausgang → Sitzung startet Live auf mpv. */
    const val EXO_UHR = "exo_uhr"
    /** P13: mpv-Rückpuffer bei Live auf 8 MiB (Live sucht in mpv nie rückwärts); Film/Serie behält das Geräte-Budget. */
    const val SPEICHER_LIVE = "speicher_live"

    // German4K 3.2.1 (u-11) — Oberflächen-Kleinigkeiten aus der Android-9-Abnahme.
    /** U48: „Länder & Bereiche" ohne Zugang — der deutsche Server-Grund steht nicht doppelt über dem App-Hinweis. */
    const val BEREICHE_EIN_HINWEIS = "bereiche_ein_hinweis"
    /** U49: Einstellungszeilen im Hochformat — ein langer Wert-Chip steht unter der Beschreibung statt sie zu quetschen. */
    const val WERT_UNTER_TEXT = "wert_unter_text"
    /** U50: „Höchstzahl der Kacheln" hat eine eigene Beschreibung (stand wie „Multiview"). */
    const val MULTIVIEW_MAX_TEXT = "multiview_max_text"
    /** U51: gewählte Knöpfe/Preset-Chips (Glas-Voreinstellung u. a.) — Schrift mit Kontrast ≥ 4,5:1 zum Grund. */
    const val AUSWAHL_KONTRAST = "auswahl_kontrast"
    /** U52: Favoriten Film/Serie, Sortierung „Anbieter" = Anbieter-/Playlist-Reihenfolge (eigene Reihenfolge vorn). */
    const val FAVORITEN_ANBIETER = "favoriten_anbieter"
    /** U53: Kanalliste ohne Sendernummern (M3U ohne tvg-chno) — keine leere Nummernspalte. */
    const val NUMMER_NUR_WENN = "nummer_nur_wenn"
    /** U54: ZURÜCK aus dem Player, gestartet auf der Detailseite → wieder auf die Detailseite (Film; Serie: Folgen → Detail). */
    const val DETAIL_NACH_PLAYER = "detail_nach_player"
    /** U55: Hilfe & Verbindung — ↑/↓ in der Knopfreihe springt nicht seitlich auf den Nachbarknopf. */
    const val HILFE_FOKUS = "hilfe_fokus"
    /** U56: Akzentfarbe-Dialog — Hex-Feld und Regler mit der aktiven Farbe vorbelegt (nicht Türkis 52DBC8). */
    const val AKZENT_HEX_AKTUELL = "akzent_hex_aktuell"
    /** U57: Startregal heißt „Neue Filme" / „New movies" (war „Neu bei Filme"). */
    const val NEUE_FILME_TITEL = "neue_filme_titel"
    /** U58: Live-Vorschau — kleinere Bild-Plaketten, damit mit U44 auch die zweite Reihe („360p") ins Bild passt. */
    const val PLAKETTEN_KOMPAKT = "plaketten_kompakt"
    // German4K 3.2.1 (p-11) — Player-Restpunkte (Touch, Hinweise, Menüs, Rest-Kerne).
    /** P-11/1: Touch — Fortschrittsleiste (Film/Serie) antippen springt, Ziehen zeigt die Zielzeit; die Leiste bleibt während der Geste stehen. */
    const val SEEK_TOUCH = "seek_touch"
    /** P-11/2: Multiview per Touch — Kachel antippen gibt ihr Fokus und Ton (leer: Sender wählen), lange drücken öffnet das Kachelmenü. */
    const val MULTIVIEW_TIPPEN = "multiview_tippen"
    /** P-11/3: Player-Hinweise (Surround, Bild-Untertitel, Engine-Wechsel …) als eigene Meldung oben im Player statt System-Toast über der Leiste. */
    const val PLAYER_HINWEIS_OBEN = "player_hinweis_oben"
    /** P-11/4: Player-Menüs — langer Spurname bricht auf 2 Zeilen um, Haken/Stern bleibt immer sichtbar. */
    const val MENUE_ZEILEN = "menue_zeilen"
    /** P-11/5: höchstens ein verworfener (hängender) mpv-Kern; hängt trotzdem ein weiterer, vorher auf ExoPlayer, bis der Rest frei ist kein neuer mpv-Kern. */
    const val KERN_OBERGRENZE = "kern_obergrenze"
    /** P-11/6: In-App-Hinweise (z. B. „Für Multiview gemerkt“) mit Seitenabstand und zentriertem Text. */
    const val HINWEIS_RAND = "hinweis_rand"
    /** P-11/7: Ton-Menü — Überschrift über dem A/V-Versatz, Regler nur unter mpv; Player-Menüs im Hochformat bis 70 % Bildhöhe, „Aus“ bleibt sichtbar. */
    const val TON_MENUE = "ton_menue"
    /** P-11/8: Handy/Tablet — Untertitel weichen der eingeblendeten Player-Leiste nach oben aus (App-Overlay und ExoPlayer). */
    const val UNTERTITEL_LEISTE = "untertitel_leiste"
    // German4K 3.2.1 (p-12) — letzte Beobachtungen Build 40.
    /** P-12/1: Film/Serie mpv → ExoPlayer — die gerade laufende Text-Untertitelzeile bleibt stehen, bis ExoPlayer selbst eine Zeile liefert (Media3 liest eine vor dem Einstieg begonnene Zeile nicht). */
    const val EXO_TEXT_BRUECKE = "exo_text_bruecke"
    /** P-12/2: Restzeit-Blase über dem Fortschrittsknopf wird nicht mehr vom abgerundeten Rahmen der Player-Leiste abgeschnitten. */
    const val LEISTE_BLASE_FREI = "leiste_blase_frei"
    /** P-12/3: Live ExoPlayer → mpv (HUD-Engine-Knopf) nimmt Untertitel und Tonspur mit (Gegenrichtung zu q-3 A). */
    const val LIVE_SPUR_MPV = "live_spur_mpv"
    /** P-12/4: Download-Benachrichtigung nur bei echtem Download, erst nach einigen Sekunden, und am Ende sicher entfernt. */
    const val DOWNLOAD_HINWEIS_KURZ = "download_hinweis_kurz"
    // German4K 3.2.1 (q-4) — Review-Funde außerhalb des Player-Kerns (Kandidat 6).
    /** Q4-4: Filme-/Serien-Raster behalten je nach Gerätespeicher 1500 (wenig RAM) bzw. 3000 Zeilen statt 6000 (U41); aus = 6000. */
    const val RASTER_SPEICHER = "raster_speicher"
    /** Q4-7: Multiview verlassen (ZURÜCK/Vollbild) — erst warten, bis die Kachel-Leitungen wirklich zu sind (≤ 1,5 s), dann Vollbild tunen. */
    const val MULTIVIEW_FREIGABE = "multiview_freigabe"
    /** Q4-11: Download geht beim Start eines echten Downloads sofort in den Vordergrund (Android 12+ im Hintergrund sonst verboten). */
    const val DOWNLOAD_SOFORT_VORNE = "download_sofort_vorne"
    /** Q4-12: Update-Prüfung beim Start wartet still auf die Panel-Antwort; „konnte nicht prüfen" nur bei manueller Prüfung. */
    const val UPDATE_START_STILL = "update_start_still"
    /** Q4-a: Player-Leiste — passen Medien- und Werkzeugknöpfe nicht in eine Zeile (Hochformat), stehen die Werkzeuge in einer zweiten Reihe statt gequetscht. */
    const val PLAYER_LEISTE_UMBRUCH = "player_leiste_umbruch"
    /** Q4-b: Meldung „Engine gewechselt" oben bei den Player-Hinweisen statt unten auf der Fortschrittslinie. */
    const val ENGINE_HINWEIS_OBEN = "engine_hinweis_oben"
    // German4K 3.2.1 (u-12) — Rückschritte aus der Fire-TV-Abnahme von Kandidat 6.
    /** U12-1: TV-Guide — ▶ aus dem Sendernamen bzw. ▶ in der markierten Zeile springt gleich auf die laufende Sendung (Vorschau unten), ◀ ▶ blättern; ◀ vor der ersten Sendung zurück zum Sender. Aus = erst OK in der Zeile. */
    const val EPG_ZELLE_DIREKT = "epg_zelle_direkt"
    /** U12-2: Startseite — Zurück aus Detailseite/Player landet auf der geöffneten Karte im richtigen Regal (alle Regale); Datennachschub rollt die Liste nicht mehr nach oben, solange der Fokus in ihr steht. */
    const val START_RUECK_KARTE = "start_rueck_karte"
    /** U12-3: Live-TV — bekommt das Suchfeld den Fokus, steht die Liste am zuletzt markierten Sender mit bis zu zwei Zeilen darüber (ganz oben = wirklich ganz oben). */
    const val LIVE_LISTE_OBEN = "live_liste_oben"
    // German4K 3.2.1 (t-7) — Funde der TV-Abnahme von Kandidat 6.
    /** T7-1: Fehlerbilder (Vollbild, Multiview-Kachel, Hinweis) zeigen nie rohen Server-Text, sondern einen Kundensatz je bekanntem Code; Technikzeile nur bei „Detaillierte Wiedergabeprotokollierung“. Belegte Leitung: Knopf „Leitung freigeben“.
     *  German4K 3.2.1 (t-15): hängt auch die dezente Hinterlegung des Fehlerbilds im Vollbild (t-14/2, PlayerHud) — bewusst
     *  an diesem Schalter, nicht an `zap_erst_schliessen`. */
    const val FEHLER_KUNDENTEXT = "fehler_kundentext"
    /** T7-2: Live „Leitung belegt“ (458/460) — ExoPlayer wiederholt die Anfrage nicht selbst, mpv ohne Nachfrist; genau eine Wiederholung nach kurzer Wartezeit, dann der Dialog. */
    const val LEITUNG_SCHNELL = "leitung_schnell"
    /** T7-3: Am Fernseher weichen Untertitel der eingeblendeten Player-Leiste nach oben aus (wie Handy/Tablet, Schalter `untertitel_leiste`). */
    const val UNTERTITEL_LEISTE_TV = "untertitel_leiste_tv"
    /** T7-4: Einstellungen „Bevorzugte Audiosprache“ — Automatisch, Deutsch, Englisch, dann übrige alphabetisch (App-Sprache), „Keine“ am Ende. */
    const val AUDIO_SPRACHEN_REIHENFOLGE = "audio_sprachen_reihenfolge"
    // German4K 3.2.1 (q-5) — Hochformat-Kleinigkeiten aus der TV-Abnahme von Kandidat 6.
    /** Q5-1: Handy/Tablet-Startseite — Filme/Folgen, die schon in „Filme/Serien weiterschauen" stehen, nicht zusätzlich unter „Weiterschauen" (dort bleiben die Sender). Fernseher unverändert. */
    const val WEITERSCHAUEN_EINMAL = "weiterschauen_einmal"
    /** Q5-2: Handy hoch — Kategorie (z. B. „Alle Filme"/„Alle Serien") per Steuerkreuz/Tastatur geöffnet: Fokus aufs erste Poster statt auf die Suche in der Kopfleiste. */
    const val KATEGORIE_FOKUS_RASTER = "kategorie_fokus_raster"
    /** Q5-3: Untere Leiste (Handy/Tablet) — ↓ aus dem Inhalt landet auf dem aktiven Eintrag statt auf dem geometrisch nächsten. */
    const val LEISTE_FOKUS_AKTIV = "leiste_fokus_aktiv"
    // German4K 3.2.1 (p-15) — Befunde der Emulator-Abnahme von Kandidat 8.
    /** P15-1: Vollbild-Overlays (Hilfe & Verbindung, Bereiche, Kunde, Erinnerungen) halten den Fokus — auch während „Verbindung prüfen“ läuft; danach zurück auf den Prüf-Knopf. */
    const val OVERLAY_FOKUS = "overlay_fokus"
    /** P15-2: Startseite — „Jetzt im TV“ zeigt keine Sender, die schon unter „Weiterschauen“ stehen (bleiben zu wenige übrig, Verhalten wie bisher). */
    const val JETZT_TV_OHNE_WEITER = "jetzt_tv_ohne_weiter"
    /** P15-3: Handy/Tablet hoch — Knöpfe in „Hilfe & Verbindung“ untereinander statt abgeschnitten nebeneinander. */
    const val HILFE_KNOEPFE_MOBIL = "hilfe_knoepfe_mobil"
    /** P15-5: „Hier anmelden“ mit leerem Feld — Hinweis am Feld statt stummem, gesperrtem Knopf. */
    const val LOGIN_LEER_HINWEIS = "login_leer_hinweis"
    /** P15-6: Live — antwortet der Server gar nicht (Verbindung abgelehnt, DNS, Verbindungsaufbau), kommt der Fehler nach ~15 s statt nach ~55 s. */
    const val TOTER_HOST_SCHNELL = "toter_host_schnell"
    // German4K 3.2.1 (q-6) — Netzlücke während Live.
    /** Q6: Live — mpv „geöffnet“ und gleich danach „loading failed“ zählt als Fehlschlag (Leiter läuft weiter); Live-Fehler ohne validiertes Netz → sobald das Netz zurück ist, startet derselbe Sender einmal von selbst neu (nie bei 458/460, Multiview, anderem Sender, App im Hintergrund). */
    const val NETZ_ZURUECK_NEUSTART = "netz_zurueck_neustart"
    // German4K 3.2.1 (p-16) — Befunde der Emulator-Abnahme von Kandidat 9.
    /** P16-1: „Verbindung prüfen“ — Uhrvergleich nur mit der frischen Panel-Antwort dieser Prüfung (Empfangszeitpunkt), nie mit der gespeicherten; kein Fehlalarm „Uhr geht falsch“, Selbsttest-Bericht ebenso. */
    const val UHR_FRISCH = "uhr_frisch"
    /** P16-2: Live — Server nimmt die Verbindung an und schließt sofort (ExoPlayer „unexpected end of stream“ UND mpvs erster Versuch „End of file“, ohne HTTP-Antwort): Leiter bricht ab statt Wiederholungen/Formatwechsel; Live-Fehler zeigt nur noch den einen Endtext „Dieser Sender konnte nicht …“ (kein Format-/User-Agent-Zwischentext). */
    const val TOTER_STREAM_SCHNELL = "toter_stream_schnell"
    /** P16-3: Einrichtung — BACK geht einen Schritt zurück (wie der Zurück-Knopf des Schritts); auf dem ersten Schritt der manuellen Einrichtung zurück zu „Fast geschafft“. Aus = BACK beendete die App. */
    const val EINRICHTEN_ZURUECK = "einrichten_zurueck"
    /** P16-4: Multiview — auch eine Fehlerkachel (z. B. „Anbieter erlaubt nur einen Stream“) hat „Kachel entfernen“; nach der Senderauswahl (Auswahl oder ZURÜCK) Fokus zurück auf die Kachel, von der man kam. */
    const val MULTIVIEW_FEHLERKACHEL = "multiview_fehlerkachel"
    /** P16-5: Automatische Update-Prüfung einmal je App-Start (nicht nach Drehen/Neuaufbau) und still — „Du verwendest die neueste Version“/„Prüfe…“/Fehler nur nach einer Prüfung durch den Kunden (Einstellungen); ein verfügbares Update zeigt sie weiter. */
    const val UPDATE_HINWEIS_EINMAL = "update_hinweis_einmal"
    /** P16-6: Startseite „Weiterschauen“ (Sender) — kommt ein neuer erster Sender dazu, rollt die Reihe an den Anfang, solange der Fokus nicht in ihr steht (sonst blieb sie seitlich verschoben, der neueste Sender links aus dem Bild). */
    const val WEITER_REIHE_ANFANG = "weiter_reihe_anfang"
    // German4K 3.2.1 (t-9) — Live-Start bei langsamem Server.
    /** T9: Live — hat ExoPlayer nach 12 s schon eine HTTP-Antwort bzw. fließen Bytes, aber noch kein Bild, einmalig ~6 s Nachfrist statt Wechsel auf mpv (nie bei 458/460). Aus = Wechsel nach 12 s wie 3.2. */
    const val EXO_NACHGEBEN = "exo_nachgeben"
    // German4K 3.2.1 (u-15) — Filme-/Serien-Raster ruckelte am Fire TV 4K Max (p50 34 ms).
    /**
     * U15: Raster-Kacheln ohne verschachtelte Rundungs-Clips — Kachel (ruhend), IMDb-Schild, IMDb-Zeichen, Plaketten und
     * Anbieter-Chip zeichnen ihre Rundung als Fläche statt als Clip-Ebene (je Kachel bis zu vier ineinander liegende
     * abgerundete Clips, die die GPU bei jedem Bild neu schneiden musste). Sieht gleich aus. aus = 3.2.1-Zeichnung.
     */
    const val RASTER_LEICHT = "raster_leicht"
    // German4K 3.2.1 (u-17) — Startseite „Jetzt im TV“/„Weiterschauen“ ruckelte am Fire TV 4K Max (p50 36–42 ms).
    /**
     * U17: Glaseffekt — die FOKUSSIERTE Glasfläche zeichnet ihr Glaslicht (Lichtkegel, Glanzstreifen, Schatten) als
     * gebackene Bilder wie L6 die ruhenden, statt drei bis fünf Verläufen je Bild (~7 ms GPU auf der Live-Karte).
     * Sieht gleich aus. aus = Verläufe live wie bisher.
     */
    const val GLAS_FOKUS_BILD = "glas_fokus_bild"
    // German4K 3.2.1 (p-17) — Befunde der Emulator-Abnahme von Kandidat 10.
    /**
     * P17-1: Live (ExoPlayer) — Neuversuch nach einem Hänger schließt vorher die alte Verbindung (Player stoppen, Sockets
     * zu); bekommt er trotzdem „Leitung belegt“ (458/460), genau eine Wiederholung nach ~4 s. Zeigt die App das Fehlerbild,
     * ist der Player gestoppt: kein Ton, keine Verbindung, kein späterer Neuversuch im Hintergrund.
     */
    const val HAENGER_SAUBER = "haenger_sauber"
    /** P17-1c: Live (ExoPlayer) — Zeitsprung im Strom (Ton-Zeitstempel springt ≥ 10 s) → sofort sauber neu verbinden statt ~13 s Standbild. */
    const val LIVE_ZEITSPRUNG = "live_zeitsprung"
    /** P17-5: Einrichtung/Import — Inhaltszahlen voll mit Tausenderpunkt („3.600 Filme“) statt Kurzform („3,6 Tsd.“). */
    const val ZAHL_VOLL = "zahl_voll"
    // German4K 3.2.1 (p-18) — Befunde der Fire-TV-Abnahme von Kandidat 12.
    /** P18-1: mpv-Kern entsteht erst, wenn mpv wirklich etwas lädt — Vollbild auf ExoPlayer baut keinen Kern und koppelt keinen ab (Oberfläche stockte 1–2 s). Aus = Kern schon beim Anlegen der Bildfläche. */
    const val MPV_BEI_BEDARF = "mpv_bei_bedarf"
    /** P18-2: Update auf 3.2.1 — Multiview einmalig an (Merker), ein späteres Ausschalten durch den Kunden bleibt. Aus = gespeicherte Wahl bleibt unangetastet. */
    const val MULTIVIEW_MIGRATION = "multiview_migration"
    /** P18-3: Player-Kopf — Sendungs-Überschriften („Danach") einzeilig, die Spalte „Danach" bekommt ihren Platz zuerst; kein Umbruch mitten im Wort. */
    const val EPG_KOPF_EINZEILIG = "epg_kopf_einzeilig"
    /** P18-4: Fußball — ZURÜCK aus einer Spielseite setzt den Fokus wieder auf die Karte dieses Spiels statt auf die erste Karte. */
    const val SPORT_RUECK_FOKUS = "sport_rueck_fokus"
    /** P18-6: Ablauf-/Info-Hinweis — auch „Später" und ZURÜCK blenden ihn bis zum nächsten Kalendertag aus (Wartung und „abgelaufen" bleiben). */
    const val ABLAUF_HINWEIS_RUHIG = "ablauf_hinweis_ruhig"
    // German4K 3.2.1 (t-14) — Befunde der Emulator-Abnahme von Kandidat 11.
    /**
     * T14: Live auf 1-Verbindungs-Zugängen (max_connections 1 oder in diesem Lauf schon 458/460 außerhalb einer Kachel;
     * t-15: German4K „0“ nicht mehr pauschal, gilt erst mit geladenen Schaltern) — beim
     * Senderwechsel (Vorschau, Vollbild, CH+/−, Senderliste, Multiview) erst die alte Leitung wirklich schließen, dann den
     * neuen Sender anfragen; „Leitung belegt“ (458/460) beim Neuverbinden mit Backoff 1,5/3/5 s je Ereignis statt nach
     * einer Wiederholung aufzugeben (ExoPlayer, mpv, Multiview-Kachel). aus = Verhalten bis t-13.
     */
    const val ZAP_ERST_SCHLIESSEN = "zap_erst_schliessen"
    // German4K 3.2.1 (q-8) — Befund der k13-Abnahme (Android 9, Handy).
    /**
     * Q8: Handy/Tablet — Drehen (Hoch ⇄ Quer, auch Fenstergröße/Splitscreen) baut die Oberfläche nicht mehr neu auf:
     * Sender, Film und Serie spielen ohne Neuverbindung weiter, Reiter und Dialoge bleiben. Fernseher: unverändert
     * (Neuaufbau wie bisher). aus = Verhalten bis 3.2.1-t-15 (jede Drehung baut die Activity neu, der Player ist weg).
     */
    const val DREHEN_OHNE_NEUSTART = "drehen_ohne_neustart"
    // German4K 3.2.1 (p-20) — Befunde der Fire-TV-Schlussabnahme von Kandidat 16.
    /** P20-4: „Leitung belegt"-Dialog — Startfokus auf „Schließen" statt „Verbindung freigeben" (ein versehentliches OK wirft das andere Gerät raus). Aus = Fokus auf „Verbindung freigeben". */
    const val LEITUNG_FOKUS_SICHER = "leitung_fokus_sicher"
    /** P20-2: Live-Vorschau bewusst stumm (Vorschauton aus / Spur abgewählt) — der Ton-Wächter zählt nicht, kein Stereo-Rückfall „exo/live: no sound…". Aus = Wächter läuft auch stumm. */
    const val TON_WAECHTER_STUMM = "ton_waechter_stumm"
    /** P20-3: HOME während der Wiedergabe — Player schon bei onUserLeaveHint anhalten (vor dem Abbau der Bildfläche), sonst wartete der Main-Thread bis zu 2 s, bis ExoPlayer die Surface vom laufenden Decoder löste (Fire TV: 142 Bilder übersprungen). Aus = Stopp erst in onStop. */
    const val HOME_FRUEH_STOPPEN = "home_frueh_stoppen"
    /** P20-5: „Wer schaut gerade?" beim Kaltstart — Fokus auf das erste Profil erst, wenn die Kachel steht (je Frame neu, bis er sitzt). Vorher ging der eine Versuch ins Leere: kein Fokus, Steuerkreuz ohne Wirkung (TV-Emulator, 2 Profile). Aus = ein Versuch wie bisher. */
    const val PROFILWAHL_FOKUS = "profilwahl_fokus"
    /** Topspiel 25.10.: Konferenz mit einer Paarung (Server `topspiel`) als große Karte — Wappen groß über die Breite, Anstoß/LIVE in der Mitte. Aus = normale Konferenz-Karte. */
    const val TOPSPIEL_KARTE = "topspiel_karte"
    // German4K 3.3 — Kategorien-Rail.
    /** Länderfilter: Flaggenleiste über der Kategorien-Rail (Live/Filme/Serien, ab 3 Ländern), mitlaufende Markierung, Filter je Land. Aus = Rail wie bis 3.2. */
    const val LAENDERLEISTE = "laenderleiste"
    /** Zahl der Einträge (Sender/Filme/Serien) rechts in jedem Kategorie-Eintrag, aus einer gruppierten Abfrage je Bereich. Aus = Rail ohne Zahl wie bis 3.2. */
    const val KATEGORIE_ANZAHL = "kategorie_anzahl"
    /**
     * German4K 3.3: Erwachsenen-Rubriken (Live, Filme, Serien) öffnen und spielen nur nach dem Erwachsenen-PIN (Standard 0000,
     * änderbar unter Einstellungen → Profil); bis dahin fehlen sie auch in „Alle", Favoriten, Verlauf, Suche und Programmführer.
     * Aus = Verhalten wie 3.2.1 (Erwachsenes offen, nur im Kinderprofil ausgeblendet).
     */
    const val ERWACHSENEN_PIN = "erwachsenen_pin"
    // German4K 3.3: Sport-Hub — „Meine Mannschaft“ und „Läuft gerade nicht? Nächste Übertragung“.
    /** Lieblingsmannschaften: Spiele zuerst mit ★, Verwaltung, Auto-Erinnerung vor Anpfiff. Aus = Hub/Startseite wie vorher, keine Auto-Erinnerung. */
    const val MEINE_MANNSCHAFT = "meine_mannschaft"
    /** Hub-Spiel ohne Bild → nächste Fassung aus `sender` (gleiche Sprache zuerst), Knopf „Nächste Übertragung“, Meldung `platz_tot`. Aus = Fehlerbild wie vorher. */
    const val NAECHSTE_UEBERTRAGUNG = "naechste_uebertragung"
    // German4K 3.3 — Kundenwünsche (ultra-3.3-d).
    /** Schlaf-Timer im Player (Live und Filme/Serien): Aus · 30 · 60 · 90 · 120 min, 1 min vorher Hinweis, dann Player zu. aus = kein Knopf. */
    const val SCHLAF_TIMER = "schlaf_timer"
    /** Event-/PPV-Kategorien (Name: PPV/Event/Eventi) beim Öffnen per get_live_streams&category_id frisch, höchstens alle 60 s; Fehler still. aus = nur Katalog-Cache. */
    const val EVENT_FRISCH = "event_frisch"
    /** Zusätzliche Quelle mit dauerhaft 401/403 (≥3× über ≥30 min): kein Hintergrund-Abgleich mehr, beim Öffnen „abgelaufen — entfernen?“. Gekoppelter Hauptzugang ausgenommen. aus = wie bisher. */
    const val PROFIL_ABGELAUFEN = "profil_abgelaufen"
    /** Film/Serie: Ende der Engine-Kette mit 502/503/504 → „Gerade sehr viel los …“ und stiller Neuversuch nach 30 s, höchstens 3×, abbrechbar. aus = „beide Engines fehlgeschlagen“. */
    const val UEBERLAST_NEUVERSUCH = "ueberlast_neuversuch"
    /**
     * M3U-Link mit unserem Host und Benutzer/Passwort ([German4kM3uXtream]) → Xtream-Zugang mit Live, Filmen, Serien und
     * EPG; ungekoppeltes Gerät wird dabei mit denselben Zugangsdaten gekoppelt (Login-Weg 1). aus = als M3U wie 3.2 (Kunde).
     */
    const val M3U_ALS_XTREAM = "m3u_als_xtream"
    // German4K 3.3.1 (B4): Wiederholbremse für Filme/Serien.
    /** Derselbe Film/dieselbe Folge scheitert 3× in 2 min (auch über Engine-Wechsel) → anhalten + Hinweis statt Endlos-Neuladen ([German4kVodBremse]). Live und Überlast-Neuversuch unberührt. aus = wie 3.3. */
    const val VOD_BREMSE = "vod_bremse"
}

/**
 * German4K 3.2.1 (D1): Zählpunkt-Namen für `app_aufrufe` — Server-Whitelist `EREIGNIS_AKTIONEN`
 * in lib/app-panel/ereignis.ts muss jeden Namen kennen, sonst wird er still verworfen.
 */
object German4kEreignisse {
    const val MULTIVIEW_AN = "multiview_an"
    const val MULTIVIEW_START = "multiview_start"
    const val LEITUNG_FREIGEBEN = "leitung_freigeben"
    const val TON_MODUS = "ton_modus"
    const val ANGESAGT_FILTER = "angesagt_filter"
}
