#include <jni.h>
#include <openjpeg.h>
#include <algorithm>
#include <cstdint>
#include <cstring>
#include <limits>
#include <vector>

extern "C" JNIEXPORT jstring JNICALL Java_com_ephora_sl_NdkCore_ndkHello(JNIEnv* e, jclass) {
  return e->NewStringUTF("NDK core + OpenJPEG 2.5.2");
}

namespace {
struct MemorySource { const uint8_t* data; OPJ_SIZE_T size; OPJ_SIZE_T offset; };
OPJ_SIZE_T read_memory(void* out, OPJ_SIZE_T requested, void* user) {
  auto* src = static_cast<MemorySource*>(user);
  if (!src || src->offset >= src->size) return static_cast<OPJ_SIZE_T>(-1);
  const OPJ_SIZE_T n = std::min(requested, src->size - src->offset);
  std::memcpy(out, src->data + src->offset, n);
  src->offset += n;
  return n;
}
OPJ_OFF_T skip_memory(OPJ_OFF_T n, void* user) {
  auto* src = static_cast<MemorySource*>(user);
  if (!src || n < 0) return -1;
  const auto step = static_cast<OPJ_SIZE_T>(n);
  src->offset = std::min(src->size, src->offset + step);
  return static_cast<OPJ_OFF_T>(step);
}
OPJ_BOOL seek_memory(OPJ_OFF_T pos, void* user) {
  auto* src = static_cast<MemorySource*>(user);
  if (!src || pos < 0 || static_cast<OPJ_SIZE_T>(pos) > src->size) return OPJ_FALSE;
  src->offset = static_cast<OPJ_SIZE_T>(pos);
  return OPJ_TRUE;
}
void quiet_message(const char*, void*) {}
uint8_t component_sample(const opj_image_comp_t& c, uint32_t x, uint32_t y, uint32_t ref_w, uint32_t ref_h) {
  if (!c.data || c.w == 0 || c.h == 0 || c.prec == 0 || c.prec > 30) return 0;
  const uint32_t sx = std::min(c.w - 1, static_cast<uint32_t>((static_cast<uint64_t>(x) * c.w) / std::max<uint32_t>(1, ref_w)));
  const uint32_t sy = std::min(c.h - 1, static_cast<uint32_t>((static_cast<uint64_t>(y) * c.h) / std::max<uint32_t>(1, ref_h)));
  int64_t v = c.data[static_cast<size_t>(sy) * c.w + sx];
  const int64_t maxv = (static_cast<int64_t>(1) << c.prec) - 1;
  if (c.sgnd) v += static_cast<int64_t>(1) << (c.prec - 1);
  v = std::clamp<int64_t>(v, 0, maxv);
  return static_cast<uint8_t>((v * 255 + maxv / 2) / maxv);
}
}

extern "C" JNIEXPORT jbyteArray JNICALL Java_com_ephora_sl_J2kDecoder_decodeNative(JNIEnv* env, jclass, jbyteArray input) {
  if (!input) return nullptr;
  const jsize input_size = env->GetArrayLength(input);
  if (input_size < 4 || input_size > 16 * 1024 * 1024) return nullptr;
  std::vector<uint8_t> compressed(static_cast<size_t>(input_size));
  env->GetByteArrayRegion(input, 0, input_size, reinterpret_cast<jbyte*>(compressed.data()));
  if (env->ExceptionCheck()) { env->ExceptionClear(); return nullptr; }
  opj_dparameters_t params;
  opj_set_default_decoder_parameters(&params);
  opj_codec_t* codec = opj_create_decompress(OPJ_CODEC_J2K);
  if (!codec) return nullptr;
  opj_set_error_handler(codec, quiet_message, nullptr);
  opj_set_warning_handler(codec, quiet_message, nullptr);
  opj_set_info_handler(codec, quiet_message, nullptr);
  opj_image_t* image = nullptr;
  opj_stream_t* stream = nullptr;
  jbyteArray result = nullptr;
  do {
    if (!opj_setup_decoder(codec, &params)) break;
    MemorySource src{compressed.data(), static_cast<OPJ_SIZE_T>(compressed.size()), 0};
    stream = opj_stream_create(64 * 1024, OPJ_TRUE);
    if (!stream) break;
    opj_stream_set_user_data(stream, &src, nullptr);
    opj_stream_set_user_data_length(stream, src.size);
    opj_stream_set_read_function(stream, read_memory);
    opj_stream_set_skip_function(stream, skip_memory);
    opj_stream_set_seek_function(stream, seek_memory);
    if (!opj_read_header(stream, codec, &image) || !image || image->numcomps < 1) break;
    if (!opj_decode(codec, stream, image) || !opj_end_decompress(codec, stream)) break;
    const uint32_t src_w = image->x1 - image->x0;
    const uint32_t src_h = image->y1 - image->y0;
    if (!src_w || !src_h || src_w > 8192 || src_h > 8192) break;
    const uint32_t factor = std::max<uint32_t>(1, (std::max(src_w, src_h) + 2047) / 2048);
    const uint32_t w = (src_w + factor - 1) / factor;
    const uint32_t h = (src_h + factor - 1) / factor;
    const uint64_t pixel_count = static_cast<uint64_t>(w) * h;
    if (pixel_count > 4 * 1024 * 1024) break;
    const size_t out_size = 8 + static_cast<size_t>(pixel_count) * 4;
    std::vector<jbyte> rgba(out_size);
    std::memcpy(rgba.data(), &w, 4);
    std::memcpy(rgba.data() + 4, &h, 4);
    const auto& c0 = image->comps[0];
    const bool gray = image->numcomps == 1;
    const bool has_alpha = image->numcomps == 2 || image->numcomps >= 4;
    for (uint32_t y = 0; y < h; ++y) for (uint32_t x = 0; x < w; ++x) {
      const uint32_t ox = std::min(src_w - 1, x * factor);
      const uint32_t oy = std::min(src_h - 1, y * factor);
      uint8_t r, g, b, a = 255;
      if (gray) { r = g = b = component_sample(c0, ox, oy, src_w, src_h); if (image->numcomps > 1) a = component_sample(image->comps[1], ox, oy, src_w, src_h); }
      else {
        r = component_sample(image->comps[0], ox, oy, src_w, src_h);
        g = component_sample(image->comps[1], ox, oy, src_w, src_h);
        b = component_sample(image->comps[2], ox, oy, src_w, src_h);
        if (image->color_space == OPJ_CLRSPC_SYCC) {
          const float yy = r / 255.0f, cb = g / 255.0f - 0.5f, cr = b / 255.0f - 0.5f;
          r = static_cast<uint8_t>(std::clamp(yy + 1.402f * cr, 0.0f, 1.0f) * 255.0f);
          g = static_cast<uint8_t>(std::clamp(yy - 0.344136f * cb - 0.714136f * cr, 0.0f, 1.0f) * 255.0f);
          b = static_cast<uint8_t>(std::clamp(yy + 1.772f * cb, 0.0f, 1.0f) * 255.0f);
        }
        if (has_alpha) a = component_sample(image->comps[image->numcomps - 1], ox, oy, src_w, src_h);
      }
      const size_t dst = 8 + (static_cast<size_t>(y) * w + x) * 4;
      rgba[dst] = static_cast<jbyte>(r); rgba[dst + 1] = static_cast<jbyte>(g);
      rgba[dst + 2] = static_cast<jbyte>(b); rgba[dst + 3] = static_cast<jbyte>(a);
    }
    if (out_size <= static_cast<size_t>(std::numeric_limits<jsize>::max())) {
      result = env->NewByteArray(static_cast<jsize>(out_size));
      if (result) env->SetByteArrayRegion(result, 0, static_cast<jsize>(out_size), rgba.data());
    }
  } while (false);
  if (image) opj_image_destroy(image);
  if (stream) opj_stream_destroy(stream);
  opj_destroy_codec(codec);
  return result;
}