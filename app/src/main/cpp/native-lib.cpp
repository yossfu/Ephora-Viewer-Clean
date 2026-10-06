#include <jni.h>
#include <string>
extern "C" JNIEXPORT jstring JNICALL Java_com_ephora_sl_NdkCore_ndkHello(JNIEnv* e, jclass) {
  std::string s = "NDK core OK v1.3-paso1 arm64";
  return e->NewStringUTF(s.c_str());
}
