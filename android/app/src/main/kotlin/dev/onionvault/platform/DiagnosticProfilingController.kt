package dev.onionvault.platform

import android.content.Context
import android.os.Build
import android.os.ProfilingManager
import android.os.ProfilingResult
import android.os.ProfilingTrigger
import androidx.annotation.RequiresApi
import java.io.File
import java.time.Instant
import java.util.concurrent.Executors
import java.util.function.Consumer
import org.json.JSONObject

/**
 * Android 16–17 (API 36–37) Zero-Overhead Local Forensics via [ProfilingManager].
 *
 * Registers rate-limited system profiling triggers (`TRIGGER_TYPE_OOM`,
 * `TRIGGER_TYPE_ANOMALY`, `TRIGGER_TYPE_KILL_EXCESSIVE_CPU_USAGE` on API 36+,
 * plus `TRIGGER_TYPE_COLD_START` on API 37+) writing strictly to app-private
 * local diagnostic storage (`filesDir/diagnostics`) with **zero** network or
 * third-party telemetry upload.
 */
object DiagnosticProfilingController {

    const val DIAGNOSTICS_SUBDIR = "diagnostics"
    const val MAX_RETAINED_DIAGNOSTIC_FILES = 8

    /** Compile-time constants matching [ProfilingTrigger] trigger types. */
    const val TRIGGER_OOM: Int = ProfilingTrigger.TRIGGER_TYPE_OOM
    const val TRIGGER_COLD_START: Int = ProfilingTrigger.TRIGGER_TYPE_COLD_START
    const val TRIGGER_ANOMALY: Int = ProfilingTrigger.TRIGGER_TYPE_ANOMALY
    const val TRIGGER_KILL_EXCESSIVE_CPU: Int = ProfilingTrigger.TRIGGER_TYPE_KILL_EXCESSIVE_CPU_USAGE

    data class TriggerSpec(
        val triggerType: Int,
        val rateLimitHours: Int,
    )

    @Volatile
    private var installed: Boolean = false

    private val ioExecutor by lazy {
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "trap-local-diagnostics").apply { isDaemon = true }
        }
    }

    fun diagnosticsDirectory(context: Context): File =
        File(context.filesDir, DIAGNOSTICS_SUBDIR).apply { mkdirs() }

    /**
     * Resolves the exact list of [ProfilingTrigger] types to register for [sdkInt].
     */
    fun triggersForSdk(sdkInt: Int = Build.VERSION.SDK_INT): List<TriggerSpec> {
        if (sdkInt < 36) return emptyList()
        val list = mutableListOf(
            TriggerSpec(
                triggerType = TRIGGER_OOM,
                rateLimitHours = 12,
            ),
            TriggerSpec(
                triggerType = TRIGGER_ANOMALY,
                rateLimitHours = 6,
            ),
            TriggerSpec(
                triggerType = TRIGGER_KILL_EXCESSIVE_CPU,
                rateLimitHours = 12,
            ),
        )
        if (sdkInt >= 37) {
            list.add(
                TriggerSpec(
                    triggerType = TRIGGER_COLD_START,
                    rateLimitHours = 24,
                ),
            )
        }
        return list
    }

    fun install(context: Context, sdkInt: Int = Build.VERSION.SDK_INT): Boolean {
        if (sdkInt < 36) return false
        if (installed) return true
        val specs = triggersForSdk(sdkInt)
        if (specs.isEmpty()) return false
        val appContext = context.applicationContext
        val ok = runCatching {
            Api36Impl.registerTriggers(appContext, specs)
            true
        }.getOrDefault(false)
        if (ok) {
            installed = true
        }
        return ok
    }

    /**
     * Persists a local-only diagnostic manifest entry inside `filesDir/diagnostics`
     * and enforces bounded retention ([MAX_RETAINED_DIAGNOSTIC_FILES]).
     */
    fun recordLocalDiagnosticMetadata(
        diagnosticsDir: File,
        triggerType: Int,
        errorCode: Int,
        resultFilePath: String?,
        errorMessage: String?,
        nowEpochMillis: Long = System.currentTimeMillis(),
    ): File {
        diagnosticsDir.mkdirs()
        // Reject any path outside app-private storage if copying/referencing trace output.
        val sanitizedPath = resultFilePath
            ?.trim()
            ?.takeIf { it.isNotEmpty() && !it.contains("://") }
            .orEmpty()

        val payload = JSONObject()
            .put("recorded_at", Instant.ofEpochMilli(nowEpochMillis).toString())
            .put("trigger_type", triggerType)
            .put("error_code", errorCode)
            .put("result_file_path", sanitizedPath)
            .put("error_message", errorMessage.orEmpty())
            .put("local_only", true)
            .toString(2)

        val outFile = File(diagnosticsDir, "profile-${nowEpochMillis}-t${triggerType}.json")
        outFile.writeText(payload, Charsets.UTF_8)
        pruneOldDiagnostics(diagnosticsDir)
        return outFile
    }

    fun pruneOldDiagnostics(diagnosticsDir: File, keepCount: Int = MAX_RETAINED_DIAGNOSTIC_FILES) {
        val files = diagnosticsDir.listFiles { file -> file.isFile }
            ?.sortedByDescending { it.lastModified() }
            ?: return
        if (files.size <= keepCount) return
        files.drop(keepCount).forEach { old ->
            runCatching { old.delete() }
        }
    }

    @RequiresApi(36)
    private object Api36Impl {
        fun registerTriggers(context: Context, specs: List<TriggerSpec>) {
            val manager = context.getSystemService(ProfilingManager::class.java) ?: return
            val dir = diagnosticsDirectory(context)
            val resultConsumer = Consumer<ProfilingResult> { result ->
                recordLocalDiagnosticMetadata(
                    diagnosticsDir = dir,
                    triggerType = result.triggerType,
                    errorCode = result.errorCode,
                    resultFilePath = result.resultFilePath,
                    errorMessage = result.errorMessage,
                )
            }
            manager.registerForAllProfilingResults(ioExecutor, resultConsumer)
            val triggers = specs.map { spec ->
                ProfilingTrigger.Builder(spec.triggerType)
                    .setRateLimitingPeriodHours(spec.rateLimitHours)
                    .build()
            }
            manager.addProfilingTriggers(triggers)
        }
    }
}
