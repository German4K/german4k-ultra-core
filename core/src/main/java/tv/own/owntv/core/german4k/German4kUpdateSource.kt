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
    fun set(features: Map<String, String>) { map = features }
    fun state(name: String): String = map[name] ?: "aus"
    fun visible(name: String): Boolean = state(name) != "aus"
    /** Show the "still in development — view anyway?" hint before opening. */
    fun inDevelopment(name: String): Boolean = state(name) == "entwicklung"
}
