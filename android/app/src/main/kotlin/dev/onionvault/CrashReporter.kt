package dev.onionvault

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.os.Process
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.time.Instant

/**
 * Compatibility alias so existing unit tests (`CrashGuardTest`) and new callers
 * (`CrashReporter`) share the exact same safe implementation.
 */
typealias CrashGuard = CrashReporter

/**
 * Captures uncaught JVM/Kotlin exceptions and reads kernel-recorded
 * [ApplicationExitInfo] native tombstones on the next launch without installing
 * any `libc::sigaction` signal handler (which would corrupt Android ART's
 * `FaultManager` implicit null/stack-overflow checks).
 */
object CrashReporter {

    private const val TAG = "TrapCrash"
    private const val JAVA_REPORT_FILE = "crash-java.txt"
    private const val LEGACY_REPORT_FILE = "crash-report.txt"
    private const val DISMISSED_EXIT_TS_FILE = "crash-dismissed-ts.txt"

    @Volatile
    private var installed: Boolean = false

    @Synchronized
    fun install(context: Context) {
        if (installed) return
        installed = true
        val appContext = context.applicationContext ?: context
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                record(appContext, error)
            } catch (nested: Throwable) {
                Log.e(TAG, "Failed to persist uncaught exception report", nested)
            }
            if (previous != null) {
                previous.uncaughtException(thread, error)
            } else {
                Process.killProcess(Process.myPid())
            }
        }
    }

    fun record(context: Context, error: Throwable) {
        val text = formatThrowableReport(error)
        runCatching { reportFile(context).writeText(text) }
            .onFailure { Log.e(TAG, "Could not write crash report", it) }
    }

    fun pendingReport(context: Context): String? = pending(context)

    fun pending(context: Context): String? {
        for (candidate in listOf(reportFile(context), File(context.filesDir, LEGACY_REPORT_FILE))) {
            if (candidate.isFile) {
                val text = runCatching { candidate.readText() }.getOrNull()
                if (!text.isNullOrBlank()) {
                    return text
                }
            }
        }
        return latestUndismissedNativeExit(context)
    }

    fun clear(context: Context) {
        runCatching { reportFile(context).delete() }
        runCatching { File(context.filesDir, LEGACY_REPORT_FILE).delete() }
        // Record the timestamp of the latest historical exit so dismissing a
        // crash report does not re-surface the same OS tombstone on future starts.
        val latestExitTs = latestExitTimestamp(context)
        if (latestExitTs > 0L) {
            runCatching {
                dismissedTsFile(context).writeText(latestExitTs.toString())
            }
        }
    }

    private fun reportFile(context: Context): File =
        File(context.filesDir, JAVA_REPORT_FILE)

    private fun dismissedTsFile(context: Context): File =
        File(context.filesDir, DISMISSED_EXIT_TS_FILE)

    private fun formatThrowableReport(error: Throwable): String {
        val trace = StringWriter()
        PrintWriter(trace).use { error.printStackTrace(it) }
        return buildString {
            appendLine("Trap stopped with an error.")
            appendLine()
            appendLine("When:  ${Instant.now()}")
            appendLine("Type:  ${error.javaClass.name}")
            appendLine("Why:  ${error.message ?: "no message"}")
            appendLine()
            append(trace.toString())
        }
    }

    private fun latestExitTimestamp(context: Context): Long {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return 0L
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return 0L
        val reasons = runCatching {
            manager.getHistoricalProcessExitReasons(context.packageName, 0, 5)
        }.getOrNull() ?: return 0L
        return reasons.maxOfOrNull { it.timestamp } ?: 0L
    }

    private fun latestUndismissedNativeExit(context: Context): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return null
        val dismissedTs = runCatching {
            dismissedTsFile(context).takeIf { it.isFile }?.readText()?.trim()?.toLongOrNull()
        }.getOrNull() ?: 0L

        val reasons = runCatching {
            manager.getHistoricalProcessExitReasons(context.packageName, 0, 5)
        }.getOrNull() ?: return null

        // Only inspect exits of the main browser process (child renderer exits are
        // isolated by design and handled without bringing down the browser).
        val info = reasons.firstOrNull { candidate ->
            candidate.processName == context.packageName &&
                candidate.timestamp > dismissedTs &&
                isCrashReason(candidate.reason)
        } ?: return null

        return buildString {
            appendLine("Trap stopped with an error.")
            appendLine()
            appendLine("When:  ${Instant.ofEpochMilli(info.timestamp)}")
            appendLine("Type:  ${describeExitReason(info.reason)}")
            appendLine("Why:  ${info.description ?: "no description"}")
            val tombstone = readTombstone(info)
            if (tombstone != null) {
                appendLine()
                appendLine("--- system tombstone ---")
                append(tombstone)
            }
        }
    }

    private fun readTombstone(info: ApplicationExitInfo): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
        val stream = runCatching { info.traceInputStream }.getOrNull() ?: return null
        return runCatching {
            stream.use { input ->
                input.readBytes().toString(Charsets.ISO_8859_1)
            }
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    private fun isCrashReason(reason: Int): Boolean =
        reason == ApplicationExitInfo.REASON_CRASH ||
            reason == ApplicationExitInfo.REASON_CRASH_NATIVE ||
            reason == ApplicationExitInfo.REASON_ANR

    private fun describeExitReason(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_CRASH -> "unhandled application exception"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "native process signal"
        ApplicationExitInfo.REASON_ANR -> "application not responding (ANR)"
        else -> "process exit ($reason)"
    }
}
