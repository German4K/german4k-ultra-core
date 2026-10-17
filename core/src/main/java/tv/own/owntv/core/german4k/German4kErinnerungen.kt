package tv.own.owntv.core.german4k

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.koin.core.context.GlobalContext
import tv.own.owntv.core.CoreBuildInfo
import tv.own.owntv.core.R
import tv.own.owntv.core.content.AdultCategoryClassifier
import tv.own.owntv.core.database.dao.CategoryDao
import tv.own.owntv.core.database.dao.ChannelDao
import tv.own.owntv.core.database.dao.ErinnerungDao
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.core.database.entity.ErinnerungEntity
import tv.own.owntv.core.i18n.AppLocale
import tv.own.owntv.core.i18n.LocaleStore
import tv.own.owntv.core.settings.SettingsRepository
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * German4K 3.2 (N7): „⏰ Erinnern“ — merken, löschen, den Sender beim Auslösen bestimmen, Wecker stellen.
 *
 * Was im Bild erscheint, entscheidet die App-Schicht (sie fragt [offeneAktiv] alle paar Sekunden und
 * zeigt [German4kErinnern.naechsterHinweis]); ist die App zu, klingelt ein ungefährer Android-Wecker
 * ([German4kErinnerungsWecker]) und zeigt eine Benachrichtigung als Bestversuch. Bewusst **kein**
 * exakter Wecker: dafür müsste die App auf Android 12+ eine Sondererlaubnis erbitten, und ein paar
 * Minuten Spiel reichen für „gleich geht es los“.
 */
class German4kErinnerungen(
    private val context: Context,
    private val dao: ErinnerungDao,
    private val settings: SettingsRepository,
    private val channelDao: ChannelDao,
    private val categoryDao: CategoryDao,
    private val sport: German4kSportRepository,
    private val resolver: SportChannelResolver,
    private val panel: German4kPanelClient,
    private val wecker: German4kErinnerungsWecker,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @OptIn(ExperimentalCoroutinesApi::class)
    val liste: Flow<List<ErinnerungEntity>> = settings.activeProfileId.flatMapLatest { p ->
        if (p < 0) flowOf(emptyList()) else dao.beobachte(p)
    }

    /** Schlüssel der offenen Erinnerungen des aktiven Profils — für die ⏰-Markierung. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val schluessel: Flow<Set<String>> = settings.activeProfileId.flatMapLatest { p ->
        if (p < 0) flowOf(emptySet()) else dao.beobachteSchluessel(p).map { it.toSet() }
    }

    suspend fun offeneAktiv(): List<ErinnerungEntity> {
        val p = settings.activeProfileIdNow()
        return if (p < 0) emptyList() else dao.offeneFuer(p)
    }

    suspend fun getById(id: Long): ErinnerungEntity? = dao.getById(id)

    /**
     * Sendung merken oder wieder vergessen. true = jetzt gemerkt, false = gelöscht, null = geht nicht
     * (schon begonnen, kein Profil, oder Erwachsenes — das ist nie erinnerbar, auch nicht mit PIN).
     */
    suspend fun sendungUmschalten(channel: ChannelEntity, titel: String, startMs: Long, endeMs: Long?): Boolean? {
        if (!aktiv()) return null // German4K 3.2: Notschalter `erinnern` aus → keine neuen Wecker
        val p = settings.activeProfileIdNow()
        if (p < 0) return null
        val key = sendungSchluessel(channel, startMs)
        dao.finde(p, key)?.let { vorhanden -> loeschen(vorhanden.id); return false }
        if (!German4kErinnern.erinnerbar(startMs, clock())) return null
        if (istErwachsen(channel)) return null
        val e = ErinnerungEntity(
            profileId = p, art = ErinnerungEntity.ART_SENDUNG, schluessel = key, titel = titel.trim().ifEmpty { channel.name },
            zusatz = channel.name, startMs = startMs, endeMs = endeMs, channelId = channel.id, sourceId = channel.sourceId,
            remoteId = channel.remoteId, vorlaufMin = German4kKonfig.erinnernVorlaufSendungMin, createdAt = clock(),
        )
        return speichern(e, detail = "sendung:${channel.name}")
    }

    /** Spiel oder Konferenz merken/vergessen — hängt an der Spiel-Kennung, der Sender kommt erst beim Auslösen. */
    suspend fun spielUmschalten(spiel: German4kSpiel): Boolean? {
        if (!aktiv()) return null // German4K 3.2: Notschalter `erinnern`
        val p = settings.activeProfileIdNow()
        if (p < 0) return null
        val key = German4kErinnern.spielSchluessel(spiel.id)
        dao.finde(p, key)?.let { vorhanden -> loeschen(vorhanden.id); return false }
        if (spiel.beendet || !German4kErinnern.erinnerbar(spiel.start.toEpochMilli(), clock())) return null
        val e = ErinnerungEntity(
            profileId = p,
            art = if (spiel.istKonferenz) ErinnerungEntity.ART_KONFERENZ else ErinnerungEntity.ART_SPIEL,
            schluessel = key, titel = spiel.titel, zusatz = spiel.wettbewerb, startMs = spiel.start.toEpochMilli(),
            spielId = spiel.id, vorlaufMin = German4kKonfig.erinnernVorlaufSpielMin, createdAt = clock(),
        )
        return speichern(e, detail = "${e.art}:${spiel.id}")
    }

    private suspend fun speichern(e: ErinnerungEntity, detail: String): Boolean? {
        val id = dao.einfuegen(e)
        if (id <= 0) return null
        wecker.stellen(id, German4kErinnern.ausloeseZeit(e))
        German4kErinnern.erlaubnisGewuenscht.value = true
        ereignis("erinnerung_gesetzt", detail)
        return true
    }

    suspend fun loeschen(id: Long) {
        wecker.loeschen(id)
        dao.loeschen(id)
    }

    /** Hinweis beantwortet (Umschalten oder Später) — er kommt nicht wieder. */
    suspend fun erledigen(id: Long) {
        wecker.loeschen(id)
        dao.erledigen(id)
    }

    /**
     * Sport: frisch beim Sport-Hub fragen. Verlegt → Erinnerung wandert mit (Rückgabe = neuer Stand);
     * beendet → erledigt (null). Ohne Netz bleibt alles, wie es ist.
     */
    suspend fun sportNachziehen(e: ErinnerungEntity): ErinnerungEntity? {
        if (e.art == ErinnerungEntity.ART_SENDUNG || e.spielId == null) return e
        val antwort = sport.ladeJetzt() ?: sport.antwort.value ?: return e
        val spiel = antwort.spiele.firstOrNull { it.id == e.spielId } ?: return e
        if (spiel.beendet) { erledigen(e.id); return null }
        val neu = German4kErinnern.verlegt(e, spiel.start.toEpochMilli()) ?: return e
        dao.verschieben(e.id, neu)
        wecker.stellen(e.id, neu - e.vorlaufMin * 60_000L)
        return e.copy(startMs = neu, benachrichtigtAt = null)
    }

    /**
     * Der Sender JETZT — für „Umschalten“. Sendung: über Quelle + Anbieter-Kennung (überlebt einen
     * Neuabgleich), sonst die lokale Zeile. Sport: frisch über den Sport-Hub, eingeschaltete Sender in
     * der Reihenfolge der Platzwahl; nie eine gespeicherte streamId.
     */
    suspend fun kanalFuer(e: ErinnerungEntity): ChannelEntity? {
        if (e.art == ErinnerungEntity.ART_SENDUNG) {
            val sourceId = e.sourceId
            val remote = e.remoteId
            if (sourceId != null && !remote.isNullOrBlank()) {
                channelDao.findByRemoteIds(sourceId, listOf(remote)).firstOrNull()?.let { return it }
            }
            return e.channelId?.let { channelDao.getById(it) }
        }
        val antwort = sport.ladeJetzt() ?: sport.antwort.value ?: return null
        val spiel = antwort.spiele.firstOrNull { it.id == e.spielId } ?: return null
        for (s in sportKandidaten(spiel)) resolver.aufloesen(s.streamIds)?.let { return it }
        return null
    }

    /** Nach jedem Start: Wecker für alles Kommende neu stellen (sie überleben keinen Neustart), Vorbei still schließen. */
    fun weckerNachziehen() {
        scope.launch {
            runCatching {
                val jetzt = clock()
                val offene = dao.offene()
                German4kErinnern.vorbei(offene, jetzt).forEach { erledigen(it.id) }
                // Notschalter aus: nichts neu stellen, aber auch nichts löschen — geht er wieder an, gelten sie weiter.
                if (!aktiv()) return@runCatching
                offene.filter { German4kErinnern.zustand(it, jetzt) == German4kErinnern.Zustand.Wartet && it.benachrichtigtAt == null }
                    .forEach { wecker.stellen(it.id, German4kErinnern.ausloeseZeit(it)) }
                dao.aufraeumen(jetzt - German4kErinnern.AUFRAEUMEN_NACH_MS)
            }.onFailure { Log.w(TAG, "Wecker nachziehen: ${it.message}") }
        }
    }

    fun ereignis(aktion: String, detail: String?) {
        scope.launch {
            runCatching { panel.ereignis(German4kDeviceId.get(context), CoreBuildInfo.versionName, aktion, detail) }
        }
    }

    /** Für den Knopf: künftig und nicht erwachsen. */
    suspend fun erinnerbar(channel: ChannelEntity, startMs: Long): Boolean =
        aktiv() && German4kErinnern.erinnerbar(startMs, clock()) && !istErwachsen(channel)

    /**
     * Nur Debug-Builds (`--ez g4k_erinnerung_test true`): eine Erinnerung, die in [inMinuten] beginnt und
     * damit sofort fällig ist — für die Abnahme des Hinweisfensters am TV-Emulator (Fokus, Zurück = Später).
     */
    suspend fun debugEinfuegen(channel: ChannelEntity?, inMinuten: Int) {
        val p = settings.activeProfileIdNow()
        if (p < 0) return
        val start = clock() + inMinuten * 60_000L
        dao.einfuegen(
            ErinnerungEntity(
                profileId = p, art = ErinnerungEntity.ART_SENDUNG, schluessel = "debug:$start",
                titel = channel?.name ?: "Test", zusatz = channel?.name, startMs = start, endeMs = start + 30 * 60_000L,
                channelId = channel?.id, sourceId = channel?.sourceId, remoteId = channel?.remoteId,
                vorlaufMin = German4kKonfig.erinnernVorlaufSendungMin, createdAt = clock(),
            ),
        )
    }

    private suspend fun istErwachsen(channel: ChannelEntity): Boolean {
        val name = channel.categoryId?.let { runCatching { categoryDao.getById(it)?.name }.getOrNull() }
        return AdultCategoryClassifier.isAdult(name)
    }

    companion object {
        private const val TAG = "German4kErinnern"

        /** German4K 3.2: Notschalter `erinnern` (fehlt = an). Aus ⇒ keine Knöpfe, keine Wecker, kein Auslösen. */
        fun aktiv(): Boolean = German4kFeatures.an(German4kSchalter.ERINNERN)

        /** Anbieter-Kennung des Senders, ersatzweise die lokale id — siehe [German4kErinnern.sendungSchluessel]. */
        fun senderKennung(channel: ChannelEntity): String = channel.remoteId?.takeIf { it.isNotBlank() } ?: "id${channel.id}"

        fun sendungSchluessel(channel: ChannelEntity, startMs: Long): String =
            German4kErinnern.sendungSchluessel(channel.sourceId, senderKennung(channel), startMs)

        /** Beginnzeiten der gemerkten Sendungen dieses Senders — für das ⏰ im Programmführer. */
        fun gemerkteBeginne(channel: ChannelEntity, schluessel: Set<String>): Set<Long> {
            val vorne = German4kErinnern.sendungSchluessel(channel.sourceId, senderKennung(channel), 0L).removeSuffix("0")
            return schluessel.asSequence().filter { it.startsWith(vorne) }
                .mapNotNull { it.substring(vorne.length).toLongOrNull() }.toSet()
        }

        /** Platzwahl beim Auslösen: eingeschaltete Sender zuerst, dann unbekannte; ausgeschaltete nie. */
        fun sportKandidaten(spiel: German4kSpiel): List<German4kSportSender> =
            spiel.sender.filter { it.an == true } + spiel.sender.filter { it.an == null }
    }
}

/**
 * Ungefährer Android-Wecker je Erinnerung (`setAndAllowWhileIdle`, keine Exakt-Erlaubnis). Die Kennung
 * steckt in der Daten-URI, nicht in einem Extra — PendingIntents mit gleicher URI fielen sonst zusammen.
 */
class German4kErinnerungsWecker(private val context: Context) {
    private val alarms: AlarmManager? = context.getSystemService(AlarmManager::class.java)

    fun stellen(id: Long, atMs: Long) {
        val manager = alarms ?: return
        val pi = intent(id) ?: return
        runCatching { manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pi) }
            .onFailure { Log.w(TAG, "Wecker $id: ${it.message}") }
    }

    fun loeschen(id: Long) {
        val manager = alarms ?: return
        intent(id)?.let { runCatching { manager.cancel(it) } }
    }

    private fun intent(id: Long): PendingIntent? {
        val i = Intent(context, German4kErinnerungReceiver::class.java).apply {
            action = German4kErinnerungReceiver.ACTION
            data = android.net.Uri.parse("g4k://erinnerung/$id")
        }
        return runCatching {
            PendingIntent.getBroadcast(context, id.toInt(), i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }.getOrNull()
    }

    private companion object { const val TAG = "German4kWecker" }
}

/**
 * Der Wecker klingelt. Ist die App sichtbar, macht sie das selbst im Bild — dann nichts tun. Sonst eine
 * Benachrichtigung (Fire TV zeigt sie in der Mitteilungszentrale); Tippen öffnet die App, die dann den
 * Hinweis bzw. „Verpasst? Läuft seit … min“ zeigt.
 */
class German4kErinnerungReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val id = intent.data?.lastPathSegment?.toLongOrNull() ?: return
        if (German4kErinnern.vordergrund) return
        val pending = goAsync()
        val app = context.applicationContext
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val koin = GlobalContext.getOrNull() ?: return@launch
                val dao = koin.get<ErinnerungDao>()
                val e = dao.getById(id) ?: return@launch
                // German4K 3.2: kalter Prozess (nur der Wecker hat ihn geweckt) kennt die Schalter noch nicht.
                runCatching { koin.get<German4kProvisioner>().schalterAusCache() }
                if (!German4kErinnerungen.aktiv()) return@launch
                if (e.erledigt || e.benachrichtigtAt != null) return@launch
                if (German4kErinnern.zustand(e, System.currentTimeMillis()) == German4kErinnern.Zustand.Vorbei) return@launch
                val tag = runCatching { koin.get<LocaleStore>().currentTag.value }.getOrNull()
                German4kErinnerungMitteilung.zeigen(app, e, tag)
                dao.benachrichtigt(id, System.currentTimeMillis())
            } catch (t: Throwable) {
                Log.w("German4kWecker", "Erinnerung $id: ${t.message}")
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION = "tv.own.owntv.core.action.G4K_ERINNERUNG"
    }
}

object German4kErinnerungMitteilung {
    private const val KANAL = "g4k_erinnerungen"
    /** Extra, mit dem die App aus der Benachrichtigung startet. */
    const val EXTRA_ID = "g4k_erinnerung"

    private val UHR: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

    fun zeigen(context: Context, e: ErinnerungEntity, localeTag: String?) {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        val l = if (localeTag.isNullOrBlank()) context else AppLocale.wrap(context, localeTag)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(
                NotificationChannel(KANAL, l.getString(R.string.g4k_erinnern_kanal), NotificationManager.IMPORTANCE_HIGH),
            )
        }
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
            putExtra(EXTRA_ID, e.id)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        } ?: return
        val pi = PendingIntent.getActivity(context, e.id.toInt(), launch, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val uhr = UHR.format(Instant.ofEpochMilli(e.startMs).atZone(ZoneId.systemDefault()))
        val text = e.zusatz?.let { l.getString(R.string.g4k_erinnern_zeile, it, uhr) } ?: uhr
        val n = NotificationCompat.Builder(context, KANAL)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(l.getString(R.string.g4k_erinnern_gleich, e.titel))
            .setContentText(text)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(NOTIFICATION_BASIS + e.id.toInt(), n) }
    }

    private const val NOTIFICATION_BASIS = 7300
}
