#include <jni.h>

#include <algorithm>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <vector>

#include "openjpeg.h"

namespace {

struct J2KErrorState {
  bool failed = false;
};

void j2kErrorCallback(const char* /*msg*/, void* clientData) {
  if (clientData != nullptr) {
    static_cast<J2KErrorState*>(clientData)->failed = true;
  }
}
void j2kWarningCallback(const char* /*msg*/, void* /*clientData*/) {}
void j2kInfoCallback(const char* /*msg*/, void* /*clientData*/) {}

struct MemStream {
  const uint8_t* data;
  size_t size;
  size_t pos;
};

OPJ_SIZE_T memRead(void* buffer, OPJ_SIZE_T nbBytes, void* userData) {
  MemStream* s = static_cast<MemStream*>(userData);
  if (s->pos >= s->size) return 0;
  size_t remaining = s->size - s->pos;
  size_t take = nbBytes < remaining ? static_cast<size_t>(nbBytes) : remaining;
  memcpy(buffer, s->data + s->pos, take);
  s->pos += take;
  return static_cast<OPJ_SIZE_T>(take);
}
OPJ_OFF_T memSkip(OPJ_OFF_T nbBytes, void* userData) {
  MemStream* s = static_cast<MemStream*>(userData);
  if (nbBytes < 0) return -1;
  size_t take = static_cast<size_t>(nbBytes);
  if (s->pos + take > s->size) take = s->size - s->pos;
  s->pos += take;
  return static_cast<OPJ_OFF_T>(take);
}
OPJ_BOOL memSeek(OPJ_OFF_T nbBytes, void* userData) {
  MemStream* s = static_cast<MemStream*>(userData);
  if (nbBytes < 0 || static_cast<size_t>(nbBytes) > s->size) return OPJ_FALSE;
  s->pos = static_cast<size_t>(nbBytes);
  return OPJ_TRUE;
}

inline uint8_t clamp8(int v) { return v < 0 ? 0 : (v > 255 ? 255 : static_cast<uint8_t>(v)); }

// rev2: cada fallo nativo vuelve como entero de etapa (1..9) en un array de 1,
// para que el reporte diga DONDE fallo en vez de un null ciego.
jobjectArray returnStage(JNIEnv* env, jint code) {
  jclass integerClass = env->FindClass("java/lang/Integer");
  jclass objectClass = env->FindClass("java/lang/Object");
  if (integerClass == nullptr || objectClass == nullptr) return nullptr;
  jmethodID intValueOf =
      env->GetStaticMethodID(integerClass, "valueOf", "(I)Ljava/lang/Integer;");
  if (intValueOf == nullptr) return nullptr;
  jobjectArray result = env->NewObjectArray(1, objectClass, nullptr);
  if (result == nullptr) return nullptr;
  jobject codeObj = env->CallStaticObjectMethod(integerClass, intValueOf, code);
  if (codeObj == nullptr) return nullptr;
  env->SetObjectArrayElement(result, 0, codeObj);
  env->DeleteLocalRef(codeObj);
  env->DeleteLocalRef(integerClass);
  env->DeleteLocalRef(objectClass);
  return result;
}

}  // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_com_lumiyaviewer_lumiya_slproto_asset_J2KDecoderNative_nativeJ2KAvailable(
    JNIEnv* /*env*/, jclass) {
  return JNI_TRUE;
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_lumiyaviewer_lumiya_slproto_asset_J2KDecoderNative_nativeDecodeJ2K(
    JNIEnv* env, jclass, jbyteArray data, jint reduce) {
  if (data == nullptr) return nullptr;
  const jsize len = env->GetArrayLength(data);
  if (len <= 0 || len > (32 << 20)) return nullptr;

  std::vector<uint8_t> bytes(static_cast<size_t>(len));
  env->GetByteArrayRegion(data, 0, len, reinterpret_cast<jbyte*>(bytes.data()));

  int reduceLevel = reduce < 0 ? 0 : (reduce > 3 ? 3 : reduce);

  opj_dparameters_t params;
  opj_set_default_decoder_parameters(&params);
  params.cp_reduce = reduceLevel;

  J2KErrorState errState;
  // SL normally carries raw J2C/J2K codestreams, but accepting JP2 containers
  // costs almost nothing and avoids rejecting otherwise valid cached assets.
  const bool isJp2 = len >= 12 &&
      static_cast<uint8_t>(bytes[0]) == 0x00 &&
      static_cast<uint8_t>(bytes[1]) == 0x00 &&
      static_cast<uint8_t>(bytes[2]) == 0x00 &&
      static_cast<uint8_t>(bytes[3]) == 0x0C &&
      static_cast<uint8_t>(bytes[4]) == 0x6A &&
      static_cast<uint8_t>(bytes[5]) == 0x50 &&
      static_cast<uint8_t>(bytes[6]) == 0x20 &&
      static_cast<uint8_t>(bytes[7]) == 0x20 &&
      static_cast<uint8_t>(bytes[8]) == 0x0D &&
      static_cast<uint8_t>(bytes[9]) == 0x0A &&
      static_cast<uint8_t>(bytes[10]) == 0x87 &&
      static_cast<uint8_t>(bytes[11]) == 0x0A;
  opj_codec_t* codec = opj_create_decompress(isJp2 ? OPJ_CODEC_JP2 : OPJ_CODEC_J2K);
  if (codec == nullptr) return returnStage(env, 1);
  opj_set_info_handler(codec, j2kInfoCallback, nullptr);
  opj_set_warning_handler(codec, j2kWarningCallback, nullptr);
  opj_set_error_handler(codec, j2kErrorCallback, &errState);
  if (!opj_setup_decoder(codec, &params)) {
    opj_destroy_codec(codec);
    return returnStage(env, 2);
  }

  MemStream stream{bytes.data(), bytes.size(), 0};
  opj_stream_t* opjStream = opj_stream_create(1024, OPJ_TRUE);
  if (opjStream == nullptr) {
    opj_destroy_codec(codec);
    return returnStage(env, 3);
  }
  opj_stream_set_user_data(opjStream, &stream, nullptr);
  opj_stream_set_read_function(opjStream, memRead);
  opj_stream_set_skip_function(opjStream, memSkip);
  opj_stream_set_seek_function(opjStream, memSeek);
  opj_stream_set_user_data_length(opjStream, bytes.size());

  opj_image_t* image = nullptr;
  int stage = 0;
  if (opj_read_header(opjStream, codec, &image) != OPJ_TRUE) {
    stage = 4;
  } else if (opj_decode(codec, opjStream, image) != OPJ_TRUE) {
    stage = 5;
  } else if (opj_end_decompress(codec, opjStream) != OPJ_TRUE) {
    stage = 6;
  } else if (errState.failed || image == nullptr) {
    stage = 6;
  }
  opj_stream_destroy(opjStream);
  opj_destroy_codec(codec);
  if (stage != 0) {
    // No destruir la imagen en rutas de fallo (etapas 4/5/6): el desenredo
    // de errores de OpenJPEG ya puede haber liberado sus buffers, y un
    // segundo destroy cuelga el proceso (tombstones 2026-09-24: SIGSEGV y
    // SIGABRT con doble free dentro de opj_image_destroy). La fuga de una
    // imagen parcial por textura corrupta es menor: los fallos de decode
    // nunca se reencolan.
    return returnStage(env, stage);
  }

  const OPJ_UINT32 w = image->x1 > image->x0 ? image->x1 - image->x0 : 0;
  const OPJ_UINT32 h = image->y1 > image->y0 ? image->y1 - image->y0 : 0;
  const OPJ_UINT32 comps = image->numcomps;
  if (w == 0 || h == 0 || comps == 0 || comps > 4 || w > 2048 || h > 2048) {
    opj_image_destroy(image);
    return returnStage(env, 7);
  }
  const uint64_t pixels = static_cast<uint64_t>(w) * h;
  if (pixels > (2048ULL * 2048ULL)) {
    opj_image_destroy(image);
    return returnStage(env, 9);
  }

  // JPEG-2000 components are allowed to be subsampled (dx/dy > 1) and to
  // have a component origin different from the reference image. The previous
  // implementation incorrectly rejected every such image at stage 8, which
  // is exactly what the device report exposed: 567 valid J2K codestreams were
  // decoded by OpenJPEG and then all discarded because comp.w/h did not equal
  // the image dimensions. Map each reference-grid pixel to the corresponding
  // component sample instead of requiring equal component dimensions.
  for (OPJ_UINT32 c = 0; c < comps; ++c) {
    const opj_image_comp_t& comp = image->comps[c];
    if (comp.w == 0 || comp.h == 0 || comp.data == nullptr ||
        comp.dx <= 0 || comp.dy <= 0 || comp.prec <= 0) {
      opj_image_destroy(image);
      return returnStage(env, 8);
    }
  }

  const size_t outSize = static_cast<size_t>(pixels) * 4;
  std::vector<uint8_t> out(outSize);

  auto scaledSample = [&](OPJ_UINT32 c, OPJ_UINT32 x, OPJ_UINT32 y) -> int {
    const opj_image_comp_t& comp = image->comps[c];
    const int64_t refX = static_cast<int64_t>(image->x0) + static_cast<int64_t>(x);
    const int64_t refY = static_cast<int64_t>(image->y0) + static_cast<int64_t>(y);
    int64_t sx = (refX - static_cast<int64_t>(comp.x0)) / comp.dx;
    int64_t sy = (refY - static_cast<int64_t>(comp.y0)) / comp.dy;
    if (sx < 0) sx = 0;
    if (sy < 0) sy = 0;
    if (sx >= comp.w) sx = comp.w - 1;
    if (sy >= comp.h) sy = comp.h - 1;
    const uint64_t index = static_cast<uint64_t>(sy) *
        static_cast<uint64_t>(comp.w) + static_cast<uint64_t>(sx);
    int64_t value = comp.data[index];
    const int prec = static_cast<int>(comp.prec);
    if (prec < 8) value <<= (8 - prec);
    else if (prec > 8) value >>= (prec - 8);
    if (comp.sgnd != 0) {
      // Convert signed JPEG-2000 samples to the same unsigned 8-bit domain
      // used by the renderer. This is defensive; SL color textures are
      // normally unsigned.
      value += 128;
    }
    if (value < 0) value = 0;
    if (value > 255) value = 255;
    return static_cast<int>(value);
  };

  auto ycbcrToRgb = [](int y, int cb, int cr, uint8_t* r, uint8_t* g, uint8_t* b) {
    const double yf = static_cast<double>(y);
    const double cbf = static_cast<double>(cb) - 128.0;
    const double crf = static_cast<double>(cr) - 128.0;
    *r = clamp8(static_cast<int>(yf + 1.40200 * crf + 0.5));
    *g = clamp8(static_cast<int>(yf - 0.34414 * cbf - 0.71414 * crf + 0.5));
    *b = clamp8(static_cast<int>(yf + 1.77200 * cbf + 0.5));
  };

  const bool isSycc = image->color_space == OPJ_CLRSPC_SYCC && comps >= 3;
  const bool isCmyk = image->color_space == OPJ_CLRSPC_CMYK && comps == 4;
  const bool hasAlphaComp = comps == 2 || (comps == 4 && !isCmyk);
  for (OPJ_UINT32 y = 0; y < h; ++y) {
    for (OPJ_UINT32 x = 0; x < w; ++x) {
      const uint64_t i = static_cast<uint64_t>(y) * w + x;
      uint8_t r, g, b, a;
      if (comps == 1) {
        r = g = b = clamp8(scaledSample(0, x, y));
        a = 255;
      } else if (comps == 2) {
        r = g = b = clamp8(scaledSample(0, x, y));
        a = clamp8(scaledSample(1, x, y));
      } else if (isSycc) {
        ycbcrToRgb(scaledSample(0, x, y), scaledSample(1, x, y),
                   scaledSample(2, x, y), &r, &g, &b);
        a = comps == 4 ? clamp8(scaledSample(3, x, y)) : 255;
      } else if (isCmyk) {
        const int c = scaledSample(0, x, y);
        const int m = scaledSample(1, x, y);
        const int yy = scaledSample(2, x, y);
        const int k = scaledSample(3, x, y);
        r = clamp8(255 - std::min(255, c + k));
        g = clamp8(255 - std::min(255, m + k));
        b = clamp8(255 - std::min(255, yy + k));
        a = 255;
      } else if (comps == 3) {
        r = clamp8(scaledSample(0, x, y));
        g = clamp8(scaledSample(1, x, y));
        b = clamp8(scaledSample(2, x, y));
        a = 255;
      } else {
        r = clamp8(scaledSample(0, x, y));
        g = clamp8(scaledSample(1, x, y));
        b = clamp8(scaledSample(2, x, y));
        a = clamp8(scaledSample(3, x, y));
      }
      out[i * 4] = r;
      out[i * 4 + 1] = g;
      out[i * 4 + 2] = b;
      out[i * 4 + 3] = a;
    }
  }
  const bool hasAlpha = hasAlphaComp;
  opj_image_destroy(image);

  jbyteArray pixelsArray = env->NewByteArray(static_cast<jsize>(outSize));
  if (pixelsArray == nullptr) {
    // image was already destroyed above; never destroy it twice.
    return returnStage(env, 9);
  }
  env->SetByteArrayRegion(pixelsArray, 0, static_cast<jsize>(outSize),
                          reinterpret_cast<const jbyte*>(out.data()));

  jclass integerClass = env->FindClass("java/lang/Integer");
  jclass booleanClass = env->FindClass("java/lang/Boolean");
  jclass objectClass = env->FindClass("java/lang/Object");
  if (integerClass == nullptr || booleanClass == nullptr || objectClass == nullptr) return nullptr;
  jmethodID intValueOf = env->GetStaticMethodID(integerClass, "valueOf", "(I)Ljava/lang/Integer;");
  jmethodID boolValueOf =
      env->GetStaticMethodID(booleanClass, "valueOf", "(Z)Ljava/lang/Boolean;");
  if (intValueOf == nullptr || boolValueOf == nullptr) return nullptr;

  jobjectArray result = env->NewObjectArray(4, objectClass, nullptr);
  if (result == nullptr) return nullptr;
  jobject jw = env->CallStaticObjectMethod(integerClass, intValueOf, static_cast<jint>(w));
  jobject jh = env->CallStaticObjectMethod(integerClass, intValueOf, static_cast<jint>(h));
  jobject ja =
      env->CallStaticObjectMethod(booleanClass, boolValueOf, static_cast<jboolean>(hasAlpha));
  if (jw == nullptr || jh == nullptr || ja == nullptr) return nullptr;
  env->SetObjectArrayElement(result, 0, jw);
  env->SetObjectArrayElement(result, 1, jh);
  env->SetObjectArrayElement(result, 2, pixelsArray);
  env->SetObjectArrayElement(result, 3, ja);
  env->DeleteLocalRef(jw);
  env->DeleteLocalRef(jh);
  env->DeleteLocalRef(ja);
  env->DeleteLocalRef(pixelsArray);
  env->DeleteLocalRef(integerClass);
  env->DeleteLocalRef(booleanClass);
  env->DeleteLocalRef(objectClass);
  return result;
}
