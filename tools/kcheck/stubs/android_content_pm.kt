package android.content.pm

object PackageManager {
    const val PERMISSION_GRANTED = 0
    const val PERMISSION_DENIED = -1
}

class ServiceInfo {
    companion object {
        const val FOREGROUND_SERVICE_TYPE_DATA_SYNC = 1
        const val FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK = 2
        const val FOREGROUND_SERVICE_TYPE_LOCATION = 8
    }
}

class ApplicationInfo {
    var nativeLibraryDir: String? = null
    var sourceDir: String = ""
    var dataDir: String? = null
    var packageName: String? = null
    var splitSourceDirs: Array<String?>? = null
}
