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
    // Zulieferer die IP dieses Anschlusses am ZWEITEN Sprung abweist. Von außen sieht das aus wie
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
            sprung(2, 302, "http://185.245.1.184/live/play/abc", "up4.german4k.tv"),
            sprung(3, 200, null, "185.245.1.184"),
        )
        assertEquals(Klasse.ALLES_GUT, entscheide(streamStatus = 200, streamOk = true, kette = kette))
    }

    @Test
    fun `511 ohne erreichbares Panel bleibt ein Zwangsportal-Verdacht, kein VPN-Filter`() {
        // Ist nicht einmal unser Panel erreichbar, sitzt das Problem vor uns — dann gilt der
        // Netz-Zweig, nicht der Zulieferer.
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
}
