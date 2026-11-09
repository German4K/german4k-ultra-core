// German4K 3.2.1 (T7): AAC-Mehrkanal -> AC3 (Dolby Digital) für Fire TV.
//
// Fire-TV-Sticks geben über HDMI kein Mehrkanal-PCM aus, reichen Dolby aber als Bitstream durch.
// Diese JNI-Hülle dekodiert AAC (FFmpeg "aac"), rechnet auf 48 kHz und ein AC3-Kanal-Layout um
// (libswresample) und kodiert mit FFmpeg "ac3" (640 kbit/s). Die Java-Seite (tv.own.owntv.player.Ac3Jni)
// bekommt fertige AC3-Frames (je 1536 Samples) samt pts in 1/48000 s.
//
// Wird in dieselbe libffmpegJNI.so gelinkt wie Media3s ffmpeg_jni.cc (FFmpeg statisch, Symbole versteckt).
// Bauskript: ~/german4k-app-rebrand/ultra/ffmpeg-321/bau.sh

#include <android/log.h>
#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

extern "C" {
#include <libavcodec/avcodec.h>
#include <libavutil/audio_fifo.h>
#include <libavutil/channel_layout.h>
#include <libavutil/error.h>
#include <libavutil/opt.h>
#include <libavutil/samplefmt.h>
#include <libswresample/swresample.h>
}

#define TAG "g4k_ac3"
#define LOGE(...) ((void)__android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__))
#define LOGI(...) ((void)__android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__))

namespace {

const int kOutRate = 48000;
const int kAc3Frame = 1536;

// Fehlercodes an Java (negativ). -1..-9 = fatal, kDecodeSkip = dieses Paket war kaputt.
const int kErrFatal = -1;
const int kErrEncoder = -2;
const int kErrOutFull = -3;
const int kDecodeSkip = -10;

struct Ctx {
  AVCodecContext* dec = nullptr;
  AVCodecContext* enc = nullptr;
  SwrContext* swr = nullptr;
  AVAudioFifo* fifo = nullptr;
  AVFrame* decFrame = nullptr;
  AVFrame* encFrame = nullptr;
  AVPacket* inPkt = nullptr;
  AVPacket* outPkt = nullptr;
  uint8_t* extradata = nullptr;
  int extradataSize = 0;
  int bitrate = 640000;
  AVChannelLayout outLayout = {};
  // Ist-Zustand des Resamplers (Eingang), um bei Formatwechsel neu aufzusetzen.
  AVChannelLayout swrInLayout = {};
  int swrInRate = 0;
  int swrInFmt = -1;
  // Zähler seit dem letzten Reset.
  int64_t decodedSamples = 0;  // dekodierte Samples (Quellrate), vor Trim
  int64_t nextPts = 0;         // pts (1/48000) des nächsten Encoder-Eingangsframes
  int lastInRate = 0;
  int trimRemaining = 0;       // Encoder-Delay der Quelle (nur beim allerersten Start)
  int64_t trimmed = 0;         // davon schon verworfen
  bool eof = false;
  uint8_t* padBuf = nullptr;
  int padBufSize = 0;
};

void layoutFor(int channels, AVChannelLayout* out) {
  if (channels >= 6) {
    *out = (AVChannelLayout)AV_CHANNEL_LAYOUT_5POINT1_BACK;
  } else if (channels == 5) {
    *out = (AVChannelLayout)AV_CHANNEL_LAYOUT_5POINT0_BACK;
  } else if (channels == 4) {
    *out = (AVChannelLayout)AV_CHANNEL_LAYOUT_4POINT0;
  } else if (channels == 3) {
    *out = (AVChannelLayout)AV_CHANNEL_LAYOUT_SURROUND;
  } else {
    *out = (AVChannelLayout)AV_CHANNEL_LAYOUT_STEREO;
  }
}

void logErr(const char* what, int err) {
  char buf[AV_ERROR_MAX_STRING_SIZE] = {0};
  av_strerror(err, buf, sizeof(buf));
  LOGE("%s: %s (%d)", what, buf, err);
}

int openDecoder(Ctx* c) {
  const AVCodec* codec = avcodec_find_decoder(AV_CODEC_ID_AAC);
  if (!codec) return kErrFatal;
  c->dec = avcodec_alloc_context3(codec);
  if (!c->dec) return kErrFatal;
  if (c->extradataSize > 0) {
    c->dec->extradata = (uint8_t*)av_mallocz(c->extradataSize + AV_INPUT_BUFFER_PADDING_SIZE);
    if (!c->dec->extradata) return kErrFatal;
    memcpy(c->dec->extradata, c->extradata, c->extradataSize);
    c->dec->extradata_size = c->extradataSize;
  }
  c->dec->request_sample_fmt = AV_SAMPLE_FMT_FLTP;
  int r = avcodec_open2(c->dec, codec, nullptr);
  if (r < 0) {
    logErr("aac open", r);
    return kErrFatal;
  }
  return 0;
}

int openEncoder(Ctx* c) {
  const AVCodec* codec = avcodec_find_encoder(AV_CODEC_ID_AC3);
  if (!codec) return kErrFatal;
  c->enc = avcodec_alloc_context3(codec);
  if (!c->enc) return kErrFatal;
  c->enc->sample_fmt = AV_SAMPLE_FMT_FLTP;
  c->enc->sample_rate = kOutRate;
  c->enc->bit_rate = c->bitrate;
  c->enc->time_base = AVRational{1, kOutRate};
  if (av_channel_layout_copy(&c->enc->ch_layout, &c->outLayout) < 0) return kErrFatal;
  int r = avcodec_open2(c->enc, codec, nullptr);
  if (r < 0) {
    logErr("ac3 open", r);
    return kErrFatal;
  }
  if (c->enc->frame_size != kAc3Frame) {
    LOGE("ac3 frame_size %d", c->enc->frame_size);
    return kErrFatal;
  }
  return 0;
}

void closeCodecs(Ctx* c) {
  if (c->dec) avcodec_free_context(&c->dec);
  if (c->enc) avcodec_free_context(&c->enc);
  if (c->swr) swr_free(&c->swr);
  av_channel_layout_uninit(&c->swrInLayout);
  c->swrInRate = 0;
  c->swrInFmt = -1;
  if (c->fifo) {
    av_audio_fifo_free(c->fifo);
    c->fifo = nullptr;
  }
}

int openAll(Ctx* c) {
  int r = openDecoder(c);
  if (r < 0) return r;
  r = openEncoder(c);
  if (r < 0) return r;
  c->fifo = av_audio_fifo_alloc(AV_SAMPLE_FMT_FLTP, c->outLayout.nb_channels, kAc3Frame * 4);
  if (!c->fifo) return kErrFatal;
  c->decodedSamples = 0;
  c->nextPts = 0;
  c->eof = false;
  return 0;
}

void freeCtx(Ctx* c) {
  if (!c) return;
  closeCodecs(c);
  if (c->decFrame) av_frame_free(&c->decFrame);
  if (c->encFrame) av_frame_free(&c->encFrame);
  if (c->inPkt) av_packet_free(&c->inPkt);
  if (c->outPkt) av_packet_free(&c->outPkt);
  av_channel_layout_uninit(&c->outLayout);
  free(c->extradata);
  free(c->padBuf);
  delete c;
}

int ensureSwr(Ctx* c, const AVFrame* f) {
  if (c->swr && c->swrInRate == f->sample_rate && c->swrInFmt == f->format &&
      av_channel_layout_compare(&c->swrInLayout, &f->ch_layout) == 0) {
    return 0;
  }
  if (c->swr) swr_free(&c->swr);
  int r = swr_alloc_set_opts2(&c->swr, &c->outLayout, AV_SAMPLE_FMT_FLTP, kOutRate,
                              &f->ch_layout, (AVSampleFormat)f->format, f->sample_rate, 0, nullptr);
  if (r < 0 || !c->swr) {
    logErr("swr alloc", r);
    return kErrFatal;
  }
  r = swr_init(c->swr);
  if (r < 0) {
    logErr("swr init", r);
    return kErrFatal;
  }
  av_channel_layout_uninit(&c->swrInLayout);
  av_channel_layout_copy(&c->swrInLayout, &f->ch_layout);
  c->swrInRate = f->sample_rate;
  c->swrInFmt = f->format;
  LOGI("swr %d ch %d Hz fmt %d -> %d ch 48000 Hz", f->ch_layout.nb_channels, f->sample_rate,
       f->format, c->outLayout.nb_channels);
  return 0;
}

// Schiebt [n] Samples ab [offset] aus [f] durch den Resampler in die FIFO. f == nullptr: Resampler leeren.
int resampleToFifo(Ctx* c, const AVFrame* f, int offset, int n) {
  int inCount = f ? n : 0;
  int outMax = swr_get_out_samples(c->swr, inCount);
  if (outMax <= 0) return 0;
  uint8_t** outData = nullptr;
  int lines = 0;
  int r = av_samples_alloc_array_and_samples(&outData, &lines, c->outLayout.nb_channels, outMax,
                                             AV_SAMPLE_FMT_FLTP, 0);
  if (r < 0) return kErrFatal;
  const uint8_t* in[AV_NUM_DATA_POINTERS * 4] = {nullptr};
  if (f) {
    int bps = av_get_bytes_per_sample((AVSampleFormat)f->format);
    bool planar = av_sample_fmt_is_planar((AVSampleFormat)f->format);
    int chans = f->ch_layout.nb_channels;
    if (planar) {
      for (int i = 0; i < chans && i < (int)(sizeof(in) / sizeof(in[0])); i++) {
        in[i] = f->extended_data[i] + (size_t)offset * bps;
      }
    } else {
      in[0] = f->extended_data[0] + (size_t)offset * bps * chans;
    }
  }
  int got = swr_convert(c->swr, outData, outMax, f ? in : nullptr, inCount);
  if (got > 0) {
    if (av_audio_fifo_write(c->fifo, (void**)outData, got) < got) got = kErrFatal;
  }
  av_freep(&outData[0]);
  av_freep(&outData);
  return got < 0 ? kErrFatal : 0;
}

struct Out {
  uint8_t* buf;
  int cap;
  int used;
  jint* sizes;
  jlong* pts;
  int maxPackets;
  int count;
};

int drainEncoder(Ctx* c, Out* o) {
  while (true) {
    int r = avcodec_receive_packet(c->enc, c->outPkt);
    if (r == AVERROR(EAGAIN) || r == AVERROR_EOF) return 0;
    if (r < 0) {
      logErr("ac3 receive", r);
      return kErrEncoder;
    }
    if (o->count >= o->maxPackets || o->used + c->outPkt->size > o->cap) {
      av_packet_unref(c->outPkt);
      return kErrOutFull;
    }
    memcpy(o->buf + o->used, c->outPkt->data, c->outPkt->size);
    o->used += c->outPkt->size;
    o->sizes[o->count] = c->outPkt->size;
    o->pts[o->count] = c->outPkt->pts;
    o->count++;
    av_packet_unref(c->outPkt);
  }
}

int encodeFifo(Ctx* c, Out* o, bool flushRest) {
  while (av_audio_fifo_size(c->fifo) >= kAc3Frame ||
         (flushRest && av_audio_fifo_size(c->fifo) > 0)) {
    av_frame_unref(c->encFrame);
    c->encFrame->nb_samples = kAc3Frame;
    c->encFrame->format = AV_SAMPLE_FMT_FLTP;
    c->encFrame->sample_rate = kOutRate;
    if (av_channel_layout_copy(&c->encFrame->ch_layout, &c->outLayout) < 0) return kErrFatal;
    if (av_frame_get_buffer(c->encFrame, 0) < 0) return kErrFatal;
    int have = av_audio_fifo_read(c->fifo, (void**)c->encFrame->data, kAc3Frame);
    if (have < kAc3Frame) {
      // Letzter Rest: mit Stille auffüllen (AC3 kennt nur volle Frames).
      av_samples_set_silence(c->encFrame->data, have > 0 ? have : 0, kAc3Frame - (have > 0 ? have : 0),
                             c->outLayout.nb_channels, AV_SAMPLE_FMT_FLTP);
    }
    c->encFrame->pts = c->nextPts;
    c->nextPts += kAc3Frame;
    int r = avcodec_send_frame(c->enc, c->encFrame);
    if (r < 0) {
      logErr("ac3 send", r);
      return kErrEncoder;
    }
    r = drainEncoder(c, o);
    if (r < 0) return r;
  }
  return 0;
}

int handleDecodedFrame(Ctx* c, Out* o) {
  AVFrame* f = c->decFrame;
  int n = f->nb_samples;
  c->decodedSamples += n;
  c->lastInRate = f->sample_rate;
  int offset = 0;
  if (c->trimRemaining > 0) {
    int cut = c->trimRemaining < n ? c->trimRemaining : n;
    c->trimRemaining -= cut;
    c->trimmed += cut;
    offset = cut;
  }
  if (offset >= n) return 0;
  // German4K 3.2.1 (T7, Review): nextPts NICHT um den gekürzten Encoder-Delay verschieben — Media3s PCM-Weg legt
  // das erste behaltene Sample auf den ersten Zeitstempel; die Verschiebung lief dem Bild um den Delay hinterher.
  int r = ensureSwr(c, f);
  if (r < 0) return r;
  r = resampleToFifo(c, f, offset, n - offset);
  if (r < 0) return r;
  return encodeFifo(c, o, false);
}

Ctx* fromHandle(jlong h) { return reinterpret_cast<Ctx*>(h); }

}  // namespace

extern "C" {

// Öffnet AAC-Decoder + AC3-Encoder. [extra] = AudioSpecificConfig (darf null sein), [channels] = Kanäle der
// Quelle (bestimmt das AC3-Layout), [trimStart] = Encoder-Delay der Quelle in Samples (Quellrate).
JNIEXPORT jlong JNICALL Java_tv_own_owntv_player_Ac3Jni_nInit(JNIEnv* env, jclass, jbyteArray extra,
                                                              jint channels, jint bitrate,
                                                              jint trimStart) {
  Ctx* c = new Ctx();
  layoutFor(channels, &c->outLayout);
  if (bitrate > 0) c->bitrate = bitrate;
  c->trimRemaining = trimStart > 0 ? trimStart : 0;
  if (extra) {
    jsize n = env->GetArrayLength(extra);
    if (n > 0) {
      c->extradata = (uint8_t*)malloc(n);
      env->GetByteArrayRegion(extra, 0, n, reinterpret_cast<jbyte*>(c->extradata));
      c->extradataSize = n;
    }
  }
  c->decFrame = av_frame_alloc();
  c->encFrame = av_frame_alloc();
  c->inPkt = av_packet_alloc();
  c->outPkt = av_packet_alloc();
  if (!c->decFrame || !c->encFrame || !c->inPkt || !c->outPkt || openAll(c) < 0) {
    freeCtx(c);
    return 0;
  }
  LOGI("ac3 transcoder: %d ch in, %d ch out, %d bit/s", channels, c->outLayout.nb_channels, c->bitrate);
  return reinterpret_cast<jlong>(c);
}

JNIEXPORT jint JNICALL Java_tv_own_owntv_player_Ac3Jni_nOutChannels(JNIEnv*, jclass, jlong h) {
  Ctx* c = fromHandle(h);
  return c ? c->outLayout.nb_channels : 0;
}

// Ein AAC-Access-Unit hinein (inSize == 0 / in == null: Ende, Rest kodieren und Encoder leeren).
// Rückgabe: Anzahl AC3-Frames in [out] (Größen in [sizes], pts 1/48000 in [pts]), kDecodeSkip bei kaputtem
// Paket, sonst negativ = fatal. stats[0] = dekodierte Samples seit Reset, stats[1] = Quellrate.
JNIEXPORT jint JNICALL Java_tv_own_owntv_player_Ac3Jni_nTranscode(JNIEnv* env, jclass, jlong h,
                                                                  jobject in, jint inSize,
                                                                  jobject out, jintArray sizes,
                                                                  jlongArray pts, jlongArray stats) {
  Ctx* c = fromHandle(h);
  if (!c || !c->dec || !c->enc) return kErrFatal;
  Out o;
  o.buf = static_cast<uint8_t*>(env->GetDirectBufferAddress(out));
  o.cap = (int)env->GetDirectBufferCapacity(out);
  o.used = 0;
  o.count = 0;
  o.maxPackets = env->GetArrayLength(sizes);
  if (env->GetArrayLength(pts) < o.maxPackets) o.maxPackets = env->GetArrayLength(pts);
  if (!o.buf || o.cap <= 0 || o.maxPackets <= 0) return kErrFatal;
  jint sizeBuf[32];
  jlong ptsBuf[32];
  if (o.maxPackets > 32) o.maxPackets = 32;
  o.sizes = sizeBuf;
  o.pts = ptsBuf;

  int result = 0;
  bool skip = false;
  if (in && inSize > 0 && !c->eof) {
    uint8_t* src = static_cast<uint8_t*>(env->GetDirectBufferAddress(in));
    if (!src) return kErrFatal;
    int need = inSize + AV_INPUT_BUFFER_PADDING_SIZE;
    if (c->padBufSize < need) {
      free(c->padBuf);
      c->padBuf = (uint8_t*)malloc(need);
      c->padBufSize = c->padBuf ? need : 0;
      if (!c->padBuf) return kErrFatal;
    }
    memcpy(c->padBuf, src, inSize);
    memset(c->padBuf + inSize, 0, AV_INPUT_BUFFER_PADDING_SIZE);
    c->inPkt->data = c->padBuf;
    c->inPkt->size = inSize;
    int r = avcodec_send_packet(c->dec, c->inPkt);
    c->inPkt->data = nullptr;
    c->inPkt->size = 0;
    if (r < 0 && r != AVERROR(EAGAIN)) {
      skip = true;  // kaputtes Paket: überspringen, nicht abbrechen
    } else {
      while (true) {
        r = avcodec_receive_frame(c->dec, c->decFrame);
        if (r == AVERROR(EAGAIN) || r == AVERROR_EOF) break;
        if (r < 0) {
          skip = true;
          break;
        }
        int e = handleDecodedFrame(c, &o);
        av_frame_unref(c->decFrame);
        if (e < 0) {
          result = e;
          break;
        }
      }
    }
  } else if (!c->eof) {
    // Ende: Resampler-Rest, FIFO-Rest (mit Stille aufgefüllt), Encoder leeren.
    c->eof = true;
    if (c->swr) result = resampleToFifo(c, nullptr, 0, 0);
    if (result >= 0) result = encodeFifo(c, &o, true);
    if (result >= 0) {
      int r = avcodec_send_frame(c->enc, nullptr);
      if (r < 0 && r != AVERROR_EOF) result = kErrEncoder;
      else result = drainEncoder(c, &o);
    }
  }
  if (o.count > 0) {
    env->SetIntArrayRegion(sizes, 0, o.count, sizeBuf);
    env->SetLongArrayRegion(pts, 0, o.count, ptsBuf);
  }
  if (stats && env->GetArrayLength(stats) >= 2) {
    jlong s[2] = {c->decodedSamples, c->lastInRate};
    env->SetLongArrayRegion(stats, 0, 2, s);
  }
  if (result < 0) return result;
  if (skip && o.count == 0) return kDecodeSkip;
  return o.count;
}

// Nach Seek/Sprung: Decoder leeren, Encoder + Resampler neu (AC3-Encoder kann kein flush), Zähler auf 0.
JNIEXPORT jint JNICALL Java_tv_own_owntv_player_Ac3Jni_nReset(JNIEnv*, jclass, jlong h) {
  Ctx* c = fromHandle(h);
  if (!c) return kErrFatal;
  int trim = 0;  // Trim gilt nur am Anfang des Stroms, nicht nach einem Seek.
  closeCodecs(c);
  c->trimRemaining = trim;
  c->trimmed = 0;
  return openAll(c) < 0 ? kErrFatal : 0;
}

JNIEXPORT void JNICALL Java_tv_own_owntv_player_Ac3Jni_nRelease(JNIEnv*, jclass, jlong h) {
  freeCtx(fromHandle(h));
}

}  // extern "C"
