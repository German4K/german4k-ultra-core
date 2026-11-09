package tv.own.owntv.core.util

import android.content.Context
import android.os.Build
import tv.own.owntv.core.CoreBuildInfo
import java.io.File
import kotlin.system.exitProcess

/**
 * Keeps the last crash on disk so it survives the process dying.
 *
 * The 4.2.3 Live TV crash took days to find because the only way to see the stack trace was
 * `adb logcat`, and the people hitting it were watching television, not holding a laptop. A crash
 * that leaves no trace behind is a crash that has to be guessed at.
 *
 * This writes the trace to the app's own files directory the moment the process goes down, and
 * [PlaybackErrorLog.export] picks it up on the next launch — so the whole handover is
 * *Settings → Playback errors → Export*, and a file in Downloads the owner can be sent.
 *
 * The trace from a release build is obfuscated: retrace it with the `mapping.txt` of the matching
 * release. Line numbers are kept (`-keepattributes SourceFile,LineNumberTable`), so a retraced
 * trace names the exact line.
 */
object CrashRecorder {

    private const val FILE_NAME = "last_crash.txt"

    /**
     * Extra text appended to a recorded crash, supplied by whoever has diagnostics worth keeping —
     * today the player's live diagnostics ring. Core cannot reach into the player, so the player
     * hands its snapshot in here instead; unset, a crash is simply recorded without the extra
     * section. Set it alongside [install], before anything can throw.
     */
    var diagnostics: (() -> String)? = null

    /** Install from `Application.onCreate`, before anything else can throw. */
    fun install(context: Context) {
        val app = context.applicationContext
        spurStarten(app)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            // Never let the recorder itself be the reason the process dies differently than it would
            // have: whatever happens here, the original handler still gets the original throwable.
            runCatching { write(app, thread, error) }
            if (previous != null) previous.uncaughtException(thread, error) else exitProcess(2)
        }
    }

    /** German4K: forget the recorded crash — called once it has reached us (German4kDiagnose). */
    fun clear(context: Context) {
        runCatching { file(context).delete() }
    }

    /** The recorded crash, or null when the app has never gone down on this device. */
    fun read(context: Context): String? =
        runCatching { file(context).takeIf { it.exists() }?.readText()?.takeIf { it.isNotBlank() } }.getOrNull()

    private fun write(context: Context, thread: Thread, error: Throwable) {
        val stamp = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
            .format(java.util.Date())
        val text = buildString {
            // German4K: der Bericht landet bei uns UND (über den Export) beim Kunden — hier steht
            // nie ein fremder Markenname.
            appendLine("German4K Ultra ${CoreBuildInfo.versionName} (${CoreBuildInfo.versionCode})")
            appendLine("${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("Crashed $stamp on thread \"${thread.name}\"")
            appendLine()
            appendLine(android.util.Log.getStackTraceString(error))
            val live = runCatching { diagnostics?.invoke() }.getOrNull().orEmpty()
            if (live.isNotBlank()) {
                appendLine("--- live diagnostics leading up to the crash ---")
                appendLine(live)
            }
        }
        val f = file(context)
        f.parentFile?.mkdirs()
        f.writeText(text)
    }

    private fun file(context: Context): File =
        File(File(context.filesDir, "diagnostics"), FILE_NAME)

    // ── German4K 3.3: Spur für native Abstürze ──────────────────────────────────────────────────────
    // libmpv & Co. reißen den Prozess ohne Java-Handler weg; 3.2.1 meldete 90 solche Enden in einer Woche,
    // jedes nur mit „crash" (API 30 hat keinen Tombstone). Alle [SPUR_MS] landet der Live-Diagnose-Puffer
    // deshalb in einer Datei — nur bei Änderung —, und German4kProzessEnde hängt sie an das Prozessende.
    private const val SPUR_DATEI = "spur.txt"
    private const val SPUR_MS = 15_000L
    private const val SPUR_ZEILEN = 40

    private fun spurDatei(context: Context): File = File(File(context.filesDir, "diagnostics"), SPUR_DATEI)

    private fun spurStarten(context: Context) {
        var zuletzt = 0
        runCatching {
            java.util.Timer("g4k-spur", true).schedule(object : java.util.TimerTask() {
                override fun run() {
                    runCatching {
                        val live = diagnostics?.invoke().orEmpty().lines().takeLast(SPUR_ZEILEN).joinToString("\n")
                        if (live.isBlank() || live.hashCode() == zuletzt) return
                        zuletzt = live.hashCode()
                        val f = spurDatei(context)
                        f.parentFile?.mkdirs()
                        f.writeText(live)
                    }
                }
            }, SPUR_MS, SPUR_MS)
        }
    }

    /** Die Spur, wenn sie in den [vorMs] vor [zeitMs] zuletzt geschrieben wurde — sonst gehört sie nicht zu diesem Ende. */
    fun spurVor(context: Context, zeitMs: Long, vorMs: Long = 120_000L): String? = runCatching {
        val f = spurDatei(context)
        f.takeIf { it.exists() && it.lastModified() in (zeitMs - vorMs)..(zeitMs + 5_000L) }?.readText()?.takeIf { it.isNotBlank() }
    }.getOrNull()
}
