package tv.own.owntv.core.german4k

import android.os.Build
import tv.own.owntv.core.CoreBuildInfo

/**
 * The name our app gives itself towards german4k.tv.
 *
 * Until Build 8 every stream we played was indistinguishable from any other Android player: the TV
 * server's session table knows VLC, TiviMate, Smarters and the IBO family by name, and everything
 * else lands under "Android". That made a simple support question unanswerable — "is this customer
 * watching through our app or still through the old one?" — and it made the adoption of Ultra
 * unmeasurable while we were asking people to switch to it.
 *
 * The version and the model are in there because they turn a session row into something usable:
 * "Build 3.8 on a Fire TV" explains an old bug without asking anyone.
 */
object German4kUserAgent {

    /** e.g. `German4K Ultra/1.3 (Build 8; AFTKA)`. */
    fun wert(): String = "German4K Ultra/${CoreBuildInfo.versionName} (Build ${CoreBuildInfo.versionCode}; ${Build.MODEL})"
}
