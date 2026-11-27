package com.example.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.provider.OpenableColumns
import com.example.data.AppFeature
import com.example.data.RecordingEntity
import com.example.data.RecordingSourceTag
import com.example.logging.AppLogger
import com.example.util.FaintRecFolder
import com.example.util.StorageHelper
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

data class ImportProgress(
    val fileName: String,
    val progress: Float,
    val currentIndex: Int,
    val totalCount: Int
)

@Singleton
class AudioImporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val logger: AppLogger
) {
    /**
     * Imports an external audio file URI (SAF, ACTION_SEND, ACTION_VIEW) into Documents/FaintRec/Imported.
     * - WAV files are copied losslessly without touching the original.
     * - Non-WAV formats (MP3, AAC/M4A, OGG, FLAC, OPUS, AMR, etc.) are decoded in chunks using
     *   MediaExtractor + MediaCodec into 16-bit PCM WAV so every player/editor/analyzer tool works identically.
     */
    suspend fun importUri(
        uri: Uri,
        onProgress: (Float) -> Unit = {}
    ): Result<RecordingEntity> = withContext(Dispatchers.IO) {
        runCatching {
            if (!StorageHelper.hasSufficientSpace(context, 25L * 1024L * 1024L)) {
                throw IllegalStateException("Low storage space: free up at least 25 MB before importing.")
            }

            val displayName = resolveDisplayName(uri) ?: "Imported_${System.currentTimeMillis()}.wav"
            val ext = displayName.substringAfterLast('.', "").lowercase()
            val baseName = displayName.substringBeforeLast('.', displayName)
            val isLossyOrigin = ext in setOf("mp3", "aac", "m4a", "ogg", "opus", "amr", "3gp", "wma")

            if (ext == "wav") {
                val outName = "${StorageHelper.sanitizeFileName(baseName)}.wav"
                val destFile = StorageHelper.createWorkingFile(context, FaintRecFolder.IMPORTED, outName)
                val inputStream = context.contentResolver.openInputStream(uri)
                    ?: throw IllegalStateException("Unable to read selected file.")
                inputStream.use { input ->
                    FileOutputStream(destFile).use { output ->
                        val buf = ByteArray(65536)
                        var copied = 0L
                        val total = resolveFileSize(uri).coerceAtLeast(1L)
                        while (true) {
                            coroutineContext.ensureActive()
                            val r = input.read(buf)
                            if (r <= 0) break
                            output.write(buf, 0, r)
                            copied += r
                            onProgress((copied.toFloat() / total).coerceIn(0f, 0.98f))
                        }
                    }
                }
                val info = WavFileHelper.parseWavInfo(destFile)
                    ?: run {
                        destFile.delete()
                        throw IllegalArgumentException("File is corrupt or not a valid PCM/Float WAV file.")
                    }

                val mediaUri = StorageHelper.publishToMediaStore(
                    context, destFile, FaintRecFolder.IMPORTED, "audio/wav", destFile.name
                )
                onProgress(1f)
                return@runCatching RecordingEntity(
                    title = baseName,
                    filePath = destFile.absolutePath,
                    mediaStoreUri = mediaUri,
                    durationMs = info.durationMs,
                    fileSizeBytes = destFile.length(),
                    format = "WAV",
                    sampleRate = info.sampleRate,
                    channels = info.channels,
                    bitsPerSample = info.bitsPerSample,
                    isFloatPcm = info.isFloat,
                    sourceTag = RecordingSourceTag.IMPORTED,
                    isLossySource = false,
                    audioSourceUsed = "IMPORTED_WAV",
                    inputDeviceName = "Imported File"
                )
            }

            // Decode non-WAV to 16-bit PCM WAV via MediaExtractor + MediaCodec
            val outName = "${StorageHelper.sanitizeFileName(baseName)}.wav"
            val destFile = StorageHelper.createWorkingFile(context, FaintRecFolder.IMPORTED, outName)
            val decodedInfo = decodeToWav16Bit(uri, destFile, onProgress)

            val mediaUri = StorageHelper.publishToMediaStore(
                context, destFile, FaintRecFolder.IMPORTED, "audio/wav", destFile.name
            )
            onProgress(1f)
            RecordingEntity(
                title = baseName,
                filePath = destFile.absolutePath,
                mediaStoreUri = mediaUri,
                durationMs = decodedInfo.durationMs,
                fileSizeBytes = destFile.length(),
                format = "WAV",
                sampleRate = decodedInfo.sampleRate,
                channels = decodedInfo.channels,
                bitsPerSample = 16,
                isFloatPcm = false,
                sourceTag = RecordingSourceTag.IMPORTED,
                isLossySource = isLossyOrigin,
                audioSourceUsed = "IMPORTED_${ext.uppercase().ifBlank { "AUDIO" }}",
                inputDeviceName = "Imported (${ext.uppercase().ifBlank { "Audio" }})"
            )
        }.onSuccess { rec ->
            logger.reportSuccess(
                AppFeature.IMPORT,
                "Imported ${rec.audioSourceUsed} (${rec.durationMs} ms, ${rec.sampleRate} Hz)"
            )
        }.onFailure { err ->
            logger.reportFailure(AppFeature.IMPORT, "Import failed: ${err.message}", err)
        }
    }

    /**
     * Decodes any local audio file (MP3, AAC/M4A, FLAC, OGG/Opus, AMR, etc.) into a 16-bit PCM WAV file.
     */
    suspend fun decodeFileToWav16Bit(
        inputFile: File,
        destFile: File,
        onProgress: (Float) -> Unit = {}
    ): WavInfo = withContext(Dispatchers.IO) {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(inputFile.absolutePath)
            decodeExtractorToWav16Bit(extractor, destFile, onProgress)
        } finally {
            try { extractor.release() } catch (_: Exception) {}
        }
    }

    private suspend fun decodeToWav16Bit(
        uri: Uri,
        destFile: File,
        onProgress: (Float) -> Unit
    ): WavInfo {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
            return decodeExtractorToWav16Bit(extractor, destFile, onProgress)
        } finally {
            try { extractor.release() } catch (_: Exception) {}
        }
    }

    private suspend fun decodeExtractorToWav16Bit(
        extractor: MediaExtractor,
        destFile: File,
        onProgress: (Float) -> Unit
    ): WavInfo {
        var codec: MediaCodec? = null
        try {
            // Detect DRM-protected streams
            if (extractor.drmInitData != null) {
                throw IllegalStateException("Cannot import DRM-protected audio files.")
            }

            var audioTrackIndex = -1
            var trackFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val fmt = extractor.getTrackFormat(i)
                val mime = fmt.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    trackFormat = fmt
                    break
                }
            }
            if (audioTrackIndex < 0 || trackFormat == null) {
                throw IllegalArgumentException("No decodable audio track found in file.")
            }

            extractor.selectTrack(audioTrackIndex)
            val mime = trackFormat.getString(MediaFormat.KEY_MIME)!!
            var sampleRate = trackFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = trackFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val durationUs = if (trackFormat.containsKey(MediaFormat.KEY_DURATION)) {
                trackFormat.getLong(MediaFormat.KEY_DURATION)
            } else 0L

            codec = try {
                MediaCodec.createDecoderByType(mime)
            } catch (_: Exception) {
                throw UnsupportedOperationException("Unsupported audio codec: $mime")
            }
            codec.configure(trackFormat, null, null, 0)
            codec.start()

            var dataBytes = 0L
            RandomAccessFile(destFile, "rw").use { raf ->
                raf.setLength(0L)
                WavFileHelper.writePlaceholderHeader(raf, sampleRate, channels, 16, isFloat = false)

                val bufferInfo = MediaCodec.BufferInfo()
                var sawInputEos = false
                var sawOutputEos = false
                var idleAfterEos = 0
                val timeoutUs = 10_000L

                while (!sawOutputEos) {
                    coroutineContext.ensureActive()

                    if (!sawInputEos) {
                        val inIdx = codec.dequeueInputBuffer(timeoutUs)
                        if (inIdx >= 0) {
                            val inBuf = codec.getInputBuffer(inIdx)!!
                            val readSize = extractor.readSampleData(inBuf, 0)
                            if (readSize < 0) {
                                codec.queueInputBuffer(
                                    inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                )
                                sawInputEos = true
                            } else {
                                val pts = extractor.sampleTime
                                codec.queueInputBuffer(inIdx, 0, readSize, pts, 0)
                                extractor.advance()
                                if (durationUs > 0 && pts >= 0) {
                                    onProgress((pts.toFloat() / durationUs).coerceIn(0f, 0.98f))
                                }
                            }
                        }
                    }

                    val outIdx = codec.dequeueOutputBuffer(bufferInfo, timeoutUs)
                    when {
                        outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            idleAfterEos = 0
                            val outFmt = codec.outputFormat
                            if (outFmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                                sampleRate = outFmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            }
                            if (outFmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                                channels = outFmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            }
                        }
                        outIdx >= 0 -> {
                            idleAfterEos = 0
                            val outBuf = codec.getOutputBuffer(outIdx)
                            if (outBuf != null && bufferInfo.size > 0) {
                                outBuf.position(bufferInfo.offset)
                                outBuf.limit(bufferInfo.offset + bufferInfo.size)
                                val chunk = ByteArray(bufferInfo.size)
                                outBuf.get(chunk)
                                raf.write(chunk)
                                dataBytes += chunk.size
                            }
                            codec.releaseOutputBuffer(outIdx, false)
                            if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                                sawOutputEos = true
                            }
                        }
                        sawInputEos -> {
                            idleAfterEos++
                            if (idleAfterEos > 500) break
                        }
                    }
                }

                // Rewrite final header with actual decoded sampleRate / channels / sizes
                WavFileHelper.writePlaceholderHeader(raf, sampleRate, channels, 16, isFloat = false)
                WavFileHelper.patchWavHeaderSizes(raf, dataBytes)
            }

            if (dataBytes <= 0L) {
                destFile.delete()
                throw IllegalStateException("Decoded audio was empty or corrupt.")
            }
            return WavFileHelper.parseWavInfo(destFile)
                ?: throw IllegalStateException("Failed to verify decoded WAV.")
        } finally {
            try { codec?.stop() } catch (_: Exception) {}
            try { codec?.release() } catch (_: Exception) {}
        }
    }

    private fun resolveDisplayName(uri: Uri): String? {
        return try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                }
        } catch (_: Exception) {
            uri.lastPathSegment
        }
    }

    private fun resolveFileSize(uri: Uri): Long {
        return try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getLong(0) else 0L
                } ?: 0L
        } catch (_: Exception) {
            0L
        }
    }
}
