# Keep rules that :player-core imposes on anything consuming it. Empty for now: the engine's
# keep rules still live in app/proguard-rules.pro. Declared by consumerProguardFiles so moving a
# rule here later is a one-line change rather than a build-config change.

# German4K 3.2.1 (T3): FFmpeg-Tondecoder (Media3 decoder_ffmpeg 1.11.1, Quellen kopiert nach
# src/main/java/androidx/media3/decoder/ffmpeg). libffmpegJNI.so registriert die nativen Methoden in
# JNI_OnLoad über Klassen- und Methodennamen — nichts davon darf umbenannt werden.
-keep,includedescriptorclasses class androidx.media3.decoder.ffmpeg.** {
    native <methods>;
}
-keep, includedescriptorclasses class androidx.media3.decoder.ffmpeg.FfmpegAudioDecoder {
  private java.nio.ByteBuffer growOutputBuffer(androidx.media3.decoder.SimpleDecoderOutputBuffer, int);
}
-keep class androidx.media3.decoder.ffmpeg.FfmpegLibrary { *; }
-keep class androidx.media3.decoder.ffmpeg.FfmpegAudioRenderer { <init>(...); }

# German4K 3.2.1 (T7): AC3-Umwandlung — libffmpegJNI.so exportiert Java_tv_own_owntv_player_Ac3Jni_*;
# Klassenname und native Methoden müssen so bleiben.
-keep class tv.own.owntv.player.Ac3Jni {
    native <methods>;
}
