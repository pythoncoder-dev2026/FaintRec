package com.example.logging

import android.content.Context
import android.media.AudioManager
import android.media.MediaCodecList
import android.os.Build
import com.example.data.AppFeature
import com.example.data.AppLogDao
import com.example.data.AppLogEntity
import com.example.data.AppLogExportEntry
import com.example.data.FeatureHealthDao
import com.example.data.FeatureHealthEntity
import com.example.data.FeatureHealthExportItem
import com.example.data.FeatureHealthStatus
import com.example.data.FullLogReportExport
import com.example.data.LogLevel
import com.example.util.FaintRecFolder
import com.example.util.StorageHelper
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Central buffered technical event logger and Feature Health tracker.
 *
 * Guarantees:
 * - Never logs audio content or personal user data (only technical events & diagnostics).
 * - Never crashes the app or blocks audio threads: all writes are buffered via a non-blocking
 *   Channel and flushed in batches on Dispatchers.IO.
 * - Retains at most the latest 2,000 log entries in Room automatically.
 */
@Singleton
class AppLogger @Inject constructor(
    @ApplicationContext private val context: Context,
    private val appLogDao: AppLogDao,
    private val featureHealthDao: FeatureHealthDao
) {
    companion object {
        const val MAX_LOG_ENTRIES = 2000
        private const val FLUSH_BATCH_SIZE = 32
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    private val logChannel = Channel<AppLogEntity>(
        capacity = 512,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    private val healthChannel = Channel<HealthUpdateEvent>(
        capacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    private val deviceLoggedOnce = AtomicBoolean(false)

    val logs: Flow<List<AppLogEntity>> = appLogDao.observeLogs()
    val featureHealth: Flow<List<FeatureHealthEntity>> = featureHealthDao.observeAll()

    private data class HealthUpdateEvent(
        val featureId: String,
        val status: FeatureHealthStatus,
        val errorMessage: String?,
        val timestampMillis: Long
    )

    init {
        startLogDrainLoop()
        startHealthDrainLoop()
        seedMissingFeatureRows()
    }

    private fun startLogDrainLoop() {
        scope.launch {
            val batch = ArrayList<AppLogEntity>(FLUSH_BATCH_SIZE)
            var insertsSinceTrim = 0
            for (first in logChannel) {
                batch.clear()
                batch.add(first)
                while (batch.size < FLUSH_BATCH_SIZE) {
                    val next = logChannel.tryReceive().getOrNull() ?: break
                    batch.add(next)
                }
                runCatching {
                    appLogDao.insertAll(batch)
                    insertsSinceTrim += batch.size
                    if (insertsSinceTrim >= 50) {
                        appLogDao.trimToLatest(MAX_LOG_ENTRIES)
                        insertsSinceTrim = 0
                    }
                }
                // Brief coalesce pause only if channel is quiet
                if (logChannel.isEmpty) delay(15L)
            }
        }
    }

    private fun startHealthDrainLoop() {
        scope.launch {
            for (evt in healthChannel) {
                runCatching {
                    val existing = featureHealthDao.getById(evt.featureId)
                    val updated = FeatureHealthEntity(
                        featureId = evt.featureId,
                        status = evt.status,
                        lastSuccessMillis = if (evt.status == FeatureHealthStatus.WORKING || evt.status == FeatureHealthStatus.FALLBACK) {
                            evt.timestampMillis
                        } else {
                            existing?.lastSuccessMillis
                        },
                        lastErrorMessage = when (evt.status) {
                            FeatureHealthStatus.WORKING -> null
                            FeatureHealthStatus.FALLBACK,
                            FeatureHealthStatus.FAILED,
                            FeatureHealthStatus.NOT_SUPPORTED -> evt.errorMessage?.take(180)
                            FeatureHealthStatus.NOT_TESTED -> null
                        },
                        lastUpdatedMillis = evt.timestampMillis
                    )
                    featureHealthDao.upsert(updated)
                }
            }
        }
    }

    private fun seedMissingFeatureRows() {
        scope.launch {
            runCatching {
                val existingIds = featureHealthDao.getAllOnce().map { it.featureId }.toSet()
                val missing = AppFeature.entries
                    .filter { it.id !in existingIds }
                    .map { FeatureHealthEntity(featureId = it.id, status = FeatureHealthStatus.NOT_TESTED) }
                if (missing.isNotEmpty()) {
                    featureHealthDao.upsertAll(missing)
                }
            }
        }
    }

    fun info(featureTag: String, message: String) {
        enqueueLog(LogLevel.INFO, featureTag, message, null)
    }

    fun info(feature: AppFeature, message: String) {
        enqueueLog(LogLevel.INFO, feature.id, message, null)
    }

    fun warn(featureTag: String, message: String) {
        enqueueLog(LogLevel.WARNING, featureTag, message, null)
    }

    fun warn(feature: AppFeature, message: String) {
        enqueueLog(LogLevel.WARNING, feature.id, message, null)
    }

    fun error(featureTag: String, message: String, throwable: Throwable? = null) {
        enqueueLog(LogLevel.ERROR, featureTag, message, throwable?.let(::stackTraceString))
    }

    fun error(feature: AppFeature, message: String, throwable: Throwable? = null) {
        enqueueLog(LogLevel.ERROR, feature.id, message, throwable?.let(::stackTraceString))
    }

    fun reportSuccess(feature: AppFeature, detail: String? = null) {
        val now = System.currentTimeMillis()
        healthChannel.trySend(HealthUpdateEvent(feature.id, FeatureHealthStatus.WORKING, null, now))
        if (!detail.isNullOrBlank()) {
            enqueueLog(LogLevel.INFO, feature.id, detail, null)
        }
    }

    fun reportFailure(feature: AppFeature, reason: String, throwable: Throwable? = null) {
        val now = System.currentTimeMillis()
        val shortReason = reason.ifBlank { throwable?.message ?: "Operation failed" }.take(180)
        healthChannel.trySend(HealthUpdateEvent(feature.id, FeatureHealthStatus.FAILED, shortReason, now))
        enqueueLog(LogLevel.ERROR, feature.id, shortReason, throwable?.let(::stackTraceString))
    }

    fun reportNotSupported(feature: AppFeature, reason: String) {
        val now = System.currentTimeMillis()
        val shortReason = reason.take(180)
        healthChannel.trySend(HealthUpdateEvent(feature.id, FeatureHealthStatus.NOT_SUPPORTED, shortReason, now))
        enqueueLog(LogLevel.WARNING, feature.id, "Not supported: $shortReason", null)
    }

    fun reportFallback(feature: AppFeature, detail: String) {
        val now = System.currentTimeMillis()
        val shortDetail = detail.take(180)
        healthChannel.trySend(HealthUpdateEvent(feature.id, FeatureHealthStatus.FALLBACK, shortDetail, now))
        enqueueLog(LogLevel.WARNING, feature.id, shortDetail, null)
    }

    /**
     * Logs technical device audio capabilities once per app process launch:
     * Android version, device model, UNPROCESSED audio source support, input devices,
     * supported sample rates, and available MediaCodecList audio encoders.
     */
    fun logDeviceCapabilitiesOnce() {
        if (!deviceLoggedOnce.compareAndSet(false, true)) return
        scope.launch {
            runCatching {
                val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                val unprocessedSupported = audioManager
                    .getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED)
                    .equals("true", ignoreCase = true)

                val inputs = runCatching {
                    audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
                }.getOrDefault(emptyArray())

                val deviceSummary = inputs.joinToString("; ") { dev ->
                    val rates = dev.sampleRates.toList().ifEmpty { listOf(48000) }.sorted()
                    "type=${dev.type} rates=${rates.joinToString("/")}"
                }.ifBlank { "none" }

                val allRates = inputs.flatMap { it.sampleRates.toList() }.distinct().sorted()
                    .ifEmpty { listOf(48000) }

                val audioEncoders = runCatching {
                    val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
                    list.codecInfos
                        .filter { it.isEncoder }
                        .flatMap { info -> info.supportedTypes.filter { t -> t.startsWith("audio/") } }
                        .distinct()
                        .sorted()
                }.getOrDefault(emptyList())

                val summary = buildString {
                    append("Device: ${Build.MANUFACTURER} ${Build.MODEL}, ")
                    append("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}). ")
                    append("UNPROCESSED=${unprocessedSupported}. ")
                    append("SampleRates=${allRates.joinToString(",")}. ")
                    append("Inputs(${inputs.size}): $deviceSummary. ")
                    append("Encoders: ${audioEncoders.joinToString(", ")}")
                }
                enqueueLog(LogLevel.INFO, "device_info", summary, null)

                if (!unprocessedSupported) {
                    reportFallback(
                        AppFeature.RECORDING_UNPROCESSED,
                        "Fallback active (VOICE_RECOGNITION)"
                    )
                }
            }
        }
    }

    suspend fun clearLogs() = withContext(Dispatchers.IO) {
        runCatching { appLogDao.clearAll() }
    }

    suspend fun forceTrim() = withContext(Dispatchers.IO) {
        runCatching { appLogDao.trimToLatest(MAX_LOG_ENTRIES) }
    }

    fun buildSummaryLine(healthList: List<FeatureHealthEntity>): String {
        val byId = healthList.associateBy { it.featureId }
        var working = 0
        var fallback = 0
        var failed = 0
        var notSupported = 0
        AppFeature.entries.forEach { feat ->
            when (byId[feat.id]?.status ?: FeatureHealthStatus.NOT_TESTED) {
                FeatureHealthStatus.WORKING -> working++
                FeatureHealthStatus.FALLBACK -> fallback++
                FeatureHealthStatus.FAILED -> failed++
                FeatureHealthStatus.NOT_SUPPORTED -> notSupported++
                FeatureHealthStatus.NOT_TESTED -> Unit
            }
        }
        return if (fallback > 0) {
            "$working working, $fallback fallback, $failed failed, $notSupported not supported"
        } else {
            "$working working, $failed failed, $notSupported not supported"
        }
    }

    fun formatAsPlainText(
        logs: List<AppLogEntity>,
        healthList: List<FeatureHealthEntity>
    ): String {
        val dateFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
        val byId = healthList.associateBy { it.featureId }
        return buildString {
            appendLine("=== FaintRec Feature Health & Technical Event Log ===")
            appendLine("Summary: ${buildSummaryLine(healthList)}")
            appendLine("Exported: ${dateFmt.format(Date())}")
            appendLine()
            appendLine("--- Feature Health Dashboard ---")
            AppFeature.entries.forEach { feat ->
                val h = byId[feat.id]
                val st = h?.status ?: FeatureHealthStatus.NOT_TESTED
                val lastOk = h?.lastSuccessMillis?.let { dateFmt.format(Date(it)) } ?: "Never"
                val err = h?.lastErrorMessage?.let { " | Error: $it" } ?: ""
                appendLine("[${st.name}] ${feat.displayName} (Last OK: $lastOk)$err")
            }
            appendLine()
            appendLine("--- Event Log (${logs.size} entries) ---")
            logs.forEach { entry ->
                appendLine(
                    "[${dateFmt.format(Date(entry.timestampMillis))}] [${entry.level.name}] [${entry.featureTag}] ${entry.message}"
                )
                if (!entry.stackTrace.isNullOrBlank()) {
                    appendLine(entry.stackTrace)
                }
            }
        }
    }

    fun formatAsJson(
        logs: List<AppLogEntity>,
        healthList: List<FeatureHealthEntity>
    ): String {
        val byId = healthList.associateBy { it.featureId }
        val healthItems = AppFeature.entries.map { feat ->
            val h = byId[feat.id]
            FeatureHealthExportItem(
                featureId = feat.id,
                displayName = feat.displayName,
                status = h?.status ?: FeatureHealthStatus.NOT_TESTED,
                lastSuccessMillis = h?.lastSuccessMillis,
                lastErrorMessage = h?.lastErrorMessage
            )
        }
        val report = FullLogReportExport(
            exportedAtMillis = System.currentTimeMillis(),
            summary = buildSummaryLine(healthList),
            featureHealth = healthItems,
            logs = logs.map {
                AppLogExportEntry(
                    timestampMillis = it.timestampMillis,
                    level = it.level,
                    featureTag = it.featureTag,
                    message = it.message,
                    stackTrace = it.stackTrace
                )
            }
        )
        return json.encodeToString(report)
    }

    suspend fun exportLogFile(asJson: Boolean): File = withContext(Dispatchers.IO) {
        val logsSnapshot = appLogDao.getAllLogsOnce()
        val healthSnapshot = featureHealthDao.getAllOnce()
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val ext = if (asJson) "json" else "txt"
        val mime = if (asJson) "application/json" else "text/plain"
        val outFile = StorageHelper.createWorkingFile(
            context,
            FaintRecFolder.ROOT,
            "FaintRec_Log_$ts.$ext"
        )
        val content = if (asJson) {
            formatAsJson(logsSnapshot, healthSnapshot)
        } else {
            formatAsPlainText(logsSnapshot, healthSnapshot)
        }
        outFile.writeText(content)
        StorageHelper.publishToMediaStore(
            context = context,
            sourceFile = outFile,
            folder = FaintRecFolder.ROOT,
            mimeType = mime,
            displayName = outFile.name
        )
        outFile
    }

    private fun enqueueLog(
        level: LogLevel,
        featureTag: String,
        message: String,
        stackTrace: String?
    ) {
        runCatching {
            logChannel.trySend(
                AppLogEntity(
                    timestampMillis = System.currentTimeMillis(),
                    level = level,
                    featureTag = featureTag,
                    message = message.take(500),
                    stackTrace = stackTrace?.take(4000)
                )
            )
        }
    }

    private fun stackTraceString(t: Throwable): String {
        val sw = StringWriter()
        t.printStackTrace(PrintWriter(sw))
        return sw.toString()
    }
}
