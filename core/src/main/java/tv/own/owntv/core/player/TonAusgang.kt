package tv.own.owntv.core.player

import android.media.AudioDeviceInfo
import android.media.AudioFormat
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * German4K 3.2.1 (T1): was der Tonausgang dieses Geräts gerade kann — gemessen von `AudioRoute`
 * (player-core: Media3 `AudioCapabilities` + `AudioManager.getDevices(OUTPUTS)`), hier nur als Daten,
 * damit Diagnose, Hilfe-Seite und Einstellungen (core/app) ihn lesen können, ohne player-core zu kennen.
 *
 * Reine Logik ([aus], [TonWahl]) ist ohne Android testbar; die Android-Konstanten unten sind
 * Compile-Zeit-Konstanten und werden eingesetzt.
 */
data class TonAusgang(
    val art: Art,
    /** Höchste PCM-Kanalzahl, die der Ausgang meldet (2 = Stereo, 6 = 5.1, 8 = 7.1). */
    val maxKanaele: Int,
    /** Bitstream-Formate, die unverändert durchgereicht werden können. */
    val kodierungen: Set<Kodierung>,
    /** Fire TV: Mehrkanal-PCM kommt dort oft nur als Stereo am Receiver an (Kunde 20.10.). */
    val fireTv: Boolean = false,
) {
    enum class Art { HDMI, HDMI_ARC, HDMI_EARC, BLUETOOTH, USB, KOPFHOERER, LAUTSPRECHER, UNBEKANNT }

    /** [mpv] = Name in mpvs `audio-spdif` (null = mpv reicht das nicht durch). */
    enum class Kodierung(val androidEncoding: Int, val mpv: String?) {
        AC3(AudioFormat.ENCODING_AC3, "ac3"),
        EAC3(AudioFormat.ENCODING_E_AC3, "eac3"),
        EAC3_JOC(AudioFormat.ENCODING_E_AC3_JOC, "eac3"),
        DTS(AudioFormat.ENCODING_DTS, "dts"),
        DTS_HD(AudioFormat.ENCODING_DTS_HD, "dts"),
        TRUEHD(AudioFormat.ENCODING_DOLBY_TRUEHD, null),
        AC4(AudioFormat.ENCODING_AC4, null),
    }

    val hdmi: Boolean get() = art == Art.HDMI || art == Art.HDMI_ARC || art == Art.HDMI_EARC

    /** Ein Receiver/TV am HDMI nimmt mindestens ein Dolby/DTS-Format als Bitstream an. */
    val bitstream: Boolean get() = hdmi && kodierungen.any { it.mpv != null }

    /** Mehr als zwei PCM-Kanäle gemeldet. */
    val mehrkanalPcm: Boolean get() = maxKanaele > 2

    /** mpv `audio-spdif`-Liste (z. B. "ac3,eac3,dts"), leer = kein Passthrough. */
    fun mpvSpdif(): String = if (!hdmi) "" else kodierungen.mapNotNull { it.mpv }.distinct().joinToString(",")

    /** Technische Kurzform für Diagnose/Ereignisse, z. B. "hdmi 8ch ac3,eac3". Nie Oberfläche. */
    fun technisch(): String =
        art.name.lowercase() + " " + maxKanaele + "ch " + kodierungen.joinToString(",") { it.name.lowercase() }.ifEmpty { "pcm" }

    /** Ein Ausgabegerät, wie `AudioDeviceInfo` es meldet (für Tests als Fake). */
    data class Geraet(val typ: Int, val kanaele: List<Int> = emptyList())

    companion object {
        /** Was gerade gemessen ist (null = noch nichts). Wird von `AudioRoute` geschrieben. */
        val aktuell = MutableStateFlow<TonAusgang?>(null)

        /** Zuletzt gewählter Weg (für Diagnose/Hilfe), geschrieben von `AudioOutputPolicy`. */
        val letzterWeg = MutableStateFlow<TonWeg?>(null)

        /**
         * German4K 3.2.1 (T7): zuletzt aktive Umwandlung im Player, technisch (z. B. "aac 5.1 → ac3 5.1"),
         * null = keine. Geschrieben vom AC3-Renderer (player-core), gelesen von Diagnose/Hilfe.
         */
        val letzteUmwandlung = MutableStateFlow<String?>(null)

        /** Android-Encodings, nach denen `AudioRoute` Media3 fragt. */
        val KANDIDATEN: List<Kodierung> = Kodierung.entries

        /**
         * Baut den Ausgang aus Geräteliste + Media3-Fähigkeiten.
         * Bluetooth/Lautsprecher/Kopfhörer ⇒ nie Bitstream, höchstens Stereo — auch wenn ein ebenfalls
         * gemeldetes HDMI mehr könnte: Android leitet Medienton dann dorthin, nicht an HDMI.
         */
        fun aus(
            geraete: List<Geraet>,
            capsMaxKanaele: Int,
            capsEncodings: Collection<Int>,
            fireTv: Boolean = false,
        ): TonAusgang {
            val art = artVon(geraete)
            val hdmiArt = art == Art.HDMI || art == Art.HDMI_ARC || art == Art.HDMI_EARC
            if (!hdmiArt) {
                return TonAusgang(art, maxKanaele = 2, kodierungen = emptySet(), fireTv = fireTv)
            }
            val kodierungen = Kodierung.entries.filter { it.androidEncoding in capsEncodings }.toSet()
            val geraetMax = geraete.filter { artVonTyp(it.typ) == art }.flatMap { it.kanaele }.maxOrNull() ?: 0
            val max = when {
                capsMaxKanaele > 0 -> capsMaxKanaele
                geraetMax > 0 -> geraetMax
                else -> 2
            }
            return TonAusgang(art, maxKanaele = max.coerceIn(1, 8), kodierungen = kodierungen, fireTv = fireTv)
        }

        internal fun artVon(geraete: List<Geraet>): Art {
            val arten = geraete.map { artVonTyp(it.typ) }.toSet()
            return listOf(
                Art.BLUETOOTH, Art.KOPFHOERER, Art.USB, Art.HDMI_EARC, Art.HDMI_ARC, Art.HDMI, Art.LAUTSPRECHER,
            ).firstOrNull { it in arten } ?: Art.UNBEKANNT
        }

        /** HDMI_EARC (29) ist erst ab API 31 benannt; der Wert ist eine Konstante und kommt älter nie vor. */
        internal fun artVonTyp(typ: Int): Art = when (typ) {
            AudioDeviceInfo.TYPE_HDMI -> Art.HDMI
            AudioDeviceInfo.TYPE_HDMI_ARC -> Art.HDMI_ARC
            TYPE_HDMI_EARC -> Art.HDMI_EARC
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, TYPE_BLE_HEADSET, TYPE_BLE_SPEAKER -> Art.BLUETOOTH
            AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_ACCESSORY -> Art.USB
            AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> Art.KOPFHOERER
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> Art.LAUTSPRECHER
            else -> Art.UNBEKANNT
        }

        private const val TYPE_HDMI_EARC = 29
        private const val TYPE_BLE_HEADSET = 26
        private const val TYPE_BLE_SPEAKER = 27
    }
}

/**
 * German4K 3.2.1 (T1/T2/T4): welcher Tonweg gilt. Eine Entscheidung für alle Engines (Exo-Sink und mpv).
 */
enum class TonWeg {
    /** 2.0-PCM, in der App heruntergemischt. */
    STEREO,
    /** 3.2-„Surround“: Exo nimmt die Gerätefähigkeiten wie gemeldet, mpv `auto-safe`, nie spdif. */
    ALT_GERAET,
    /** Mehrkanal-PCM ohne Bitstream. */
    MEHRKANAL_PCM,
    /** Dolby/DTS durchreichen, PCM mehrkanalig. */
    DURCHREICHEN,
    /** Dolby/DTS durchreichen, PCM (z. B. AAC 5.1) auf Stereo heruntermischen. */
    DURCHREICHEN_PCM_STEREO;

    val mehrkanal: Boolean get() = this != STEREO
    val durchreichen: Boolean get() = this == DURCHREICHEN || this == DURCHREICHEN_PCM_STEREO
}

/** German4K 3.2.1 (T2): Stufe + Schalter + Sperre + Ausgang → [TonWeg]. Reine Funktion (Unit-Tests). */
object TonWahl {
    fun weg(mode: SurroundMode, tonAuto: Boolean, gesperrt: Boolean, ausgang: TonAusgang?): TonWeg {
        if (gesperrt) return TonWeg.STEREO
        if (!tonAuto) {
            // Exakt 3.2: Automatisch = Stereo, Surround = Gerät entscheidet; Mehrkanal gab es nicht.
            return when (mode) {
                SurroundMode.AUTO, SurroundMode.STEREO -> TonWeg.STEREO
                SurroundMode.SURROUND, SurroundMode.MULTICHANNEL -> TonWeg.ALT_GERAET
            }
        }
        return when (mode) {
            SurroundMode.STEREO -> TonWeg.STEREO
            SurroundMode.MULTICHANNEL -> TonWeg.MEHRKANAL_PCM
            SurroundMode.SURROUND -> TonWeg.DURCHREICHEN
            SurroundMode.AUTO -> automatisch(ausgang)
        }
    }

    /**
     * Automatisch: Bitstream nur am HDMI, das ihn meldet; Mehrkanal-PCM nur, wenn HDMI > 2 Kanäle meldet
     * und es kein Fire TV ist (dort kommt AAC 5.1 als PCM oft nur stereo an oder der Ton fiel nach ein
     * paar Folgen aus — Dolby geht dort aber als Bitstream sauber durch). Nichts erkannt ⇒ Stereo.
     */
    private fun automatisch(a: TonAusgang?): TonWeg {
        if (a == null || !a.hdmi) return TonWeg.STEREO
        val pcmMehr = a.mehrkanalPcm && !a.fireTv
        return when {
            a.bitstream -> if (pcmMehr) TonWeg.DURCHREICHEN else TonWeg.DURCHREICHEN_PCM_STEREO
            pcmMehr -> TonWeg.MEHRKANAL_PCM
            else -> TonWeg.STEREO
        }
    }
}

/**
 * German4K 3.2.1 (T5): Stereo-Downmix mit Dialog in der Mitte, je Kanal-Layout (AudioTrack-Reihenfolge,
 * so wie Media3 PCM je Kanalzahl auslegt):
 *  3 = FL FR FC · 4 = FL FR BL BR · 5 = FL FR FC BL BR · 6 = 5.1 FL FR FC LFE BL BR
 *  7 = 6.1 FL FR FC LFE BL BR BC · 8 = 7.1 FL FR FC LFE BL BR SL SR
 * Center und Surround mit 0,707 (−3 dB) auf beide Seiten, LFE fällt weg (wie mpv/ITU), danach je
 * Ausgangskanal auf konstante Leistung normiert (Summe der Quadrate = 1), damit nichts lauter wird.
 */
object TonDownmix {
    const val MITTE = 0.70710677f

    private const val FL = 0
    private const val FR = 1
    private const val FC = 2
    private const val LFE = 3
    private const val BL = 4
    private const val BR = 5
    private const val BC = 6
    private const val SL = 7
    private const val SR = 8

    /** Kanalrollen je Kanalzahl (nur für Tests und Log). */
    fun layout(n: Int): IntArray? = when (n) {
        3 -> intArrayOf(FL, FR, FC)
        4 -> intArrayOf(FL, FR, BL, BR)
        5 -> intArrayOf(FL, FR, FC, BL, BR)
        6 -> intArrayOf(FL, FR, FC, LFE, BL, BR)
        7 -> intArrayOf(FL, FR, FC, LFE, BL, BR, BC)
        8 -> intArrayOf(FL, FR, FC, LFE, BL, BR, SL, SR)
        else -> null
    }

    private fun roh(rolle: Int): Pair<Float, Float> = when (rolle) {
        FL -> 1f to 0f
        FR -> 0f to 1f
        FC -> MITTE to MITTE
        LFE -> 0f to 0f
        BL, SL -> MITTE to 0f
        BR, SR -> 0f to MITTE
        BC -> MITTE * MITTE to MITTE * MITTE
        else -> 0f to 0f
    }

    /**
     * Koeffizienten zeilenweise je Eingangskanal: (links, rechts) — das Format von Media3s
     * `ChannelMixingMatrix(n, 2, …)`. null für Kanalzahlen ohne Layout (1, 2, > 8).
     */
    fun dialogMitte(n: Int): FloatArray? {
        val rollen = layout(n) ?: return null
        val roh = rollen.map { roh(it) }
        val l = kotlin.math.sqrt(roh.sumOf { (it.first * it.first).toDouble() }).toFloat()
        val r = kotlin.math.sqrt(roh.sumOf { (it.second * it.second).toDouble() }).toFloat()
        val out = FloatArray(n * 2)
        roh.forEachIndexed { i, (a, b) ->
            out[i * 2] = if (l > 0f) a / l else 0f
            out[i * 2 + 1] = if (r > 0f) b / r else 0f
        }
        return out
    }

    /** mpv: dieselbe Gewichtung für libswresample (`pan` fehlt im ausgelieferten libavfilter). */
    const val MPV_SWRESAMPLE = "center_mix_level=0.707,surround_mix_level=0.707,lfe_mix_level=0"
}
