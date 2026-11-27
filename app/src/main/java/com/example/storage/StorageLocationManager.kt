package com.example.storage

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.os.StatFs
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.system.Os
import com.example.audio.WavCuePoint
import com.example.audio.WavFileHelper
import com.example.data.AppFeature
import com.example.data.RecordingDao
import com.example.data.RecordingEntity
import com.example.data.RecordingSourceTag
import com.example.data.RecordingStorageType
import com.example.logging.AppLogger
import com.example.util.StorageHelper
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

data class StorageLocationInfo(
    val storageType: RecordingStorageType,
    val displayPath: String,
    val freeBytes: Long,
    val estimatedSecondsLeft: Long,
    val isAvailable: Boolean,
    val unavailableReason: String? = null,
    val isSdCardDetected: Boolean = false,
    val safTreeUri: String? = null
) {
    val isLowSpaceWarning: Boolean
        get() = isAvailable && freeBytes in 0L until StorageLocationManager.LOW_SPACE_WARNING_BYTES

    val isCriticallyFull: Boolean
        get() = isAvailable && freeBytes in 0L until StorageLocationManager.CRITICAL_STOP_BYTES
}

data class BatchMoveResult(
    val movedCount: Int,
    val skippedCount: Int,
    val failedCount: Int,
    val lastError: String? = null
)

data class FinalizedWavSummary(
    val fileSizeBytes: Long,
    val durationMs: Long,
    val sampleRate: Int,
    val channels: Int,
    val bitsPerSample: Int,
    val isFloat: Boolean
)

@Serializable
private data class ActiveRecordingJournal(
    val storageTypeName: String,
    val filePath: String,
    val contentUri: String?,
    val fileName: String,
    val sampleRate: Int,
    val channels: Int,
    val bitsPerSample: Int,
    val isFloat: Boolean,
    val startedAtMillis: Long,
    val deviceName: String,
    val sourceLabel: String,
    val saveRaw: Boolean,
    val gainDb: Float
)

/**
 * Direct-to-destination WAV stream writer backed by a seekable FileChannel on either
 * app-private internal storage, public MediaStore Documents/FaintRec, or an external SD SAF tree.
 */
class DirectWavTarget(
    val storageType: RecordingStorageType,
    val filePath: String,
    val contentUri: String?,
    val fileName: String,
    val channel: FileChannel,
    private val closables: List<Closeable>,
    private val onFinalizeMediaStore: () -> Unit,
    private val onDeleteEmpty: () -> Unit,
    private val onClearJournal: () -> Unit
) {
    fun writePlaceholderHeader(sampleRate: Int, channels: Int, bitsPerSample: Int, isFloat: Boolean) {
        WavFileHelper.writePlaceholderHeader(channel, sampleRate, channels, bitsPerSample, isFloat)
    }

    fun writeFloatSamples(samples: FloatArray, sampleCount: Int, writeAsFloat32: Boolean): Long {
        return WavFileHelper.writeFloatSamples(channel, samples, sampleCount, writeAsFloat32)
    }

    fun patchHeaderPeriodic(dataBytes: Long) {
        runCatching {
            val savedPos = channel.position()
            WavFileHelper.patchWavHeaderSizes(channel, dataBytes)
            channel.position(savedPos)
        }
    }

    fun finalizeAndClose(
        dataBytes: Long,
        cues: List<WavCuePoint>,
        fallbackSampleRate: Int,
        fallbackChannels: Int,
        fallbackIsFloat: Boolean
    ): FinalizedWavSummary? {
        if (dataBytes <= 0L) {
            abortAndDelete()
            return null
        }
        return try {
            WavFileHelper.patchWavHeaderSizes(channel, dataBytes)
            if (cues.isNotEmpty()) {
                WavFileHelper.appendCueChunks(channel, cues)
            }
            channel.force(true)
            val info = WavFileHelper.parseWavInfo(channel)
            val totalSize = runCatching { channel.size() }.getOrDefault(dataBytes + 44L)
            closeHandles()
            onFinalizeMediaStore()
            onClearJournal()
            val sr = info?.sampleRate ?: fallbackSampleRate
            val ch = info?.channels ?: fallbackChannels
            val bps = info?.bitsPerSample ?: if (fallbackIsFloat) 32 else 16
            val isFlt = info?.isFloat ?: fallbackIsFloat
            val frameBytes = ((bps / 8) * ch).coerceAtLeast(1)
            val durMs = info?.durationMs ?: ((dataBytes / frameBytes) * 1000L / sr.coerceAtLeast(1))
            FinalizedWavSummary(
                fileSizeBytes = totalSize,
                durationMs = durMs,
                sampleRate = sr,
                channels = ch,
                bitsPerSample = bps,
                isFloat = isFlt
            )
        } catch (e: Exception) {
            closeHandles()
            throw e
        }
    }

    fun abortAndDelete() {
        closeHandles()
        runCatching { onDeleteEmpty() }
        onClearJournal()
    }

    private fun closeHandles() {
        closables.forEach { c -> runCatching { c.close() } }
    }
}

@Singleton
class StorageLocationManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val recordingDao: RecordingDao,
    private val logger: AppLogger
) {
    companion object {
        const val LOW_SPACE_WARNING_BYTES = 200L * 1024L * 1024L // 200 MB
        const val CRITICAL_STOP_BYTES = 25L * 1024L * 1024L      // 25 MB safety floor
        private const val PREFS_RECOVERY = "faintrec_recovery_journal"
        private const val KEY_ACTIVE_JOURNAL = "active_recording_json"
        private const val PRIVATE_RECORDINGS_DIR = "recordings"

        fun estimateRecordingSecondsLeft(
            freeBytes: Long,
            sampleRate: Int,
            bitsPerSample: Int,
            channels: Int
        ): Long {
            val usable = (freeBytes - CRITICAL_STOP_BYTES).coerceAtLeast(0L)
            val bytesPerSecond = sampleRate.coerceAtLeast(8000).toLong() *
                (bitsPerSample.coerceAtLeast(8) / 8L) *
                channels.coerceIn(1, 2).toLong()
            if (bytesPerSecond <= 0L) return 0L
            return usable / bytesPerSecond
        }

        fun formatEstimatedDuration(totalSeconds: Long): String {
            if (totalSeconds <= 0L) return "0m"
            val hours = totalSeconds / 3600L
            val mins = (totalSeconds % 3600L) / 60L
            val secs = totalSeconds % 60L
            return when {
                hours >= 24L -> "${hours}h ${mins}m"
                hours > 0L -> "${hours}h ${mins}m"
                mins > 0L -> "${mins}m ${secs}s"
                else -> "${secs}s"
            }
        }
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val recoveryPrefs by lazy {
        context.getSharedPreferences(PREFS_RECOVERY, Context.MODE_PRIVATE)
    }

    fun getAppPrivateRecordingsDir(): File {
        return File(context.filesDir, PRIVATE_RECORDINGS_DIR).apply {
            if (!exists()) mkdirs()
        }
    }

    /**
     * Detects whether a removable SD card or external storage volume is currently mounted.
     */
    fun isRemovableSdCardAvailable(): Boolean {
        val sm = context.getSystemService(Context.STORAGE_SERVICE) as? StorageManager
        val volumeMounted = runCatching {
            sm?.storageVolumes?.any { vol ->
                vol.isRemovable && vol.state == Environment.MEDIA_MOUNTED
            } == true
        }.getOrDefault(false)
        if (volumeMounted) return true

        return runCatching {
            context.getExternalFilesDirs(null)
                .drop(1)
                .any { dir -> dir != null && Environment.getExternalStorageState(dir) == Environment.MEDIA_MOUNTED }
        }.getOrDefault(false)
    }

    /**
     * Checks whether the persisted SAF tree URI still has valid read+write permissions
     * and the underlying SD card / external volume is still mounted and reachable.
     */
    fun hasValidSafPermission(safTreeUriStr: String?): Boolean {
        if (safTreeUriStr.isNullOrBlank()) return false
        val uri = runCatching { Uri.parse(safTreeUriStr) }.getOrNull() ?: return false
        val persisted = context.contentResolver.persistedUriPermissions.any { perm ->
            perm.uri == uri && perm.isReadPermission && perm.isWritePermission
        }
        if (!persisted) return false

        // Probe the tree document to verify the SD card was not physically removed
        return runCatching {
            val docId = DocumentsContract.getTreeDocumentId(uri)
            val docUri = DocumentsContract.buildDocumentUriUsingTree(uri, docId)
            context.contentResolver.query(
                docUri,
                arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID),
                null,
                null,
                null
            )?.use { cursor -> cursor.moveToFirst() } == true
        }.getOrDefault(false)
    }

    /**
     * Persists read/write SAF permissions after the user selects an SD card / external folder.
     */
    fun takePersistableSafPermission(treeUri: Uri): Result<String> {
        return runCatching {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            context.contentResolver.takePersistableUriPermission(treeUri, flags)
            val display = resolveSafDisplayPath(treeUri)
            logger.info("storage", "Persisted SAF folder permission: $display")
            display
        }.onFailure { err ->
            logger.error("storage", "Failed to persist SAF folder permission: ${err.message}", err)
        }
    }

    fun resolveSafDisplayPath(treeUri: Uri): String {
        val rawId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull()
        if (rawId.isNullOrBlank()) return treeUri.lastPathSegment ?: "External Folder"
        return if (rawId.startsWith("primary:")) {
            "Internal/${rawId.removePrefix("primary:")}"
        } else {
            val parts = rawId.split(":", limit = 2)
            val vol = parts.getOrNull(0).orEmpty()
            val rel = parts.getOrNull(1).orEmpty().trimStart('/')
            if (rel.isBlank()) "SD ($vol)" else "SD ($vol)/$rel"
        }
    }

    /**
     * Computes live path, availability, free space, and estimated recording time left
     * for the selected storage location and current capture format.
     */
    fun getStorageInfo(
        storageType: RecordingStorageType,
        safTreeUri: String?,
        safDisplayPath: String?,
        sampleRate: Int = 48000,
        preferFloatPcm: Boolean = true,
        channels: Int = 1
    ): StorageLocationInfo {
        val sdDetected = isRemovableSdCardAvailable()
        val bitsPerSample = if (preferFloatPcm) 32 else 16

        return when (storageType) {
            RecordingStorageType.APP_PRIVATE -> {
                val dir = getAppPrivateRecordingsDir()
                val free = statFsAvailableBytes(dir.absolutePath)
                val est = estimateRecordingSecondsLeft(free, sampleRate, bitsPerSample, channels)
                StorageLocationInfo(
                    storageType = storageType,
                    displayPath = "Internal (Private)/files/$PRIVATE_RECORDINGS_DIR",
                    freeBytes = free,
                    estimatedSecondsLeft = est,
                    isAvailable = free >= 0L,
                    unavailableReason = if (free < 0L) "Internal storage unavailable." else null,
                    isSdCardDetected = sdDetected,
                    safTreeUri = safTreeUri
                )
            }
            RecordingStorageType.PUBLIC_DOCUMENTS -> {
                val path = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    "Internal Storage/Documents/FaintRec"
                } else {
                    "Documents/FaintRec"
                }
                val probeDir = runCatching {
                    Environment.getExternalStorageDirectory()?.absolutePath
                }.getOrNull() ?: context.filesDir.absolutePath
                val free = statFsAvailableBytes(probeDir)
                val est = estimateRecordingSecondsLeft(free, sampleRate, bitsPerSample, channels)
                StorageLocationInfo(
                    storageType = storageType,
                    displayPath = path,
                    freeBytes = free,
                    estimatedSecondsLeft = est,
                    isAvailable = free >= 0L,
                    unavailableReason = if (free < 0L) "Public internal storage unavailable." else null,
                    isSdCardDetected = sdDetected,
                    safTreeUri = safTreeUri
                )
            }
            RecordingStorageType.EXTERNAL_SD_SAF -> {
                val label = safDisplayPath?.takeIf { it.isNotBlank() }
                    ?: safTreeUri?.let { runCatching { resolveSafDisplayPath(Uri.parse(it)) }.getOrNull() }
                    ?: "SD Card (Not selected)"
                if (safTreeUri.isNullOrBlank()) {
                    StorageLocationInfo(
                        storageType = storageType,
                        displayPath = label,
                        freeBytes = 0L,
                        estimatedSecondsLeft = 0L,
                        isAvailable = false,
                        unavailableReason = "Pick a folder on your SD card first.",
                        isSdCardDetected = sdDetected,
                        safTreeUri = null
                    )
                } else if (!hasValidSafPermission(safTreeUri)) {
                    val reason = if (!sdDetected) {
                        "SD card removed or unmounted. Re-insert SD card or switch save location."
                    } else {
                        "Folder access permission lost. Please pick the SD card folder again."
                    }
                    StorageLocationInfo(
                        storageType = storageType,
                        displayPath = label,
                        freeBytes = 0L,
                        estimatedSecondsLeft = 0L,
                        isAvailable = false,
                        unavailableReason = reason,
                        isSdCardDetected = sdDetected,
                        safTreeUri = safTreeUri
                    )
                } else {
                    val free = querySafAvailableBytes(safTreeUri)
                    val est = estimateRecordingSecondsLeft(free, sampleRate, bitsPerSample, channels)
                    StorageLocationInfo(
                        storageType = storageType,
                        displayPath = label,
                        freeBytes = free,
                        estimatedSecondsLeft = est,
                        isAvailable = true,
                        unavailableReason = null,
                        isSdCardDetected = sdDetected,
                        safTreeUri = safTreeUri
                    )
                }
            }
        }
    }

    /**
     * Quick free-space probe for the active storage type, safe to call periodically while recording.
     */
    fun getCurrentFreeBytes(storageType: RecordingStorageType, safTreeUri: String?): Long {
        return when (storageType) {
            RecordingStorageType.APP_PRIVATE -> statFsAvailableBytes(getAppPrivateRecordingsDir().absolutePath)
            RecordingStorageType.PUBLIC_DOCUMENTS -> {
                val probe = runCatching { Environment.getExternalStorageDirectory()?.absolutePath }.getOrNull()
                    ?: context.filesDir.absolutePath
                statFsAvailableBytes(probe)
            }
            RecordingStorageType.EXTERNAL_SD_SAF -> {
                if (!hasValidSafPermission(safTreeUri)) -1L
                else querySafAvailableBytes(safTreeUri!!)
            }
        }
    }

    /**
     * Opens a direct-to-destination WAV target (no temporary cache staging) and records
     * a crash-recovery journal entry so unfinished WAV headers can be repaired on next launch.
     */
    fun openDirectWavTarget(
        storageType: RecordingStorageType,
        safTreeUri: String?,
        fileName: String,
        sampleRate: Int,
        channels: Int,
        bitsPerSample: Int,
        isFloat: Boolean,
        deviceName: String,
        sourceLabel: String,
        saveRaw: Boolean,
        gainDb: Float
    ): Result<DirectWavTarget> {
        val cleanName = StorageHelper.sanitizeFileName(fileName)
        return runCatching {
            val target = when (storageType) {
                RecordingStorageType.APP_PRIVATE -> {
                    val dir = getAppPrivateRecordingsDir()
                    val file = File(dir, cleanName)
                    val raf = RandomAccessFile(file, "rw")
                    raf.setLength(0L)
                    DirectWavTarget(
                        storageType = RecordingStorageType.APP_PRIVATE,
                        filePath = file.absolutePath,
                        contentUri = null,
                        fileName = file.name,
                        channel = raf.channel,
                        closables = listOf(raf),
                        onFinalizeMediaStore = {},
                        onDeleteEmpty = { file.delete() },
                        onClearJournal = { clearActiveJournal() }
                    )
                }

                RecordingStorageType.PUBLIC_DOCUMENTS -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        val values = ContentValues().apply {
                            put(MediaStore.MediaColumns.DISPLAY_NAME, cleanName)
                            put(MediaStore.MediaColumns.MIME_TYPE, "audio/wav")
                            put(MediaStore.MediaColumns.RELATIVE_PATH, "Documents/FaintRec")
                            put(MediaStore.MediaColumns.IS_PENDING, 1)
                        }
                        val resolver = context.contentResolver
                        val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                        val uri = resolver.insert(collection, values)
                            ?: throw IllegalStateException("MediaStore refused to create file in Documents/FaintRec.")
                        val pfd = resolver.openFileDescriptor(uri, "rw")
                            ?: run {
                                runCatching { resolver.delete(uri, null, null) }
                                throw IllegalStateException("Unable to open direct write descriptor for Documents/FaintRec.")
                            }
                        val fos = FileOutputStream(pfd.fileDescriptor)
                        DirectWavTarget(
                            storageType = RecordingStorageType.PUBLIC_DOCUMENTS,
                            filePath = "",
                            contentUri = uri.toString(),
                            fileName = cleanName,
                            channel = fos.channel,
                            closables = listOf(fos, pfd),
                            onFinalizeMediaStore = {
                                val done = ContentValues().apply {
                                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                                }
                                runCatching { resolver.update(uri, done, null, null) }
                            },
                            onDeleteEmpty = {
                                runCatching { resolver.delete(uri, null, null) }
                            },
                            onClearJournal = { clearActiveJournal() }
                        )
                    } else {
                        val extDir = File(
                            context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS),
                            "FaintRec"
                        ).apply { if (!exists()) mkdirs() }
                        val file = File(extDir, cleanName)
                        val raf = RandomAccessFile(file, "rw")
                        raf.setLength(0L)
                        DirectWavTarget(
                            storageType = RecordingStorageType.PUBLIC_DOCUMENTS,
                            filePath = file.absolutePath,
                            contentUri = Uri.fromFile(file).toString(),
                            fileName = file.name,
                            channel = raf.channel,
                            closables = listOf(raf),
                            onFinalizeMediaStore = {},
                            onDeleteEmpty = { file.delete() },
                            onClearJournal = { clearActiveJournal() }
                        )
                    }
                }

                RecordingStorageType.EXTERNAL_SD_SAF -> {
                    if (safTreeUri.isNullOrBlank()) {
                        throw IllegalStateException("No SD card / external folder selected.")
                    }
                    if (!hasValidSafPermission(safTreeUri)) {
                        throw SecurityException("SD card unavailable or folder permission lost. Re-select folder in Settings.")
                    }
                    val treeUri = Uri.parse(safTreeUri)
                    val parentDocId = DocumentsContract.getTreeDocumentId(treeUri)
                    val parentDocUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, parentDocId)
                    val docUri = DocumentsContract.createDocument(
                        context.contentResolver,
                        parentDocUri,
                        "audio/wav",
                        cleanName
                    ) ?: throw IllegalStateException("Could not create WAV file on SD card (storage may be read-only or removed).")

                    val pfd = context.contentResolver.openFileDescriptor(docUri, "rw")
                        ?: run {
                            runCatching { DocumentsContract.deleteDocument(context.contentResolver, docUri) }
                            throw IllegalStateException("Unable to open direct write stream on SD card.")
                        }
                    val fos = FileOutputStream(pfd.fileDescriptor)
                    DirectWavTarget(
                        storageType = RecordingStorageType.EXTERNAL_SD_SAF,
                        filePath = "",
                        contentUri = docUri.toString(),
                        fileName = cleanName,
                        channel = fos.channel,
                        closables = listOf(fos, pfd),
                        onFinalizeMediaStore = {},
                        onDeleteEmpty = {
                            runCatching { DocumentsContract.deleteDocument(context.contentResolver, docUri) }
                        },
                        onClearJournal = { clearActiveJournal() }
                    )
                }
            }

            saveActiveJournal(
                ActiveRecordingJournal(
                    storageTypeName = target.storageType.name,
                    filePath = target.filePath,
                    contentUri = target.contentUri,
                    fileName = target.fileName,
                    sampleRate = sampleRate,
                    channels = channels,
                    bitsPerSample = bitsPerSample,
                    isFloat = isFloat,
                    startedAtMillis = System.currentTimeMillis(),
                    deviceName = deviceName,
                    sourceLabel = sourceLabel,
                    saveRaw = saveRaw,
                    gainDb = gainDb
                )
            )
            target
        }.onFailure { err ->
            logger.reportFailure(AppFeature.WAV_SAVING, "Open direct WAV target ($storageType) failed: ${err.message}", err)
        }
    }

    /**
     * Startup crash/service-kill recovery step:
     * 1) Repairs any active recording recorded in the recovery journal whose WAV header was left unfinalized.
     * 2) Scans app-private recordings folder for any orphaned unfinalized WAV files and repairs/registers them.
     * Returns the number of recordings recovered.
     */
    suspend fun recoverInterruptedRecordings(): Int = withContext(Dispatchers.IO) {
        var recoveredCount = 0

        // Step 1: Check active recording journal (covers Private, Public MediaStore, and SD Card SAF!)
        val rawJournal = recoveryPrefs.getString(KEY_ACTIVE_JOURNAL, null)
        if (!rawJournal.isNullOrBlank()) {
            val entry = runCatching { json.decodeFromString<ActiveRecordingJournal>(rawJournal) }.getOrNull()
            if (entry != null) {
                val storageType = RecordingStorageType.entries.firstOrNull { it.name == entry.storageTypeName }
                    ?: RecordingStorageType.APP_PRIVATE
                val repaired = recoverSingleJournalEntry(entry, storageType)
                if (repaired) recoveredCount++
            }
            clearActiveJournal()
        }

        // Step 2: Scan app-private recordings folder for any other unfinalized WAVs
        val privateDir = getAppPrivateRecordingsDir()
        val existingPaths = recordingDao.getAllRecordingsOnce().map { it.filePath }.toSet()
        privateDir.listFiles { f -> f.isFile && f.extension.equals("wav", ignoreCase = true) && f.length() > 44L }
            ?.forEach { file ->
                runCatching {
                    var wasRepaired = false
                    RandomAccessFile(file, "rw").use { raf ->
                        val repairedBytes = WavFileHelper.repairWavHeaderIfNeeded(raf.channel)
                        if (repairedBytes != null) {
                            wasRepaired = true
                            logger.warn("storage_recovery", "Repaired interrupted WAV header: ${file.name} ($repairedBytes B)")
                        }
                    }
                    if (wasRepaired || file.absolutePath !in existingPaths) {
                        val info = WavFileHelper.parseWavInfo(file)
                        if (info != null && info.totalFrames > 0L && file.absolutePath !in existingPaths) {
                            recordingDao.insert(
                                RecordingEntity(
                                    title = "${file.nameWithoutExtension} (Recovered)",
                                    filePath = file.absolutePath,
                                    mediaStoreUri = null,
                                    durationMs = info.durationMs,
                                    fileSizeBytes = file.length(),
                                    createdAtMillis = file.lastModified(),
                                    format = "WAV",
                                    sampleRate = info.sampleRate,
                                    channels = info.channels,
                                    bitsPerSample = info.bitsPerSample,
                                    isFloatPcm = info.isFloat,
                                    sourceTag = RecordingSourceTag.RECORDED,
                                    storageType = RecordingStorageType.APP_PRIVATE,
                                    storageUri = null
                                )
                            )
                            recoveredCount++
                        }
                    }
                }.onFailure { err ->
                    logger.error("storage_recovery", "Scan recovery error for ${file.name}: ${err.message}", err)
                }
            }

        if (recoveredCount > 0) {
            logger.info("storage_recovery", "Recovered $recoveredCount interrupted recording(s) on launch")
        }
        recoveredCount
    }

    private suspend fun recoverSingleJournalEntry(
        entry: ActiveRecordingJournal,
        storageType: RecordingStorageType
    ): Boolean {
        return runCatching {
            val allExisting = recordingDao.getAllRecordingsOnce()
            val alreadyTracked = allExisting.any {
                (entry.filePath.isNotBlank() && it.filePath == entry.filePath) ||
                    (!entry.contentUri.isNullOrBlank() && (it.storageUri == entry.contentUri || it.mediaStoreUri == entry.contentUri))
            }

            var fileSize = 0L
            var parsedInfo: com.example.audio.WavInfo? = null

            if (entry.filePath.isNotBlank()) {
                val f = File(entry.filePath)
                if (!f.exists() || f.length() <= 44L) return false
                RandomAccessFile(f, "rw").use { raf ->
                    WavFileHelper.repairWavHeaderIfNeeded(raf.channel)
                    fileSize = raf.length()
                    parsedInfo = WavFileHelper.parseWavInfo(raf.channel)
                }
            } else if (!entry.contentUri.isNullOrBlank()) {
                val uri = Uri.parse(entry.contentUri)
                // Clear MediaStore IS_PENDING first if applicable so it becomes accessible
                if (storageType == RecordingStorageType.PUBLIC_DOCUMENTS && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    runCatching {
                        val cv = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                        context.contentResolver.update(uri, cv, null, null)
                    }
                }
                context.contentResolver.openFileDescriptor(uri, "rw")?.use { pfd ->
                    FileOutputStream(pfd.fileDescriptor).use { fos ->
                        val ch = fos.channel
                        if (ch.size() <= 44L) return false
                        WavFileHelper.repairWavHeaderIfNeeded(ch)
                        fileSize = ch.size()
                        parsedInfo = WavFileHelper.parseWavInfo(ch)
                    }
                }
            } else {
                return false
            }

            val info = parsedInfo ?: return false
            if (!alreadyTracked && info.totalFrames > 0L) {
                recordingDao.insert(
                    RecordingEntity(
                        title = "${entry.fileName.substringBeforeLast('.')} (Recovered)",
                        filePath = entry.filePath,
                        mediaStoreUri = if (storageType == RecordingStorageType.PUBLIC_DOCUMENTS) entry.contentUri else null,
                        durationMs = info.durationMs,
                        fileSizeBytes = fileSize,
                        createdAtMillis = entry.startedAtMillis,
                        format = "WAV",
                        sampleRate = info.sampleRate,
                        channels = info.channels,
                        bitsPerSample = info.bitsPerSample,
                        isFloatPcm = info.isFloat,
                        sourceTag = RecordingSourceTag.RECORDED,
                        audioSourceUsed = entry.sourceLabel,
                        inputDeviceName = entry.deviceName,
                        gainAppliedDb = if (entry.saveRaw) 0f else entry.gainDb,
                        savedRaw = entry.saveRaw,
                        storageType = storageType,
                        storageUri = entry.contentUri
                    )
                )
                logger.warn("storage_recovery", "Repaired & recovered interrupted recording (${storageType.name}): ${entry.fileName}")
                true
            } else {
                false
            }
        }.onFailure { err ->
            logger.error("storage_recovery", "Failed to recover ${entry.fileName}: ${err.message}", err)
        }.getOrDefault(false)
    }

    /**
     * Safely moves or copies a recording between App-Private, Public Documents/FaintRec,
     * and External SD Card (SAF) locations with byte-count verification before removing the source.
     */
    suspend fun moveOrCopyRecording(
        recording: RecordingEntity,
        targetType: RecordingStorageType,
        safTreeUri: String?,
        isMove: Boolean,
        onProgress: (Float) -> Unit = {}
    ): Result<RecordingEntity> = withContext(Dispatchers.IO) {
        runCatching {
            if (isMove && recording.storageType == targetType) {
                return@runCatching recording
            }

            // 1) Verify destination is available and has enough space
            val freeDest = getCurrentFreeBytes(targetType, safTreeUri)
            if (freeDest < 0L) {
                val msg = if (targetType == RecordingStorageType.EXTERNAL_SD_SAF) {
                    "SD card unavailable or folder permission lost."
                } else {
                    "Destination storage is currently unavailable."
                }
                throw IllegalStateException(msg)
            }
            val srcSize = resolveSourceByteSize(recording)
            if (srcSize <= 0L) {
                throw IllegalStateException("Source recording file is missing or unreadable.")
            }
            if (freeDest < srcSize + CRITICAL_STOP_BYTES) {
                throw IllegalStateException("Not enough free space at destination (need ${StorageHelper.formatSizeBytes(srcSize + CRITICAL_STOP_BYTES)}).")
            }

            val ext = resolveExtension(recording)
            val mime = StorageHelper.mimeTypeForExtension(ext)
            val baseSafe = StorageHelper.sanitizeFileName(recording.title).substringBeforeLast('.')
            val destFileName = if (isMove) {
                "${baseSafe}.${ext}"
            } else {
                "${baseSafe}_copy_${System.currentTimeMillis() % 10000}.${ext}"
            }

            // 2) Stream source -> destination directly and verify written byte count
            val (newFilePath, newContentUri, writtenBytes) = when (targetType) {
                RecordingStorageType.APP_PRIVATE -> {
                    val dir = getAppPrivateRecordingsDir()
                    var destFile = File(dir, destFileName)
                    if (destFile.exists()) {
                        destFile = File(dir, "${baseSafe}_${System.currentTimeMillis() % 100000}.$ext")
                    }
                    val copied = openSourceInputStream(recording).use { input ->
                        FileOutputStream(destFile).use { output ->
                            copyWithProgress(input, output, srcSize, onProgress)
                        }
                    }
                    if (copied != srcSize || !destFile.exists() || destFile.length() != srcSize) {
                        destFile.delete()
                        throw IllegalStateException("Verification failed after copy (expected $srcSize B, wrote $copied B).")
                    }
                    Triple(destFile.absolutePath, null as String?, copied)
                }

                RecordingStorageType.PUBLIC_DOCUMENTS -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        val values = ContentValues().apply {
                            put(MediaStore.MediaColumns.DISPLAY_NAME, destFileName)
                            put(MediaStore.MediaColumns.MIME_TYPE, mime)
                            put(MediaStore.MediaColumns.RELATIVE_PATH, "Documents/FaintRec")
                            put(MediaStore.MediaColumns.IS_PENDING, 1)
                        }
                        val resolver = context.contentResolver
                        val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                        val destUri = resolver.insert(collection, values)
                            ?: throw IllegalStateException("Could not create destination file in Documents/FaintRec.")
                        val copied = try {
                            openSourceInputStream(recording).use { input ->
                                (resolver.openOutputStream(destUri)
                                    ?: throw IllegalStateException("Could not open MediaStore output stream.")).use { output ->
                                    copyWithProgress(input, output, srcSize, onProgress)
                                }
                            }
                        } catch (e: Exception) {
                            runCatching { resolver.delete(destUri, null, null) }
                            throw e
                        }
                        if (copied != srcSize) {
                            runCatching { resolver.delete(destUri, null, null) }
                            throw IllegalStateException("Verification failed (expected $srcSize B, wrote $copied B).")
                        }
                        val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                        resolver.update(destUri, done, null, null)
                        Triple("", destUri.toString(), copied)
                    } else {
                        val extDir = File(
                            context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS),
                            "FaintRec"
                        ).apply { if (!exists()) mkdirs() }
                        val destFile = File(extDir, destFileName)
                        val copied = openSourceInputStream(recording).use { input ->
                            FileOutputStream(destFile).use { output ->
                                copyWithProgress(input, output, srcSize, onProgress)
                            }
                        }
                        if (copied != srcSize) {
                            destFile.delete()
                            throw IllegalStateException("Verification failed (expected $srcSize B, wrote $copied B).")
                        }
                        Triple(destFile.absolutePath, Uri.fromFile(destFile).toString(), copied)
                    }
                }

                RecordingStorageType.EXTERNAL_SD_SAF -> {
                    if (safTreeUri.isNullOrBlank() || !hasValidSafPermission(safTreeUri)) {
                        throw SecurityException("SD card folder permission missing or SD card removed.")
                    }
                    val treeUri = Uri.parse(safTreeUri)
                    val parentDocUri = DocumentsContract.buildDocumentUriUsingTree(
                        treeUri,
                        DocumentsContract.getTreeDocumentId(treeUri)
                    )
                    val destUri = DocumentsContract.createDocument(
                        context.contentResolver,
                        parentDocUri,
                        mime,
                        destFileName
                    ) ?: throw IllegalStateException("Failed to create file on SD card.")

                    val copied = try {
                        openSourceInputStream(recording).use { input ->
                            (context.contentResolver.openOutputStream(destUri)
                                ?: throw IllegalStateException("Cannot write to SD card file.")).use { output ->
                                copyWithProgress(input, output, srcSize, onProgress)
                            }
                        }
                    } catch (e: Exception) {
                        runCatching { DocumentsContract.deleteDocument(context.contentResolver, destUri) }
                        throw e
                    }
                    if (copied != srcSize) {
                        runCatching { DocumentsContract.deleteDocument(context.contentResolver, destUri) }
                        throw IllegalStateException("Verification failed on SD card (expected $srcSize B, wrote $copied B).")
                    }
                    Triple("", destUri.toString(), copied)
                }
            }

            // 3) Commit Room metadata FIRST, then delete old source on Move (zero data-loss ordering!)
            val resultEntity = if (isMove) {
                val updated = recording.copy(
                    filePath = newFilePath,
                    mediaStoreUri = if (targetType == RecordingStorageType.PUBLIC_DOCUMENTS) newContentUri else null,
                    storageType = targetType,
                    storageUri = newContentUri,
                    fileSizeBytes = writtenBytes
                )
                recordingDao.update(updated)
                deleteOldSourceOnly(recording, keepNewPath = newFilePath, keepNewUri = newContentUri)
                clearStreamCacheFor(recording.id)
                logger.info(
                    "storage",
                    "Moved recording #${recording.id} from ${recording.storageType.name} -> ${targetType.name} ($writtenBytes B)"
                )
                updated
            } else {
                val copiedEntity = recording.copy(
                    id = 0L,
                    title = "${recording.title} (Copy)",
                    filePath = newFilePath,
                    mediaStoreUri = if (targetType == RecordingStorageType.PUBLIC_DOCUMENTS) newContentUri else null,
                    storageType = targetType,
                    storageUri = newContentUri,
                    fileSizeBytes = writtenBytes,
                    createdAtMillis = System.currentTimeMillis()
                )
                val newId = recordingDao.insert(copiedEntity)
                logger.info(
                    "storage",
                    "Copied recording #${recording.id} -> #$newId in ${targetType.name} ($writtenBytes B)"
                )
                copiedEntity.copy(id = newId)
            }
            onProgress(1f)
            resultEntity
        }.onFailure { err ->
            logger.error("storage", "${if (isMove) "Move" else "Copy"} recording #${recording.id} failed: ${err.message}", err)
        }
    }

    /**
     * Moves all existing recordings that are not yet in [targetType] to [targetType].
     */
    suspend fun moveAllRecordingsTo(
        targetType: RecordingStorageType,
        safTreeUri: String?,
        onProgress: (done: Int, total: Int, currentTitle: String) -> Unit
    ): BatchMoveResult = withContext(Dispatchers.IO) {
        val all = recordingDao.getAllRecordingsOnce()
        val toMove = all.filter { it.storageType != targetType }
        val skipped = all.size - toMove.size
        var moved = 0
        var failed = 0
        var lastError: String? = null

        toMove.forEachIndexed { idx, rec ->
            coroutineContext.ensureActive()
            onProgress(idx, toMove.size, rec.title)
            val res = moveOrCopyRecording(
                recording = rec,
                targetType = targetType,
                safTreeUri = safTreeUri,
                isMove = true
            )
            if (res.isSuccess) {
                moved++
            } else {
                failed++
                lastError = res.exceptionOrNull()?.message ?: "Move failed"
            }
        }
        onProgress(toMove.size, toMove.size, "Done")
        BatchMoveResult(moved, skipped, failed, lastError)
    }

    /**
     * Resolves a readable local [File] for tools requiring RandomAccessFile (reverse playback,
     * editor, spectrogram, converter). Returns direct file immediately for APP_PRIVATE/legacy files,
     * or materializes a read-only cache view for pure MediaStore/SAF recordings.
     */
    fun resolveReadableFile(recording: RecordingEntity): File? {
        if (recording.filePath.isNotBlank()) {
            val direct = File(recording.filePath)
            if (direct.exists() && direct.length() > 0L) return direct
        }
        val uriStr = recording.storageUri ?: recording.mediaStoreUri ?: return null
        val uri = runCatching { Uri.parse(uriStr) }.getOrNull() ?: return null
        val cacheDir = File(context.cacheDir, "stream_cache").apply { if (!exists()) mkdirs() }
        val ext = resolveExtension(recording)
        val cached = File(cacheDir, "rec_${recording.id}_${recording.fileSizeBytes}.$ext")
        if (cached.exists() && cached.length() > 0L) return cached

        return runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(cached).use { out ->
                    input.copyTo(out, bufferSize = 65536)
                }
            } ?: return null
            cached.takeIf { it.exists() && it.length() > 0L }
        }.onFailure { err ->
            cached.delete()
            logger.error("storage", "Unable to read recording #${recording.id} from ${recording.storageType}: ${err.message}", err)
        }.getOrNull()
    }

    /**
     * Returns the direct playback Uri (file:// or content://) for ExoPlayer without copying.
     */
    fun resolvePlaybackUri(recording: RecordingEntity): Uri? {
        if (recording.filePath.isNotBlank()) {
            val f = File(recording.filePath)
            if (f.exists()) return Uri.fromFile(f)
        }
        val uriStr = recording.storageUri ?: recording.mediaStoreUri ?: return null
        return runCatching { Uri.parse(uriStr) }.getOrNull()
    }

    /**
     * Returns a shareable Uri (content:// directly for MediaStore/SAF, FileProvider for APP_PRIVATE).
     */
    fun resolveShareUri(recording: RecordingEntity): Uri? {
        val uriStr = recording.storageUri ?: recording.mediaStoreUri
        if (!uriStr.isNullOrBlank()) {
            val u = runCatching { Uri.parse(uriStr) }.getOrNull()
            if (u?.scheme == "content") return u
        }
        if (recording.filePath.isNotBlank()) {
            val f = File(recording.filePath)
            if (f.exists()) return StorageHelper.getShareUri(context, f)
        }
        return null
    }

    /**
     * Deletes the underlying file or content URI across any storage type.
     */
    fun deleteRecordingBackingData(recording: RecordingEntity) {
        if (recording.filePath.isNotBlank()) {
            runCatching { File(recording.filePath).delete() }
        }
        val uris = listOfNotNull(recording.storageUri, recording.mediaStoreUri).distinct()
        uris.forEach { uriStr ->
            deleteContentUri(uriStr, recording.storageType)
        }
        clearStreamCacheFor(recording.id)
    }

    private fun deleteOldSourceOnly(old: RecordingEntity, keepNewPath: String, keepNewUri: String?) {
        if (old.filePath.isNotBlank() && old.filePath != keepNewPath) {
            runCatching { File(old.filePath).delete() }
        }
        val oldUris = listOfNotNull(old.storageUri, old.mediaStoreUri).distinct()
        oldUris.forEach { uriStr ->
            if (uriStr != keepNewUri) {
                deleteContentUri(uriStr, old.storageType)
            }
        }
    }

    private fun deleteContentUri(uriStr: String, storageType: RecordingStorageType) {
        runCatching {
            val uri = Uri.parse(uriStr)
            when {
                uri.scheme == "file" && uri.path != null -> File(uri.path!!).delete()
                storageType == RecordingStorageType.EXTERNAL_SD_SAF && uri.scheme == "content" -> {
                    DocumentsContract.deleteDocument(context.contentResolver, uri)
                }
                uri.scheme == "content" -> {
                    context.contentResolver.delete(uri, null, null)
                }
            }
        }
    }

    private fun clearStreamCacheFor(recordingId: Long) {
        val cacheDir = File(context.cacheDir, "stream_cache")
        cacheDir.listFiles { f -> f.name.startsWith("rec_${recordingId}_") }
            ?.forEach { runCatching { it.delete() } }
    }

    private fun openSourceInputStream(recording: RecordingEntity): InputStream {
        if (recording.filePath.isNotBlank()) {
            val f = File(recording.filePath)
            if (f.exists()) return FileInputStream(f)
        }
        val uriStr = recording.storageUri ?: recording.mediaStoreUri
            ?: throw IllegalStateException("Recording has no file path or content URI.")
        val uri = Uri.parse(uriStr)
        return context.contentResolver.openInputStream(uri)
            ?: throw IllegalStateException("Cannot open source recording (storage unavailable or permission revoked).")
    }

    private fun resolveSourceByteSize(recording: RecordingEntity): Long {
        if (recording.filePath.isNotBlank()) {
            val f = File(recording.filePath)
            if (f.exists()) return f.length()
        }
        val uriStr = recording.storageUri ?: recording.mediaStoreUri ?: return 0L
        val uri = runCatching { Uri.parse(uriStr) }.getOrNull() ?: return 0L
        return runCatching {
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { afd ->
                if (afd.length >= 0L) afd.length else recording.fileSizeBytes
            } ?: recording.fileSizeBytes
        }.getOrDefault(recording.fileSizeBytes)
    }

    private fun resolveExtension(recording: RecordingEntity): String {
        val fromPath = recording.filePath.substringAfterLast('.', "").lowercase()
        if (fromPath.length in 2..5) return fromPath
        return when (recording.format.uppercase()) {
            "WAV" -> "wav"
            "MP3" -> "mp3"
            "M4A", "AAC" -> "m4a"
            "OGG", "OPUS" -> "ogg"
            "FLAC" -> "flac"
            else -> "wav"
        }
    }

    private suspend fun copyWithProgress(
        input: InputStream,
        output: java.io.OutputStream,
        totalBytes: Long,
        onProgress: (Float) -> Unit
    ): Long {
        val buf = ByteArray(65536)
        var total = 0L
        while (true) {
            coroutineContext.ensureActive()
            val n = input.read(buf)
            if (n <= 0) break
            output.write(buf, 0, n)
            total += n
            if (totalBytes > 0L) {
                onProgress((total.toFloat() / totalBytes).coerceIn(0f, 0.98f))
            }
        }
        output.flush()
        return total
    }

    private fun statFsAvailableBytes(path: String): Long {
        return runCatching {
            StatFs(path).availableBytes
        }.getOrDefault(-1L)
    }

    private fun querySafAvailableBytes(safTreeUriStr: String): Long {
        // 1) Best accuracy: fstatvfs on a descriptor or matching external volume directory
        val treeUri = runCatching { Uri.parse(safTreeUriStr) }.getOrNull() ?: return 0L
        val rawDocId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull().orEmpty()
        val volId = rawDocId.substringBefore(':', "")

        if (volId.equals("primary", ignoreCase = true)) {
            val primaryPath = runCatching { Environment.getExternalStorageDirectory()?.absolutePath }.getOrNull()
                ?: context.filesDir.absolutePath
            return statFsAvailableBytes(primaryPath).coerceAtLeast(0L)
        }

        // Match removable external files dir containing the volume UUID (e.g. /storage/XXXX-XXXX/...)
        val matchingExtDir = runCatching {
            context.getExternalFilesDirs(null).drop(1).firstOrNull { dir ->
                dir != null && (volId.isBlank() || dir.absolutePath.contains(volId, ignoreCase = true))
            }
        }.getOrNull()
        if (matchingExtDir != null) {
            val bytes = statFsAvailableBytes(matchingExtDir.absolutePath)
            if (bytes >= 0L) return bytes
        }

        // Fallback on Android 11+ via StorageVolume.directory
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val sm = context.getSystemService(Context.STORAGE_SERVICE) as? StorageManager
            val dir = runCatching {
                sm?.storageVolumes?.firstOrNull { v ->
                    v.isRemovable && (volId.isBlank() || v.uuid.equals(volId, ignoreCase = true))
                }?.directory
            }.getOrNull()
            if (dir != null) {
                val bytes = statFsAvailableBytes(dir.absolutePath)
                if (bytes >= 0L) return bytes
            }
        }

        // Final fallback via fstatvfs on SAF root descriptor
        return runCatching {
            val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, rawDocId)
            val pfd: ParcelFileDescriptor? = context.contentResolver.openFileDescriptor(docUri, "r")
            pfd?.use {
                val stat = Os.fstatvfs(it.fileDescriptor)
                stat.f_bavail * stat.f_bsize
            } ?: 0L
        }.getOrDefault(0L)
    }

    private fun saveActiveJournal(journal: ActiveRecordingJournal) {
        runCatching {
            recoveryPrefs.edit().putString(KEY_ACTIVE_JOURNAL, json.encodeToString(journal)).apply()
        }
    }

    private fun clearActiveJournal() {
        runCatching {
            recoveryPrefs.edit().remove(KEY_ACTIVE_JOURNAL).apply()
        }
    }
}
