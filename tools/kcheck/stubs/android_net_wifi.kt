package android.net.wifi

open class WifiManager {
    open class WifiLock {
        var isHeld: Boolean = false
        fun setReferenceCounted(counted: Boolean) {}
        fun acquire() {}
        fun release() {}
    }

    fun createWifiLock(lockType: Int, tag: String?): WifiLock = WifiLock()

    companion object {
        const val WIFI_MODE_FULL = 1
        const val WIFI_MODE_SCAN_ONLY = 2
        const val WIFI_MODE_FULL_HIGH_PERF = 3
        const val WIFI_MODE_FULL_LOW_LATENCY = 4
    }
}
