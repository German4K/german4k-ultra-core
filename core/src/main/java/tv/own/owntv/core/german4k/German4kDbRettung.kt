package tv.own.owntv.core.german4k

import android.content.Context
import android.database.sqlite.SQLiteDatabaseCorruptException
import android.util.Log
import org.koin.core.context.GlobalContext
import tv.own.owntv.core.database.OwnTVDatabase

/**
 * German4K: Rettung bei beschädigter Datenbank (Formuler: SQLiteDatabaseCorruptException mitten in
 * einer Abfrage des Katalog-Workers).
 *
 * Warum hier und nicht im Room-Builder: SQLites eigener Fehlerhandler löscht die Datei bei erkannter
 * Beschädigung meist schon selbst — aber nur, wenn die Beschädigung über seinen Weg gemeldet wird.
 * Der Worker ist die Stelle, an der sie im Feld tatsächlich aufschlug. Dort wird die Datenbank
 * geschlossen und die Datei gelöscht (doppelt schadet nicht), damit der nächste Start sauber
 * beginnt: leere Datenbank → German4kProvisioner legt Profil und Zugang aus dem Panel neu an und
 * importiert. Ohne das käme dieselbe kaputte Datei bei jedem Start wieder.
 *
 * Bewusst nur bei Beschädigung, nie bei Migrationsfehlern — dafür gibt es den
 * DatabaseRecoveryScreen mit ausdrücklicher Zustimmung des Kunden.
 */
object German4kDbRettung {
    private const val TAG = "German4kDbRettung"

    /** True, wenn [t] oder eine seiner Ursachen eine SQLite-Beschädigung meldet. */
    fun istKorrupt(t: Throwable): Boolean =
        generateSequence(t) { it.cause }.take(8).any { it is SQLiteDatabaseCorruptException }

    /** Datenbank schließen und Datei samt WAL/SHM löschen. Danach ist Room in diesem Prozess unbrauchbar. */
    fun verwerfen(context: Context, grund: Throwable) {
        Log.e(TAG, "database corrupt — deleting it, next start re-imports from the panel", grund)
        runCatching { GlobalContext.get().get<OwnTVDatabase>().close() }
            .onFailure { Log.w(TAG, "close failed: ${it.message}") }
        runCatching { context.deleteDatabase(OwnTVDatabase.NAME) }
            .onFailure { Log.w(TAG, "delete failed: ${it.message}") }
    }
}
