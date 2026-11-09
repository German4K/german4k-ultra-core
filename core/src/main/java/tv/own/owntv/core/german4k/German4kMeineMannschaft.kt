package tv.own.owntv.core.german4k

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * German4K 3.3: „Meine Mannschaft“ — die reinen Regeln, ohne Android (German4kMeineMannschaftTest).
 *
 * - Eine Mannschaft ist ihr Name, wie der Server ihn in `heim`/`gast` (bzw. in den Paarungen einer Konferenz)
 *   schickt. Verglichen wird ohne Groß/Klein und mit zusammengezogenem Leerraum ([schluessel]).
 * - Spiele der eigenen Mannschaften stehen in jedem Regal zuerst; die Reihenfolge innerhalb beider Teile bleibt.
 * - Nie ein Spielstand oder Ergebnis — hier wird nur sortiert und markiert.
 */
object German4kMeineMannschaft {
    /** Auto-Erinnerungen nur für Spiele in den nächsten Tagen — weiter vorn ändert sich der Plan noch. */
    const val ERINNERN_VORAUS_MS = 8L * 24 * 60 * 60 * 1000

    /** Startseite: so viele Spiele der eigenen Mannschaften aus „Morgen & später“ kommen zusätzlich ins Regal. */
    const val START_SPAETER_MAX = 2

    private val LEERRAUM = Regex("\\s+")

    fun schluessel(name: String): String = name.trim().replace(LEERRAUM, " ").lowercase()

    /** Alle Mannschaften eines Eintrags: Heim, Gast und bei einer Konferenz die Vereine der Paarungen. */
    fun mannschaften(spiel: German4kSpiel): List<String> = buildList {
        add(spiel.heim)
        add(spiel.gast)
        spiel.paarungen.forEach { add(it.heim); add(it.gast) }
    }.map { it.trim() }.filter { it.isNotEmpty() }.distinctBy { schluessel(it) }

    /** [meine] = Schlüssel ([schluessel]) der gewählten Mannschaften. */
    fun istMeine(spiel: German4kSpiel, meine: Set<String>): Boolean =
        meine.isNotEmpty() && mannschaften(spiel).any { schluessel(it) in meine }

    /** Stabil: erst die Spiele der eigenen Mannschaften, dann der Rest — je in der bisherigen Reihenfolge. */
    fun meineZuerst(spiele: List<German4kSpiel>, meine: Set<String>): List<German4kSpiel> {
        if (meine.isEmpty()) return spiele
        val (vorn, rest) = spiele.partition { istMeine(it, meine) }
        return vorn + rest
    }

    fun regaleMeineZuerst(r: SportRegale, meine: Set<String>): SportRegale =
        if (meine.isEmpty()) r else SportRegale(
            konferenzen = meineZuerst(r.konferenzen, meine),
            live = meineZuerst(r.live, meine),
            heute = meineZuerst(r.heute, meine),
            spaeter = meineZuerst(r.spaeter, meine),
            beendet = meineZuerst(r.beendet, meine),
        )

    /**
     * Startseiten-Regal „Jetzt im Fußball“: laufende und heutige Spiele wie bisher; dazu höchstens
     * [START_SPAETER_MAX] kommende Spiele der eigenen Mannschaften. Eigene zuerst, dann auf [max] kürzen.
     */
    fun startRegal(r: SportRegale, meine: Set<String>, max: Int): List<German4kSpiel> {
        val basis = r.live + r.heute
        if (meine.isEmpty()) return basis.take(max)
        val dazu = r.spaeter.filter { istMeine(it, meine) }.take(START_SPAETER_MAX)
        return meineZuerst(basis + dazu, meine).take(max)
    }

    /**
     * Für welche Spiele die App jetzt von selbst eine Erinnerung anlegt: kommende (nicht laufend, nicht beendet,
     * Anstoß in der Zukunft und höchstens [ERINNERN_VORAUS_MS] entfernt) der eigenen Mannschaften, die noch nicht
     * behandelt wurden ([erledigt] = Spiel-Kennungen). Eine Konferenz nur, wenn es für die Mannschaft zur selben
     * Anstoßzeit kein eigenes Spiel gibt — sonst klingelte es zweimal für dasselbe Spiel.
     */
    fun erinnerungsKandidaten(
        spiele: List<German4kSpiel>,
        meine: Set<String>,
        jetztMs: Long,
        erledigt: Set<Long>,
    ): List<German4kSpiel> {
        if (meine.isEmpty()) return emptyList()
        val kommend = spiele.filter { s ->
            val start = s.start.toEpochMilli()
            !s.laeuft && !s.beendet && start > jetztMs && start - jetztMs <= ERINNERN_VORAUS_MS && istMeine(s, meine)
        }
        val (konferenzen, einzeln) = kommend.partition { it.istKonferenz }
        val konfOhneEinzel = konferenzen.filter { k ->
            val teams = mannschaften(k).map { schluessel(it) }.filter { it in meine }.toSet()
            einzeln.none { e -> e.start == k.start && mannschaften(e).any { schluessel(it) in teams } }
        }
        return (einzeln + konfOhneEinzel).filter { it.id !in erledigt }.sortedBy { it.start }
    }
}

private val Context.german4kMannschaftStore: DataStore<Preferences> by preferencesDataStore(name = "german4k_mannschaft")

/**
 * German4K 3.3: gewählte Mannschaften und „Erinnerung vor Anpfiff“ — lokal je Gerät (DataStore, kein Server).
 * Die Auto-Erinnerungen merken sich je Profil, welche Spiele schon behandelt sind ([erledigteSpiele]): löscht
 * der Kunde eine, kommt sie nicht wieder.
 */
class German4kMannschaften(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sperre = Mutex()

    /** Namen, wie sie gewählt wurden (zum Anzeigen). */
    val namen: StateFlow<Set<String>> = context.german4kMannschaftStore.data
        .map { it[KEY_NAMEN] ?: emptySet() }
        .stateIn(scope, SharingStarted.Eagerly, emptySet())

    /** Schlüssel ([German4kMeineMannschaft.schluessel]) — zum Vergleichen. */
    val schluessel: StateFlow<Set<String>> = namen
        .map { n -> n.map { German4kMeineMannschaft.schluessel(it) }.toSet() }
        .stateIn(scope, SharingStarted.Eagerly, emptySet())

    /** „Erinnerung vor Anpfiff“ — Standard an. */
    val erinnernAn: StateFlow<Boolean> = context.german4kMannschaftStore.data
        .map { it[KEY_ERINNERN] ?: true }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, true)

    fun istMeine(name: String): Boolean = German4kMeineMannschaft.schluessel(name) in schluessel.value

    /** Merken bzw. wieder entfernen. true = jetzt gemerkt. */
    suspend fun umschalten(name: String): Boolean {
        val sauber = name.trim()
        if (sauber.isEmpty()) return false
        var gemerkt = false
        bearbeite { p ->
            val alt = p[KEY_NAMEN] ?: emptySet()
            val key = German4kMeineMannschaft.schluessel(sauber)
            val ohne = alt.filterNot { German4kMeineMannschaft.schluessel(it) == key }.toSet()
            gemerkt = ohne.size == alt.size
            p[KEY_NAMEN] = if (gemerkt) ohne + sauber else ohne
        }
        return gemerkt
    }

    suspend fun entfernen(name: String) {
        val key = German4kMeineMannschaft.schluessel(name)
        bearbeite { p -> p[KEY_NAMEN] = (p[KEY_NAMEN] ?: emptySet()).filterNot { German4kMeineMannschaft.schluessel(it) == key }.toSet() }
    }

    suspend fun setzeErinnern(an: Boolean) = bearbeite { p -> p[KEY_ERINNERN] = an }

    /** Spiel-Kennungen, die für [profilId] schon behandelt sind. */
    suspend fun erledigteSpiele(profilId: Long): Set<Long> = runCatching {
        val vorne = "$profilId:"
        (context.german4kMannschaftStore.data.first()[KEY_ERLEDIGT] ?: emptySet())
            .filter { it.startsWith(vorne) }.mapNotNull { it.substring(vorne.length).toLongOrNull() }.toSet()
    }.getOrDefault(emptySet())

    /** Von der App selbst angelegte (nicht vom Kunden gesetzte) — nur die räumt „Erinnern aus“ wieder weg. */
    suspend fun selbstAngelegt(profilId: Long): Set<Long> = runCatching {
        val vorne = "$profilId:"
        (context.german4kMannschaftStore.data.first()[KEY_ANGELEGT] ?: emptySet())
            .filter { it.startsWith(vorne) }.mapNotNull { it.substring(vorne.length).toLongOrNull() }.toSet()
    }.getOrDefault(emptySet())

    suspend fun merkeErledigt(profilId: Long, spielId: Long, angelegt: Boolean) = bearbeite { p ->
        val eintrag = "$profilId:$spielId"
        p[KEY_ERLEDIGT] = kuerzen((p[KEY_ERLEDIGT] ?: emptySet()) + eintrag)
        if (angelegt) p[KEY_ANGELEGT] = kuerzen((p[KEY_ANGELEGT] ?: emptySet()) + eintrag)
    }

    /** Nach „Erinnern aus“: die selbst angelegten vergessen — auch als erledigt, damit „an“ sie wieder anlegt. */
    suspend fun vergissAngelegt(profilId: Long) = bearbeite { p ->
        val vorne = "$profilId:"
        val angelegt = (p[KEY_ANGELEGT] ?: emptySet()).filter { it.startsWith(vorne) }.toSet()
        p[KEY_ANGELEGT] = (p[KEY_ANGELEGT] ?: emptySet()) - angelegt
        p[KEY_ERLEDIGT] = (p[KEY_ERLEDIGT] ?: emptySet()) - angelegt
    }

    private suspend fun bearbeite(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        sperre.withLock {
            runCatching { context.german4kMannschaftStore.edit { block(it) } }
                .onFailure { Log.w(TAG, "Speichern: ${it.message}") }
        }
    }

    private companion object {
        const val TAG = "German4kMannschaft"
        val KEY_NAMEN = stringSetPreferencesKey("namen")
        val KEY_ERINNERN = booleanPreferencesKey("erinnern_vor_anpfiff")
        val KEY_ERLEDIGT = stringSetPreferencesKey("auto_erledigt")
        val KEY_ANGELEGT = stringSetPreferencesKey("auto_angelegt")

        /** Spiel-Kennungen wachsen; die ältesten (kleinsten) fallen weg, damit der Speicher klein bleibt. */
        const val MAX_EINTRAEGE = 400

        fun kuerzen(s: Set<String>): Set<String> =
            if (s.size <= MAX_EINTRAEGE) s
            else s.sortedBy { it.substringAfter(':').toLongOrNull() ?: 0L }.takeLast(MAX_EINTRAEGE).toSet()
    }
}

/**
 * German4K 3.3: legt für Spiele der eigenen Mannschaften die „⏰ Erinnern“-Erinnerung (10 min vor Anpfiff) selbst an —
 * über das bestehende Erinnerungs-System, keine eigene Push-Infrastruktur. Beobachtet den Spielplan, solange die
 * App läuft ([starte] ist idempotent). Schalter `meine_mannschaft` aus oder Einstellung aus → nichts Neues.
 */
class German4kMannschaftWache(
    private val mannschaften: German4kMannschaften,
    private val sport: German4kSportRepository,
    private val erinnerungen: German4kErinnerungen,
    private val settings: tv.own.owntv.core.settings.SettingsRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var gestartet = false
    private val sperre = Mutex()

    @Synchronized
    fun starte() {
        if (gestartet) return
        gestartet = true
        scope.launch {
            combine(sport.antwort, mannschaften.schluessel, mannschaften.erinnernAn, German4kFeatures.flow) { a, meine, an, _ ->
                Triple(a, meine, an)
            }.collect { (a, meine, an) ->
                runCatching { abgleichen(a, meine, an) }.onFailure { Log.w("German4kMannschaft", "Wache: ${it.message}") }
            }
        }
    }

    private suspend fun abgleichen(a: German4kSportAntwort?, meine: Set<String>, an: Boolean) = sperre.withLock {
        if (!German4kFeatures.an(German4kSchalter.MEINE_MANNSCHAFT) || !German4kErinnerungen.aktiv()) return@withLock
        val profil = settings.activeProfileIdNow()
        if (profil < 0) return@withLock
        if (!an) {
            // Ausgeschaltet: die selbst angelegten, noch offenen wieder wegräumen; die vom Kunden gesetzten bleiben.
            val angelegt = mannschaften.selbstAngelegt(profil)
            if (angelegt.isEmpty()) return@withLock
            angelegt.forEach { erinnerungen.spielVergessen(it) }
            mannschaften.vergissAngelegt(profil)
            return@withLock
        }
        if (a == null || !a.ok || meine.isEmpty()) return@withLock
        val kandidaten = German4kMeineMannschaft.erinnerungsKandidaten(
            a.spiele, meine, System.currentTimeMillis(), mannschaften.erledigteSpiele(profil),
        )
        for (s in kandidaten) {
            val r = erinnerungen.spielMerkenWennNeu(s) ?: continue
            mannschaften.merkeErledigt(profil, s.id, angelegt = r)
        }
    }
}
