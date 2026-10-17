package tv.own.owntv.core.german4k

/**
 * Panel-driven updates: the German4K panel tells each device which build it should run (stable or
 * beta channel, see `/mac beta`), the provisioner hands it here, and [tv.own.owntv.core.update.UpdateManager]
 * offers it instead of asking GitHub. No panel answer yet → the GitHub release stays the fallback.
 */
object German4kUpdateSource {
    @Volatile var current: German4kUpdate? = null
        private set

    fun offer(update: German4kUpdate?) { current = update }

    /** The panel update when it is newer than the running build (by versionCode), else null. */
    fun newerThan(versionCode: Int): German4kUpdate? = current?.takeIf { it.versionCode > versionCode && it.url.isNotBlank() }
}

/** Feature switches from the panel: aus | entwicklung | beta | an. */
object German4kFeatures {
    @Volatile private var map: Map<String, String> = emptyMap()
    /** Dieselben Schalter als Flow — NavVisibility blendet damit Bereiche wie `sport` ein/aus. */
    val flow = kotlinx.coroutines.flow.MutableStateFlow<Map<String, String>>(emptyMap())
    fun set(features: Map<String, String>) { map = features; flow.value = features }
    fun state(name: String): String = map[name] ?: "aus"
    fun visible(name: String): Boolean = state(name) != "aus"
    /** Show the "still in development — view anyway?" hint before opening. */
    fun inDevelopment(name: String): Boolean = state(name) == "entwicklung"

    /**
     * German4K 3.2: Notschalter für Neuerungen, die schon im Build stecken. Anders als [visible] gilt hier:
     * Schlüssel fehlt ⇒ AN (das 3.2-Verhalten), nur exakt "aus" schaltet auf das alte Verhalten zurück.
     * Warum: ein Server, der den Schalter (noch) nicht kennt, darf die Neuerung nicht abschalten.
     */
    fun an(name: String): Boolean = map[name] != "aus"
}

/** German4K 3.2: Namen der Notschalter (siehe [German4kFeatures.an]). */
object German4kSchalter {
    const val TOP10 = "top10"
    const val ANGESAGT_HERO = "angesagt_hero"
    const val VEREINSLOGOS = "vereinslogos"
    const val WETTBEWERB_LOGO = "wettbewerb_logo"
    const val KONFERENZ_KARTE = "konferenz_karte"
    const val ERINNERN = "erinnern"
    const val LEITUNG_BELEGT = "leitung_belegt"
    const val ZAPP_LISTE = "zapp_liste"
    const val LAENDER_V2 = "laender_v2"
}
