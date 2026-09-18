package tv.own.owntv.core.german4k

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject
import tv.own.owntv.core.CoreBuildInfo
import tv.own.owntv.core.util.CrashRecorder

/**
 * Sends what went wrong to German4K, so nobody has to ask the customer for it.
 *
 * The problem this solves: the person hitting a crash is watching television, not holding a laptop
 * with `adb logcat` attached. OwnTV already writes the last crash to disk ([CrashRecorder]) and keeps
 * a ring of playback failures, but the only way out of the device was *Settings → Playback errors →
 * Export* and a file in the Download folder — unusable on a TV, and nobody exports a file they were
 * never told about. So the app hands it over by itself:
 *
 *  - **On every start** a recorded crash goes to the panel once, then the file is deleted.
 *  - **On request** (`/mac diagnose <MAC>` in the shop bot sets a flag in the panel answer) the error
 *    log, the host switches of this run and the device capabilities follow — no customer interaction.
 *  - **On a button press** ("Fehler an German4K senden" in the help screen) the same report goes out
 *    immediately, which is the sentence support can say instead of asking for photos.
 *
 * Nothing here blocks anything: every send is fire-and-forget on an IO scope, failures are logged and
 * forgotten. A diagnostics feature that can keep the app from starting would be worse than none.
 *
 * **Credentials never leave the device.** [bereinige] strips them from every report; the server strips
 * them a second time (lib/app-panel/diagnose.ts). Both sides do it because both sides can be the one
 * that forgets — a report is text we did not write by hand.
 */
object German4kDiagnose {

    /**
     * Where the playback report comes from. Core cannot reach player-core (no dependency, on purpose),
     * so the app hands the getter in at startup — the same arrangement [CrashRecorder.diagnostics] uses.
     */
    @Volatile
    var berichtGeber: (() -> String)? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var crashGesendet = false

    /**
     * Strip credentials from a report. Mirrors `bereinige()` in lib/app-panel/diagnose.ts — three
     * shapes appear in our URLs: query parameters, Xtream path segments (`/live/<user>/<pass>/…`)
     * and credentials in the authority (`http://user:pass@host`).
     */
    fun bereinige(text: String): String = text
        .replace(Regex("""((?:username|password|pass|user|token|key)=)[^&\s"'<>]*""", RegexOption.IGNORE_CASE)) { "${it.groupValues[1]}***" }
        .replace(Regex("""(/(?:live|movie|series|timeshift|streaming)/)[^/\s]+/[^/\s]+""", RegexOption.IGNORE_CASE)) { "${it.groupValues[1]}***/***" }
        .replace(Regex("""(https?://)[^/\s:@]+:[^/\s@]+@""", RegexOption.IGNORE_CASE)) { "${it.groupValues[1]}***:***@" }

    /**
     * Called by the provisioner after every panel answer. Sends a recorded crash (once per process)
     * and, when the panel asked for it, the full error log.
     */
    fun nachPanelAntwort(context: Context, panel: German4kPanelClient, deviceId: String, answer: German4kPanelAnswer) {
        sendeAbsturz(context, panel, deviceId)
        if (answer.diagnoseRequested) sende(context, panel, deviceId, TYP_MANUELL, protokoll(context))
    }

    /** The crash on disk, if any — sent once per process, then removed so it is not sent again. */
    private fun sendeAbsturz(context: Context, panel: German4kPanelClient, deviceId: String) {
        if (crashGesendet) return
        val crash = runCatching { CrashRecorder.read(context) }.getOrNull()?.takeIf { it.isNotBlank() } ?: return
        crashGesendet = true
        scope.launch {
            val ok = liefere(panel, deviceId, TYP_CRASH, crash, meta(context))
            // Only forget the crash once it is safely with us; otherwise a start without network
            // would silently throw away the one trace we have.
            if (ok) runCatching { CrashRecorder.clear(context) }
        }
    }

    /** "Fehler an German4K senden" / self-test result. [zusatz] is put above the standard report. */
    fun sendeJetzt(context: Context, panel: German4kPanelClient, deviceId: String, typ: String = TYP_MANUELL, zusatz: String = "") {
        val text = if (zusatz.isBlank()) protokoll(context) else zusatz + "\n\n" + protokoll(context)
        sende(context, panel, deviceId, typ, text)
    }

    private fun sende(context: Context, panel: German4kPanelClient, deviceId: String, typ: String, text: String) {
        if (text.isBlank()) return
        scope.launch { liefere(panel, deviceId, typ, text, meta(context)) }
    }

    private suspend fun liefere(panel: German4kPanelClient, deviceId: String, typ: String, text: String, meta: JSONObject): Boolean {
        val ok = runCatching {
            panel.sendDiagnose(deviceId, CoreBuildInfo.versionName, CoreBuildInfo.versionCode, typ, bereinige(text), meta)
        }.getOrElse {
            Log.w(TAG, "send failed: ${it.message}")
            false
        }
        Log.d(TAG, "$typ ${if (ok) "delivered" else "not delivered"} (${text.length} chars)")
        return ok
    }

    /** One playback failure, handed over by the app (core does not know player-core). */
    data class Wiedergabe(
        val zeitMs: Long,
        val art: String,
        val engine: String,
        val live: Boolean,
        val grund: String?,
        val spec: String?,
        val roh: String?,
    )

    /** Formats what the app handed over. Lives here so the report has one shape, wherever it starts. */
    fun wiedergabeText(eintraege: List<Wiedergabe>, live: String): String = buildString {
        val stempel = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
        eintraege.forEach { e ->
            appendLine("[${stempel.format(java.util.Date(e.zeitMs))}] ${e.art} · ${e.engine} · ${if (e.live) "live" else "vod"}")
            e.grund?.let { appendLine("  grund: $it") }
            e.spec?.let { appendLine("  spec : $it") }
            e.roh?.let { appendLine("  roh  : $it") }
        }
        if (live.isNotBlank()) { appendLine("--- live ---"); appendLine(live) }
    }

    /** The standard report: device, host switches of this run, playback errors. */
    fun protokoll(context: Context): String = buildString {
        appendLine(German4kDeviceCaps.get(context).summary())
        appendLine("German4K Ultra ${CoreBuildInfo.versionName} (${CoreBuildInfo.versionCode})")
        val wechsel = German4kHostFailover.recentEvents()
        if (wechsel.isNotEmpty()) {
            appendLine()
            appendLine("--- Host-Wechsel in dieser Sitzung ---")
            wechsel.forEach { appendLine(it) }
        }
        val bericht = runCatching { berichtGeber?.invoke() }.getOrNull().orEmpty()
        if (bericht.isNotBlank()) {
            appendLine()
            appendLine("--- Wiedergabe ---")
            appendLine(bericht)
        }
    }

    private fun meta(context: Context): JSONObject {
        val caps = German4kDeviceCaps.get(context)
        return JSONObject().apply {
            put("model", caps.model)
            put("api", caps.api)
            put("fire_tv", caps.fireTv)
            put("tv", caps.tv)
            put("hevc", caps.hevc)
            put("hevc4k", caps.hevc4k)
            put("hdr10", caps.hdr10)
            put("dolby_vision", caps.dolbyVision)
            put("sprache", java.util.Locale.getDefault().toLanguageTag())
        }
    }

    const val TYP_CRASH = "crash"
    const val TYP_MANUELL = "manuell"
    const val TYP_SELBSTTEST = "selbsttest"
    private const val TAG = "German4kDiagnose"
}
