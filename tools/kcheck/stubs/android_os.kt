package android.os
class Bundle
class IBinder
class Looper {
    fun quit() {}
    fun quitSafely() {}
    companion object {
        fun prepare() {}
        fun loop() {}
        fun myLooper(): Looper? = Looper()
        fun getMainLooper(): Looper = Looper()
    }
}
class Handler {
    constructor() {}
    constructor(looper: Looper?) {}
    fun post(action: Runnable): Boolean = true
    fun removeCallbacks(action: Runnable) {}
}
object Build {
    const val MANUFACTURER: String = "prueba"
    const val MODEL: String = "prueba"
    val SUPPORTED_ABIS: Array<String> = arrayOf("arm64-v8a")
    object VERSION {
        const val RELEASE: String = "14"
        const val SDK_INT = 35
    }

    object VERSION_CODES {
        const val LOLLIPOP = 21
        const val M = 23
        const val N = 24
        const val O = 26
        const val P = 28
        const val Q = 29
        const val R = 30
        const val S = 31
        const val TIRAMISU = 33
        const val UPSIDE_DOWN_CAKE = 34
        const val VANILLA_ICE_CREAM = 35
    }
}