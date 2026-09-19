package tv.own.owntv.core.german4k

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException

/** One Xtream source the German4K panel hands out (a "Kachel": same line on another host). */
data class German4kSource(
    val id: String,
    val name: String,
    val server: String,
    val username: String,
    val password: String,
    val isDefault: Boolean,
    /** Further hosts serving the same line (Multi-DNS stage 2); the app switches between them on failure. */
    val altServers: List<String> = emptyList(),
)

data class German4kUpdate(val channel: String, val versionName: String, val versionCode: Int, val url: String, val notes: String, val required: Boolean)

/**
 * Vier Wege, die am Fernseher sonst fremde Hilfe brauchen: verlängern, ein zweites Gerät einrichten,
 * jemanden werben, uns schreiben. Die App macht QR-Codes daraus — das Handy übernimmt den Rest.
 */
data class German4kKunde(
    val verlaengernUrl: String = "",
    val zweitgeraetUrl: String = "",
    /** Leer, solange das Gerät an keine Line gekoppelt ist. */
    val werbenUrl: String = "",
    /** WhatsApp mit vorbereitetem Text (Gerät, Gerätecode). */
    val kontaktUrl: String = "",
    /** Restlaufzeit in Tagen, oder null. */
    val tageOffen: Int? = null,
)

/**
 * Ein laufender Testzugang.
 *
 * Ein Testkunde sieht dasselbe Bild wie ein zahlender und merkt oft erst, dass es ein Test war,
 * wenn das Bild weg ist. Die Stundenzahl gehört deshalb dorthin, wo sonst das Ablaufdatum steht.
 */
data class German4kTest(val stundenOffen: Int, val kaufenUrl: String)

/** Ein Land oder Bereich der Senderliste, mit Senderzahl — niemand schaltet blind etwas ab. */
data class German4kBereich(val id: Int, val name: String, val sender: Int, val an: Boolean, val standard: Boolean)

data class German4kBereiche(val ok: Boolean, val grund: String?, val liste: List<German4kBereich>)

/** Answer of `POST https://german4k.com/api/app/ultra` — contract: lib/app-panel/ultra.ts in the website repo. */
data class German4kPanelAnswer(
    val mac: String,
    val deviceKey: String,
    val registered: Boolean,
    val expireDate: String,
    val locked: Boolean,
    val noteTitle: String,
    val noteContent: String,
    val appVersion: String,
    val apkLink: String,
    val einrichtenUrl: String,
    val loginHost: String,
    val sources: List<German4kSource>,
    /** Hint kind (verlaengern | abgelaufen | wartung | gesperrt | willkommen | login | info | ""), its stable id
     *  (so "Verstanden" can hide it for the day) and the QR target for renewing. */
    val noteTyp: String = "",
    val noteId: String = "",
    val verlaengernUrl: String = "",
    /** What the in-app updater should offer (panel-driven, per device channel), or null. */
    val update: German4kUpdate? = null,
    /** Feature switches: aus | entwicklung | beta | an. */
    val features: Map<String, String> = emptyMap(),
    /** Verlängern, Zweitgerät, Werben, Kontakt — siehe [German4kKunde]. */
    val kunde: German4kKunde = German4kKunde(),
    /** Läuft hinter diesem Gerät ein Test, oder null bei einem gewöhnlichen Zugang. */
    val test: German4kTest? = null,
    /** Rubriken, die wir als „für Erwachsene" führen — das Kinderprofil blendet sie aus. */
    val erwachsen: List<String> = emptyList(),
    /** The panel asks this device to upload its error log on the next start (`/mac diagnose <MAC>`). */
    val diagnoseRequested: Boolean = false,
    /** Server clock (UTC, ISO). A device clock far off this makes the guide look shifted. */
    val serverTime: String = "",
) {
    companion object {
        fun parse(json: String): German4kPanelAnswer {
            val o = JSONObject(json)
            val arr = o.optJSONArray("sources")
            val sources = buildList {
                if (arr != null) for (i in 0 until arr.length()) {
                    val s = arr.getJSONObject(i)
                    add(
                        German4kSource(
                            id = s.optString("id"),
                            name = s.optString("name"),
                            server = s.optString("server"),
                            username = s.optString("username"),
                            password = s.optString("password"),
                            isDefault = s.optBoolean("is_default", false),
                            altServers = s.optJSONArray("alt_servers")?.let { a -> List(a.length()) { a.optString(it) }.filter { it.isNotBlank() } } ?: emptyList(),
                        ),
                    )
                }
            }
            return German4kPanelAnswer(
                mac = o.optString("mac"),
                deviceKey = o.optString("device_key"),
                registered = o.optBoolean("mac_registered", false),
                expireDate = o.optString("expire_date"),
                locked = o.optInt("lock", 0) == 1,
                noteTitle = o.optString("note_title"),
                noteContent = o.optString("note_content"),
                appVersion = o.optString("app_version"),
                apkLink = o.optString("apk_link"),
                einrichtenUrl = o.optString("einrichten_url"),
                loginHost = o.optString("login_host"),
                sources = sources,
                noteTyp = o.optString("note_typ"),
                noteId = o.optString("note_id"),
                verlaengernUrl = o.optString("verlaengern_url"),
                update = o.optJSONObject("update")?.let { u ->
                    German4kUpdate(u.optString("channel"), u.optString("version_name"), u.optInt("version_code", 0), u.optString("url"), u.optString("notes"), u.optBoolean("required", false))
                },
                features = o.optJSONObject("features")?.let { f -> f.keys().asSequence().associateWith { k -> f.optString(k) } } ?: emptyMap(),
                kunde = o.optJSONObject("kunde")?.let { k ->
                    German4kKunde(
                        verlaengernUrl = k.optString("verlaengern_url"),
                        zweitgeraetUrl = k.optString("zweitgeraet_url"),
                        werbenUrl = k.optString("werben_url"),
                        kontaktUrl = k.optString("kontakt_url"),
                        tageOffen = if (k.isNull("tage_offen")) null else k.optInt("tage_offen"),
                    )
                } ?: German4kKunde(),
                test = o.optJSONObject("test")?.let { t -> German4kTest(t.optInt("stunden_offen", 0), t.optString("kaufen_url")) },
                erwachsen = o.optJSONArray("erwachsen")?.let { a -> List(a.length()) { a.optString(it) }.filter { it.isNotBlank() } } ?: emptyList(),
                diagnoseRequested = o.optBoolean("diagnose_requested", false),
                serverTime = o.optString("server_time"),
            )
        }
    }

    fun toJson(): String = JSONObject().apply {
        put("mac", mac); put("device_key", deviceKey); put("mac_registered", registered); put("expire_date", expireDate)
        put("lock", if (locked) 1 else 0); put("note_title", noteTitle); put("note_content", noteContent)
        put("app_version", appVersion); put("apk_link", apkLink); put("einrichten_url", einrichtenUrl); put("login_host", loginHost)
        put("note_typ", noteTyp); put("note_id", noteId); put("verlaengern_url", verlaengernUrl)
        update?.let { u -> put("update", JSONObject().apply { put("channel", u.channel); put("version_name", u.versionName); put("version_code", u.versionCode); put("url", u.url); put("notes", u.notes); put("required", u.required) }) }
        put("features", JSONObject().apply { features.forEach { (k, v) -> put(k, v) } })
        put("diagnose_requested", diagnoseRequested); put("server_time", serverTime)
        test?.let { t -> put("test", JSONObject().apply { put("stunden_offen", t.stundenOffen); put("kaufen_url", t.kaufenUrl) }) }
        put("erwachsen", org.json.JSONArray(erwachsen))
        put("kunde", JSONObject().apply {
            put("verlaengern_url", kunde.verlaengernUrl); put("zweitgeraet_url", kunde.zweitgeraetUrl)
            put("werben_url", kunde.werbenUrl); put("kontakt_url", kunde.kontaktUrl)
            if (kunde.tageOffen != null) put("tage_offen", kunde.tageOffen) else put("tage_offen", JSONObject.NULL)
        })
        put("sources", org.json.JSONArray().apply {
            sources.forEach { s ->
                put(JSONObject().apply {
                    put("id", s.id); put("name", s.name); put("server", s.server); put("username", s.username)
                    put("password", s.password); put("is_default", s.isDefault)
                    put("alt_servers", org.json.JSONArray(s.altServers))
                })
            }
        })
    }.toString()
}

/**
 * Talks to the German4K panel. Plain JSON over HTTPS; the panel always answers 200 with a valid body
 * (a hint instead of an error), so anything else is a transport failure and the caller falls back to
 * the last cached answer.
 */
class German4kPanelClient(private val client: OkHttpClient, private val endpoint: String = ENDPOINT) {

    suspend fun fetch(deviceId: String, appVersion: String, username: String? = null, password: String? = null): German4kPanelAnswer =
        withContext(Dispatchers.IO) {
            val body = JSONObject().apply {
                put("app_device_id", deviceId)
                put("version", appVersion)
                put("app", "ultra")
                if (!username.isNullOrBlank()) { put("username", username.trim()); put("password", password.orEmpty()) }
            }.toString()
            val request = Request.Builder()
                .url(endpoint)
                .header("User-Agent", "German4K-Ultra/$appVersion")
                .header("Accept", "application/json")
                .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("HTTP ${response.code} vom German4K-Panel")
                val text = response.body.string()
                Log.d(TAG, "panel answered: ${text.length} chars")
                German4kPanelAnswer.parse(text)
            }
        }

    /** Sends a diagnostics report (crash, playback errors, self-test). Fire and forget: never blocks the UI. */
    suspend fun sendDiagnose(deviceId: String, appVersion: String, versionCode: Int, typ: String, text: String, meta: JSONObject?): Boolean =
        withContext(Dispatchers.IO) {
            val body = JSONObject().apply {
                put("app_device_id", deviceId)
                put("version", appVersion)
                put("build", versionCode)
                put("typ", typ)
                put("text", text)
                if (meta != null) put("meta", meta)
            }.toString()
            val request = Request.Builder()
                .url("$endpoint/diagnose")
                .header("User-Agent", "German4K-Ultra/$appVersion")
                .header("Accept", "application/json")
                .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()
            runCatching {
                client.newCall(request).execute().use { it.isSuccessful }
            }.getOrElse {
                Log.w(TAG, "diagnose not delivered: ${it.message}")
                false
            }
        }

    /**
     * „Verbindung freigeben": tritt die Line dieses Geräts einmal durch (Soft-Reset im Panel,
     * kostet nichts). Antwort ist `{ok, grund?}` — `grund` ist bereits Kundentext.
     */
    suspend fun sendReset(deviceId: String, appVersion: String): Pair<Boolean, String?> =
        withContext(Dispatchers.IO) {
            val body = JSONObject().apply { put("app_device_id", deviceId); put("aktion", "kick") }.toString()
            val request = Request.Builder()
                .url("$endpoint/reset")
                .header("User-Agent", "German4K-Ultra/$appVersion")
                .header("Accept", "application/json")
                .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()
            runCatching {
                client.newCall(request).execute().use { r ->
                    val o = JSONObject(r.body.string())
                    Pair(o.optBoolean("ok", false), o.optString("grund").takeIf { it.isNotBlank() })
                }
            }.getOrElse {
                Log.w(TAG, "reset failed: ${it.message}")
                Pair(false, null)
            }
        }

    /**
     * Länder und Bereiche lesen (ohne [id]) oder einen schalten. Die Antwort trägt immer den neuen
     * Stand, damit die Oberfläche nie raten muss, was gerade gilt.
     */
    /**
     * Favoriten je Zugang abgleichen: schickt den eigenen Stand und bekommt den gemeinsamen zurück.
     *
     * `stand` ist eine flache Karte `Schlüssel -> { wert, zeit }`. Der Server versteht die Schlüssel
     * nicht, er führt nur je Schlüssel den jüngeren Eintrag zusammen — damit zwei Geräte sich nicht
     * gegenseitig überschreiben. Was die Werte bedeuten, weiß allein die App.
     */
    suspend fun sync(deviceId: String, appVersion: String, stand: Map<String, Pair<String, Long>>): Map<String, Pair<String, Long>>? =
        withContext(Dispatchers.IO) {
            val body = JSONObject().apply {
                put("app_device_id", deviceId)
                if (stand.isNotEmpty()) {
                    put(
                        "stand",
                        JSONObject().apply {
                            stand.forEach { (k, v) -> put(k, JSONObject().put("wert", v.first).put("zeit", v.second)) }
                        },
                    )
                }
            }.toString()
            val request = Request.Builder()
                .url("$endpoint/sync")
                .header("User-Agent", "German4K-Ultra/$appVersion")
                .header("Accept", "application/json")
                .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()
            runCatching {
                client.newCall(request).execute().use { r ->
                    val o = JSONObject(r.body.string())
                    if (!o.optBoolean("ok", false)) return@use null
                    val st = o.optJSONObject("stand") ?: return@use emptyMap<String, Pair<String, Long>>()
                    buildMap {
                        st.keys().forEach { k ->
                            val e = st.optJSONObject(k) ?: return@forEach
                            put(k, e.optString("wert") to e.optLong("zeit"))
                        }
                    }
                }
            }.getOrNull()
        }

    suspend fun bereiche(deviceId: String, appVersion: String, id: Int? = null, an: Boolean? = null): German4kBereiche =
        withContext(Dispatchers.IO) {
            val body = JSONObject().apply {
                put("app_device_id", deviceId)
                put("typ", "live")
                if (id != null && an != null) { put("id", id); put("an", an) }
            }.toString()
            val request = Request.Builder()
                .url("$endpoint/bereiche")
                .header("User-Agent", "German4K-Ultra/$appVersion")
                .header("Accept", "application/json")
                .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()
            runCatching {
                client.newCall(request).execute().use { r ->
                    val o = JSONObject(r.body.string())
                    val arr = o.optJSONArray("bereiche")
                    German4kBereiche(
                        ok = o.optBoolean("ok", false),
                        grund = o.optString("grund").takeIf { it.isNotBlank() },
                        liste = buildList {
                            if (arr != null) for (i in 0 until arr.length()) {
                                val b = arr.getJSONObject(i)
                                add(German4kBereich(b.optInt("id"), b.optString("name"), b.optInt("sender"), b.optBoolean("an"), b.optBoolean("standard")))
                            }
                        },
                    )
                }
            }.getOrElse {
                Log.w(TAG, "bereiche failed: ${it.message}")
                German4kBereiche(ok = false, grund = null, liste = emptyList())
            }
        }

    companion object {
        const val ENDPOINT = "https://german4k.com/api/app/ultra"
        private const val TAG = "German4kPanel"
    }
}
