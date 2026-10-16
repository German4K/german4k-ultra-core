package tv.own.owntv.core.german4k

import android.content.Context
import android.util.Log
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import tv.own.owntv.core.CoreBuildInfo

/**
 * German4K 3.2: Woher die Datei `angesagt.json` kommt — die URL steht in der Antwort von
 * `/api/app/ultra` (Feld `angesagt_url`, nur wenn das Feature für dieses Gerät an ist). Fehlt das Feld,
 * bleibt das Regal aus. Wie [German4kFeatures] ein schlichter Halter, den der Provisioner füllt.
 */
object German4kAngesagtQuelle {
    @Volatile var url: String? = null
        private set

    /** true, sobald in diesem Prozess eine FRISCHE Panel-Antwort da war (nicht nur die aus dem Cache). */
    @Volatile var frisch: Boolean = false
        private set

    /** Zählt jede Änderung hoch — die Bildschirme holen die Datei dann neu (nach dem 6-h-Riegel). */
    val flow = MutableStateFlow(0)

    fun setze(neu: String?, frischeAntwort: Boolean) {
        url = neu?.trim()?.takeIf { it.startsWith("http") }
        if (frischeAntwort) frisch = true
        flow.value = flow.value + 1
    }
}

/**
 * Hält die zuletzt geholte `angesagt.json` und holt sie höchstens alle sechs Stunden neu (Plan §2.3:
 * die Rangliste ändert sich einmal am Tag; R2 cacht eine Stunde). Bewusst eine Datei im App-Speicher
 * statt Room: abgeleiteter Zustand, keine Migration, keine Sicherung — und Agent B baut gleichzeitig
 * am Schema.
 *
 * Fehlt das Netz, bleibt die letzte Datei stehen (höchstens drei Tage alt) — eine Rangliste von gestern
 * ist besser als ein leeres Regal. Fehlt die URL (Feature aus), wird die Datei gelöscht.
 */
class German4kAngesagtRepository(
    private val context: Context,
    private val http: OkHttpClient,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val _datei = MutableStateFlow<AngesagtDatei?>(null)
    val datei: StateFlow<AngesagtDatei?> = _datei.asStateFlow()

    private val cacheDatei: File get() = File(context.filesDir, CACHE_NAME)

    init {
        scope.launch { runCatching { ausCache()?.let { (_, _, d) -> if (_datei.value == null) _datei.value = d } } }
    }

    /** Holt die Datei, wenn sie älter als sechs Stunden ist oder die URL gewechselt hat. Wirft nie. */
    suspend fun auffrischen(force: Boolean = false) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val url = debugUrl() ?: German4kAngesagtQuelle.url
            val cache = runCatching { ausCache() }.getOrNull()
            if (url == null) {
                // Feature aus (oder noch keine Panel-Antwort): eine alte Datei nur verwerfen, wenn die
                // Panel-Antwort wirklich da war — sonst löschte ein Start ohne Netz das Regal.
                if (German4kAngesagtQuelle.frisch) { cacheDatei.delete(); _datei.value = null }
                else if (_datei.value == null) _datei.value = cache?.third?.takeIf { jung(cache.second, ALT_MAX_MS) }
                return@withLock
            }
            val jetzt = System.currentTimeMillis()
            if (!force && cache != null && cache.first == url && jetzt - cache.second < FRISCH_MS) {
                if (_datei.value == null) _datei.value = cache.third
                return@withLock
            }
            val neu = runCatching { holen(url) }.onFailure { Log.w(TAG, "angesagt: ${it.message}") }.getOrNull()
            if (neu != null) {
                val d = AngesagtDatei.parse(neu)
                if (d != null) {
                    _datei.value = d
                    runCatching { schreibeCache(url, jetzt, neu) }
                    return@withLock
                }
                Log.w(TAG, "angesagt: Datei unlesbar")
            }
            // Netz weg oder Datei kaputt: die letzte gute bleibt, solange sie nicht zu alt ist.
            _datei.value = cache?.takeIf { jung(it.second, ALT_MAX_MS) }?.third
        }
    }

    /**
     * Mess-Ereignis (Plan M1, Schnittstelle C1): `POST /api/app/ultra/ereignis`. Feuern und vergessen —
     * ein Fehler bleibt still, der Kunde hat gerade etwas angeklickt und soll nichts davon merken.
     */
    fun ereignis(aktion: String, detail: String) {
        scope.launch {
            runCatching {
                val body = JSONObject().apply {
                    put("app_device_id", German4kDeviceId.get(context))
                    put("aktion", aktion)
                    put("detail", detail)
                }.toString()
                val request = Request.Builder()
                    .url(ereignisEndpoint())
                    .header("User-Agent", "German4K-Ultra/${CoreBuildInfo.versionName}")
                    .header("Accept", "application/json")
                    .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
                    .build()
                http.newCall(request).execute().close()
            }.onFailure { Log.d(TAG, "ereignis: ${it.message}") }
        }
    }

    private fun holen(url: String): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "German4K-Ultra/${CoreBuildInfo.versionName}")
            .header("Accept", "application/json")
            .get()
            .build()
        http.newCall(request).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            return r.body.string()
        }
    }

    /** Cache = eine JSON-Hülle {url, zeit, body}; (url, zeit, Datei) oder null. */
    private fun ausCache(): Triple<String, Long, AngesagtDatei>? {
        val f = cacheDatei
        if (!f.exists()) return null
        val o = JSONObject(f.readText())
        val d = AngesagtDatei.parse(o.optString("body")) ?: return null
        return Triple(o.optString("url"), o.optLong("zeit"), d)
    }

    private fun schreibeCache(url: String, zeit: Long, body: String) {
        val tmp = File(context.filesDir, "$CACHE_NAME.tmp")
        tmp.writeText(JSONObject().put("url", url).put("zeit", zeit).put("body", body).toString())
        if (!tmp.renameTo(cacheDatei)) { cacheDatei.delete(); tmp.renameTo(cacheDatei) }
    }

    private fun jung(zeit: Long, maxMs: Long): Boolean = System.currentTimeMillis() - zeit < maxMs

    companion object {
        private const val TAG = "German4kAngesagt"
        private const val CACHE_NAME = "german4k_angesagt.json"
        const val FRISCH_MS = 6L * 60 * 60 * 1000
        private const val ALT_MAX_MS = 3L * 24 * 60 * 60 * 1000

        fun ereignisEndpoint(): String =
            (German4kPanelClient.debugBasis() ?: "https://german4k.com") + "/api/app/ultra/ereignis"

        /**
         * Nur Debug-Builds: `adb shell setprop debug.g4k.angesagt http://10.0.2.2:8765/angesagt.json`
         * lenkt die Datei auf einen lokalen Server um (Bildschirmfotos, Tests ohne Server-Teil).
         */
        fun debugUrl(): String? {
            if (!CoreBuildInfo.debug) return null
            val prop = runCatching {
                Class.forName("android.os.SystemProperties").getMethod("get", String::class.java)
                    .invoke(null, "debug.g4k.angesagt") as String
            }.getOrNull()
            return prop?.trim()?.takeIf { it.startsWith("http") }
        }
    }
}
