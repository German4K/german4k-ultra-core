package tv.own.owntv.core.content

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * German4K 3.1.1: Suche zerlegt die Eingabe wie der FTS-Tokenizer. Die Ausdrücke sind gegen SQLite 3.19
 * (Android 9) geprüft — Standard- und erweiterte Abfragesyntax liefern mit den Klammern dasselbe.
 */
class SearchFtsQueryTest {
    private fun q(s: String) = SearchReader.ftsQuery(s)

    @Test fun `O C with dots becomes a phrase of its parts`() = assertEquals("\"o* c*\"", q("O.C."))
    @Test fun `ORF1 also matches the spaced form`() = assertEquals("(orf1* OR \"orf 1*\")", q("ORF1"))
    @Test fun `PRO7 also matches the spaced form`() = assertEquals("(pro7* OR \"pro 7*\")", q("PRO7"))
    @Test fun `SAT dot 1 is split like the index`() = assertEquals("\"sat* 1*\"", q("SAT.1"))
    @Test fun `O C with a further word`() = assertEquals("\"o* c*\" calif*", q("o.c. Calif"))
    @Test fun `Sky1 also matches the spaced form`() = assertEquals("(sky1* OR \"sky 1*\")", q("Sky1"))
    @Test fun `plain words stay prefix terms`() = assertEquals("harry* pot*", q("Harry  Pot"))
    @Test fun `mixed word in a longer query`() = assertEquals("(orf1* OR \"orf 1*\") hd*", q("orf1 hd"))
    @Test fun `operators typed by the user are lower-cased`() = assertEquals("tom* or* jerry*", q("Tom OR Jerry"))
    @Test fun `quotes and stars are stripped`() = assertEquals("a* b*", q("\"a*\" -b"))
    @Test fun `hyphenated word is a phrase`() = assertEquals("\"spider* man*\"", q("Spider-Man"))
    @Test fun `umlauts are letters`() = assertEquals("märchen*", q("Märchen"))
    @Test fun `symbols only gives null`() = assertNull(q(" .-!? "))
}
