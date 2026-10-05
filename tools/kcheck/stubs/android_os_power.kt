package android.os

open class PowerManager {
    open class WakeLock {
        var isHeld: Boolean = false
        fun setReferenceCounted(counted: Boolean) {}
        fun acquire() {}
        fun acquire(timeout: Long) {}
        fun release() {}
    }

    fun newWakeLock(levelAndFlags: Int, tag: String?): WakeLock = WakeLock()

    companion object {
        const val PARTIAL_WAKE_LOCK = 1
        const val SCREEN_DIM_WAKE_LOCK = 6
    }
}
