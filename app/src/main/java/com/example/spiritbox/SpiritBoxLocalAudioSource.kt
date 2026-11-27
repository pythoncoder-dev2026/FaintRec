package com.example.spiritbox

import android.content.Context
import android.net.Uri
import com.example.audio.WavFileHelper
import com.example.audio.WavInfo
import com.example.data.FaintRecRepository
import com.example.data.RecordingEntity
import com.example.data.RecordingSourceTag
import com.example.data.RecordingStorageType
import com.example.storage.StorageLocationManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min
import kotlin.random.Random

data class ValidatedLocalWav(
    val recording: RecordingEntity,
    val wavInfo: WavInfo,
    val directFile: File?,
    val contentUri: Uri?
)

data class StorageScanCount(
    val totalInLibrary: Int = 0,
    val validWavCount: Int = 0,
    val skippedMissingOrCorrupt: Int = 0
)

data class LocalAudioScanReport(
    val validWavs: List<ValidatedLocalWav>,
    val totalLibraryRecordings: Int,
    val byStorageType: Map<RecordingStorageType, StorageScanCount>,
    val importedCount: StorageScanCount
) {
    fun formatPerStorageSummary(): String {
        val priv = byStorageType[RecordingStorageType.APP_PRIVATE] ?: StorageScanCount()
        val pub = byStorageType[RecordingStorageType.PUBLIC_DOCUMENTS] ?: StorageScanCount()
        val sd = byStorageType[RecordingStorageType.EXTERNAL_SD_SAF] ?: StorageScanCount()
        return "APP_PRIVATE=${priv.validWavCount}/${priv.totalInLibrary}, " +
            "PUBLIC_DOCUMENTS=${pub.validWavCount}/${pub.totalInLibrary}, " +
            "EXTERNAL_SD_SAF=${sd.validWavCount}/${sd.totalInLibrary}, " +
            "IMPORTED=${importedCount.validWavCount}/${importedCount.totalInLibrary}"
    }
}

data class LocalAudioFragment(
    val samples: FloatArray,
    val title: String,
    val durationSec: Float,
    val storageType: RecordingStorageType
)

/**
 * Shared local WAV pool and random-fragment reader for both the Spirit Box "Local Audio" source
 * and its Log self-test. Reads directly from the same Room Library repository used by LibraryScreen,
 * covering APP_PRIVATE (filesDir), PUBLIC_DOCUMENTS (MediaStore), imported files, and EXTERNAL_SD_SAF
 * (SAF URIs) via stored file paths or ContentResolver without copying full files to cache.
 */
@Singleton
class SpiritBoxLocalAudioSource @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: FaintRecRepository,
    private val storageLocationManager: StorageLocationManager
) {
    companion object {
        const val MIN_FRAGMENT_SEC = 0.3f
        const val MAX_FRAGMENT_SEC = 2.0f
        const val DEFAULT_TARGET_SAMPLE_RATE = 48000

        /**
         * Pure helper for computing random fragment frame count (0.3s..2.0s) and valid start frame.
         */
        fun pickFragmentWindow(
            totalFrames: Long,
            sampleRate: Int,
            minSec: Float = MIN_FRAGMENT_SEC,
            maxSec: Float = MAX_FRAGMENT_SEC,
            rng: Random = Random.Default
        ): Pair<Long, Int> {
            if (totalFrames <= 0L || sampleRate <= 0) return 0L to 0
            val durSec = minSec + rng.nextFloat() * (maxSec - minSec).coerceAtLeast(0f)
            val desiredFrames = (durSec * sampleRate).toLong().coerceAtLeast(1L)
            val framesToRead = min(desiredFrames, totalFrames).toInt().coerceAtLeast(1)
            val maxStart = (totalFrames - framesToRead).coerceAtLeast(0L)
            val startFrame = if (maxStart > 0L) rng.nextLong(maxStart + 1L) else 0L
            return startFrame to framesToRead
        }

        /**
         * Downmixes interleaved samples to mono and linearly resamples to [targetSampleRate].
         */
        fun downmixAndResampleToMono(
            interleaved: FloatArray,
            framesRead: Int,
            channels: Int,
            sourceSampleRate: Int,
            targetSampleRate: Int = DEFAULT_TARGET_SAMPLE_RATE
        ): FloatArray {
            if (framesRead <= 0 || channels <= 0 || sourceSampleRate <= 0 || targetSampleRate <= 0) {
                return FloatArray(0)
            }
            val mono = FloatArray(framesRead) { f ->
                var acc = 0f
                val base = f * channels
                for (c in 0 until channels) {
                    acc += interleaved[base + c]
                }
                acc / channels
            }
            if (sourceSampleRate == targetSampleRate || framesRead == 1) {
                return mono
            }
            val ratio = sourceSampleRate.toDouble() / targetSampleRate.toDouble()
            val outLen = ((framesRead / ratio).toInt()).coerceAtLeast(1)
            return FloatArray(outLen) { outIdx ->
                val srcPos = outIdx * ratio
                val i0 = srcPos.toInt().coerceIn(0, framesRead - 1)
                val i1 = (i0 + 1).coerceAtMost(framesRead - 1)
                val frac = (srcPos - i0).toFloat()
                mono[i0] * (1f - frac) + mono[i1] * frac
            }
        }
    }

    /**
     * Scans all recordings registered in the Library repository across all storage types,
     * validates each WAV header via stored path or ContentResolver URI, and returns a detailed report.
     */
    suspend fun scanLibraryWavs(): LocalAudioScanReport = withContext(Dispatchers.IO) {
        val allRecordings = repository.getAllRecordingsOnce()

        val totals = mutableMapOf(
            RecordingStorageType.APP_PRIVATE to 0,
            RecordingStorageType.PUBLIC_DOCUMENTS to 0,
            RecordingStorageType.EXTERNAL_SD_SAF to 0
        )
        val valids = mutableMapOf(
            RecordingStorageType.APP_PRIVATE to 0,
            RecordingStorageType.PUBLIC_DOCUMENTS to 0,
            RecordingStorageType.EXTERNAL_SD_SAF to 0
        )
        val skipped = mutableMapOf(
            RecordingStorageType.APP_PRIVATE to 0,
            RecordingStorageType.PUBLIC_DOCUMENTS to 0,
            RecordingStorageType.EXTERNAL_SD_SAF to 0
        )
        var importedTotal = 0
        var importedValid = 0
        var importedSkipped = 0

        val validList = mutableListOf<ValidatedLocalWav>()

        for (rec in allRecordings) {
            val isImported = rec.sourceTag == RecordingSourceTag.IMPORTED
            if (isImported) {
                importedTotal++
            } else {
                totals[rec.storageType] = (totals[rec.storageType] ?: 0) + 1
            }

            val validated = validateRecordingWav(rec)
            if (validated != null) {
                validList.add(validated)
                if (isImported) {
                    importedValid++
                } else {
                    valids[rec.storageType] = (valids[rec.storageType] ?: 0) + 1
                }
            } else {
                if (isImported) {
                    importedSkipped++
                } else {
                    skipped[rec.storageType] = (skipped[rec.storageType] ?: 0) + 1
                }
            }
        }

        val byStorage = RecordingStorageType.entries.associateWith { st ->
            StorageScanCount(
                totalInLibrary = totals[st] ?: 0,
                validWavCount = valids[st] ?: 0,
                skippedMissingOrCorrupt = skipped[st] ?: 0
            )
        }

        LocalAudioScanReport(
            validWavs = validList,
            totalLibraryRecordings = allRecordings.size,
            byStorageType = byStorage,
            importedCount = StorageScanCount(
                totalInLibrary = importedTotal,
                validWavCount = importedValid,
                skippedMissingOrCorrupt = importedSkipped
            )
        )
    }

    /**
     * Picks a random valid WAV from [pool] and extracts a random 0.3..2.0 second mono fragment
     * resampled to [targetSampleRate]. Automatically skips any file that disappeared or became corrupt.
     */
    fun readRandomFragment(
        pool: List<ValidatedLocalWav>,
        targetSampleRate: Int = DEFAULT_TARGET_SAMPLE_RATE,
        minDurationSec: Float = MIN_FRAGMENT_SEC,
        maxDurationSec: Float = MAX_FRAGMENT_SEC,
        rng: Random = Random.Default
    ): LocalAudioFragment? {
        if (pool.isEmpty()) return null
        val maxAttempts = min(pool.size, 6)
        val triedIndices = HashSet<Int>(maxAttempts)

        repeat(maxAttempts) {
            var idx = rng.nextInt(pool.size)
            while (idx in triedIndices && triedIndices.size < pool.size) {
                idx = (idx + 1) % pool.size
            }
            triedIndices.add(idx)

            val candidate = pool[idx]
            val fragment = extractFragmentFrom(
                candidate = candidate,
                targetSampleRate = targetSampleRate,
                minDurationSec = minDurationSec,
                maxDurationSec = maxDurationSec,
                rng = rng
            )
            if (fragment != null && fragment.samples.isNotEmpty()) {
                return fragment
            }
        }
        return null
    }

    private fun validateRecordingWav(rec: RecordingEntity): ValidatedLocalWav? {
        // Quick filter: skip clearly non-WAV formats (MP3, AAC, OGG, FLAC), while allowing WAV or unknown formats
        val looksLikeWav = rec.format.equals("WAV", ignoreCase = true) ||
            rec.format.contains("WAV", ignoreCase = true) ||
            rec.filePath.endsWith(".wav", ignoreCase = true) ||
            rec.storageUri?.endsWith(".wav", ignoreCase = true) == true ||
            rec.mediaStoreUri?.endsWith(".wav", ignoreCase = true) == true
        if (!looksLikeWav) return null

        // 1) Try direct file path first (APP_PRIVATE, imported working file, legacy public documents)
        val directFile = resolveDirectFile(rec)
        if (directFile != null) {
            val info = WavFileHelper.parseWavInfo(directFile)
            if (info != null && info.totalFrames > 0L) {
                return ValidatedLocalWav(rec, info, directFile = directFile, contentUri = null)
            }
        }

        // 2) Try ContentResolver on stored content:// URIs (PUBLIC_DOCUMENTS MediaStore, EXTERNAL_SD_SAF, imported MediaStore)
        val candidateUris = listOfNotNull(rec.storageUri, rec.mediaStoreUri)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()

        for (uriStr in candidateUris) {
            val uri = runCatching { Uri.parse(uriStr) }.getOrNull() ?: continue
            if (uri.scheme.equals("file", ignoreCase = true)) {
                val f = uri.path?.let { File(it) }
                if (f != null && f.exists() && f.isFile && f.canRead()) {
                    val info = WavFileHelper.parseWavInfo(f)
                    if (info != null && info.totalFrames > 0L) {
                        return ValidatedLocalWav(rec, info, directFile = f, contentUri = null)
                    }
                }
                continue
            }
            if (uri.scheme.equals("content", ignoreCase = true)) {
                val info = parseWavInfoFromContentUri(uri, rec.fileSizeBytes)
                if (info != null && info.totalFrames > 0L) {
                    return ValidatedLocalWav(rec, info, directFile = null, contentUri = uri)
                }
            }
        }
        return null
    }

    private fun resolveDirectFile(rec: RecordingEntity): File? {
        if (rec.filePath.isNotBlank()) {
            val f = File(rec.filePath)
            if (f.exists() && f.isFile && f.canRead() && f.length() >= 44L) {
                return f
            }
            // Fallback: if APP_PRIVATE directory path changed or only filename was stored
            if (rec.storageType == RecordingStorageType.APP_PRIVATE) {
                val byName = File(storageLocationManager.getAppPrivateRecordingsDir(), f.name)
                if (byName.exists() && byName.isFile && byName.canRead() && byName.length() >= 44L) {
                    return byName
                }
            }
        } else if (rec.storageType == RecordingStorageType.APP_PRIVATE) {
            val byTitle = File(storageLocationManager.getAppPrivateRecordingsDir(), "${rec.title}.wav")
            if (byTitle.exists() && byTitle.isFile && byTitle.canRead() && byTitle.length() >= 44L) {
                return byTitle
            }
        }
        return null
    }

    private fun parseWavInfoFromContentUri(uri: Uri, sizeHint: Long): WavInfo? {
        // Prefer seekable ParcelFileDescriptor -> FileChannel (fast, zero-copy)
        val viaPfd = runCatching {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                FileInputStream(pfd.fileDescriptor).use { fis ->
                    WavFileHelper.parseWavInfo(fis.channel)
                }
            }
        }.getOrNull()
        if (viaPfd != null && viaPfd.totalFrames > 0L) return viaPfd

        // Fallback for non-seekable SAF providers: parse sequentially via InputStream
        return runCatching {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                WavFileHelper.parseWavInfo(stream, totalBytesHint = sizeHint)
            }
        }.getOrNull()
    }

    private fun extractFragmentFrom(
        candidate: ValidatedLocalWav,
        targetSampleRate: Int,
        minDurationSec: Float,
        maxDurationSec: Float,
        rng: Random
    ): LocalAudioFragment? {
        // 1) Direct File path
        val file = candidate.directFile
        if (file != null) {
            if (!file.exists() || !file.isFile || !file.canRead()) return null
            return runCatching {
                FileInputStream(file).use { fis ->
                    val channel = fis.channel
                    val info = WavFileHelper.parseWavInfo(channel) ?: return null
                    if (info.totalFrames <= 0L) return null
                    val (startFrame, framesToRead) = pickFragmentWindow(
                        totalFrames = info.totalFrames,
                        sampleRate = info.sampleRate,
                        minSec = minDurationSec,
                        maxSec = maxDurationSec,
                        rng = rng
                    )
                    if (framesToRead <= 0) return null
                    val raw = FloatArray(framesToRead * info.channels)
                    val read = WavFileHelper.readFramesAsFloat(channel, info, startFrame, framesToRead, raw)
                    if (read <= 0) return null
                    val mono = downmixAndResampleToMono(raw, read, info.channels, info.sampleRate, targetSampleRate)
                    LocalAudioFragment(
                        samples = mono,
                        title = candidate.recording.title,
                        durationSec = mono.size.toFloat() / targetSampleRate.toFloat(),
                        storageType = candidate.recording.storageType
                    )
                }
            }.getOrNull()
        }

        // 2) ContentResolver URI (MediaStore or SAF SD card)
        val uri = candidate.contentUri ?: return null
        val viaPfd = runCatching {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                FileInputStream(pfd.fileDescriptor).use { fis ->
                    val channel = fis.channel
                    val info = WavFileHelper.parseWavInfo(channel) ?: return@use null
                    if (info.totalFrames <= 0L) return@use null
                    val (startFrame, framesToRead) = pickFragmentWindow(
                        totalFrames = info.totalFrames,
                        sampleRate = info.sampleRate,
                        minSec = minDurationSec,
                        maxSec = maxDurationSec,
                        rng = rng
                    )
                    if (framesToRead <= 0) return@use null
                    val raw = FloatArray(framesToRead * info.channels)
                    val read = WavFileHelper.readFramesAsFloat(channel, info, startFrame, framesToRead, raw)
                    if (read <= 0) return@use null
                    val mono = downmixAndResampleToMono(raw, read, info.channels, info.sampleRate, targetSampleRate)
                    LocalAudioFragment(
                        samples = mono,
                        title = candidate.recording.title,
                        durationSec = mono.size.toFloat() / targetSampleRate.toFloat(),
                        storageType = candidate.recording.storageType
                    )
                }
            }
        }.getOrNull()
        if (viaPfd != null) return viaPfd

        // Fallback via ContentResolver.openInputStream for non-seekable SAF documents
        return runCatching {
            val info = candidate.wavInfo
            val (startFrame, framesToRead) = pickFragmentWindow(
                totalFrames = info.totalFrames,
                sampleRate = info.sampleRate,
                minSec = minDurationSec,
                maxSec = maxDurationSec,
                rng = rng
            )
            if (framesToRead <= 0) return null
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val raw = FloatArray(framesToRead * info.channels)
                val read = WavFileHelper.readFramesAsFloat(stream, info, startFrame, framesToRead, raw)
                if (read <= 0) return null
                val mono = downmixAndResampleToMono(raw, read, info.channels, info.sampleRate, targetSampleRate)
                LocalAudioFragment(
                    samples = mono,
                    title = candidate.recording.title,
                    durationSec = mono.size.toFloat() / targetSampleRate.toFloat(),
                    storageType = candidate.recording.storageType
                )
            }
        }.getOrNull()
    }
}
