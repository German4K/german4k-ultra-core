#!/bin/bash
# German4K 3.2.1 (T3): FFmpeg-Tondecoder (ac3, eac3, dca, truehd, mlp) statisch in libffmpegJNI.so je ABI.
# Quellen: FFmpeg 7.1.1 + Media3 1.11.1 libraries/decoder_ffmpeg/src/main/jni/ffmpeg_jni.cc.
# German4K 3.2.1 (T7): zusätzlich aac-Decoder + ac3-Encoder und ac3_transcode_jni.cc (AAC-Mehrkanal -> AC3).
# German4K 3.2.1 (p-19): zusätzlich mp3-Decoder (FFmpeg „mp3“ dekodiert MPEG-1/2 Layer I/II/III; Media3 fragt für
#   audio/mpeg-L1/-L2 nach „mp3“). Das Erste HD liefert MP2 — Fire TV hat dafür keinen MediaCodec, Exo wich auf mpv aus.
# Ergebnis: out/<abi>/libffmpegJNI.so (keine zweite libavcodec.so; FFmpeg-Symbole nicht exportiert).
set -euo pipefail
HIER="$(cd "$(dirname "$0")" && pwd)"
NDK="$HOME/Library/Android/sdk/ndk/27.2.12479018"
TC="$NDK/toolchains/llvm/prebuilt/darwin-x86_64/bin"
API=26
FF="$HIER/ffmpeg-7.1.1"
JNI="$HIER/media-1.11.1/libraries/decoder_ffmpeg/src/main/jni/ffmpeg_jni.cc"
JNI_AC3="$HIER/ac3_transcode_jni.cc"
JOBS="$(sysctl -n hw.ncpu)"
ABIS=("${@:-arm64-v8a armeabi-v7a x86_64}")
ABIS=(${ABIS[@]})

COMMON="--target-os=android --enable-static --disable-shared --disable-doc --disable-programs
 --disable-everything --disable-avdevice --disable-avformat --disable-swscale --disable-avfilter
 --disable-symver --enable-swresample --disable-v4l2-m2m --disable-vulkan --disable-network
 --disable-autodetect --enable-pic
 --enable-decoder=ac3 --enable-decoder=eac3 --enable-decoder=dca --enable-decoder=truehd --enable-decoder=mlp
 --enable-decoder=aac --enable-encoder=ac3
 --enable-decoder=mp3"

for abi in "${ABIS[@]}"; do
  case "$abi" in
    arm64-v8a) arch=aarch64; cpu=armv8-a; triple=aarch64-linux-android; extra="" ;;
    armeabi-v7a) arch=arm; cpu=armv7-a; triple=armv7a-linux-androideabi; extra="-march=armv7-a -mfloat-abi=softfp" ;;
    x86_64) arch=x86_64; cpu=x86-64; triple=x86_64-linux-android; extra="" ;;
    *) echo "unbekannte ABI $abi"; exit 1 ;;
  esac
  echo "=== $abi ==="
  pushd "$FF" >/dev/null
  make distclean >/dev/null 2>&1 || true
  asm=""; [[ "$abi" == x86_64 ]] && asm="--disable-asm"
  ./configure --prefix="$HIER/install/$abi" --libdir="$HIER/install/$abi/lib" --incdir="$HIER/install/$abi/include" \
    --arch=$arch --cpu=$cpu --cross-prefix="$TC/${triple}${API}-" \
    --cc="$TC/${triple}${API}-clang" --cxx="$TC/${triple}${API}-clang++" \
    --nm="$TC/llvm-nm" --ar="$TC/llvm-ar" --ranlib="$TC/llvm-ranlib" --strip="$TC/llvm-strip" \
    --extra-cflags="-fvisibility=hidden -O2 $extra" $asm $COMMON > "$HIER/configure-$abi.log"
  make -j"$JOBS" > "$HIER/make-$abi.log" 2>&1
  make install-libs install-headers > /dev/null
  popd >/dev/null
  mkdir -p "$HIER/out/$abi"
  "$TC/${triple}${API}-clang++" -std=c++11 -shared -fPIC -O2 -fvisibility=hidden -fvisibility-inlines-hidden \
    -I"$HIER/install/$abi/include" "$JNI" "$JNI_AC3" \
    "$HIER/install/$abi/lib/libswresample.a" "$HIER/install/$abi/lib/libavcodec.a" "$HIER/install/$abi/lib/libavutil.a" \
    -llog -landroid -lm -static-libstdc++ \
    -Wl,--exclude-libs,ALL -Wl,-Bsymbolic -Wl,-z,max-page-size=16384 -Wl,--gc-sections -Wl,-soname,libffmpegJNI.so \
    -o "$HIER/out/$abi/libffmpegJNI.so"
  "$TC/llvm-strip" --strip-unneeded "$HIER/out/$abi/libffmpegJNI.so"
  ls -l "$HIER/out/$abi/libffmpegJNI.so"
done
