package dev.onionvault

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.time.Instant

/**
 * The guard against the crashes that kill the application.
 *
 * Two kinds of failure are caught:
 *
 *   * an **exception** that reaches the top of a thread: the handler of
 *     this object writes the stack trace into the private directory of the
 *     application and lets the process die; the next start shows the trace
 *     instead of an empty window, and the user can copy it or try again.
 *   * a **native crash** (a signal of the engine of CEF, for instance):
 *     the handler of the system cannot see it, but the platform remembers
 *     why the process died; the reason is read back on the next start.
 */
object CrashGuard {

    private const val TAG = "TrapCrash"

    /** The name of the file that holds the report of the last crash. */
    private const val REPORT_FILE = "crash-report.txt"

    /**
     * The name of the file that holds the report of the last crash of the
     * native code.
     *
     * The file is written by the guard of the native code (see
     * `crates/ov-core/src/crash.rs`), in the moment of the crash: it is
     * the only trace that exists when the engine receives a signal.
     */
    private const val NATIVE_REPORT_FILE = "crash-native.txt"

    /**
     * Catch the exceptions that would kill the application.
     *
     * The handler is installed in the application object, so that it is
     * active before the first activity and in every thread of the process.
     */
    fun install(context: Context) {
        val application = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                record(application, error)
            } catch (nested: Throwable) {
                Log.e(TAG, "the crash report could not be written", nested)
            }
            if (previous != null) {
                previous.uncaughtException(thread, error)
            } else {
                // Without a handler the process would stay alive in a broken
                // state; the engine is not usable any more.
                android.os.Process.killProcess(android.os.Process.myPid())
            }
        }
    }

    /**
     * Write the report of a crash.
     *
     * The report holds the moment of the crash, the thread and the whole
     * stack trace, so that it can be copied and read as it is.
     */
    fun record(context: Context, error: Throwable) {
        val report = report(error)
        runCatching { reportFile(context).writeText(report) }
            .onFailure { Log.e(TAG, "the crash report could not be written", it) }
    }

    /**
     * The report of the last crash, if there is one.
     *
     * Three sources are read, in the order of how close they are to the
     * crash:
     *
     *   * the file of the **native guard**, which is written in the moment
     *     of a crash of the engine — a signal is not an exception, and
     *     the handler of Java never sees it;
     *   * the file of the **Java handler**, which holds the stack trace of
     *     an exception;
     *   * the **record of the platform**, which says why the process died
     *     and carries the trace of the system for a crash of the native
     *     code.
     */
    fun pending(context: Context): String? {
        nativeReport(context)?.let { return it }

        val file = reportFile(context)
        if (file.isFile) {
            val text = runCatching { file.readText() }.getOrNull()
            if (!text.isNullOrBlank()) {
                return text
            }
        }
        return nativeExit(context)
    }

    /**
     * The report of the native guard, if there is one.
     *
     * The report is the trace of a crash inside the engine: the guard of
     * the native code writes it in the moment of the crash, with the
     * signal that killed the process.
     */
    private fun nativeReport(context: Context): String? {
        val file = nativeReportFile(context)
        if (!file.isFile) {
            return null
        }
        val text = runCatching { file.readText() }.getOrNull()
        return text?.takeIf { it.isNotBlank() }
    }

    /** The file that holds the report of a crash of the native code. */
    fun nativeReportFile(context: Context): File =
        File(context.filesDir, NATIVE_REPORT_FILE)

    /** Forget the report of the last crash. */
    fun clear(context: Context) {
        runCatching { reportFile(context).delete() }
        runCatching { nativeReportFile(context).delete() }
    }

    /** The file that holds the report of the last crash. */
    private fun reportFile(context: Context): File =
        File(context.filesDir, REPORT_FILE)

    /** The text of the report of an exception. */
    private fun report(error: Throwable): String {
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

    /**
     * The reason of the last exit of the process.
     *
     * The platform remembers why a process died; a crash of the native
     * engine is reported there, with the signal that killed it.  Since
     * Android 11 the tombstone of the crash is part of the record, and it
     * is read here: the trace of a native crash is the one that says where
     * the engine stopped.
     */
    private fun nativeExit(context: Context): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return null
        }
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return null
        val reasons = runCatching {
            manager.getHistoricalProcessExitReasons(context.packageName, 0, 1)
        }.getOrNull() ?: return null
        val info = reasons.firstOrNull() ?: return null
        if (!isCrash(info)) {
            return null
        }
        return buildString {
            appendLine("Trap stopped with an error.")
            appendLine()
            appendLine("When:  ${Instant.ofEpochMilli(info.timestamp)}")
            appendLine("Type:  ${describe(info.reason)}")
            appendLine("Why:  ${info.description ?: "no description"}")
            if (info.importance != android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_NORMAL) {
                appendLine("Note: the system considered the process as ${info.importance}")
            }
            val tombstone = tombstone(info)
            if (tombstone != null) {
                appendLine()
                appendLine("--- the trace of the system ---")
                append(tombstone)
            } else {
                appendLine()
                appendLine("The engine of the browser stopped inside the native code;")
                appendLine("the system did not leave a trace of it.")
            }
        }
    }

    /**
     * The tombstone of a native crash.
     *
     * The record of the exit of the process carries the trace that the
     * system wrote when the process died; it is the only trace of a crash
     * inside the native code of the engine.
     */
    private fun tombstone(info: ApplicationExitInfo): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return null
        }
        val stream = runCatching { info.traceInputStream }.getOrNull() ?: return null
        return runCatching {
            stream.use { input ->
                input.readBytes().toString(Charsets.ISO_8859_1)
            }
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    /** Whether the reason of an exit is a crash. */
    private fun isCrash(info: ApplicationExitInfo): Boolean =
        info.reason == ApplicationExitInfo.REASON_CRASH ||
            info.reason == ApplicationExitInfo.REASON_CRASH_NATIVE ||
            info.reason == ApplicationExitInfo.REASON_ANR

    /** The name of a reason of an exit. */
    private fun describe(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_CRASH -> "a crash of the application"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "a crash of the native code"
        ApplicationExitInfo.REASON_ANR -> "the application did not answer"
        else -> "an unknown error ($reason)"
    }
}
