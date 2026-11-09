package tv.own.owntv.core.german4k

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.core.database.entity.SourceEntity
import tv.own.owntv.core.model.SourceType

class German4kQuelleTest {
    private fun src(url: String, max: Int = 0) = SourceEntity(id = 1, name = "x", type = SourceType.XTREAM, url = url, maxConnections = max)

    @Test fun erkenntEigeneHosts() {
        assertTrue(German4kQuelle.ist(src("https://german4k.tv")))
        assertTrue(German4kQuelle.ist(src("http://tv.german4k.tv:80/")))
        assertTrue(German4kQuelle.ist(src("http://cf.german4k.xyz")))
    }

    @Test fun fremdeQuelleNicht() {
        assertFalse(German4kQuelle.ist(src("http://example.test")))
        assertFalse(German4kQuelle.ist(src("")))
        assertFalse(German4kQuelle.ist(null))
    }

    @Test fun einzelLeitungNurOhneZahl() {
        German4kFeatures.set(emptyMap())
        assertTrue(German4kQuelle.einzelLeitungFuerAufnahmen(src("https://german4k.tv")))
        assertFalse(German4kQuelle.einzelLeitungFuerAufnahmen(src("https://german4k.tv", max = 2)))
        German4kFeatures.set(mapOf(German4kSchalter.AUFNAHMEN to "aus"))
        assertFalse(German4kQuelle.einzelLeitungFuerAufnahmen(src("https://german4k.tv")))
        German4kFeatures.set(emptyMap())
    }
}
