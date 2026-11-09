package tv.own.owntv.core.german4k

import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import tv.own.owntv.core.german4k.German4kHealth.Companion.NETZ_KEINS
import tv.own.owntv.core.german4k.German4kHealth.Companion.NETZ_WLAN
import tv.own.owntv.core.german4k.German4kHealth.HostStatus
import tv.own.owntv.core.german4k.German4kHealth.Sprung
import tv.own.owntv.core.german4k.German4kHealth.Klasse

/**
 * The verdict table. Every case here is a support conversation that happened: a busy line, a hotel
 * WiFi, a router filter, a La-Liga block during a match. If one of these rows is wrong, the app tells
 * a customer something untrue about their own network — worse than saying nothing.
 */
class German4kHealthTest {

    private val gut = HostStatus(ok = true, status = 200, auth = true, zustand = "Active")
    private val tot = HostStatus(ok = false, status = 0, auth = null, zustand = "")

    @Before
    fun setUp() {
        German4kHostFailover.clear()
    }

    private fun entscheide(
        netzart: String = NETZ_WLAN,
        internet: Boolean = true,
        portal: Boolean = false,
        panel: Boolean = true,
        hosts: List<HostStatus> = listOf(gut, gut),
        streamStatus: Int? = 200,
        streamOk: Boolean? = true,
        uhr: Long = 0,
        kette: List<Sprung> = emptyList(),
    ) = German4kHealth.entscheide(netzart, internet, portal, panel, hosts, streamStatus, streamOk, uhr, kette)

    @Test
    fun `alles erreichbar ist alles gut`() {
        assertEquals(Klasse.ALLES_GUT, entscheide())
    }

    @Test
    fun `kein Netz schlaegt alles andere`() {
        assertEquals(Klasse.KEIN_NETZ, entscheide(netzart = NETZ_KEINS, internet = false, panel = false, hosts = listOf(tot), streamOk = false, streamStatus = 0))
    }

    @Test
    fun `Zwangsportal wird vor allem anderen erkannt`() {
        // Hotel-WLAN: der Test kommt gar nicht erst raus, alles andere sieht deshalb tot aus.
        assertEquals(Klasse.ZWANGSPORTAL, entscheide(portal = true, panel = false, hosts = listOf(tot, tot), streamStatus = 0, streamOk = false))
    }

    @Test
    fun `Internet da, aber nichts von uns erreichbar ist eine Router-Sperre`() {
        assertEquals(Klasse.ROUTER_SPERRE, entscheide(panel = false, hosts = listOf(tot, tot), streamStatus = 0, streamOk = false))
    }

    @Test
    fun `gar nichts erreichbar ohne Portal bleibt kein Netz`() {
        assertEquals(Klasse.KEIN_NETZ, entscheide(internet = false, panel = false, hosts = listOf(tot, tot), streamStatus = 0, streamOk = false))
    }

    @Test
    fun `abgelehnter Zugang heisst abgelaufen`() {
        val abgelaufen = HostStatus(ok = false, status = 200, auth = false, zustand = "Expired")
        assertEquals(Klasse.ABGELAUFEN, entscheide(hosts = listOf(abgelaufen, abgelaufen), streamStatus = 403, streamOk = false))
        val verboten = HostStatus(ok = false, status = 403, auth = null, zustand = "")
        assertEquals(Klasse.ABGELAUFEN, entscheide(hosts = listOf(verboten), streamStatus = 403, streamOk = false))
    }

    @Test
    fun `458 am Stream heisst Leitung belegt`() {
        assertEquals(Klasse.LEITUNG_BELEGT, entscheide(streamStatus = 458, streamOk = false))
        assertEquals(Klasse.LEITUNG_BELEGT, entscheide(streamStatus = 460, streamOk = false))
    }

    @Test
    fun `456 heisst Land gesperrt`() {
        assertEquals(Klasse.LAND_GESPERRT, entscheide(streamStatus = 456, streamOk = false))
        assertEquals(Klasse.LAND_GESPERRT, entscheide(hosts = listOf(HostStatus(false, 456, null, ""), gut), streamStatus = 456, streamOk = false))
    }

    @Test
    fun `ein toter Host von zweien ist ein Host-Ausfall`() {
        assertEquals(Klasse.HOST_AUSFALL, entscheide(hosts = listOf(tot, gut), streamStatus = 200, streamOk = true))
    }

    @Test
    fun `alles unseres antwortet, nur der Stream nicht - Anbieter-Sperre`() {
        // Der Spanien-Fall: Panel und beide Hosts antworten, kein Sender spielt.
        assertEquals(Klasse.ANBIETER_SPERRE, entscheide(streamStatus = 0, streamOk = false))
    }

    @Test
    fun `falsche Uhr faellt erst auf, wenn sonst alles laeuft`() {
        assertEquals(Klasse.UHRZEIT, entscheide(uhr = 7200))
        assertEquals(Klasse.UHRZEIT, entscheide(uhr = -7200))
        // Kleine Abweichungen sind normal (NTP, Laufzeit) und dürfen nichts melden.
        assertEquals(Klasse.ALLES_GUT, entscheide(uhr = 120))
    }

    @Test
    fun `ohne Quelle reicht ein erreichbares Panel`() {
        assertEquals(Klasse.ALLES_GUT, entscheide(hosts = emptyList(), streamStatus = null, streamOk = null))
    }

    // --- Die Weiterleitungskette (19.09.2026) --------------------------------------------
    //
    // Der Fall, der uns Wochen gekostet hat: Anmeldung sagt ja, Bild kommt trotzdem nicht, weil der
    // Verteiler die IP dieses Anschlusses am ZWEITEN Sprung abweist. Von außen sieht das aus wie
    // eine Anbieter-Sperre — der Rat ist aber der umgekehrte.

    private fun sprung(nr: Int, status: Int, ziel: String? = null, host: String = "german4k.tv") =
        Sprung(nr, status, ziel, host)

    @Test
    fun `511 am zweiten Sprung ist ein Filter auf der Leitung des Kunden, keine Anbieter-Sperre`() {
        val kette = listOf(
            sprung(1, 302, "http://up4.german4k.tv/live/…", "german4k.tv"),
            sprung(2, 511, null, "up4.german4k.tv"),
        )
        assertEquals(Klasse.VPN_FILTER, entscheide(streamStatus = 511, streamOk = false, kette = kette))
    }

    @Test
    fun `511 gilt auch dann, wenn die Anmeldung tadellos ist — genau das ist das Tueckische`() {
        val kette = listOf(sprung(1, 302, "http://up4.german4k.tv/x"), sprung(2, 511, null, "up4.german4k.tv"))
        // Panel ok, beide Hosts ok, auth=1 — trotzdem kein Bild.
        assertEquals(Klasse.VPN_FILTER, entscheide(hosts = listOf(gut, gut), streamStatus = 511, streamOk = false, kette = kette))
    }

    @Test
    fun `ohne 511 in der Kette bleibt es bei der Anbieter-Sperre`() {
        val kette = listOf(sprung(1, 302, "http://up4.german4k.tv/x"), sprung(2, 0, null, "up4.german4k.tv"))
        assertEquals(Klasse.ANBIETER_SPERRE, entscheide(streamStatus = 0, streamOk = false, kette = kette))
    }

    @Test
    fun `eine Kette, die durchlaeuft, aendert nichts am guten Befund`() {
        val kette = listOf(
            sprung(1, 302, "http://up4.german4k.tv/x"),
            sprung(2, 302, "http://203.0.113.10/live/play/abc", "up4.german4k.tv"),
            sprung(3, 200, null, "203.0.113.10"),
        )
        assertEquals(Klasse.ALLES_GUT, entscheide(streamStatus = 200, streamOk = true, kette = kette))
    }

    @Test
    fun `511 ohne erreichbares Panel bleibt ein Zwangsportal-Verdacht, kein VPN-Filter`() {
        // Ist nicht einmal unser Panel erreichbar, sitzt das Problem vor uns — dann gilt der
        // Netz-Zweig, nicht der Verteiler.
        val kette = listOf(sprung(1, 511, null, "german4k.tv"))
        assertEquals(Klasse.ROUTER_SPERRE, entscheide(panel = false, hosts = listOf(tot), streamStatus = 511, streamOk = false, kette = kette))
    }

    @Test
    fun `Fehlertexte des Players werden richtig eingeordnet`() {
        assertEquals(Klasse.LEITUNG_BELEGT, German4kHealth.klassifiziere("Source error: response code: 458"))
        assertEquals(Klasse.LEITUNG_BELEGT, German4kHealth.klassifiziere("Stream failed: connection limit reached"))
        assertEquals(Klasse.ABGELAUFEN, German4kHealth.klassifiziere("HTTP 403 forbidden"))
        assertEquals(Klasse.LAND_GESPERRT, German4kHealth.klassifiziere("response code: 456"))
        assertEquals(Klasse.ZWANGSPORTAL, German4kHealth.klassifiziere("http error 511 network authentication required"))
        assertEquals(Klasse.HOST_AUSFALL, German4kHealth.klassifiziere("failed to connect to german4k.tv: timed out"))
        assertEquals(Klasse.UNBEKANNT, German4kHealth.klassifiziere("Decoder init failed: OMX.hevc"))
        assertEquals(Klasse.UNBEKANNT, German4kHealth.klassifiziere(null))
    }

    // German4K 3.2.1 (u-14): Codes aus konfig.leitung_codes zählen auch im Störungsdialog als belegt.
    @Test
    fun `konfigurierter Leitungs-Code wird als belegt eingeordnet`() {
        try {
            assertEquals(Klasse.UNBEKANNT, German4kStoerung.einordnen("HTTP 509")) // vorher: still verworfen
            German4kKonfig.set(German4kKonfigWerte(leitungCodes = setOf(458, 509)))
            assertEquals(Klasse.LEITUNG_BELEGT, German4kHealth.klassifiziere("HTTP 509"))
            assertEquals(Klasse.LEITUNG_BELEGT, German4kHealth.klassifiziere("HTTP 460")) // fest bleibt fest
            assertEquals(Klasse.LEITUNG_BELEGT, German4kStoerung.einordnen("HTTP 509"))
        } finally {
            German4kKonfig.set(null)
        }
    }

    // German4K 3.2.1 (p-16): Uhrvergleich nur mit frischer Serverzeit und eigenem Empfangszeitpunkt.
    @Test
    fun `alte Antwort mit alter Serverzeit ergibt keinen Uhr-Alarm`() {
        val jetzt = java.time.Instant.parse("2026-10-22T20:00:00Z").toEpochMilli()
        // Gespeicherte Antwort von vor 2537 s: wird nicht mehr herangezogen — ohne frische Antwort kein Urteil.
        val alt = java.time.Instant.ofEpochMilli(jetzt - 2_537_000).toString()
        assertEquals(2537L, (jetzt - java.time.Instant.parse(alt).toEpochMilli()) / 1000) // das war der Fehlalarm
        val ohneFrisch = German4kHealth.uhrAbweichungFrisch(null, jetzt)
        assertEquals(0L, ohneFrisch)
        assertEquals(Klasse.ALLES_GUT, entscheide(uhr = ohneFrisch))
        // Frische Antwort, sekundengleich empfangen: keine Abweichung, egal wie alt die gespeicherte ist.
        val frisch = German4kHealth.uhrAbweichungFrisch("2026-10-22T19:59:59.500Z", jetzt)
        assertEquals(0L, frisch)
        assertEquals(Klasse.ALLES_GUT, entscheide(uhr = frisch))
    }

    @Test
    fun `echte Abweichung ueber fuenf Minuten ergibt Uhr-Alarm`() {
        val jetzt = java.time.Instant.parse("2026-10-22T20:00:00Z").toEpochMilli()
        val vor = German4kHealth.uhrAbweichungFrisch("2026-10-22T19:54:00Z", jetzt)
        assertEquals(360L, vor)
        assertEquals(Klasse.UHRZEIT, entscheide(uhr = vor))
        val nach = German4kHealth.uhrAbweichungFrisch("2026-10-22T20:06:00Z", jetzt)
        assertEquals(-360L, nach)
        assertEquals(Klasse.UHRZEIT, entscheide(uhr = nach))
        assertEquals(0L, German4kHealth.uhrAbweichungFrisch("kaputt", jetzt))
    }
}
