German4K 3.2.1 (T3/T7): Quellen für player-core/src/main/jniLibs/<abi>/libffmpegJNI.so.
Gebaut wird in ~/german4k-app-rebrand/ultra/ffmpeg-321/ (FFmpeg 7.1.1 + Media3 1.11.1 entpackt) mit bau.sh;
diese Kopien halten fest, womit die eingecheckten .so gebaut sind.
- bau.sh: Decoder ac3/eac3/dca/truehd/mlp (T3) + aac, Encoder ac3 (T7), mp3 = MPEG-Audio Layer I/II/III (p-19, MP2-Sender wie Das Erste HD), swresample, statisch, Symbole versteckt.
- ac3_transcode_jni.cc: T7, AAC -> 48 kHz -> AC3 640 kbit/s (tv.own.owntv.player.Ac3Jni).
