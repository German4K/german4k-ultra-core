package tv.own.owntv.core.setup

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * German4K 3.2.1 (t-13): wer den Profil-Schlüssel `g4k_einrichten_profil` löschen darf. SourceImporter hängt an
 * Room/DataStore (ohne Mock-Bibliothek nicht als Unit-Test baubar) — daher Quelltext-Wächter auf die Funktionskörper.
 */
class G4kEinrichtenSchluesselTest {
    private val quelle: String = listOf(
        "src/main/java/tv/own/owntv/core/setup/SourceImporter.kt",
        "core/src/main/java/tv/own/owntv/core/setup/SourceImporter.kt",
    ).map(::File).first { it.exists() }.readText()

    private fun koerper(signatur: String): String {
        val start = quelle.indexOf(signatur)
        check(start >= 0) { signatur }
        var tiefe = 0
        var i = quelle.indexOf('{', start)
        val anfang = i
        while (true) {
            when (quelle[i]) { '{' -> tiefe++; '}' -> if (--tiefe == 0) return quelle.substring(anfang, i + 1) }
            i++
        }
    }

    private val loeschen = Regex("""setG4kEinrichtenProfil\(\s*-1L\s*\)""")

    @Test fun `reset (Erneut versuchen, Abbrechen des Imports) laesst den Schluessel stehen`() {
        assertFalse(loeschen.containsMatchIn(koerper("fun reset()")))
    }

    @Test fun `Einrichtung verlassen und abschliessen loeschen den Schluessel`() {
        assertTrue(loeschen.containsMatchIn(koerper("suspend fun einrichtungVerlassen()")))
        assertTrue(loeschen.containsMatchIn(koerper("suspend fun finish()")))
    }
}
