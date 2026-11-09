package tv.own.owntv.core.tv

import android.content.Context
import android.content.pm.PackageManager
import androidx.tvprovider.media.tv.TvContractCompat

/**
 * German4K 3.2.1 (Q3): Die Ultra ist EIN APK für Fernseher und Handy. Auf dem Handy gibt es weder den
 * Leanback-Startbildschirm noch den TV-Provider `android.media.tv` — jeder Watch-Next-/Kanal-Aufruf dort
 * endete in „Failed to find provider info for android.media.tv“ + „persistWatchNext insert failed“
 * (Android-9-Abnahme: 28× bzw. 10× je Sitzung). Nur sprechen, wo beides da ist.
 */
object TvHomePlattform {
    @Volatile private var gemerkt: Boolean? = null

    /** Rein, für Tests: nur mit Leanback UND TV-Provider. */
    fun entscheide(leanback: Boolean, tvProvider: Boolean): Boolean = leanback && tvProvider

    /** Ob dieses Gerät Android-TV-Startbildschirm-Zeilen kann. Fragt ohne Provider-Zugriff (kein Log-Lärm). */
    fun da(context: Context): Boolean = gemerkt ?: run {
        val pm = context.packageManager
        val leanback = runCatching { pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK) }.getOrDefault(false)
        val provider = leanback && runCatching { pm.resolveContentProvider(TvContractCompat.AUTHORITY, 0) != null }.getOrDefault(false)
        entscheide(leanback, provider).also { gemerkt = it }
    }
}
