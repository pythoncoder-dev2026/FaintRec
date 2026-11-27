package com.example.editor

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.example.audio.WavFileHelper
import com.example.data.EdlOpType
import com.example.data.EdlOperation
import com.example.data.NoiseAddParams
import com.example.data.NoiseLevelParams
import com.example.data.NoiseProfilePayload
import com.example.data.NoiseRemovalParams
import com.example.data.OneTapNoiseTool
import com.example.data.RecordingEntity
import com.example.data.RecordingSourceTag
import com.example.util.AudioMath
import com.example.util.FaintRecFolder
import com.example.util.StorageHelper
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.min

/**
 * Represents a contiguous timeline segment referencing a source file (non-destructive EDL).
 */
data class TimelineSegment(
    val sourceFile: File,
    val startFrame: Long,
    val endFrame: Long, // exclusive
    val sourceSampleRate: Int,
    val sourceChannels: Int
) {
    val frameCount: Long get() = max(0L, endFrame - startFrame)
}

@Singleton
class NonDestructiveEditorEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val storageLocationManager: com.example.storage.StorageLocationManager
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var previewJob: Job? = null
    private var previewTrack: AudioTrack? = null

    @Volatile var liveEqBypass: Boolean = false
    @Volatile var liveEqBandsDb: List<Float> = EqPreset.FLAT.gainsDb
    @Volatile var liveHpfHz: Float = 0f
    @Volatile var liveHpfSlope: Int = 12
    @Volatile var liveLpfHz: Float = 0f
    @Volatile var liveLpfSlope: Int = 12

    // Live real-time Noise Tools parameters for interactive slider preview
    @Volatile var liveNoiseBypass: Boolean = false
    @Volatile var liveNoiseOnlyMode: Boolean = false
    @Volatile var liveNoiseProfile: NoiseProfilePayload? = null
    @Volatile var liveNoiseRemoval: NoiseRemovalParams = NoiseRemovalParams()
    @Volatile var liveOneTapTool: OneTapNoiseTool = OneTapNoiseTool.NONE
    @Volatile var liveNoiseAdd: NoiseAddParams = NoiseAddParams()
    @Volatile var liveNoiseLevel: NoiseLevelParams = NoiseLevelParams()
    @Volatile var liveSelectionStartMs: Long = 0L
    @Volatile var liveSelectionEndMs: Long = Long.MAX_VALUE

    private val _isPreviewPlaying = MutableStateFlow(false)
    val isPreviewPlaying: StateFlow<Boolean> = _isPreviewPlaying.asStateFlow()

    private val _previewPositionMs = MutableStateFlow(0L)
    val previewPositionMs: StateFlow<Long> = _previewPositionMs.asStateFlow()

    private val _beforeAfterDb = MutableStateFlow(AudioMath.MIN_DB to AudioMath.MIN_DB)
    val beforeAfterDb: StateFlow<Pair<Float, Float>> = _beforeAfterDb.asStateFlow()

    /**
     * Resolves non-destructive Trim / Cut / Merge operations into a virtual timeline of source segments
     * without ever modifying the original audio file.
     */
    fun buildVirtualTimeline(
        originalFile: File,
        operations: List<EdlOperation>
    ): Pair<List<TimelineSegment>, Int> {
        val baseInfo = WavFileHelper.parseWavInfo(originalFile) ?: return emptyList<TimelineSegment>() to 48000
        val sr = baseInfo.sampleRate
        var segments = mutableListOf(
            TimelineSegment(originalFile, 0L, baseInfo.totalFrames, sr, baseInfo.channels)
        )

        for (op in operations) {
            when (op.type) {
                EdlOpType.TRIM -> {
                    val sFrame = (op.startMs * sr) / 1000L
                    val eFrame = (op.endMs * sr) / 1000L
                    if (eFrame > sFrame) {
                        segments = sliceSegments(segments, sFrame, eFrame)
                    }
                }
                EdlOpType.CUT -> {
                    val sFrame = (op.startMs * sr) / 1000L
                    val eFrame = (op.endMs * sr) / 1000L
                    if (eFrame > sFrame) {
                        val total = segments.sumOf { it.frameCount }
                        val before = if (sFrame > 0) sliceSegments(segments, 0L, sFrame) else mutableListOf()
                        val after = if (eFrame < total) sliceSegments(segments, eFrame, total) else mutableListOf()
                        segments = (before + after).toMutableList()
                    }
                }
                EdlOpType.MERGE_APPEND -> {
                    val mPath = op.mergeSourcePath
                    if (!mPath.isNullOrBlank()) {
                        val mFile = File(mPath)
                        val mInfo = WavFileHelper.parseWavInfo(mFile)
                        if (mInfo != null && mInfo.totalFrames > 0) {
                            segments.add(
                                TimelineSegment(mFile, 0L, mInfo.totalFrames, mInfo.sampleRate, mInfo.channels)
                            )
                        }
                    }
                }
                else -> Unit
            }
        }
        return segments to sr
    }

    fun startLivePreview(
        originalFile: File,
        operations: List<EdlOperation>,
        startMs: Long = 0L,
        endMs: Long = Long.MAX_VALUE
    ) {
        stopPreview()
        val baseInfo = WavFileHelper.parseWavInfo(originalFile) ?: return
        val (segments, sr) = buildVirtualTimeline(originalFile, operations)
        if (segments.isEmpty()) return

        val playRate = sr.coerceIn(8000, 96000)
        val ch = baseInfo.channels.coerceIn(1, 2)
        val chMask = if (ch == 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO

        previewJob = scope.launch {
            val minBuf = AudioTrack.getMinBufferSize(playRate, chMask, AudioFormat.ENCODING_PCM_FLOAT)
                .coerceAtLeast(4096)
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                        .setSampleRate(playRate)
                        .setChannelMask(chMask)
                        .build()
                )
                .setBufferSizeInBytes(minBuf * 3)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            previewTrack = track
            track.play()
            _isPreviewPlaying.value = true

            val eqChain = GraphicEqAndFilterChain(sr, ch)
            val startFrame = (startMs * sr) / 1000L
            val endFrame = if (endMs == Long.MAX_VALUE) Long.MAX_VALUE else (endMs * sr) / 1000L

            try {
                streamTimelineChunks(
                    segments = segments,
                    targetSampleRate = sr,
                    targetChannels = ch,
                    operations = operations,
                    startTimelineFrame = startFrame,
                    endTimelineFrame = endFrame,
                    lufsGainLinear = computeLufsGainIfNeeded(originalFile, operations),
                    useLivePreviewOverrides = true,
                    eqChain = eqChain
                ) { chunk, frames, currentTimelineFrame ->
                    if (!isActive) return@streamTimelineChunks false
                    track.write(chunk, 0, frames * ch, AudioTrack.WRITE_BLOCKING)
                    _previewPositionMs.value = (currentTimelineFrame * 1000L) / sr
                    true
                }
            } catch (_: Exception) {
            } finally {
                try {
                    track.pause()
                    track.flush()
                    track.release()
                } catch (_: Exception) {
                }
                previewTrack = null
                _isPreviewPlaying.value = false
            }
        }
    }

    fun stopPreview() {
        previewJob?.cancel()
        previewJob = null
        try {
            previewTrack?.pause()
            previewTrack?.flush()
            previewTrack?.release()
        } catch (_: Exception) {
        }
        previewTrack = null
        _isPreviewPlaying.value = false
    }

    /**
     * Renders the non-destructive EDL (including Noise Tools) into a new WAV file in chunks
     * on Dispatchers.IO, keeping the original file completely untouched.
     */
    suspend fun renderEdlToWav(
        source: RecordingEntity,
        operations: List<EdlOperation>,
        customSuffix: String = "edited",
        onProgress: (Float) -> Unit = {}
    ): Result<RecordingEntity> = withContext(Dispatchers.IO) {
        runCatching {
            val origFile = storageLocationManager.resolveReadableFile(source)
                ?: throw IllegalStateException("Original WAV cannot be read (storage unavailable).")
            val baseInfo = WavFileHelper.parseWavInfo(origFile)
                ?: throw IllegalStateException("Original WAV cannot be read.")
            val (segments, sr) = buildVirtualTimeline(origFile, operations)
            if (segments.isEmpty()) throw IllegalStateException("Timeline is empty after edits.")

            val ch = baseInfo.channels.coerceIn(1, 2)
            val baseName = StorageHelper.sanitizeFileName(source.title).substringBeforeLast('.')
            val outName = "${baseName}_${customSuffix}_${System.currentTimeMillis() % 10000}.wav"
            val outFile = StorageHelper.createWorkingFile(context, FaintRecFolder.PROCESSED, outName)
            val eqChain = GraphicEqAndFilterChain(sr, ch)
            val lufsGain = computeLufsGainIfNeeded(origFile, operations)
            val totalExpectedFrames = segments.sumOf {
                ((it.frameCount.toDouble() * sr) / it.sourceSampleRate.coerceAtLeast(1)).toLong()
            }.coerceAtLeast(1L)

            var dataBytes = 0L
            RandomAccessFile(outFile, "rw").use { outRaf ->
                outRaf.setLength(0L)
                WavFileHelper.writePlaceholderHeader(
                    outRaf, sr, ch, if (baseInfo.isFloat) 32 else 16, baseInfo.isFloat
                )

                streamTimelineChunks(
                    segments = segments,
                    targetSampleRate = sr,
                    targetChannels = ch,
                    operations = operations,
                    startTimelineFrame = 0L,
                    endTimelineFrame = Long.MAX_VALUE,
                    lufsGainLinear = lufsGain,
                    useLivePreviewOverrides = false,
                    eqChain = eqChain
                ) { chunk, frames, currentFrame ->
                    dataBytes += WavFileHelper.writeFloatSamples(
                        outRaf, chunk, frames * ch, baseInfo.isFloat
                    )
                    onProgress((currentFrame.toFloat() / totalExpectedFrames).coerceIn(0f, 1f))
                    true
                }

                WavFileHelper.patchWavHeaderSizes(outRaf, dataBytes)
            }

            val outInfo = WavFileHelper.parseWavInfo(outFile)
            val mediaUri = StorageHelper.publishToMediaStore(
                context, outFile, FaintRecFolder.PROCESSED, "audio/wav", outFile.name
            )
            RecordingEntity(
                title = "${source.title} [Edited]",
                filePath = outFile.absolutePath,
                mediaStoreUri = mediaUri,
                durationMs = outInfo?.durationMs ?: source.durationMs,
                fileSizeBytes = outFile.length(),
                format = "WAV",
                sampleRate = sr,
                channels = ch,
                bitsPerSample = if (baseInfo.isFloat) 32 else 16,
                isFloatPcm = baseInfo.isFloat,
                sourceTag = RecordingSourceTag.PROCESSED,
                audioSourceUsed = source.audioSourceUsed,
                inputDeviceName = source.inputDeviceName
            )
        }
    }

    /**
     * Splits a recording at [splitPointMs] into two brand-new non-destructive WAV files (Part A & Part B).
     */
    suspend fun splitRecordingAt(
        source: RecordingEntity,
        splitPointMs: Long,
        onProgress: (Float) -> Unit = {}
    ): Result<Pair<RecordingEntity, RecordingEntity>> = withContext(Dispatchers.IO) {
        runCatching {
            val partAOp = EdlOperation(
                id = "split_a",
                type = EdlOpType.TRIM,
                label = "Split Part 1",
                startMs = 0L,
                endMs = splitPointMs
            )
            val partBOp = EdlOperation(
                id = "split_b",
                type = EdlOpType.TRIM,
                label = "Split Part 2",
                startMs = splitPointMs,
                endMs = source.durationMs
            )
            val a = renderEdlToWav(source, listOf(partAOp), "part1") { onProgress(it * 0.5f) }.getOrThrow()
            val b = renderEdlToWav(source, listOf(partBOp), "part2") { onProgress(0.5f + it * 0.5f) }.getOrThrow()
            a.copy(title = "${source.title} (Part 1)") to b.copy(title = "${source.title} (Part 2)")
        }
    }

    private suspend fun computeLufsGainIfNeeded(
        origFile: File,
        operations: List<EdlOperation>
    ): Float {
        val lufsOp = operations.lastOrNull { it.type == EdlOpType.LUFS_NORMALIZE } ?: return 1f
        val measured = LufsMeter.measureFile(origFile) ?: return 1f
        val deltaDb = (lufsOp.targetLufs - measured.integratedLufs).coerceIn(-30f, 30f)
        return AudioMath.dbToLinear(deltaDb)
    }

    private fun streamTimelineChunks(
        segments: List<TimelineSegment>,
        targetSampleRate: Int,
        targetChannels: Int,
        operations: List<EdlOperation>,
        startTimelineFrame: Long,
        endTimelineFrame: Long,
        lufsGainLinear: Float,
        useLivePreviewOverrides: Boolean,
        eqChain: GraphicEqAndFilterChain,
        onChunk: (FloatArray, Int, Long) -> Boolean
    ) {
        val lastEq = operations.lastOrNull { it.type == EdlOpType.EQ }
        val lastFilter = operations.lastOrNull { it.type == EdlOpType.FILTER }
        val lufsOp = operations.lastOrNull { it.type == EdlOpType.LUFS_NORMALIZE }
        val peakCeiling = if (lufsOp != null) AudioMath.dbToLinear(lufsOp.truePeakCeilingDb) else 0.98f

        // Committed EDL Noise Tools ops (used during render or as baseline)
        val edlNoiseRemovalOp = operations.lastOrNull { it.type == EdlOpType.NOISE_REMOVAL }
        val edlNoiseLevelOp = operations.lastOrNull { it.type == EdlOpType.NOISE_LEVEL_CONTROL }
        val edlOneTapOps = operations.filter { it.type == EdlOpType.NOISE_ONE_TAP }
        val edlNoiseAddOps = operations.filter { it.type == EdlOpType.NOISE_ADD }
        val edlNoiseProfile = operations.lastOrNull { it.noiseProfile != null }?.noiseProfile

        val spectralProcessor = SpectralNoiseProcessor(targetSampleRate, targetChannels, fftSize = 1024)
        val oneTapProcessors = if (useLivePreviewOverrides) {
            listOf(OneTapNoiseProcessor(targetSampleRate, targetChannels))
        } else {
            edlOneTapOps.map { OneTapNoiseProcessor(targetSampleRate, targetChannels) }
        }
        val liveNoiseAddSynth = NoiseAddSynthesizer(targetSampleRate, targetChannels, seed = 2026)
        val edlNoiseAddSynths = edlNoiseAddOps.mapIndexed { idx, _ ->
            NoiseAddSynthesizer(targetSampleRate, targetChannels, seed = 4000 + idx)
        }

        if (!useLivePreviewOverrides) {
            eqChain.configure(
                bandsDb = lastEq?.eqBandsDb ?: EqPreset.FLAT.gainsDb,
                hpfHz = lastFilter?.hpfHz ?: 0f,
                hpfSlopeDb = lastFilter?.hpfSlopeDb ?: 12,
                lpfHz = lastFilter?.lpfHz ?: 0f,
                lpfSlopeDb = lastFilter?.lpfSlopeDb ?: 12
            )
        }

        val totalTimelineFrames = segments.sumOf {
            ((it.frameCount.toDouble() * targetSampleRate) / it.sourceSampleRate.coerceAtLeast(1)).toLong()
        }.coerceAtLeast(1L)

        val chunkFrames = 2048
        var timelineCursor = 0L

        for (seg in segments) {
            val segInfo = WavFileHelper.parseWavInfo(seg.sourceFile) ?: continue
            val ratio = segInfo.sampleRate.toDouble() / targetSampleRate.toDouble()
            val outSegFrames = ((seg.frameCount.toDouble() / ratio)).toLong()
            if (timelineCursor + outSegFrames <= startTimelineFrame) {
                timelineCursor += outSegFrames
                continue
            }
            if (timelineCursor >= endTimelineFrame) break

            val rawBuf = FloatArray((chunkFrames * ratio + 8).toInt().coerceAtLeast(chunkFrames) * segInfo.channels)
            val outBuf = FloatArray(chunkFrames * targetChannels)

            RandomAccessFile(seg.sourceFile, "r").use { raf ->
                var segOutPos = max(0L, startTimelineFrame - timelineCursor)
                while (segOutPos < outSegFrames) {
                    val globalFrame = timelineCursor + segOutPos
                    if (globalFrame >= endTimelineFrame) return
                    val framesThisChunk = min(
                        chunkFrames.toLong(),
                        min(outSegFrames - segOutPos, endTimelineFrame - globalFrame)
                    ).toInt()
                    if (framesThisChunk <= 0) break

                    val srcStart = seg.startFrame + (segOutPos * ratio).toLong()
                    val srcNeed = ((framesThisChunk * ratio).toInt() + 2)
                        .coerceAtMost((seg.endFrame - srcStart).toInt().coerceAtLeast(1))
                    val readSrc = WavFileHelper.readFramesAsFloat(raf, segInfo, srcStart, srcNeed, rawBuf)
                    if (readSrc <= 0) break

                    // Resample + channel convert into outBuf
                    for (f in 0 until framesThisChunk) {
                        val srcPosF = f * ratio
                        val i0 = srcPosF.toInt().coerceIn(0, readSrc - 1)
                        val i1 = (i0 + 1).coerceIn(0, readSrc - 1)
                        val frac = (srcPosF - i0).toFloat()

                        for (tc in 0 until targetChannels) {
                            val s0 = sampleForTargetChannel(rawBuf, i0, segInfo.channels, tc)
                            val s1 = sampleForTargetChannel(rawBuf, i1, segInfo.channels, tc)
                            outBuf[f * targetChannels + tc] = s0 + (s1 - s0) * frac
                        }
                    }

                    // Apply time-range operations (Fade In, Fade Out, Silence, Gain)
                    applyTimeRangeOperations(
                        buffer = outBuf,
                        frames = framesThisChunk,
                        channels = targetChannels,
                        chunkStartFrame = globalFrame,
                        sampleRate = targetSampleRate,
                        operations = operations
                    )

                    val totalSamples = framesThisChunk * targetChannels
                    val beforeDb = AudioMath.computeRmsDb(outBuf, totalSamples)

                    // Apply Noise Tools (Real-time live sliders in preview OR committed EDL ops in render)
                    if (useLivePreviewOverrides) {
                        if (!liveNoiseBypass) {
                            val oneTap = liveOneTapTool
                            if (oneTap != OneTapNoiseTool.NONE) {
                                oneTapProcessors[0].processInterleaved(outBuf, framesThisChunk, oneTap)
                            }
                            spectralProcessor.processInterleaved(
                                buffer = outBuf,
                                frames = framesThisChunk,
                                profile = liveNoiseProfile ?: edlNoiseProfile,
                                removal = liveNoiseRemoval,
                                level = liveNoiseLevel,
                                noiseOnlyMode = liveNoiseOnlyMode
                            )
                            val addParams = liveNoiseAdd
                            val levelOverride = if (liveNoiseLevel.enabled && addParams.enabled && !addParams.fixedSnrMode) {
                                liveNoiseLevel.addedNoiseDb
                            } else null
                            liveNoiseAddSynth.mixIntoChunk(
                                buffer = outBuf,
                                frames = framesThisChunk,
                                chunkStartFrame = globalFrame,
                                totalTimelineFrames = totalTimelineFrames,
                                params = addParams,
                                selectionStartFrame = (liveSelectionStartMs * targetSampleRate) / 1000L,
                                selectionEndFrame = if (liveSelectionEndMs == Long.MAX_VALUE) totalTimelineFrames
                                else (liveSelectionEndMs * targetSampleRate) / 1000L,
                                learnedProfile = liveNoiseProfile ?: edlNoiseProfile,
                                overrideVolumeDb = levelOverride,
                                noiseOnlyMode = liveNoiseOnlyMode && !liveNoiseRemoval.enabled && !liveNoiseLevel.enabled
                            )
                        }
                    } else {
                        // Render all committed Noise Tools operations non-destructively
                        edlOneTapOps.forEachIndexed { idx, op ->
                            val tool = op.oneTapNoiseTool ?: OneTapNoiseTool.NONE
                            if (tool != OneTapNoiseTool.NONE) {
                                oneTapProcessors[idx].processInterleaved(outBuf, framesThisChunk, tool)
                            }
                        }
                        val remParams = edlNoiseRemovalOp?.noiseRemoval ?: NoiseRemovalParams(enabled = false)
                        val lvlParams = edlNoiseLevelOp?.noiseLevel ?: NoiseLevelParams(enabled = false)
                        if (remParams.enabled || lvlParams.enabled) {
                            spectralProcessor.processInterleaved(
                                buffer = outBuf,
                                frames = framesThisChunk,
                                profile = edlNoiseRemovalOp?.noiseProfile
                                    ?: edlNoiseLevelOp?.noiseProfile
                                    ?: edlNoiseProfile,
                                removal = remParams,
                                level = lvlParams,
                                noiseOnlyMode = false
                            )
                        }
                        edlNoiseAddOps.forEachIndexed { idx, op ->
                            val params = op.noiseAdd ?: return@forEachIndexed
                            val levelOverride = if (lvlParams.enabled && !params.fixedSnrMode) {
                                lvlParams.addedNoiseDb
                            } else null
                            edlNoiseAddSynths[idx].mixIntoChunk(
                                buffer = outBuf,
                                frames = framesThisChunk,
                                chunkStartFrame = globalFrame,
                                totalTimelineFrames = totalTimelineFrames,
                                params = params,
                                selectionStartFrame = (op.startMs * targetSampleRate) / 1000L,
                                selectionEndFrame = if (op.endMs > op.startMs) (op.endMs * targetSampleRate) / 1000L
                                else totalTimelineFrames,
                                learnedProfile = op.noiseProfile ?: edlNoiseProfile,
                                overrideVolumeDb = levelOverride,
                                noiseOnlyMode = false
                            )
                        }
                    }

                    // Apply EQ + High-Pass / Low-Pass filters (supports live A/B bypass during preview)
                    if (useLivePreviewOverrides) {
                        if (!liveEqBypass) {
                            eqChain.configure(liveEqBandsDb, liveHpfHz, liveHpfSlope, liveLpfHz, liveLpfSlope)
                            eqChain.processInterleaved(outBuf, framesThisChunk)
                        }
                    } else {
                        eqChain.processInterleaved(outBuf, framesThisChunk)
                    }

                    // Apply LUFS normalization + Soft Limiter against clipping on every chunk
                    for (i in 0 until totalSamples) {
                        outBuf[i] = AudioMath.softLimit(outBuf[i] * lufsGainLinear, ceiling = peakCeiling)
                    }

                    if (useLivePreviewOverrides) {
                        val afterDb = AudioMath.computeRmsDb(outBuf, totalSamples)
                        _beforeAfterDb.value = beforeDb to afterDb
                    }

                    val keepGoing = onChunk(outBuf, framesThisChunk, globalFrame)
                    if (!keepGoing) return
                    segOutPos += framesThisChunk
                }
            }
            timelineCursor += outSegFrames
        }
    }

    private fun applyTimeRangeOperations(
        buffer: FloatArray,
        frames: Int,
        channels: Int,
        chunkStartFrame: Long,
        sampleRate: Int,
        operations: List<EdlOperation>
    ) {
        for (op in operations) {
            if (op.type !in setOf(EdlOpType.FADE_IN, EdlOpType.FADE_OUT, EdlOpType.SILENCE, EdlOpType.GAIN)) {
                continue
            }
            val sFrame = (op.startMs * sampleRate) / 1000L
            val eFrame = (op.endMs * sampleRate) / 1000L
            if (eFrame <= sFrame) continue
            val chunkEnd = chunkStartFrame + frames
            if (chunkEnd <= sFrame || chunkStartFrame >= eFrame) continue

            val span = (eFrame - sFrame).toFloat().coerceAtLeast(1f)
            val gainLin = if (op.type == EdlOpType.GAIN) AudioMath.dbToLinear(op.gainDb) else 1f

            for (f in 0 until frames) {
                val gf = chunkStartFrame + f
                if (gf in sFrame until eFrame) {
                    val mult = when (op.type) {
                        EdlOpType.SILENCE -> 0f
                        EdlOpType.FADE_IN -> ((gf - sFrame) / span).coerceIn(0f, 1f)
                        EdlOpType.FADE_OUT -> (1f - (gf - sFrame) / span).coerceIn(0f, 1f)
                        EdlOpType.GAIN -> gainLin
                        else -> 1f
                    }
                    for (c in 0 until channels) {
                        val idx = f * channels + c
                        buffer[idx] = AudioMath.softLimit(buffer[idx] * mult)
                    }
                }
            }
        }
    }

    private fun sampleForTargetChannel(buf: FloatArray, frame: Int, srcCh: Int, targetCh: Int): Float {
        return when {
            srcCh == 1 -> buf[frame]
            targetCh < srcCh -> buf[frame * srcCh + targetCh]
            else -> {
                var sum = 0f
                for (c in 0 until srcCh) sum += buf[frame * srcCh + c]
                sum / srcCh
            }
        }
    }

    private fun sliceSegments(
        segments: List<TimelineSegment>,
        keepStart: Long,
        keepEnd: Long
    ): MutableList<TimelineSegment> {
        val out = mutableListOf<TimelineSegment>()
        var cursor = 0L
        for (seg in segments) {
            val segEnd = cursor + seg.frameCount
            val ovStart = max(cursor, keepStart)
            val ovEnd = min(segEnd, keepEnd)
            if (ovEnd > ovStart) {
                val relStart = ovStart - cursor
                val relEnd = ovEnd - cursor
                out.add(
                    seg.copy(
                        startFrame = seg.startFrame + relStart,
                        endFrame = seg.startFrame + relEnd
                    )
                )
            }
            cursor = segEnd
        }
        return out
    }
}
