package tv.own.owntv.core.german4k

import androidx.compose.runtime.Immutable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import tv.own.owntv.core.customize.CustomizationStore
import tv.own.owntv.core.customize.CustomizeKeys
import tv.own.owntv.core.database.dao.CategoryDao
import tv.own.owntv.core.database.dao.MovieDao
import tv.own.owntv.core.database.dao.ProfileDao
import tv.own.owntv.core.database.dao.SeriesDao
import tv.own.owntv.core.database.dao.SourceDao
import tv.own.owntv.core.database.entity.MovieEntity
import tv.own.owntv.core.database.entity.SeriesEntity
import tv.own.owntv.core.model.MediaType
import tv.own.owntv.core.repository.activeSourceIds
import tv.own.owntv.core.settings.SettingsRepository

/** German4K 3.2: Ein Poster in Angesagt / Top 10 — fertig für die Oberfläche, die Fassung ist schon gewählt. */
@Immutable
data class German4kAngesagtKarte(
    val tmdb: Int,
    val serie: Boolean,
    /** Lokale id der Standard-Fassung (Film- bzw. Serienzeile). Ein Klick öffnet GENAU diese. */
    val itemId: Long,
    val remoteId: String,
    val sourceId: Long,
    /** Angezeigter Platz (1, 2, 3 … ohne Lücken), siehe [German4kAngesagtReader]. */
    val platz: Int,
    /** Rang laut Datei — geht ins Mess-Ereignis, damit „Platz 3 geklickt" auch serverseitig stimmt. */
    val rang: Int,
    val name: String,
    val jahr: Int?,
    val plakat: String?,
    val querbild: String?,
    val handlung: String?,
    val note: Double?,
    val badge: AngesagtBadge?,
    val fassungen: Int,
)

/** Alle Regale auf einmal; ein leeres Regal bleibt aus (weniger als [German4kAngesagt.MIN_EINTRAEGE]). */
@Immutable
data class German4kAngesagtRegale(
    /** Startseite: ein gemischtes Regal, die #1 ist der Hero. */
    val start: List<German4kAngesagtKarte> = emptyList(),
    val filmeTop: List<German4kAngesagtKarte> = emptyList(),
    val filmeAngesagt: List<German4kAngesagtKarte> = emptyList(),
    val serienTop: List<German4kAngesagtKarte> = emptyList(),
    val serienAngesagt: List<German4kAngesagtKarte> = emptyList(),
) {
    val leer: Boolean get() = start.isEmpty() && filmeTop.isEmpty() && filmeAngesagt.isEmpty() && serienTop.isEmpty() && serienAngesagt.isEmpty()
}

/**
 * Baut aus [German4kAngesagtRepository.datei] die Regale für EIN Profil: Abgleich über die `remoteId`
 * der Fassungen gegen den lokalen Katalog (aktive Quellen des Profils), Erwachsene nie, Kinderprofil nur
 * Kinder-Rubriken, ausgeblendete Rubriken des Profils nicht. Zwei kleine IN-Abfragen je Art statt eines
 * Namensvergleichs — der Katalog hat 70.000 Filme, die Datei ein paar Dutzend Einträge.
 */
class German4kAngesagtReader(
    private val repo: German4kAngesagtRepository,
    private val movieDao: MovieDao,
    private val seriesDao: SeriesDao,
    private val categoryDao: CategoryDao,
    private val profileDao: ProfileDao,
    private val sourceDao: SourceDao,
    private val settings: SettingsRepository,
    private val customize: CustomizationStore,
) {
    suspend fun regale(profileId: Long): German4kAngesagtRegale = withContext(Dispatchers.IO) {
        val datei = repo.datei.value ?: return@withContext German4kAngesagtRegale()
        if (profileId < 0) return@withContext German4kAngesagtRegale()
        val kinder = profileDao.getById(profileId)?.isKids == true
        val caps = German4kDeviceCaps.letzte()
        val filmQuellen = activeSourceIds(settings, sourceDao, profileId, MediaType.MOVIE)
        val serienQuellen = activeSourceIds(settings, sourceDao, profileId, MediaType.SERIES)

        val filmEintraege = (datei.angesagt + datei.topFilme).filter { it.art == AngesagtArt.FILM }
        val serienEintraege = (datei.angesagt + datei.topSerien).filter { it.art == AngesagtArt.SERIE }

        // Je Fassung alle Kennungen, unter denen sie lokal liegen kann (Hauptzeile, Regalplätze, alte Kennung).
        val filme = lokaleFilme(filmQuellen, filmEintraege.flatMap { e -> e.fassungen.flatMap(German4kAngesagt::kandidaten) }.distinct())
        val serien = lokaleSerien(serienQuellen, serienEintraege.flatMap { e -> e.fassungen.flatMap(German4kAngesagt::kandidaten) }.distinct())
        val filmVerborgen = verborgeneRubriken(profileId, filmQuellen, MediaType.MOVIE)
        val serienVerborgen = verborgeneRubriken(profileId, serienQuellen, MediaType.SERIES)
        val filmRubrik = rubrikNamen(filmQuellen, MediaType.MOVIE)
        val serienRubrik = rubrikNamen(serienQuellen, MediaType.SERIES)

        val filmLokal = filme.values.associate { m -> m.remoteId!! to AngesagtLokal(m.id, m.remoteId, m.name, m.categoryId?.let(filmRubrik::get)) }
        val serienLokal = serien.values.associate { s -> s.remoteId!! to AngesagtLokal(s.id, s.remoteId, s.name, s.categoryId?.let(serienRubrik::get)) }
        val filmAus: (AngesagtLokal) -> Boolean = { l -> filme[l.remoteId]?.categoryId?.let { it in filmVerborgen } == true }
        val serieAus: (AngesagtLokal) -> Boolean = { l -> serien[l.remoteId]?.categoryId?.let { it in serienVerborgen } == true }

        fun treffer(liste: List<AngesagtEintrag>): List<AngesagtTreffer> {
            val f = German4kAngesagt.abgleichen(liste.filter { it.art == AngesagtArt.FILM }, filmLokal, kinder, caps, filmAus)
            val s = German4kAngesagt.abgleichen(liste.filter { it.art == AngesagtArt.SERIE }, serienLokal, kinder, caps, serieAus)
            // Zurück in die Reihenfolge der Datei (gemischtes Regal = Rang über beide Arten).
            val nachSchluessel = (f + s).associateBy { it.eintrag.art to it.eintrag.tmdb }
            return liste.mapNotNull { nachSchluessel[it.art to it.tmdb] }.distinctBy { it.eintrag.art to it.eintrag.tmdb }
        }

        fun karten(t: List<AngesagtTreffer>): List<German4kAngesagtKarte> = t.mapIndexedNotNull { i, tr ->
            if (tr.eintrag.art == AngesagtArt.FILM) filme[tr.gewaehlt.remoteId]?.let { karte(tr, i + 1, it) }
            else serien[tr.gewaehlt.remoteId]?.let { karte(tr, i + 1, it) }
        }

        val start = treffer(datei.angesagt)
        val topF = treffer(datei.topFilme)
        val topS = treffer(datei.topSerien)
        val angF = German4kAngesagt.ohneTop(start.filter { it.eintrag.art == AngesagtArt.FILM }, topF)
        val angS = German4kAngesagt.ohneTop(start.filter { it.eintrag.art == AngesagtArt.SERIE }, topS)
        German4kAngesagtRegale(
            start = German4kAngesagt.regal(karten(start)),
            filmeTop = German4kAngesagt.regal(karten(topF)),
            filmeAngesagt = German4kAngesagt.regal(karten(angF)),
            serienTop = German4kAngesagt.regal(karten(topS)),
            serienAngesagt = German4kAngesagt.regal(karten(angS)),
        )
    }

    private fun karte(t: AngesagtTreffer, platz: Int, m: MovieEntity) = German4kAngesagtKarte(
        tmdb = t.eintrag.tmdb, serie = false, itemId = m.id, remoteId = m.remoteId.orEmpty(), sourceId = m.sourceId,
        platz = platz, rang = t.eintrag.rang, name = m.name, jahr = t.eintrag.jahr ?: m.year ?: m.parsedYear,
        plakat = m.posterUrl?.takeIf { it.isNotBlank() }, querbild = m.backdropUrl?.takeIf { it.isNotBlank() },
        handlung = m.plot?.takeIf { it.isNotBlank() }, note = m.rating?.takeIf { it > 0 }, badge = t.badge, fassungen = t.lokaleFassungen,
    )

    private fun karte(t: AngesagtTreffer, platz: Int, s: SeriesEntity) = German4kAngesagtKarte(
        tmdb = t.eintrag.tmdb, serie = true, itemId = s.id, remoteId = s.remoteId.orEmpty(), sourceId = s.sourceId,
        platz = platz, rang = t.eintrag.rang, name = s.name, jahr = t.eintrag.jahr ?: s.year ?: s.parsedYear,
        plakat = s.posterUrl?.takeIf { it.isNotBlank() }, querbild = s.backdropUrl?.takeIf { it.isNotBlank() },
        handlung = s.plot?.takeIf { it.isNotBlank() }, note = s.rating?.takeIf { it > 0 }, badge = t.badge, fassungen = t.lokaleFassungen,
    )

    /** remoteId → Zeile; bei mehreren Quellen gewinnt die erste aktive (Reihenfolge des Profils). */
    private suspend fun lokaleFilme(quellen: List<Long>, ids: List<String>): Map<String, MovieEntity> {
        if (quellen.isEmpty() || ids.isEmpty()) return emptyMap()
        val aus = LinkedHashMap<String, MovieEntity>()
        for (q in quellen) for (teil in ids.chunked(CHUNK)) {
            movieDao.findByRemoteIds(q, teil).forEach { m -> m.remoteId?.let { aus.putIfAbsent(it, m) } }
        }
        return aus
    }

    private suspend fun lokaleSerien(quellen: List<Long>, ids: List<String>): Map<String, SeriesEntity> {
        if (quellen.isEmpty() || ids.isEmpty()) return emptyMap()
        val aus = LinkedHashMap<String, SeriesEntity>()
        for (q in quellen) for (teil in ids.chunked(CHUNK)) {
            seriesDao.findSeriesByRemoteIds(q, teil).forEach { s -> s.remoteId?.let { aus.putIfAbsent(it, s) } }
        }
        return aus
    }

    private suspend fun rubrikNamen(quellen: List<Long>, typ: MediaType): Map<Long, String> =
        if (quellen.isEmpty()) emptyMap() else categoryDao.observe(quellen, typ).first().associate { it.id to it.name }

    private suspend fun verborgeneRubriken(profileId: Long, quellen: List<Long>, typ: MediaType): Set<Long> {
        if (quellen.isEmpty()) return emptySet()
        val versteckt = customize.observe(profileId, typ).first().hiddenCategories
        if (versteckt.isEmpty()) return emptySet()
        return categoryDao.observe(quellen, typ).first().filter { CustomizeKeys.category(it) in versteckt }.mapTo(HashSet()) { it.id }
    }

    private companion object {
        /** SQLite erlaubt 999 Platzhalter je Abfrage (API 28); mit Luft. */
        const val CHUNK = 500
    }
}
