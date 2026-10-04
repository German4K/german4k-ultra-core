package tv.own.owntv.core.german4k

import tv.own.owntv.core.live.EpgNowNext
import tv.own.owntv.core.parser.XtEpgEntry
import java.time.Instant
import java.time.ZoneId

/** Versatz Gerät → Server. Alle Plan-Rechnungen laufen in Serverzeit, damit eine falsche Geräteuhr nichts verschiebt. */
class VirtuellUhr(val versatzMs: Long) {
    fun serverJetzt(deviceNow: Long = System.currentTimeMillis()): Long = deviceNow + versatzMs
    fun geraetMs(serverMs: Long): Long = serverMs - versatzMs

    companion object {
        fun aus(serverTime: Instant?, deviceNowBeiAntwort: Long): VirtuellUhr =
            VirtuellUhr(serverTime?.let { it.toEpochMilli() - deviceNowBeiAntwort } ?: 0L)
    }
}

/** Was jetzt auf einem virtuellen Sender läuft und wo. */
data class VirtuellStand(
    val sender: German4kVirtuellSender,
    val eintrag: VirtuellEintrag,
    /** Position im Titel (ms) zum Zeitpunkt der Berechnung. */
    val positionMs: Long,
    val naechster: VirtuellEintrag?,
    /** Restzeit des Titels (ms) zum Zeitpunkt der Berechnung. */
    val restMs: Long,
    val uhr: VirtuellUhr,
    /** Serverzeit, zu der der Plan geholt wurde (für den Tageswechsel). */
    val geholtServerMs: Long,
)

object VirtuellPlan {
    private val ZONE: ZoneId = ZoneId.of("Europe/Berlin")
    private const val NACHLADEN_MS = 30 * 60_000L

    /** Eintrag mit start ≤ t < ende. */
    fun eintragBei(plan: List<VirtuellEintrag>, serverMs: Long): VirtuellEintrag? =
        plan.firstOrNull { it.start.toEpochMilli() <= serverMs && serverMs < it.ende.toEpochMilli() }

    fun stand(
        sender: German4kVirtuellSender,
        uhr: VirtuellUhr,
        deviceNow: Long = System.currentTimeMillis(),
        geholtServerMs: Long = uhr.serverJetzt(deviceNow),
    ): VirtuellStand? {
        val t = uhr.serverJetzt(deviceNow)
        val plan = sender.plan.ifEmpty { listOfNotNull(sender.jetzt) }
        val e = eintragBei(plan, t) ?: return null
        val i = plan.indexOf(e)
        return VirtuellStand(
            sender = sender, eintrag = e,
            positionMs = t - e.start.toEpochMilli(),
            naechster = plan.getOrNull(i + 1),
            restMs = e.ende.toEpochMilli() - t,
            uhr = uhr, geholtServerMs = geholtServerMs,
        )
    }

    /**
     * Was nach [aktuell] kommt: läuft laut Uhr schon ein anderer Eintrag, der; sonst der erste, der nach dem
     * Ende von [aktuell] beginnt (die Datei war etwas kürzer als laut Plan).
     */
    fun naechsterNach(plan: List<VirtuellEintrag>, aktuell: VirtuellEintrag, serverMs: Long): VirtuellEintrag? {
        eintragBei(plan, serverMs)?.let { if (it != aktuell) return it }
        return plan.firstOrNull { !it.start.isBefore(aktuell.ende) }
    }

    /** Neu holen, wenn das Fenster in < 30 min ausläuft, seit dem Holen Mitternacht (Berlin) war, oder leer. */
    fun brauchtNeu(plan: List<VirtuellEintrag>, serverMs: Long, geholtServerMs: Long): Boolean {
        val letzt = plan.lastOrNull() ?: return true
        if (letzt.ende.toEpochMilli() - serverMs < NACHLADEN_MS) return true
        val tag = { ms: Long -> Instant.ofEpochMilli(ms).atZone(ZONE).toLocalDate() }
        return tag(serverMs) != tag(geholtServerMs)
    }

    /** „S3 E4 · noch 12 Min · danach: …" — Texte liefert der Aufrufer (Ressourcen). Rest aufgerundet. */
    fun untertitel(
        stand: VirtuellStand,
        serverMs: Long,
        kopf: (VirtuellEintrag) -> String,
        fmt: (kopf: String, restMin: Int, danach: String?) -> String,
    ): String {
        val rest = ((stand.eintrag.ende.toEpochMilli() - serverMs).coerceAtLeast(0) + 59_999) / 60_000
        return fmt(kopf(stand.eintrag), rest.toInt(), stand.naechster?.titel)
    }

    /**
     * Plan als TV-Guide-Zeilen in Gerätezeit, auf das Fenster [vonMs, bisMs) beschnitten. Die Kennungen sind
     * negativ, damit sie nie mit gespeicherten Zeilen zusammenfallen (Detaildialog liest dann nichts nach).
     */
    fun alsProgramme(
        plan: List<VirtuellEintrag>, epgId: String, sourceId: Long, uhr: VirtuellUhr, vonMs: Long, bisMs: Long,
    ): List<tv.own.owntv.core.database.entity.EpgProgrammeEntity> = plan.mapNotNull { e ->
        val start = uhr.geraetMs(e.start.toEpochMilli())
        val stop = uhr.geraetMs(e.ende.toEpochMilli())
        if (stop <= vonMs || start >= bisMs) return@mapNotNull null
        tv.own.owntv.core.database.entity.EpgProgrammeEntity(
            id = -((e.zielId xor e.start.toEpochMilli()) and Long.MAX_VALUE).coerceAtLeast(1),
            sourceId = sourceId, epgChannelId = epgId, startMs = start, stopMs = stop,
            title = e.titel, description = e.folgentitel,
        )
    }

    /** Plan als Jetzt/Danach für EPG-Karte und Senderliste, in Gerätezeit. */
    fun nowNext(stand: VirtuellStand): EpgNowNext {
        val plan = stand.sender.plan
        val i = plan.indexOf(stand.eintrag)
        fun xt(e: VirtuellEintrag) = XtEpgEntry(
            e.titel, e.folgentitel,
            stand.uhr.geraetMs(e.start.toEpochMilli()), stand.uhr.geraetMs(e.ende.toEpochMilli()),
        )
        val danach = if (i >= 0) plan.drop(i + 1) else emptyList()
        return EpgNowNext(
            now = xt(stand.eintrag),
            next = danach.firstOrNull()?.let(::xt),
            upcoming = danach.drop(1).map(::xt),
            previous = null,
        )
    }
}

/** Zählt Fehler in Folge; true, sobald [grenze] erreicht ist (dann zurück auf den normalen Sender). */
class VirtuellFehlerZaehler(private val grenze: Int = 3) {
    private var n = 0
    fun fehler(): Boolean = ++n >= grenze
    fun erfolg() { n = 0 }
}

/** Weg A oder B? Reine Entscheidung, damit der Live-View-Model nur noch ausführt. */
object VirtuellWeg {
    sealed interface Ausgang {
        data class Spielen(val stand: VirtuellStand) : Ausgang
        /** Server hat abgelehnt und einen Satz für den Kunden mitgegeben; gespielt wird normal. */
        data class Hinweis(val grund: String) : Ausgang
        data object WegA : Ausgang
    }

    fun darf(istVirtuell: Boolean, featureSichtbar: Boolean, gesperrtBis: Long, jetzt: Long): Boolean =
        istVirtuell && featureSichtbar && gesperrtBis <= jetzt

    fun ausgang(ergebnis: German4kVirtuellRepository.Ergebnis?, streamId: Long, deviceNow: Long): Ausgang = when (ergebnis) {
        null, German4kVirtuellRepository.Ergebnis.Transport -> Ausgang.WegA
        is German4kVirtuellRepository.Ergebnis.Abgelehnt ->
            if (ergebnis.grund.isBlank()) Ausgang.WegA else Ausgang.Hinweis(ergebnis.grund)
        is German4kVirtuellRepository.Ergebnis.Ok -> {
            val s = ergebnis.antwort.sender.firstOrNull { it.streamId == streamId }
            val stand = s?.let { VirtuellPlan.stand(it, ergebnis.uhr, deviceNow) }
            if (stand == null) Ausgang.WegA else Ausgang.Spielen(stand)
        }
    }
}
