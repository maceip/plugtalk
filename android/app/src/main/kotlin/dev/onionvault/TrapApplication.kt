package dev.onionvault

import android.app.Application
import android.util.Log

/**
 * The application object of Trap.
 *
 * Its duties are three:
 *
 *   * install the guard against the crashes before the first activity is
 *     created, because that is where the crashes of the native code
 *     happen;
 *   * prepare the Java half of the engine, which the native half expects
 *     to find ready when it initializes itself;
 *   * declare that the native libraries of the application are extracted to
 *     the disk, which the engine needs to map them.
 */
class TrapApplication : Application() {

    private companion object {
        const val TAG = "TrapApplication"
    }

    override fun attachBaseContext(base: android.content.Context) {
        super.attachBaseContext(base)

        // The guard against the crashes is armed first, before anything
        // else can fail: the Java half catches the exceptions, and the
        // native half catches the signals of the engine.
        CrashGuard.install(this)

        // The engine is prepared before anything else: the native side
        // initializes Chromium, and Chromium reads the application context,
        // the command line and the resource files of the APK while it does
        // so.
        //
        // The preparation is limited to the browser process: the child
        // processes of Chromium bootstrap themselves through the services
        // that the bundle declares in the manifest.
        val prepared = try {
            CefRuntime.prepare(this)
        } catch (error: Throwable) {
            Log.e(TAG, "the engine could not be prepared", error)
            false
        }
        if (!prepared && CefRuntime.isBrowserProcess(this)) {
            Log.e(TAG, "the browser cannot start without the engine")
        }

        // The library of Trap is loaded after the engine, because it links
        // against it: the loader of the engine has to be the one that puts
        // `libcef.so` into the process.
        //
        // The load is inside the guard, so that a failure is reported like
        // any other error: the trace is shown on the next start instead of
        // the application dying silently.
        //
        // The child processes do not need the library of Trap: they render
        // the pages, and the work of the application happens in the browser
        // process.
        if (CefRuntime.isBrowserProcess(this)) {
            try {
                OvNative.load()
                OvNative.nativeInstallCrashGuard(
                    CrashGuard.nativeReportFile(this).absolutePath,
                )
            } catch (error: Throwable) {
                Log.e(TAG, "the native library of Trap could not be loaded", error)
                CrashGuard.record(this, error)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
    }
}
