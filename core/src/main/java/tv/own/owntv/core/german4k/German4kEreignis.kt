package tv.own.owntv.core.german4k

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.koin.core.context.GlobalContext
import tv.own.owntv.core.CoreBuildInfo

/**
 * German4K 3.2.1 (D1): EIN Weg für Zählpunkte an `app_aufrufe` (`POST /api/app/ultra/ereignis`), aus jeder
 * Schicht ohne eigene Abhängigkeit aufrufbar. Still: Fehler werden geschluckt, nie wartet eine Oberfläche.
 * Namen: [German4kEreignisse]; der Server verwirft unbekannte.
 */
object German4kEreignis {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun melde(aktion: String, detail: String? = null) {
        scope.launch {
            runCatching {
                val koin = GlobalContext.get()
                val context = koin.get<Context>()
                val panel = koin.get<German4kPanelClient>()
                panel.ereignis(German4kDeviceId.get(context), CoreBuildInfo.versionName, aktion, detail)
            }
        }
    }
}
