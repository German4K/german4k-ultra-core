package tv.own.owntv.core.recording

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.core.database.entity.RecordingEntity

/** German4K 3.2.1 (q-3, G): Aufnahme-Dienst nur, wenn wirklich etwas aufzunehmen ist. */
class RecordingStartTest {
    private fun row(url: String) = RecordingEntity(
        profileId = 1, sourceId = 1, channelId = 1, channelName = "K", streamUrl = url, title = "T",
        programmeStartMs = 0, programmeStopMs = 1, startMs = 0, stopMs = 1,
    )

    @Test fun senderwechsel_ohne_aufnahme_startet_keinen_dienst() {
        assertFalse(RecordingStart.noetig(laeuft = false, faellig = emptyList()))
    }

    @Test fun aufnehmen_was_ich_schaue_braucht_keinen_dienst() {
        assertFalse(RecordingStart.noetig(laeuft = false, faellig = listOf(row(""))))
    }

    @Test fun faellige_aufnahme_mit_eigener_leitung_startet_dienst() {
        assertTrue(RecordingStart.noetig(laeuft = false, faellig = listOf(row("http://x/1.ts"))))
    }

    @Test fun laufende_aufnahme_haelt_dienst() {
        assertTrue(RecordingStart.noetig(laeuft = true, faellig = emptyList()))
    }
}
