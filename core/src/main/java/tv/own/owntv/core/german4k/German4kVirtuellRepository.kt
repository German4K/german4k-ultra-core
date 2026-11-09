package tv.own.owntv.core.german4k

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Sendepläne virtueller Sender (Weg B). Hält Antworten 60 s je (Sender, Fenster) — so viel erlaubt der
 * Vertrag (§3.6) — und teilt eine laufende Abfrage. Kein DataStore: ohne frische `server_time` ist ein
 * gespeicherter Plan wertlos, und ohne Plan spielt der Sender einfach normal.
 *
 * [holen] bekommt `streamId` (null = alle) und `fensterH`; null heißt Transportfehler.
 */
class German4kVirtuellRepository(
    private val holen: suspend (streamId: Long?, fensterH: Int) -> German4kVirtuellAntwort?,
    private val uhrJetzt: () -> Long = System::currentTimeMillis,
) {
    sealed interface Ergebnis {
        data class Ok(val antwort: German4kVirtuellAntwort, val uhr: VirtuellUhr) : Ergebnis
        data class Abgelehnt(val grund: String) : Ergebnis
        data object Transport : Ergebnis
    }

    private data class Eintrag(val ok: Ergebnis.Ok, val bis: Long)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val cache = HashMap<Pair<Long, Int>, Eintrag>()
    private val laufend = HashMap<Pair<Long, Int>, Deferred<Ergebnis>>()
    private val _plaene = MutableStateFlow<Map<String, List<VirtuellEintrag>>>(emptyMap())

    /** Letzter bekannter Plan je EPG-Kennung (klein geschrieben) — für Senderliste und TV-Guide. */
    val plaene: StateFlow<Map<String, List<VirtuellEintrag>>> = _plaene.asStateFlow()

    suspend fun plan(streamId: Long, fensterH: Int = 6): Ergebnis = laden(streamId, fensterH)

    suspend fun alle(fensterH: Int = 1): Ergebnis = laden(ALLE, fensterH)

    /** Ohne Netz: die jüngste noch gültige Antwort, die diesen Sender enthält. */
    fun gecacht(streamId: Long): Ergebnis.Ok? = synchronized(cache) {
        val t = uhrJetzt()
        cache.values.filter { it.bis > t && it.ok.antwort.sender.any { s -> s.streamId == streamId } }
            .maxByOrNull { it.bis }?.ok
    }

    fun invalidiere(streamId: Long) = synchronized(cache) {
        cache.keys.removeAll { it.first == streamId || it.first == ALLE }
    }

    private suspend fun laden(id: Long, fensterH: Int): Ergebnis {
        val key = id to fensterH
        val d = mutex.withLock {
            synchronized(cache) { cache[key]?.takeIf { it.bis > uhrJetzt() }?.let { return it.ok } }
            laufend[key] ?: scope.async { abfragen(key) }.also { laufend[key] = it }
        }
        return try {
            d.await()
        } finally {
            // Auch nach Abbruch (Zeitlimit beim Umschalten) aufräumen, sonst bliebe eine fertige Abfrage hängen.
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                mutex.withLock { if (laufend[key] === d) laufend.remove(key) }
            }
        }
    }

    private suspend fun abfragen(key: Pair<Long, Int>): Ergebnis {
        val a = holen(key.first.takeIf { it != ALLE }, key.second) ?: return Ergebnis.Transport
        val t = uhrJetzt()
        if (!a.ok) return Ergebnis.Abgelehnt(a.grund ?: "")
        val ok = Ergebnis.Ok(a, VirtuellUhr.aus(a.serverTime, t))
        synchronized(cache) { cache[key] = Eintrag(ok, t + TTL_MS) }
        if (a.sender.isNotEmpty()) {
            // Ein kürzeres Fenster (Startseite: 1 h) darf einen längeren Plan (TV-Guide: 6 h) nicht ersetzen —
            // sonst zeigt das Raster nach einem Blick auf die Startseite nur noch eine Stunde.
            val alt = _plaene.value
            _plaene.value = alt + a.sender.mapNotNull { s ->
                val key = s.epgId.lowercase()
                val bisher = alt[key]
                val neuEnde = s.plan.lastOrNull()?.ende
                val altEnde = bisher?.lastOrNull()?.ende
                if (bisher != null && altEnde != null && (neuEnde == null || neuEnde.isBefore(altEnde))) null else key to s.plan
            }
        }
        return ok
    }

    companion object {
        const val TTL_MS = 60_000L
        private const val ALLE = 0L
    }
}
