package dev.onionvault

import android.app.Application
import android.content.Context
import android.util.Log
import com.cefrium.Cefrium
import org.chromium.base.ApplicationStatus
import org.chromium.base.ContextUtils
import org.chromium.base.PathUtils
import org.chromium.base.library_loader.LibraryLoader
import org.chromium.base.library_loader.LibraryProcessType
import org.chromium.ui.base.ResourceBundle

/**
 * Process setup for Chromium, as the Cefrium SDK requires it.
 *
 * Every process of the application (the browser process and the Chromium
 * child processes the SDK starts as services) runs [attachBaseContext]; only
 * the browser process initializes Cefrium.  Each step prevents a hard crash
 * inside Chromium, see QUICKSTART-CEFRIUM.md of the SDK.
 */
class TrapApplication : Application() {

    companion object {
        private const val TAG = "TrapApplication"

        /** `null` when Cefrium started, otherwise the reason it did not. */
        @Volatile
        var cefriumFailure: String? = "Cefrium was not initialized"
            private set
    }

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        try {
            val isBrowser = !ContextUtils.getProcessName().contains(":")
            // `this`, not getApplicationContext(): the latter is null here.
            ContextUtils.initApplicationContext(this)
            ResourceBundle.setAvailablePakLocales(arrayOf("en-US"))
            LibraryLoader.getInstance().setLibraryProcessType(
                if (isBrowser) LibraryProcessType.PROCESS_BROWSER else LibraryProcessType.PROCESS_CHILD,
            )
            if (isBrowser) {
                PathUtils.setPrivateDataDirectorySuffix("cefrium")
                ApplicationStatus.initialize(this)
            }
        } catch (error: Throwable) {
            Log.e(TAG, "the Chromium process setup failed", error)
            cefriumFailure = "The Chromium process setup failed: ${error.message ?: error}"
        }
    }

    override fun onCreate() {
        super.onCreate()
        val isBrowser = try {
            !ContextUtils.getProcessName().contains(":")
        } catch (error: Throwable) {
            Log.e(TAG, "cannot read the process name", error)
            return
        }
        if (!isBrowser) {
            return
        }
        try {
            val ok = Cefrium.initialize(this)
            cefriumFailure = if (ok) null else "Cefrium.initialize returned false"
            Log.i(TAG, "Cefrium ${Cefrium.getVersion()} (Chromium ${Cefrium.getChromiumVersion()}): " +
                if (ok) "OK" else "FAILED")
        } catch (error: Throwable) {
            Log.e(TAG, "Cefrium.initialize failed", error)
            cefriumFailure = "Cefrium could not start: ${error.message ?: error}"
        }
    }
}
