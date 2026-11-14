package dev.onionvault

import android.content.Context
import android.util.Log

/**
 * The start-up of the CEF engine.
 *
 * ## What the engine is
 *
 * Trap ships CEF, the Chromium Embedded Framework, built from its own sources
 * with the Android NDK by the `cefpack` service of the workspace.  CEF
 * publishes no Android distribution — there is no package to download — so the
 * service builds one: the engine (`libcef.so`), the C API shim that the Rust
 * part links against (`libcef_dll_wrapper.a`), the public headers of that
 * exact version and the resources that the engine reads at runtime.
 *
 * ## The two halves of an Android build
 *
 * Chromium on Android has two halves, and both of them are needed before the
 * first frame:
 *
 *   * the **native half**, which is what this project builds: the engine, the
 *     windowless rendering, the child processes;
 *   * the **Java half**, which is Chromium's own base layer for Android
 *     (`org.chromium.base.*` and the services of the child processes).  The
 *     native half reaches into it over JNI while it initializes itself: it
 *     asks for the application context, it reads the command line and it
 *     opens the resource files of the APK.
 *
 * The Java half belongs to the same bundle and is dexed into the APK by the
 * Android build.  When it is missing, those calls fail inside Chromium and
 * the process aborts before the first frame: the application dies at once,
 * with no window and no message.
 *
 * ## Why the engine is started from Rust and not from Java
 *
 * Trap owns the C API.  The Rust part of the application creates the
 * browser, the handlers and the off-screen surface, and it is the Rust part
 * that calls `CefInitialize` with the application object of Trap.  A second
 * entry point would initialize the engine twice, which Chromium refuses.
 *
 * This object therefore owns the part that belongs to Java: the knowledge of
 * which process this is, the loading of the library of Trap and the call
 * into the native side that starts the engine.
 */
object CefRuntime {

    private const val TAG = "TrapCefRuntime"

    /** Whether the engine has been prepared in this process. */
    @Volatile
    private var prepared = false

    /**
     * Prepare the engine in this process.
     *
     * The call is safe to repeat: the second call is ignored and the result
     * of the first one is returned.
     *
     * @return `true` when the engine is ready for the native side.
     */
    fun prepare(context: Context): Boolean {
        if (prepared) {
            return true
        }
        synchronized(this) {
            if (prepared) {
                return true
            }
            // The child processes of Chromium (the renderers, the utility and
            // the GPU processes) start the application object as well.  They
            // bootstrap themselves through the services that the bundle
            // declares in the manifest, and the native side of the engine
            // runs `CefExecuteProcess` in them.  The preparation below is
            // therefore limited to the browser process.
            if (!isBrowserProcess(context)) {
                Log.i(TAG, "the engine bootstraps this child process by itself")
                return false
            }
            val ok = try {
                prepareProcess(context.applicationContext)
            } catch (error: Throwable) {
                Log.e(TAG, "the engine could not be prepared", error)
                false
            }
            if (!ok) {
                Log.e(TAG, "the engine refused to start")
                return false
            }
            prepared = true
            Log.i(TAG, "the engine is ready")
            return true
        }
    }

    /**
     * Whether this process is the browser process of the application.
     *
     * The name of the process of the browser is the name of the package;
     * every other process carries a colon and the name of the service.
     */
    fun isBrowserProcess(context: Context): Boolean {
        val name = context.applicationInfo.processName ?: return true
        return !name.contains(":")
    }

    /**
     * Load the library of Trap and hand the engine over to the native side.
     *
     * The library of Trap links against `libcef.so`, which the dynamic linker
     * pulls in as a dependency.  The engine is therefore already in the
     * process when `nativeInit` runs, and the native side may call the C API
     * straight away.
     */
    private fun prepareProcess(context: Context): Boolean {
        OvNative.load()
        Log.i(TAG, "the library of Trap is loaded in the process")
        return true
    }
}
