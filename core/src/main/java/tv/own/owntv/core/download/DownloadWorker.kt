package tv.own.owntv.core.download

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import tv.own.owntv.core.i18n.LocaleStore
import tv.own.owntv.core.german4k.German4kFeatures
import tv.own.owntv.core.german4k.German4kSchalter
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Runs the download queue as a foreground service so transfers keep going once the user leaves
 * OwnTV — an app-scoped coroutine died with the process the moment Android reclaimed it (DL1).
 *
 * There is exactly one of these at a time (unique work, KEEP), and it lives until the queue is
 * empty, which is what preserves the one-at-a-time semantics downloads always had.
 */
class DownloadWorker(
    appContext: Context,
    params: WorkerParameters,
    private val engine: DownloadEngine,
    private val localeStore: LocaleStore,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        if (German4kFeatures.an(German4kSchalter.DOWNLOAD_HINWEIS_KURZ)) return mitKurzemHinweis()
        setForeground(DownloadNotifications.foregroundInfo(applicationContext, null, localeStore))
        var lastTick = 0L
        engine.drainQueue { progress ->
            // The transfer reports twice a second; the notification does not need that.
            val now = System.currentTimeMillis()
            if (now - lastTick > NOTIFICATION_INTERVAL_MS) {
                lastTick = now
                runCatching {
                    setForegroundAsync(DownloadNotifications.foregroundInfo(applicationContext, progress, localeStore))
                }
            }
        }
        return Result.success()
    }

    /**
     * German4K 3.2.1 (p-12/4, Schalter `download_hinweis_kurz`): Der Worker startet bei jedem App-Start (Warteschlange
     * prüfen) und ging sofort in den Vordergrund — auch mit leerer Warteschlange. Auf dem Handy blieb diese Meldung
     * („German4K Ultra", Endlos-Balken, wirkt wie ein Katalog-Import) stehen. Jetzt: die Schlange läuft erst ohne
     * Meldung; nur wenn sie nach [VORDERGRUND_NACH_MS] noch läuft, geht der Worker in den Vordergrund. Am Ende wird
     * die Meldung ausdrücklich entfernt, auch wenn ein später Fortschritts-Post nach dem Dienst-Ende ankäme.
     */
    private suspend fun mitKurzemHinweis(): Result = coroutineScope {
        val letzter = AtomicReference<DownloadProgress?>(null)
        val vorne = AtomicBoolean(false)
        val spaeter = launch {
            delay(VORDERGRUND_NACH_MS)
            vorne.set(runCatching { setForeground(DownloadNotifications.foregroundInfo(applicationContext, letzter.get(), localeStore)) }.isSuccess)
        }
        var lastTick = 0L
        var letzteMeldung: com.google.common.util.concurrent.ListenableFuture<Void>? = null
        // German4K 3.2.1 (q-4/11, Schalter `download_sofort_vorne`): nach 3 s war der Kunde oft schon aus der App —
        // ab Android 12 ist der Vordergrunddienst aus dem Hintergrund verboten (ForegroundServiceStartNotAllowedException,
        // von runCatching geschluckt), der Download lief ohne Dienst und endete nach ~10 min. Jetzt: beim Start eines
        // ECHTEN Downloads sofort in den Vordergrund; der leere Lauf (App-Start, Schlange leer) bleibt ohne Meldung (p-12).
        val sofort = German4kFeatures.an(German4kSchalter.DOWNLOAD_SOFORT_VORNE)
        val beimStart: suspend (String) -> Unit = start@{ titel ->
            if (!sofort || vorne.get()) return@start
            spaeter.cancel()
            val ok = runCatching {
                setForeground(DownloadNotifications.foregroundInfo(applicationContext, letzter.get() ?: DownloadProgress(titel, 0, 0), localeStore))
            }.onFailure { android.util.Log.w(TAG, "download: Vordergrund verweigert (${it.javaClass.simpleName})") }.isSuccess
            vorne.set(ok)
        }
        try {
            engine.drainQueue(onStart = beimStart) { progress ->
                letzter.set(progress)
                val now = System.currentTimeMillis()
                if (vorne.get() && now - lastTick > NOTIFICATION_INTERVAL_MS) {
                    lastTick = now
                    runCatching {
                        letzteMeldung = setForegroundAsync(DownloadNotifications.foregroundInfo(applicationContext, progress, localeStore))
                    }
                }
            }
        } finally {
            spaeter.cancel()
            vorne.set(false)
            runCatching { letzteMeldung?.get(1, TimeUnit.SECONDS) }
            DownloadNotifications.entfernen(applicationContext)
        }
        Result.success()
    }

    companion object {
        /** German4K 3.2.1 (p-12/4): erst nach so langer Laufzeit eine Benachrichtigung (kurze Läufe bleiben still). */
        const val VORDERGRUND_NACH_MS = 3_000L
        const val WORK_NAME = "owntv-downloads"
        private const val TAG = "DownloadWorker"
        private const val NOTIFICATION_INTERVAL_MS = 2_000L

        /**
         * Make sure the queue is being drained. KEEP, not REPLACE: if a worker is already running,
         * it will pick up the newly queued row itself when it finishes the current one.
         *
         * [wifiOnly] is the user's "download over Wi-Fi only" choice, enforced by WorkManager itself
         * rather than by a check here: an UNMETERED constraint also *stops* a running transfer the
         * moment the phone falls off Wi-Fi, which a check at start could never do.
         */
        fun kick(context: Context, wifiOnly: Boolean = false, replace: Boolean = false) {
            val request = OneTimeWorkRequestBuilder<DownloadWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(
                            if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED,
                        )
                        .build(),
                )
                .addTag(WORK_NAME)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME,
                if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
                request,
            )
        }
    }
}
