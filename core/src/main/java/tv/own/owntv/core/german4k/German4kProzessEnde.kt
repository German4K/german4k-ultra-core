package tv.own.owntv.core.german4k

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi

/**
 * German4K 3.2.1 (Diagnose): ANRs und native Abstürze (libmpv) sieht der [tv.own.owntv.core.util.CrashRecorder] nie —
 * der Prozess stirbt, bevor ein Java-Handler läuft. Ab Android 11 (API 30) hebt das System die Gründe auf
 * ([ActivityManager.getHistoricalProcessExitReasons]); beim Start lesen wir die neuen und melden sie über den
 * Diagnose-Weg als `anr` bzw. `crash_nativ`. Der Zeitstempel des zuletzt gemeldeten Endes liegt in den Prefs.
 */
internal object German4kProzessEnde {

    /** Ein Prozessende, wie es zum Melden gebraucht wird (rein, damit [neue] testbar ist). */
    data class Ende(val zeitMs: Long, val typ: String, val text: String)

    /** Die noch nicht gemeldeten Enden, älteste zuerst, höchstens [MAX]. Ohne Merker nur die letzten [ERSTLAUF_MS]. */
    fun neue(enden: List<Ende>, seitMs: Long?, jetztMs: Long): List<Ende> {
        val grenze = seitMs ?: (jetztMs - ERSTLAUF_MS)
        return enden.filter { it.zeitMs > grenze }.sortedBy { it.zeitMs }.takeLast(MAX)
    }

    fun lesen(context: Context): List<Ende> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return emptyList()
        return runCatching { lesenAb30(context) }.getOrDefault(emptyList())
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun lesenAb30(context: Context): List<Ende> {
        val am = context.getSystemService(ActivityManager::class.java) ?: return emptyList()
        return am.getHistoricalProcessExitReasons(null, 0, 16).mapNotNull { info ->
            val typ = when (info.reason) {
                ApplicationExitInfo.REASON_ANR -> German4kDiagnose.TYP_ANR
                ApplicationExitInfo.REASON_CRASH_NATIVE -> German4kDiagnose.TYP_CRASH_NATIV
                else -> return@mapNotNull null
            }
            Ende(info.timestamp, typ, beschreibe(context, info, typ))
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun beschreibe(context: Context, info: ApplicationExitInfo, typ: String): String = buildString {
        append(typ).append(" @ ").append(java.time.Instant.ofEpochMilli(info.timestamp)).append('\n')
        append("prozess=").append(info.processName).append(" wichtigkeit=").append(info.importance)
        append(" pss=").append(info.pss).append("kB rss=").append(info.rss).append("kB\n")
        info.description?.let { append(it).append('\n') }
        // ANR: Text mit den Stacks aller Threads — genau das, was wir für A1 & Co. brauchen. Gekappt.
        // Native: Tombstone ist binär (Protobuf), daher nur die Beschreibung.
        // German4K 3.3: Spur der letzten Wiedergabe (CrashRecorder) — Engine, Sender, Fehler kurz vor dem Ende.
        if (typ == German4kDiagnose.TYP_CRASH_NATIV) {
            tv.own.owntv.core.util.CrashRecorder.spurVor(context, info.timestamp)?.let {
                append("--- Wiedergabe kurz vor dem Ende ---\n").append(it).append('\n')
            }
        }
        if (typ == German4kDiagnose.TYP_ANR) {
            runCatching {
                info.traceInputStream?.use { s ->
                    val bytes = s.readNBytesKompat(TRACE_BYTES)
                    append('\n').append(String(bytes, Charsets.UTF_8))
                }
            }
        }
    }

    private fun java.io.InputStream.readNBytesKompat(max: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (out.size() < max) {
            val n = read(buf, 0, minOf(buf.size, max - out.size()))
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    const val PREFS = "german4k_prozessende"
    const val KEY_SEIT = "gemeldet_bis"
    private const val MAX = 3
    private const val ERSTLAUF_MS = 3L * 24 * 60 * 60 * 1000
    private const val TRACE_BYTES = 24 * 1024
}
