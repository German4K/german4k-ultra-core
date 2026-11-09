package tv.own.owntv.core.german4k

import android.annotation.SuppressLint
import android.content.Context
import android.provider.Settings
import android.util.Base64

/**
 * Stable device id for the German4K panel: `base64(ANDROID_ID)` — byte-for-byte what the German4K Pro
 * app sends, so the panel derives the same MAC-shaped key and both apps share one device row
 * (one pairing covers both). ANDROID_ID is per app-signing-key + user on API 26+, which is stable for
 * our release key across updates.
 */
object German4kDeviceId {
    @SuppressLint("HardwareIds")
    fun get(context: Context): String {
        val raw = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID).orEmpty().trim()
        if (raw.isEmpty()) return ""
        return Base64.encodeToString(raw.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
    }
}
