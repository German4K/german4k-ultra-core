package tv.own.owntv.core.setup

import tv.own.owntv.core.database.entity.ProfileEntity

/**
 * German4K 3.2.1 (t-12, Schalter `einrichten_zurueck`): wie ein schon angelegtes Assistenten-Profil (noch ohne
 * Wiedergabeliste) bei erneutem „Profil anlegen“ übernommen wird.
 *
 * - [gleicherLauf] (derselbe Importer, z. B. BACK von „Wiedergabeliste hinzufügen“ zum Profil): die neue Eingabe gilt
 *   ganz — der Nutzer hat das Profil gerade eben selbst neu ausgefüllt.
 * - sonst (unterbrochener Lauf nach App-Ende, Übernahme durch den Provisioner): Name und Avatar neu, aber ein
 *   vorhandenes Kinder-Kennzeichen und eine vorhandene PIN werden nie still entfernt.
 */
object G4kEinrichtenProfil {
    fun uebernehmen(
        alt: ProfileEntity,
        gleicherLauf: Boolean,
        name: String,
        avatarId: Int,
        isKids: Boolean,
        pinHash: String?,
    ): ProfileEntity =
        if (gleicherLauf) {
            alt.copy(name = name, avatarId = avatarId, isKids = isKids, pinHash = pinHash)
        } else {
            alt.copy(name = name, avatarId = avatarId, isKids = isKids || alt.isKids, pinHash = pinHash ?: alt.pinHash)
        }
}
