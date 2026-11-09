package tv.own.owntv.player

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioCapabilities
import androidx.media3.exoplayer.audio.AudioCapabilitiesReceiver
import tv.own.owntv.core.player.TonAusgang

/**
 * German4K 3.2.1 (T1): misst, was der Tonausgang kann, und meldet Wechsel.
 *
 * - Fähigkeiten (Bitstream-Formate, PCM-Kanäle) von Media3 [AudioCapabilities]: wertet das HDMI-Plug-Intent,
 *   Amazons „Surround extern“-Einstellung und ab API 29/33 die Direktwiedergabe-Abfragen aus — alles mit
 *   SDK_INT-Weichen innerhalb von Media3, also API-28-tauglich.
 * - Art des Ausgangs (HDMI/ARC/eARC/Bluetooth/…) aus `AudioManager.getDevices(OUTPUTS)` (API 23).
 * - [AudioCapabilitiesReceiver] (HDMI-Plug + Gerätewechsel + Amazon-Einstellung) und ein eigener
 *   [AudioDeviceCallback] (Bluetooth an/aus ändert die Fähigkeiten nicht immer) stoßen neu an.
 *
 * Ergebnis landet in [TonAusgang.aktuell]. Ändert sich der Ausgang, wird die Stereo-Sitzungssperre
 * zurückgesetzt (neues Kabel/Gerät = neue Chance); die Engines bauen über [AudioOutputPolicy.tonLage] neu.
 * Läuft einmal je Prozess, nie abgemeldet (wie die Media3-Sinks selbst).
 */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
object AudioRoute {
    private const val TAG = "AudioRoute"

    @Volatile private var gestartet = false
    private var receiver: AudioCapabilitiesReceiver? = null

    /**
     * Realtek-SoC (z. B. TCL/Philips-TVs, manche Boxen): mpv-spdif friert dort die Uhr ein (der
     * Passthrough-AudioTrack liefert keinen Zeitstempel → Diashow mit ~2 fps, Kommentar in OwnTVPlayer).
     * Einmal gemessen; Aufruf nicht auf dem Hauptfaden (Codec-Liste).
     */
    val realtek: Boolean by lazy {
        val hw = listOf(android.os.Build.HARDWARE, android.os.Build.BOARD, android.os.Build.MANUFACTURER)
            .joinToString(" ").lowercase()
        if ("realtek" in hw || hw.contains(Regex("rtd[0-9]"))) return@lazy true
        runCatching {
            android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS).codecInfos
                .any { it.name.lowercase().contains("realtek") }
        }.getOrDefault(false)
    }

    /** Idempotent; darf von jedem Faden kommen (die Anmeldung läuft auf dem Hauptfaden). */
    fun start(context: Context) {
        if (gestartet) return
        val app = context.applicationContext ?: context
        val anmelden = Runnable { anmelden(app) }
        if (Looper.myLooper() == Looper.getMainLooper()) anmelden.run() else Handler(Looper.getMainLooper()).post(anmelden)
    }

    @Synchronized
    private fun anmelden(app: Context) {
        if (gestartet) return
        gestartet = true
        runCatching {
            val r = AudioCapabilitiesReceiver(app) { caps -> aktualisieren(app, caps, "caps") }
            receiver = r
            aktualisieren(app, r.register(), "start")
        }.onFailure {
            android.util.Log.w(TAG, "AudioCapabilitiesReceiver unavailable", it)
            runCatching { aktualisieren(app, AudioCapabilities.getCapabilities(app), "start-fallback") }
        }
        runCatching {
            val am = app.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return@runCatching
            am.registerAudioDeviceCallback(object : AudioDeviceCallback() {
                override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) = neu()
                override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) = neu()
                private fun neu() {
                    runCatching { aktualisieren(app, AudioCapabilities.getCapabilities(app), "device") }
                }
            }, Handler(Looper.getMainLooper()))
        }.onFailure { android.util.Log.w(TAG, "AudioDeviceCallback unavailable", it) }
    }

    private fun aktualisieren(app: Context, caps: AudioCapabilities, anlass: String) {
        val am = app.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val geraete = runCatching {
            am?.getDevices(AudioManager.GET_DEVICES_OUTPUTS).orEmpty().map { d ->
                TonAusgang.Geraet(d.type, d.channelCounts.toList())
            }
        }.getOrDefault(emptyList())
        val encodings = TonAusgang.KANDIDATEN.map { it.androidEncoding }.filter { caps.supportsEncoding(it) }
        val neu = TonAusgang.aus(geraete, caps.maxChannelCount, encodings, fireTv = fireTv(app))
        val alt = TonAusgang.aktuell.value
        if (neu == alt) return
        android.util.Log.i(TAG, "audio output ($anlass): ${neu.technisch()} (was ${alt?.technisch()}) devices=${geraete.map { it.typ }}")
        // Neuer Ausgang = neue Chance; beim allerersten Messen gibt es nichts zurückzusetzen.
        if (alt != null) AudioOutputPolicy.clearLatch()
        TonAusgang.aktuell.value = neu
    }

    private fun fireTv(app: Context): Boolean = runCatching {
        app.packageManager.hasSystemFeature("amazon.hardware.fire_tv") ||
            android.os.Build.MANUFACTURER.equals("Amazon", ignoreCase = true)
    }.getOrDefault(false)
}
