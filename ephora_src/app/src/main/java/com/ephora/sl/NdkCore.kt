package com.ephora.sl
object NdkCore {
  init { try { System.loadLibrary("ephora") } catch(_: Throwable) {} }
  @JvmStatic external fun ndkHello(): String
  fun helloSafe(): String = try { ndkHello() } catch(t: Throwable) { "NDK no cargado: ${t.message}" }
}
