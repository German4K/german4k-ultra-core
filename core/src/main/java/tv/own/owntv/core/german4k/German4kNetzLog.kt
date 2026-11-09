package tv.own.owntv.core.german4k

import android.util.Log
import tv.own.owntv.core.CoreBuildInfo
import java.util.concurrent.ConcurrentHashMap

/**
 * German4K 3.2.1 (Q1): Debug-Nachweis, welche Hosts die App anspricht — jeder Host einmal je Prozess
 * (`adb logcat -s G4kNetz`). In Release-Builds stumm. So lässt sich ohne tcpdump prüfen, dass keine
 * fremden Dienste mehr gefragt werden.
 */
object German4kNetzLog {
    private val gesehen = ConcurrentHashMap.newKeySet<String>()

    fun host(host: String) {
        if (!CoreBuildInfo.debug) return
        if (gesehen.add(host)) Log.d("G4kNetz", "host=$host")
    }
}
