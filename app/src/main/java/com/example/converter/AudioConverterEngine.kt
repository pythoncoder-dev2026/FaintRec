package com.example.converter

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import com.example.audio.AudioImporter
import com.example.audio.WavFileHelper
import com.example.data.AppFeature
import com.example.data.EdlOperation
import com.example.data.RecordingEntity
import com.example.data.RecordingSourceTag
import com.example.editor.EqPreset
import com.example.editor.GraphicEqAndFilterChain
import com.example.logging.AppLogger
import com.example.util.AudioMath
import com.example.util.FaintRecFolder
import com.example.util.StorageHelper
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext
import kotlin.math.abs
import kotlin.math.min

enum class OutputAudioFormat(
    val displayName: String,
    val extension: String,
    val mimeType: String,
    val isLossless: Boolean
) {
    WAV("WAV (PCM)", "wav", "audio/wav", true),
    AAC_M4A("AAC (M4A)", "m4a", "audio/mp4a-latm", false),
    FLAC("FLAC", "flac", "audio/flac", true),
    OGG_OPUS("OGG (Opus)", "ogg", "audio/opus", false),
    OGG_VORBIS("OGG (Vorbis)", "ogg", "audio/vorbis", false),
    MP3("MP3 (LAME)", "mp3", "audio/mpeg", false)
}

data class FormatSupportStatus(
    val format: OutputAudioFormat,
    val isSupported: Boolean,
    val reason: String? = null
)

data class ConversionConfig(
    val outputFormat: OutputAudioFormat = OutputAudioFormat.AAC_M4A,
    val bitrateKbps: Int = 192, // 64..320
    val mp3UseVbr: Boolean = false,
    val mp3VbrQuality: Int = 2, // 0=best .. 9=smallest
    val targetSampleRate: Int = 0, // 0 = Original, or 44100, 48000
    val targetChannels: Int = 0, // 0 = Original, 1 = Mono, 2 = Stereo
    val wavFloat32: Boolean = false, // false = 16-bit, true = 32-bit float
    val flacCompressionLevel: Int = 5, // 0..8
    val bakeEffects: Boolean = false,
    val gainDb: Float = 0f,
    val normalizeMinus1Db: Boolean = false,
    val speed: Float = 1.0f,
    val reverse: Boolean = false,
    val trimStartMs: Long = 0L,
    val trimEndMs: Long = 0L, // 0 = full length
    val eqBandsDb: List<Float> = EqPreset.FLAT.gainsDb
)

data class ConversionResultSummary(
    val sourceRecording: RecordingEntity,
    val outputRecording: RecordingEntity,
    val originalSizeBytes: Long,
    val outputSizeBytes: Long,
    val formatName: String,
    val bitrateDescription: String,
    val durationMs: Long
)

@Singleton
class AudioConverterEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val audioImporter: AudioImporter,
    private val logger: AppLogger,
    private val storageLocationManager: com.example.storage.StorageLocationManager
) {
    private fun OutputAudioFormat.toAppFeature(): AppFeature = when (this) {
        OutputAudioFormat.WAV -> AppFeature.CONVERT_WAV
        OutputAudioFormat.MP3 -> AppFeature.CONVERT_MP3
        OutputAudioFormat.AAC_M4A -> AppFeature.CONVERT_AAC
        OutputAudioFormat.OGG_OPUS, OutputAudioFormat.OGG_VORBIS -> AppFeature.CONVERT_OGG
        OutputAudioFormat.FLAC -> AppFeature.CONVERT_FLAC
    }
    /**
     * Queries MediaCodecList, API level, and LAME JNI availability so unsupported codecs
     * are disabled in the UI with a clear technical explanation.
     */
    fun checkCodecSupport(): List<FormatSupportStatus> {
        val codecs = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
        fun hasEncoderForMime(mime: String): Boolean = codecs.any { info ->
            info.isEncoder && info.supportedTypes.any { it.equals(mime, ignoreCase = true) }
        }

        val api29Plus = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

        return listOf(
            FormatSupportStatus(OutputAudioFormat.WAV, true, null),
            FormatSupportStatus(
                OutputAudioFormat.AAC_M4A,
                hasEncoderForMime(MediaFormat.MIMETYPE_AUDIO_AAC),
                if (hasEncoderForMime(MediaFormat.MIMETYPE_AUDIO_AAC)) null
                else "No AAC hardware/software encoder found on this device."
            ),
            FormatSupportStatus(
                OutputAudioFormat.FLAC,
                api29Plus && hasEncoderForMime(MediaFormat.MIMETYPE_AUDIO_FLAC),
                when {
                    !api29Plus -> "FLAC MediaCodec encoder requires Android 10 (API 29+)."
                    !hasEncoderForMime(MediaFormat.MIMETYPE_AUDIO_FLAC) ->
                        "This device does not expose a MediaCodec FLAC encoder."
                    else -> null
                }
            ),
            FormatSupportStatus(
                OutputAudioFormat.OGG_OPUS,
                api29Plus && hasEncoderForMime(MediaFormat.MIMETYPE_AUDIO_OPUS),
                when {
                    !api29Plus -> "OGG/Opus MediaMuxer requires Android 10 (API 29+)."
                    !hasEncoderForMime(MediaFormat.MIMETYPE_AUDIO_OPUS) ->
                        "No Opus encoder reported by MediaCodecList."
                    else -> null
                }
            ),
            FormatSupportStatus(
                OutputAudioFormat.OGG_VORBIS,
                api29Plus && hasEncoderForMime(MediaFormat.MIMETYPE_AUDIO_VORBIS),
                when {
                    !api29Plus -> "OGG/Vorbis muxing requires Android 10 (API 29+)."
                    !hasEncoderForMime(MediaFormat.MIMETYPE_AUDIO_VORBIS) ->
                        "Android MediaCodecList does not include a Vorbis encoder on this device (use OGG/Opus instead)."
                    else -> null
                }
            ),
            FormatSupportStatus(
                OutputAudioFormat.MP3,
                LameNativeBridge.isAvailable,
                if (LameNativeBridge.isAvailable) null else LameNativeBridge.unavailableReason
            )
        )
    }

    /**
     * Converts a recording using a chunked background pipeline:
     * WAV PCM read -> optional baked DSP (trim, reverse, speed, EQ, gain, normalize) -> resample/channels -> target encoder.
     * Saves to Documents/FaintRec/Converted as originalname_converted.ext with "Converted" badge.
     */
    suspend fun convertRecording(
        source: RecordingEntity,
        config: ConversionConfig,
        edlOperations: List<EdlOperation> = emptyList(),
        onProgress: (Float) -> Unit = {}
    ): Result<ConversionResultSummary> = withContext(Dispatchers.IO) {
        runCatching {
            if (!StorageHelper.hasSufficientSpace(context, 20L * 1024L * 1024L)) {
                throw IllegalStateException("Insufficient storage space to convert file.")
            }
            val origFile = storageLocationManager.resolveReadableFile(source)
                ?: throw IllegalStateException("Source audio file is unavailable on storage.")
            var tempDecodedWav: File? = null
            val directInfo = WavFileHelper.parseWavInfo(origFile)
            val (srcFile, info) = if (directInfo != null) {
                origFile to directInfo
            } else {
                val decoded = File(context.cacheDir, "conv_dec_${System.currentTimeMillis()}.wav")
                tempDecodedWav = decoded
                val decodedInfo = audioImporter.decodeFileToWav16Bit(origFile, decoded) { p ->
                    onProgress(p * 0.15f)
                }
                decoded to decodedInfo
            }

            val outSampleRate = when {
                config.outputFormat == OutputAudioFormat.OGG_OPUS -> 48000 // Opus standard rate
                config.targetSampleRate > 0 -> config.targetSampleRate
                else -> info.sampleRate
            }
            val outChannels = if (config.targetChannels in 1..2) config.targetChannels else info.channels.coerceIn(1, 2)

            // Step 1: Prepare intermediate processed PCM WAV (32-bit float) if baking effects, resampling, or reversing
            val preparedFile = File(context.cacheDir, "conv_stage_${System.currentTimeMillis()}.wav")
            try {
                prepareIntermediatePcm(
                    srcFile = srcFile,
                    srcInfo = info,
                    destFile = preparedFile,
                    targetRate = outSampleRate,
                    targetChannels = outChannels,
                    config = config,
                    edlOperations = edlOperations
                ) { p -> onProgress(p * 0.45f) }

                val stageInfo = WavFileHelper.parseWavInfo(preparedFile)
                    ?: throw IllegalStateException("Failed to prepare intermediate PCM buffer.")

                val baseName = StorageHelper.sanitizeFileName(source.title).substringBeforeLast('.')
                val outName = "${baseName}_converted.${config.outputFormat.extension}"
                val outFile = StorageHelper.createWorkingFile(context, FaintRecFolder.CONVERTED, outName)

                // Step 2: Encode staged PCM to destination format
                when (config.outputFormat) {
                    OutputAudioFormat.WAV -> encodeWav(preparedFile, stageInfo, outFile, config.wavFloat32) {
                        onProgress(0.45f + it * 0.55f)
                    }
                    OutputAudioFormat.AAC_M4A -> encodeAacM4a(preparedFile, stageInfo, outFile, config.bitrateKbps) {
                        onProgress(0.45f + it * 0.55f)
                    }
                    OutputAudioFormat.FLAC -> encodeFlac(preparedFile, stageInfo, outFile, config.flacCompressionLevel) {
                        onProgress(0.45f + it * 0.55f)
                    }
                    OutputAudioFormat.OGG_OPUS -> encodeOggOpus(preparedFile, stageInfo, outFile, config.bitrateKbps) {
                        onProgress(0.45f + it * 0.55f)
                    }
                    OutputAudioFormat.OGG_VORBIS -> throw UnsupportedOperationException("Vorbis encoder not available.")
                    OutputAudioFormat.MP3 -> encodeMp3Lame(preparedFile, stageInfo, outFile, config) {
                        onProgress(0.45f + it * 0.55f)
                    }
                }

                val mime = StorageHelper.mimeTypeForExtension(config.outputFormat.extension)
                val mediaUri = StorageHelper.publishToMediaStore(
                    context = context,
                    sourceFile = outFile,
                    folder = FaintRecFolder.CONVERTED,
                    mimeType = mime,
                    displayName = outFile.name
                )

                val outEntity = RecordingEntity(
                    title = "${source.title} [${config.outputFormat.displayName}]",
                    filePath = outFile.absolutePath,
                    mediaStoreUri = mediaUri,
                    durationMs = stageInfo.durationMs,
                    fileSizeBytes = outFile.length(),
                    format = config.outputFormat.extension.uppercase(),
                    sampleRate = stageInfo.sampleRate,
                    channels = stageInfo.channels,
                    bitsPerSample = if (config.outputFormat == OutputAudioFormat.WAV && config.wavFloat32) 32 else 16,
                    isFloatPcm = config.outputFormat == OutputAudioFormat.WAV && config.wavFloat32,
                    sourceTag = RecordingSourceTag.CONVERTED,
                    isLossySource = !config.outputFormat.isLossless,
                    audioSourceUsed = "CONVERTED_${config.outputFormat.name}",
                    inputDeviceName = "Converted"
                )

                val bitrateDesc = when (config.outputFormat) {
                    OutputAudioFormat.WAV -> if (config.wavFloat32) "32-bit Float PCM" else "16-bit PCM"
                    OutputAudioFormat.FLAC -> "Lossless (Level ${config.flacCompressionLevel})"
                    OutputAudioFormat.MP3 -> if (config.mp3UseVbr) "VBR Q${config.mp3VbrQuality}" else "${config.bitrateKbps} kbps CBR"
                    else -> "${config.bitrateKbps} kbps"
                }

                onProgress(1f)
                ConversionResultSummary(
                    sourceRecording = source,
                    outputRecording = outEntity,
                    originalSizeBytes = source.fileSizeBytes,
                    outputSizeBytes = outFile.length(),
                    formatName = config.outputFormat.displayName,
                    bitrateDescription = bitrateDesc,
                    durationMs = stageInfo.durationMs
                )
            } finally {
                preparedFile.delete()
                tempDecodedWav?.delete()
            }
        }.onSuccess { summary ->
            logger.reportSuccess(
                config.outputFormat.toAppFeature(),
                "Converted to ${summary.formatName} (${summary.bitrateDescription}, ${summary.outputSizeBytes} B)"
            )
        }.onFailure { err ->
            val feat = config.outputFormat.toAppFeature()
            if (err is UnsupportedOperationException) {
                logger.reportNotSupported(feat, err.message ?: "Codec not supported on device")
            } else {
                logger.reportFailure(feat, "Conversion failed: ${err.message}", err)
            }
        }
    }

    private suspend fun prepareIntermediatePcm(
        srcFile: File,
        srcInfo: com.example.audio.WavInfo,
        destFile: File,
        targetRate: Int,
        targetChannels: Int,
        config: ConversionConfig,
        edlOperations: List<EdlOperation>,
        onProgress: (Float) -> Unit
    ) {
        val startFrame = if (config.bakeEffects && config.trimStartMs > 0) {
            ((config.trimStartMs * srcInfo.sampleRate) / 1000L).coerceIn(0L, srcInfo.totalFrames)
        } else 0L
        val endFrame = if (config.bakeEffects && config.trimEndMs > config.trimStartMs) {
            ((config.trimEndMs * srcInfo.sampleRate) / 1000L).coerceIn(startFrame + 1, srcInfo.totalFrames)
        } else srcInfo.totalFrames

        val spanFrames = (endFrame - startFrame).coerceAtLeast(1L)
        val effectiveSpeed = if (config.bakeEffects) config.speed.coerceIn(0.25f, 4.0f) else 1.0f
        val rateRatio = (srcInfo.sampleRate.toDouble() / targetRate.toDouble()) * effectiveSpeed

        // Optional peak scan if normalizing to -1 dBFS
        var normMult = 1f
        if (config.bakeEffects && config.normalizeMinus1Db) {
            var peak = 1e-6f
            val scanBuf = FloatArray(4096 * srcInfo.channels)
            RandomAccessFile(srcFile, "r").use { raf ->
                var pos = startFrame
                while (pos < endFrame) {
                    coroutineContext.ensureActive()
                    val r = WavFileHelper.readFramesAsFloat(
                        raf, srcInfo, pos, min(4096L, endFrame - pos).toInt(), scanBuf
                    )
                    if (r <= 0) break
                    for (i in 0 until r * srcInfo.channels) {
                        val a = abs(scanBuf[i])
                        if (a > peak) peak = a
                    }
                    pos += r
                }
            }
            normMult = AudioMath.dbToLinear(-1f) / peak
        }

        val gainMult = if (config.bakeEffects) AudioMath.dbToLinear(config.gainDb) * normMult else 1f
        val eqChain = GraphicEqAndFilterChain(targetRate, targetChannels)
        if (config.bakeEffects) {
            val eqOp = edlOperations.lastOrNull { it.type == com.example.data.EdlOpType.EQ }
            val filtOp = edlOperations.lastOrNull { it.type == com.example.data.EdlOpType.FILTER }
            val bands = if (config.eqBandsDb.any { abs(it) > 0.05f }) config.eqBandsDb else (eqOp?.eqBandsDb ?: EqPreset.FLAT.gainsDb)
            eqChain.configure(
                bandsDb = bands,
                hpfHz = filtOp?.hpfHz ?: 0f,
                hpfSlopeDb = filtOp?.hpfSlopeDb ?: 12,
                lpfHz = filtOp?.lpfHz ?: 0f,
                lpfSlopeDb = filtOp?.lpfSlopeDb ?: 12
            )
        }

        var dataBytes = 0L
        RandomAccessFile(srcFile, "r").use { inRaf ->
            RandomAccessFile(destFile, "rw").use { outRaf ->
                outRaf.setLength(0L)
                WavFileHelper.writePlaceholderHeader(outRaf, targetRate, targetChannels, 32, isFloat = true)

                val outChunkFrames = 4096
                val outBuf = FloatArray(outChunkFrames * targetChannels)
                val totalOutFrames = (spanFrames.toDouble() / rateRatio).toLong().coerceAtLeast(1L)
                var outWritten = 0L

                while (outWritten < totalOutFrames) {
                    coroutineContext.ensureActive()
                    val framesNow = min(outChunkFrames.toLong(), totalOutFrames - outWritten).toInt()
                    val srcOffsetRel = outWritten * rateRatio
                    val srcSpanNeed = ((framesNow * rateRatio).toInt() + 4).coerceAtLeast(2)

                    val chunkSrcStart: Long
                    val actualNeed: Int
                    if (config.bakeEffects && config.reverse) {
                        val chunkSrcEnd = (endFrame - srcOffsetRel.toLong()).coerceIn(startFrame + 1, endFrame)
                        chunkSrcStart = (chunkSrcEnd - srcSpanNeed).coerceAtLeast(startFrame)
                        actualNeed = (chunkSrcEnd - chunkSrcStart).toInt().coerceAtLeast(1)
                    } else {
                        chunkSrcStart = (startFrame + srcOffsetRel.toLong()).coerceAtMost(endFrame - 1)
                        actualNeed = min(srcSpanNeed.toLong(), endFrame - chunkSrcStart).toInt().coerceAtLeast(1)
                    }
                    val inBuf = FloatArray(actualNeed * srcInfo.channels)
                    val read = WavFileHelper.readFramesAsFloat(inRaf, srcInfo, chunkSrcStart, actualNeed, inBuf)
                    if (read <= 0) break

                    for (f in 0 until framesNow) {
                        val rel = if (config.bakeEffects && config.reverse) {
                            (read - 1) - (f * rateRatio)
                        } else {
                            f * rateRatio
                        }
                        val i0 = rel.toInt().coerceIn(0, read - 1)
                        val i1 = (i0 + 1).coerceIn(0, read - 1)
                        val frac = (rel - i0).toFloat().coerceIn(0f, 1f)

                        for (tc in 0 until targetChannels) {
                            val s0 = mapChannel(inBuf, i0, srcInfo.channels, tc)
                            val s1 = mapChannel(inBuf, i1, srcInfo.channels, tc)
                            var sample = s0 + (s1 - s0) * frac
                            if (gainMult != 1f) sample = AudioMath.softLimit(sample * gainMult)
                            outBuf[f * targetChannels + tc] = sample
                        }
                    }

                    if (config.bakeEffects) {
                        eqChain.processInterleaved(outBuf, framesNow)
                    }

                    dataBytes += WavFileHelper.writeFloatSamples(outRaf, outBuf, framesNow * targetChannels, true)
                    outWritten += framesNow
                    onProgress((outWritten.toFloat() / totalOutFrames).coerceIn(0f, 1f))
                }
                WavFileHelper.patchWavHeaderSizes(outRaf, dataBytes)
            }
        }
    }

    private fun mapChannel(buf: FloatArray, frame: Int, srcCh: Int, tc: Int): Float = when {
        srcCh == 1 -> buf[frame]
        tc < srcCh -> buf[frame * srcCh + tc]
        else -> {
            var s = 0f
            for (c in 0 until srcCh) s += buf[frame * srcCh + c]
            s / srcCh
        }
    }

    private suspend fun encodeWav(
        srcFile: File,
        info: com.example.audio.WavInfo,
        outFile: File,
        float32: Boolean,
        onProgress: (Float) -> Unit
    ) {
        val chunkFrames = 8192
        val buf = FloatArray(chunkFrames * info.channels)
        var dataBytes = 0L
        RandomAccessFile(srcFile, "r").use { inRaf ->
            RandomAccessFile(outFile, "rw").use { outRaf ->
                outRaf.setLength(0L)
                WavFileHelper.writePlaceholderHeader(
                    outRaf, info.sampleRate, info.channels, if (float32) 32 else 16, float32
                )
                var pos = 0L
                while (pos < info.totalFrames) {
                    coroutineContext.ensureActive()
                    val r = WavFileHelper.readFramesAsFloat(inRaf, info, pos, chunkFrames, buf)
                    if (r <= 0) break
                    dataBytes += WavFileHelper.writeFloatSamples(outRaf, buf, r * info.channels, float32)
                    pos += r
                    onProgress((pos.toFloat() / info.totalFrames.coerceAtLeast(1L)).coerceIn(0f, 1f))
                }
                WavFileHelper.patchWavHeaderSizes(outRaf, dataBytes)
            }
        }
    }

    private suspend fun encodeAacM4a(
        srcFile: File,
        info: com.example.audio.WavInfo,
        outFile: File,
        bitrateKbps: Int,
        onProgress: (Float) -> Unit
    ) {
        val format = MediaFormat.createAudioFormat(
            MediaFormat.MIMETYPE_AUDIO_AAC, info.sampleRate, info.channels
        ).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrateKbps.coerceIn(64, 320) * 1000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 65536)
        }
        encodeWithMediaCodecAndMuxer(
            srcFile, info, outFile, MediaFormat.MIMETYPE_AUDIO_AAC, format,
            MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4, onProgress
        )
    }

    private suspend fun encodeOggOpus(
        srcFile: File,
        info: com.example.audio.WavInfo,
        outFile: File,
        bitrateKbps: Int,
        onProgress: (Float) -> Unit
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            throw UnsupportedOperationException("OGG/Opus encoding requires Android 10 (API 29+).")
        }
        val format = MediaFormat.createAudioFormat(
            MediaFormat.MIMETYPE_AUDIO_OPUS, info.sampleRate, info.channels
        ).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, bitrateKbps.coerceIn(64, 320) * 1000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 65536)
        }
        encodeWithMediaCodecAndMuxer(
            srcFile, info, outFile, MediaFormat.MIMETYPE_AUDIO_OPUS, format,
            MediaMuxer.OutputFormat.MUXER_OUTPUT_OGG, onProgress
        )
    }

    private suspend fun encodeFlac(
        srcFile: File,
        info: com.example.audio.WavInfo,
        outFile: File,
        compressionLevel: Int,
        onProgress: (Float) -> Unit
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            throw UnsupportedOperationException("FLAC encoding requires Android 10 (API 29+).")
        }
        val format = MediaFormat.createAudioFormat(
            MediaFormat.MIMETYPE_AUDIO_FLAC, info.sampleRate, info.channels
        ).apply {
            setInteger(MediaFormat.KEY_FLAC_COMPRESSION_LEVEL, compressionLevel.coerceIn(0, 8))
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 65536)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_FLAC)
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()

            FileOutputStream(outFile).use { fos ->
                streamPcmThroughMediaCodec(srcFile, info, codec, onProgress) { bytes, _, _ ->
                    fos.write(bytes)
                }
            }
        } finally {
            try { codec.stop() } catch (_: Exception) {}
            try { codec.release() } catch (_: Exception) {}
        }
    }

    private suspend fun encodeWithMediaCodecAndMuxer(
        srcFile: File,
        info: com.example.audio.WavInfo,
        outFile: File,
        mime: String,
        format: MediaFormat,
        muxerFormat: Int,
        onProgress: (Float) -> Unit
    ) {
        val codec = MediaCodec.createEncoderByType(mime)
        val muxer = MediaMuxer(outFile.absolutePath, muxerFormat)
        var trackIndex = -1
        var muxerStarted = false

        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()

            streamPcmThroughMediaCodec(
                srcFile = srcFile,
                info = info,
                codec = codec,
                onProgress = onProgress,
                onFormatChanged = { outFmt ->
                    if (!muxerStarted) {
                        trackIndex = muxer.addTrack(outFmt)
                        muxer.start()
                        muxerStarted = true
                    }
                }
            ) { bytes, bufferInfo, isCodecConfig ->
                if (!isCodecConfig && muxerStarted && trackIndex >= 0 && bytes.isNotEmpty()) {
                    val muxInfo = MediaCodec.BufferInfo().apply {
                        set(0, bytes.size, bufferInfo.presentationTimeUs, bufferInfo.flags)
                    }
                    muxer.writeSampleData(trackIndex, ByteBuffer.wrap(bytes), muxInfo)
                }
            }
        } finally {
            try { codec.stop() } catch (_: Exception) {}
            try { codec.release() } catch (_: Exception) {}
            try {
                if (muxerStarted) muxer.stop()
                muxer.release()
            } catch (_: Exception) {}
        }
    }

    private suspend fun streamPcmThroughMediaCodec(
        srcFile: File,
        info: com.example.audio.WavInfo,
        codec: MediaCodec,
        onProgress: (Float) -> Unit,
        onFormatChanged: (MediaFormat) -> Unit = {},
        onEncodedPacket: (ByteArray, MediaCodec.BufferInfo, Boolean) -> Unit
    ) {
        val chunkFrames = 2048
        val floatBuf = FloatArray(chunkFrames * info.channels)
        val bufferInfo = MediaCodec.BufferInfo()
        var framePos = 0L
        var inputDone = false
        var outputDone = false
        var idleAfterEos = 0

        RandomAccessFile(srcFile, "r").use { raf ->
            while (!outputDone) {
                coroutineContext.ensureActive()

                if (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(10_000L)
                    if (inIdx >= 0) {
                        val inBuf = codec.getInputBuffer(inIdx)!!
                        inBuf.clear()
                        val maxFrames = min(chunkFrames, inBuf.capacity() / (info.channels * 2))
                        val read = WavFileHelper.readFramesAsFloat(raf, info, framePos, maxFrames, floatBuf)
                        if (read <= 0) {
                            codec.queueInputBuffer(
                                inIdx, 0, 0,
                                (framePos * 1_000_000L) / info.sampleRate,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            inputDone = true
                        } else {
                            val byteCount = read * info.channels * 2
                            val pcmBytes = ByteBuffer.allocate(byteCount).order(ByteOrder.LITTLE_ENDIAN)
                            for (i in 0 until read * info.channels) {
                                val s = (floatBuf[i].coerceIn(-1f, 1f) * 32767f).toInt().toShort()
                                pcmBytes.putShort(s)
                            }
                            inBuf.put(pcmBytes.array())
                            val ptsUs = (framePos * 1_000_000L) / info.sampleRate
                            codec.queueInputBuffer(inIdx, 0, byteCount, ptsUs, 0)
                            framePos += read
                            onProgress((framePos.toFloat() / info.totalFrames.coerceAtLeast(1L)).coerceIn(0f, 0.98f))
                        }
                    }
                }

                val outIdx = codec.dequeueOutputBuffer(bufferInfo, 10_000L)
                when {
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        idleAfterEos = 0
                        onFormatChanged(codec.outputFormat)
                    }
                    outIdx >= 0 -> {
                        idleAfterEos = 0
                        val outBuf = codec.getOutputBuffer(outIdx)
                        if (outBuf != null && bufferInfo.size > 0) {
                            outBuf.position(bufferInfo.offset)
                            outBuf.limit(bufferInfo.offset + bufferInfo.size)
                            val packet = ByteArray(bufferInfo.size)
                            outBuf.get(packet)
                            val isConfig = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                            onEncodedPacket(packet, bufferInfo, isConfig)
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            outputDone = true
                        }
                    }
                    inputDone -> {
                        idleAfterEos++
                        if (idleAfterEos > 500) break
                    }
                }
            }
        }
    }

    private suspend fun encodeMp3Lame(
        srcFile: File,
        info: com.example.audio.WavInfo,
        outFile: File,
        config: ConversionConfig,
        onProgress: (Float) -> Unit
    ) {
        if (!LameNativeBridge.isAvailable) {
            throw UnsupportedOperationException(LameNativeBridge.unavailableReason)
        }
        val handle = LameNativeBridge.nativeInit(
            inSampleRate = info.sampleRate,
            numChannels = info.channels,
            outSampleRate = info.sampleRate,
            bitrateKbps = config.bitrateKbps,
            vbrQuality = config.mp3VbrQuality,
            useVbr = config.mp3UseVbr
        )
        if (handle == 0L) throw IllegalStateException("LAME nativeInit failed.")

        try {
            val chunkFrames = 4096
            val floatBuf = FloatArray(chunkFrames * info.channels)
            val shortBuf = ShortArray(chunkFrames * info.channels)
            val mp3Buf = ByteArray((1.25 * chunkFrames + 7200).toInt())

            RandomAccessFile(srcFile, "r").use { raf ->
                FileOutputStream(outFile).use { fos ->
                    var pos = 0L
                    while (pos < info.totalFrames) {
                        coroutineContext.ensureActive()
                        val read = WavFileHelper.readFramesAsFloat(raf, info, pos, chunkFrames, floatBuf)
                        if (read <= 0) break
                        for (i in 0 until read * info.channels) {
                            shortBuf[i] = (floatBuf[i].coerceIn(-1f, 1f) * 32767f).toInt().toShort()
                        }
                        val written = LameNativeBridge.nativeEncodeInterleaved(
                            handle, shortBuf, read, info.channels, mp3Buf
                        )
                        if (written > 0) fos.write(mp3Buf, 0, written)
                        pos += read
                        onProgress((pos.toFloat() / info.totalFrames.coerceAtLeast(1L)).coerceIn(0f, 0.98f))
                    }
                    val flushed = LameNativeBridge.nativeFlush(handle, mp3Buf)
                    if (flushed > 0) fos.write(mp3Buf, 0, flushed)
                }
            }
        } finally {
            LameNativeBridge.nativeClose(handle)
        }
    }
}
