package com.lumiyaviewer.lumiya.renderer.filament

import android.content.Context
import android.os.Build
import android.util.Log
import com.google.android.filament.Filament
import com.lumiyaviewer.lumiya.renderer.RenderDiagnostics
import java.io.File
import java.util.zip.ZipFile

/**
 * Loads Filament's native libraries, in one place, before anything else touches
 * a Filament class.
 *
 * This exists because of a real, device-reproduced crash: `Filament.init()` was
 * called inside the `FilamentRenderer` **constructor**, but a Kotlin class's
 * `companion object` is initialized by the class's `<clinit>`, which runs
 * *before* the constructor body — and the companion was building `TextureSampler`
 * objects. `TextureSampler`'s constructor calls the native `nCreateSampler`
 * immediately, so the class initializer invoked native code before
 * `Filament.init()` had loaded `libfilament-jni.so`. The result was a failed
 * class initializer: the renderer class became erroneous, every later attempt
 * threw `NoClassDefFoundError: ...FilamentRenderer`, and the app reported
 * "Filament no pudo iniciarse" with no engine, no viewport and a black screen.
 *
 * Two rules follow from that, and both are enforced here:
 *
 *  1. `Filament.init()` runs at **application start**, on a path that no
 *     rendering code can jump ahead of, so the library is loaded long before any
 *     Filament class is initialized.
 *  2. Nothing in the viewer may construct a Filament object in a class
 *     initializer (a `companion object` property, a top-level `val`, a field
 *     initializer declared before the `init` block). Filament objects are
 *     created inside constructors or methods, after [ensureLoaded].
 *
 * [preflight] is the evidence half: it reports the device's ABIs, what native
 * libraries the APK actually carries for that ABI, what the loader can see in
 * the extracted library directory, and the result of loading each library by
 * hand — with the full throwable when one fails. "The file is inside the APK" is
 * not the same as "the loader could load it", and this is how that is settled.
 */
object FilamentBootstrap {

    private const val TAG = "FilamentBootstrap"

    /**
     * The Filament version this build is compiled against (see
     * `gradle/libs.versions.toml`, which also explains why it is pinned). Printed
     * in the environment block: "engine NO" on a device that loaded a different
     * build of the library is a different problem from a library that would not
     * load at all.
     */
    const val FILAMENT_VERSION = "1.75.1"

    /** Native libraries the viewer needs, in load order. */
    val REQUIRED_LIBRARIES = listOf("filament-jni", "filamat-jni", "slcore")

    @Volatile
    var filamentInitDone = false
        private set

    @Volatile
    var nativeLibrariesChecked = false
        private set

    /** Result of loading each library by hand, keyed by library name. */
    private val libraryResults = LinkedHashMap<String, String>()

    /** The last preflight report, so any screen can show it. */
    @Volatile
    private var lastPreflight: List<String> = emptyList()

    private val lock = Any()

    /**
     * What the Application runs at start-up: load Filament and check the native
     * libraries once, before any activity can reach rendering code. Doing nothing
     * here is not fatal (every entry point calls [ensureLoaded] first), but doing
     * it here means the library is loaded on a path no rendering code can jump
     * ahead of, which is the whole point.
     */
    fun startUp(context: Context) {
        ensureLoaded(null)
        preflight(context, null)
    }

    /** The preflight lines from [startUp] / [preflight], for the report. */
    fun lastPreflightLines(): List<String> = lastPreflight

    /**
     * Calls `Filament.init()` once, reporting START/SUCCESS/FAIL into
     * [diagnostics]. Safe to call from any thread; the first call wins.
     *
     * Returns true when the JNI library is loaded and the Filament API is usable.
     */
    fun ensureLoaded(diagnostics: RenderDiagnostics? = null): Boolean {
        if (filamentInitDone) {
            diagnostics?.step("FILAMENT_INIT", "SUCCESS (ya estaba cargado)")
            return true
        }
        diagnostics?.step("FILAMENT_INIT", "START")
        return try {
            // Filament's own class initializer is what loads libfilament-jni.so;
            // init() itself is intentionally empty (see Filament.java 1.75.1).
            Filament.init()
            filamentInitDone = true
            diagnostics?.step("FILAMENT_INIT", "SUCCESS")
            Log.i(TAG, "Filament.init() OK (libfilament-jni cargada)")
            true
        } catch (error: Throwable) {
            // Never swallowed: the whole chain and stack trace go to the report.
            diagnostics?.failDetailed("FILAMENT_INIT", error)
            Log.e(TAG, "Filament.init() FAILED", error)
            false
        }
    }

    /**
     * Checks the native side the way the loader sees it, not the way the build
     * log claims: the device's ABIs, the libraries the APK ships for the primary
     * ABI, what is actually present in the extracted native library directory,
     * and whether each required library loads.
     *
     * Every failure is recorded with its throwable. Returns the report lines.
     */
    fun preflight(context: Context, diagnostics: RenderDiagnostics? = null): List<String> {
        val out = ArrayList<String>(24)
        out.add("Dispositivo: " + Build.MANUFACTURER + " " + Build.MODEL +
            "  ·  Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")")
        out.add("ABIs soportadas: " + Build.SUPPORTED_ABIS.joinToString(", ") +
            "  ·  primary: " + Build.SUPPORTED_ABIS.firstOrNull())
        out.add("Filament " + FILAMENT_VERSION + " (filament-android + filamat-android)  ·  " +
            "librerias nativas que busca: " + REQUIRED_LIBRARIES.joinToString(", "))

        val info = context.applicationInfo

        // What the loader can see on the device (only exists when the APK's
        // libraries are extracted; with extractNativeLibs=false they are loaded
        // straight from the APK, which the next block covers).
        val nativeDir = info.nativeLibraryDir
        if (nativeDir.isNullOrEmpty()) {
            out.add("nativeLibraryDir: (vacio)")
        } else {
            val listed = File(nativeDir).list()?.sorted() ?: emptyList()
            out.add("nativeLibraryDir: " + nativeDir + " -> " +
                (if (listed.isEmpty()) "(sin archivos)" else listed.joinToString(", ")))
        }

        // What the APK actually carries, read from the installed file.
        val primaryAbi = Build.SUPPORTED_ABIS.firstOrNull()
        // The base APK, plus any split APKs (an app installed from a bundle keeps
        // its native libraries in a split named `config.<abi>`).
        val apks = ArrayList<String>()
        apks.add(info.sourceDir)
        val splits = info.splitSourceDirs
        if (splits != null) {
            for (path in splits) {
                if (path != null) apks.add(path)
            }
        }
        try {
            val entries = ArrayList<String>()
            var totalKiB = 0L
            for (apk in apks) {
                ZipFile(apk).use { zip ->
                    totalKiB += File(apk).length() / 1024
                    val names = zip.entries()
                    while (names.hasMoreElements()) {
                        val name = names.nextElement().name
                        if (name.startsWith("lib/") && name.endsWith(".so")) {
                            entries.add(name)
                        }
                    }
                }
            }
            val forAbi = entries.filter { it.startsWith("lib/$primaryAbi/") }.sorted()
            out.add("APK (" + apks.size + " fichero(s), " + totalKiB + " KiB) lib/" + primaryAbi +
                ": " + (if (forAbi.isEmpty()) "(NINGUNA)" else forAbi.joinToString(", ")))
            val packagedAbis = entries.map { it.split("/")[1] }.distinct().sorted()
            out.add("APK ABI empaquetadas: " + packagedAbis.joinToString(", "))
        } catch (error: Throwable) {
            out.add("APK no se pudo inspeccionar: " + errorSummary(error))
            diagnostics?.failDetailed("APK_INSPECT", error)
        }

        // The decisive test: can the loader actually load each library?
        for (library in REQUIRED_LIBRARIES) {
            val result = try {
                System.loadLibrary(library)
                "OK"
            } catch (error: Throwable) {
                diagnostics?.failDetailed("LOAD_LIBRARY " + library, error)
                errorSummary(error)
            }
            synchronized(lock) { libraryResults[library] = result }
            out.add("System.loadLibrary(\"" + library + "\"): " + result)
        }
        out.add("Filament.init(): " + if (filamentInitDone) "OK" else "NO EJECUTADO")
        nativeLibrariesChecked = true
        lastPreflight = out
        for (line in out) {
            Log.i(TAG, line)
        }
        return out
    }

    /** One line naming the throwable class and message, cause chain included. */
    fun errorSummary(error: Throwable): String {
        val builder = StringBuilder(120)
        var current: Throwable? = error
        var depth = 0
        val seen = HashSet<Throwable>()
        while (current != null && depth < 8 && seen.add(current)) {
            if (depth > 0) {
                builder.append(" <- ")
            }
            builder.append(current.javaClass.name)
            val message = current.message
            if (!message.isNullOrEmpty()) {
                builder.append(": ").append(message)
            }
            current = current.cause
            depth += 1
        }
        return builder.toString()
    }

    /** The per-library results, for the report. */
    fun libraryResults(): List<String> = synchronized(lock) {
        libraryResults.map { it.key + "=" + it.value }
    }
}
