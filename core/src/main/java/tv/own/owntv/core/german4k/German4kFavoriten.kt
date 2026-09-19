package tv.own.owntv.core.german4k

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import tv.own.owntv.core.CoreBuildInfo
import tv.own.owntv.core.backup.UserDataResolver
import tv.own.owntv.core.database.dao.ProfileDao
import tv.own.owntv.core.database.dao.SourceDao

/**
 * Favoriten über alle Geräte eines Zugangs.
 *
 * Zwei Nachrichten, die wir bisher nicht beantworten konnten, weil nie etwas gespeichert war:
 * „Am Fernseher habe ich alles eingerichtet, am Tablet ist nichts da" und „nach dem Neuaufsetzen
 * sind meine Favoriten weg". Der Stand hängt jetzt am Zugang, nicht am Gerät.
 *
 * **Nichts davon ist neu erfunden.** OwnTV kann Favoriten längst über Geräte hinweg abgleichen —
 * über Sicherungsdateien und über den Abgleich im Heimnetz, samt Löschmarken und der Regel „der
 * jüngere Eintrag gewinnt". Hier wird genau dieser Apparat benutzt und nur der Transportweg
 * ausgetauscht: statt einer Datei oder des lokalen Netzes unser Panel. Ein zweites Modell für
 * dasselbe wäre die Sorte Doppelung, die nach einem halben Jahr auseinanderläuft.
 *
 * Was übertragen wird, ist bewusst wenig:
 *  - nur Favoriten, keine Verlaufs- und Fortsetzungsdaten. Wer wo stehen geblieben ist, gehört
 *    niemandem außer ihm selbst, und über einen gemeinsamen Zugang wäre das eine Mitleserei.
 *  - Profile werden über den **Namen** zugeordnet, nicht über eine Kennung. Sonst landen die
 *    Favoriten des Kinderprofils im Profil der Eltern, sobald zwei Geräte ihre Profile in
 *    unterschiedlicher Reihenfolge angelegt haben.
 */
class German4kFavoriten(
    private val context: Context,
    private val panel: German4kPanelClient,
    private val resolver: UserDataResolver,
    private val profileDao: ProfileDao,
    private val sourceDao: SourceDao,
) {

    /**
     * Einmal hin und her: eigenen Stand hochladen, gemeinsamen Stand übernehmen.
     *
     * Läuft still. Klappt es nicht, bleibt schlicht alles, wie es ist — ein fehlgeschlagener
     * Abgleich darf niemandem die Favoriten wegnehmen.
     */
    suspend fun abgleichen() {
        val profile = runCatching { profileDao.getAllOnce() }.getOrNull().orEmpty()
        if (profile.isEmpty()) return
        val nameJeId = profile.associate { it.id to it.name }

        val quellen = runCatching { sourceDao.getAllOnce() }.getOrNull().orEmpty()
        if (quellen.isEmpty()) return
        val quellenName = quellen.associate { it.id to it.name }

        val eigene = buildMap<String, Pair<String, Long>> {
            runCatching { resolver.exportAll(setOf(KIND)) }.getOrNull()?.let { arr ->
                eintraege(arr, nameJeId, quellenName, geloescht = false).forEach { (k, v) -> put(k, v) }
            }
            // Löschmarken mitschicken, sonst kommt ein entfernter Favorit vom anderen Gerät zurück.
            runCatching { resolver.exportTombstones(setOf(KIND)) }.getOrNull()?.let { arr ->
                eintraege(arr, nameJeId, quellenName, geloescht = true).forEach { (k, v) ->
                    val da = get(k)
                    if (da == null || v.second >= da.second) put(k, v)
                }
            }
        }

        val geraet = German4kDeviceId.get(context)
        val gemeinsam = panel.sync(geraet, CoreBuildInfo.versionName, eigene)
        if (gemeinsam == null) { Log.i(TAG, "Abgleich: Panel nicht erreichbar") ; return }

        val idJeName = profile.associate { it.name.trim().lowercase() to it.id }

        val zusetzen = JSONArray()
        val zuloeschen = JSONArray()
        for ((schluessel, eintrag) in gemeinsam) {
            if (eigene[schluessel]?.let { it.first == eintrag.first && it.second == eintrag.second } == true) continue
            val (wertRoh, zeit) = eintrag
            val record = runCatching { JSONObject(wertRoh.removePrefix(GELOESCHT)) }.getOrNull() ?: continue
            val profilName = record.optString("profil").trim().lowercase()
            val pid = idJeName[profilName] ?: continue
            // Die Quelle wird über den Namen zugeordnet: die Kennung ist auf jedem Gerät eine andere.
            val quelle = quellen.firstOrNull { it.name == record.optString("quelle") } ?: quellen.first()
            record.remove("profil"); record.remove("quelle")
            record.put("p", pid).put("src", quelle.id).put("kind", KIND).put("at", zeit)
            if (wertRoh.startsWith(GELOESCHT)) zuloeschen.put(record) else zusetzen.put(record)
        }

        // Eine Zeile je Abgleich: Im Support ist „wie viele Favoriten hat das Gerät gesehen" die
        // erste Frage, und ohne sie bleibt nur Raten.
        Log.i(TAG, "Abgleich: ${eigene.size} eigene, ${gemeinsam.size} gemeinsam, ${zusetzen.length()} neu, ${zuloeschen.length()} entfernt")
        if (zuloeschen.length() > 0) runCatching { resolver.applyTombstones(zuloeschen) }
            .onFailure { Log.w(TAG, "Löschmarken nicht angewandt: ${it.message}") }
        if (zusetzen.length() > 0) runCatching { resolver.importAll(zusetzen) }
            .onFailure { Log.w(TAG, "Favoriten nicht übernommen: ${it.message}") }
    }

    /**
     * Aus den Datensätzen von OwnTV die flache Karte für das Panel bauen.
     *
     * Der Schlüssel muss eine Neuinstallation überleben, deshalb steht darin **nicht** die örtliche
     * Kennung eines Senders (die wird bei jedem Abgleich neu vergeben), sondern die Kennung beim
     * Anbieter, ersatzweise der Name.
     */
    private fun eintraege(
        arr: JSONArray,
        nameJeId: Map<Long, String>,
        quellenName: Map<Long, String>,
        geloescht: Boolean,
    ): List<Pair<String, Pair<String, Long>>> {
        val out = mutableListOf<Pair<String, Pair<String, Long>>>()
        for (i in 0 until arr.length()) {
            val e = arr.optJSONObject(i) ?: continue
            val typ = e.optString("t")
            if (typ.isBlank() || typ == "EPISODE") continue
            val profil = nameJeId[e.optLong("p", -1)] ?: continue
            val rid = e.optString("rid").takeIf { it.isNotBlank() }
            val name = e.optString("name")
            if (rid == null && name.isBlank()) continue
            val kennung = rid ?: "n:$name"
            val schluessel = "fav|${profil.trim().lowercase()}|$typ|$kennung".take(MAX_SCHLUESSEL)
            val record = JSONObject()
                .put("t", typ)
                .put("profil", profil)
                .put("quelle", quellenName[e.optLong("src", -1)] ?: "")
                .apply { if (rid != null) put("rid", rid) }
                .put("name", name)
            val wert = (if (geloescht) GELOESCHT else "") + record.toString()
            if (wert.length > MAX_WERT) continue
            out += schluessel to (wert to e.optLong("at", System.currentTimeMillis()))
        }
        return out
    }

    private companion object {
        const val TAG = "German4kFavoriten"
        const val KIND = "fav"
        /** Vorsatz am Wert: dieser Eintrag ist eine Löschung, keine Setzung. */
        const val GELOESCHT = "-"
        const val MAX_SCHLUESSEL = 160
        const val MAX_WERT = 400
    }
}
