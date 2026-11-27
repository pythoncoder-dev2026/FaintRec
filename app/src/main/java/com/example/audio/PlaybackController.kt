package com.example.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.PlaybackParams
import android.media.audiofx.LoudnessEnhancer
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.example.analysis.FftProcessor
import com.example.analysis.FftResult
import com.example.analysis.FftWindowType
import com.example.data.AppFeature
import com.example.data.RecordingEntity
import com.example.data.RecordingSourceTag
import com.example.logging.AppLogger
import com.example.storage.StorageLocationManager
import com.example.util.AudioMath
import com.example.util.FaintRecFolder
import com.example.util.StorageHelper
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
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
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

enum class PlaybackDirection { FORWARD, REVERSE }
enum class PitchMode { PRESERVE_PITCH, TAPE_MODE }

data class PlaybackUiState(
    val activeRecording: RecordingEntity? = null,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val direction: PlaybackDirection = PlaybackDirection.FORWARD,
    val forwardSpeed: Float = 1.0f,
    val reverseSpeed: Float = 1.0f,
    val pitchMode: PitchMode = PitchMode.PRESERVE_PITCH,
    val gainBoostDb: Float = 0f,
    val showHearingWarning: Boolean = false
) {
    val activeSpeed: Float
        get() = if (direction == PlaybackDirection.FORWARD) forwardSpeed else reverseSpeed
}

/**
 * Dual-engine player:
 * 1) Forward playback via Media3 ExoPlayer with live PlaybackParameters(speed, pitch) & LoudnessEnhancer gain boost.
 * 2) Reverse playback via custom chunked AudioTrack streaming WAV PCM from end to start, reversing channel frames,
 *    with live speed (0.25x–4x), pitch mode, gain boost + soft limiter, pause/resume, and accurate reverse seeking.
 * Also feeds live playback FFT spectrum and exports reversed / speed-changed WAV copies.
 */
@Singleton
class PlaybackController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val logger: AppLogger,
    private val storageLocationManager: StorageLocationManager
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow(PlaybackUiState())
    val state: StateFlow<PlaybackUiState> = _state.asStateFlow()

    private val _playbackFft = MutableStateFlow<FftResult?>(null)
    val playbackFft: StateFlow<FftResult?> = _playbackFft.asStateFlow()

    private var exoPlayer: ExoPlayer? = null
    private var loudnessEnhancer: LoudnessEnhancer? = null
    private var positionTickerJob: Job? = null

    // Reverse AudioTrack streaming state
    private var reverseJob: Job? = null
    private var reverseTrack: AudioTrack? = null
    @Volatile private var reverseFrameCursor: Long = 0L
    @Volatile private var reverseSeekTargetFrame: Long = -1L
    @Volatile private var reversePaused: Boolean = false

    private val fftProcessor = FftProcessor(2048, FftWindowType.HANN)

    fun configureFft(size: Int, window: FftWindowType) {
        fftProcessor.configure(size, window)
    }

    fun playRecording(recording: RecordingEntity, startPositionMs: Long = 0L) {
        val current = _state.value
        val same = current.activeRecording?.id == recording.id
        if (!same) {
            stopAll()
        }
        val clampedStart = startPositionMs.coerceIn(0L, recording.durationMs.coerceAtLeast(1L))
        _state.value = _state.value.copy(
            activeRecording = recording,
            durationMs = recording.durationMs,
            positionMs = clampedStart
        )
        if (_state.value.direction == PlaybackDirection.FORWARD) {
            startForwardPlayback(recording, clampedStart)
        } else {
            val startPos = if (clampedStart == 0L && !same) recording.durationMs else clampedStart
            startReversePlayback(recording, startPos)
        }
    }

    fun togglePlayPause() {
        val st = _state.value
        val rec = st.activeRecording ?: return
        if (st.direction == PlaybackDirection.FORWARD) {
            val player = exoPlayer
            if (player == null) {
                startForwardPlayback(rec, st.positionMs)
            } else if (player.isPlaying) {
                player.pause()
                _state.value = _state.value.copy(isPlaying = false)
            } else {
                if (player.playbackState == Player.STATE_ENDED) {
                    player.seekTo(0L)
                }
                player.play()
                _state.value = _state.value.copy(isPlaying = true)
            }
        } else {
            if (reverseJob?.isActive == true) {
                reversePaused = !reversePaused
                if (reversePaused) {
                    try { reverseTrack?.pause() } catch (_: Exception) {}
                } else {
                    try { reverseTrack?.play() } catch (_: Exception) {}
                }
                _state.value = _state.value.copy(isPlaying = !reversePaused)
            } else {
                val pos = if (st.positionMs <= 50L) rec.durationMs else st.positionMs
                startReversePlayback(rec, pos)
            }
        }
    }

    fun seekTo(positionMs: Long) {
        val st = _state.value
        val rec = st.activeRecording ?: return
        val clamped = positionMs.coerceIn(0L, st.durationMs.coerceAtLeast(1L))
        _state.value = st.copy(positionMs = clamped)

        if (st.direction == PlaybackDirection.FORWARD) {
            exoPlayer?.seekTo(clamped)
        } else {
            val file = storageLocationManager.resolveReadableFile(rec)
            val info = file?.let { WavFileHelper.parseWavInfo(it) }
            if (info != null) {
                reverseSeekTargetFrame = ((clamped * info.sampleRate) / 1000L)
                    .coerceIn(0L, info.totalFrames)
            }
        }
    }

    fun setDirection(direction: PlaybackDirection) {
        val st = _state.value
        if (st.direction == direction) return
        val wasPlaying = st.isPlaying
        val pos = st.positionMs
        val rec = st.activeRecording
        stopEnginesOnly()
        _state.value = _state.value.copy(direction = direction, isPlaying = false)
        if (rec != null && wasPlaying) {
            if (direction == PlaybackDirection.FORWARD) {
                startForwardPlayback(rec, pos)
            } else {
                val startReverse = if (pos <= 100L) rec.durationMs else pos
                startReversePlayback(rec, startReverse)
            }
        }
    }

    fun setSpeed(speed: Float) {
        val clamped = speed.coerceIn(0.25f, 4.0f)
        val st = _state.value
        val updated = if (st.direction == PlaybackDirection.FORWARD) {
            st.copy(forwardSpeed = clamped)
        } else {
            st.copy(reverseSpeed = clamped)
        }
        _state.value = updated
        applyLiveForwardParams()
        applyLiveReverseParams()
        logger.reportSuccess(AppFeature.SPEED_CONTROL, "Speed set to ${String.format(java.util.Locale.US, "%.2fx", clamped)}")
    }

    fun setPitchMode(mode: PitchMode) {
        _state.value = _state.value.copy(pitchMode = mode)
        applyLiveForwardParams()
        applyLiveReverseParams()
    }

    fun setGainBoostDb(gainDb: Float) {
        val safeClamped = gainDb.coerceIn(0f, AudioMath.SAFE_MAX_PLAYBACK_BOOST_DB)
        _state.value = _state.value.copy(
            gainBoostDb = safeClamped,
            showHearingWarning = safeClamped >= AudioMath.HEARING_WARNING_THRESHOLD_DB
        )
        applyForwardGainBoost(safeClamped)
    }

    fun stopAll() {
        stopEnginesOnly()
        _state.value = _state.value.copy(isPlaying = false, positionMs = 0L)
    }

    private fun stopEnginesOnly() {
        positionTickerJob?.cancel()
        positionTickerJob = null
        reverseJob?.cancel()
        reverseJob = null
        reversePaused = false
        try {
            reverseTrack?.pause()
            reverseTrack?.flush()
            reverseTrack?.release()
        } catch (_: Exception) {
        }
        reverseTrack = null

        try {
            loudnessEnhancer?.release()
        } catch (_: Exception) {
        }
        loudnessEnhancer = null

        try {
            exoPlayer?.stop()
            exoPlayer?.release()
        } catch (_: Exception) {
        }
        exoPlayer = null
    }

    private fun startForwardPlayback(recording: RecordingEntity, startMs: Long) {
        stopEnginesOnly()
        val playUri = storageLocationManager.resolvePlaybackUri(recording) ?: run {
            logger.reportFailure(AppFeature.PLAYBACK_FORWARD, "Recording file unavailable (${recording.storageType.name})")
            return
        }

        val player = ExoPlayer.Builder(context).build().also { exoPlayer = it }
        player.setMediaItem(MediaItem.fromUri(playUri))
        player.prepare()
        player.seekTo(startMs)
        applyLiveForwardParams()

        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    attachLoudnessEnhancer(player.audioSessionId)
                } else if (playbackState == Player.STATE_ENDED) {
                    _state.value = _state.value.copy(isPlaying = false, positionMs = recording.durationMs)
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _state.value = _state.value.copy(isPlaying = isPlaying)
            }
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                logger.reportFailure(AppFeature.PLAYBACK_FORWARD, "ExoPlayer error: ${error.message}", error)
            }
        })

        player.play()
        _state.value = _state.value.copy(isPlaying = true)
        logger.reportSuccess(AppFeature.PLAYBACK_FORWARD, "Forward playback started (${recording.format}, ${recording.sampleRate} Hz)")

        // Position ticker + background FFT slice reader for playback spectrum analyzer
        val directLocalFile = recording.filePath.takeIf { it.isNotBlank() }?.let { File(it) }?.takeIf { it.exists() }
        val wavInfo = directLocalFile?.let { WavFileHelper.parseWavInfo(it) }
        positionTickerJob = scope.launch {
            while (isActive) {
                val p = exoPlayer ?: break
                val pos = p.currentPosition.coerceAtLeast(0L)
                val dur = p.duration.takeIf { it > 0L } ?: recording.durationMs
                _state.value = _state.value.copy(positionMs = pos, durationMs = dur)

                if (p.isPlaying && directLocalFile != null && wavInfo != null) {
                    launch(Dispatchers.Default) {
                        analyzeWavSliceForFft(directLocalFile, wavInfo, pos)
                    }
                }
                delay(70L)
            }
        }
    }

    private fun attachLoudnessEnhancer(sessionId: Int) {
        if (sessionId <= 0) return
        try {
            loudnessEnhancer?.release()
            loudnessEnhancer = LoudnessEnhancer(sessionId).apply {
                setTargetGain((_state.value.gainBoostDb * 100f).toInt()) // millibels
                enabled = _state.value.gainBoostDb > 0.1f
            }
        } catch (_: Exception) {
        }
    }

    private fun applyForwardGainBoost(gainDb: Float) {
        try {
            loudnessEnhancer?.setTargetGain((gainDb * 100f).toInt())
            loudnessEnhancer?.enabled = gainDb > 0.1f
        } catch (_: Exception) {
        }
    }

    private fun applyLiveForwardParams() {
        val player = exoPlayer ?: return
        val st = _state.value
        val speed = st.forwardSpeed.coerceIn(0.25f, 4.0f)
        val pitch = if (st.pitchMode == PitchMode.PRESERVE_PITCH) 1.0f else speed
        player.playbackParameters = PlaybackParameters(speed, pitch)
    }

    private fun applyLiveReverseParams() {
        val track = reverseTrack ?: return
        val st = _state.value
        val speed = st.reverseSpeed.coerceIn(0.25f, 4.0f)
        try {
            val params = PlaybackParams().apply {
                this.speed = speed
                this.pitch = if (st.pitchMode == PitchMode.PRESERVE_PITCH) 1.0f else speed
            }
            track.playbackParams = params
        } catch (_: Exception) {
            // Fallback for HALs that cap PlaybackParams to 0.5x–2.0x
            try {
                val safeSpeed = speed.coerceIn(0.5f, 2.0f)
                track.playbackParams = PlaybackParams().apply {
                    this.speed = safeSpeed
                    this.pitch = if (st.pitchMode == PitchMode.PRESERVE_PITCH) 1.0f else safeSpeed
                }
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Streams WAV PCM backwards in chunks from [startMs] down to 0 ms, reversing sample frames
     * while preserving stereo L/R channel order, applying software gain boost + soft limiter.
     */
    private fun startReversePlayback(recording: RecordingEntity, startMs: Long) {
        stopEnginesOnly()
        val file = storageLocationManager.resolveReadableFile(recording)
        val info = file?.let { WavFileHelper.parseWavInfo(it) }
        if (file == null || info == null) {
            // Non-WAV converted files fall back to forward ExoPlayer seamlessly
            _state.value = _state.value.copy(direction = PlaybackDirection.FORWARD)
            startForwardPlayback(recording, startMs)
            return
        }

        reversePaused = false
        reverseSeekTargetFrame = -1L
        reverseFrameCursor = ((startMs * info.sampleRate) / 1000L).coerceIn(1L, info.totalFrames)
        _state.value = _state.value.copy(
            isPlaying = true,
            durationMs = info.durationMs,
            positionMs = startMs
        )

        reverseJob = scope.launch(Dispatchers.IO) {
            val chMask = if (info.channels >= 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
            val playRate = info.sampleRate.coerceIn(8000, 96000)
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

            reverseTrack = track
            applyLiveReverseParams()
            track.play()
            logger.reportSuccess(AppFeature.PLAYBACK_REVERSE, "Reverse AudioTrack playback started (${info.sampleRate} Hz)")

            val chunkFrames = 4096
            val readBuf = FloatArray(chunkFrames * info.channels)
            val reversedBuf = FloatArray(chunkFrames * info.channels)
            val monoFft = FloatArray(chunkFrames)

            try {
                RandomAccessFile(file, "r").use { raf ->
                    while (isActive && reverseFrameCursor > 0L) {
                        if (reversePaused) {
                            delay(35L)
                            continue
                        }
                        val pendingSeek = reverseSeekTargetFrame
                        if (pendingSeek >= 0L) {
                            reverseFrameCursor = pendingSeek
                            reverseSeekTargetFrame = -1L
                            track.flush()
                        }

                        val framesToRead = min(chunkFrames.toLong(), reverseFrameCursor).toInt()
                        if (framesToRead <= 0) break
                        val startFrame = reverseFrameCursor - framesToRead
                        val actualRead = WavFileHelper.readFramesAsFloat(
                            raf, info, startFrame, framesToRead, readBuf
                        )
                        if (actualRead <= 0) break

                        val gainLin = AudioMath.dbToLinear(_state.value.gainBoostDb)
                        val ch = info.channels
                        // Reverse frame order while preserving channel order within each frame!
                        for (f in 0 until actualRead) {
                            val srcFrame = actualRead - 1 - f
                            var monoSum = 0f
                            for (c in 0 until ch) {
                                val raw = readBuf[srcFrame * ch + c]
                                val boosted = AudioMath.softLimit(raw * gainLin)
                                reversedBuf[f * ch + c] = boosted
                                monoSum += boosted
                            }
                            monoFft[f] = monoSum / ch
                        }

                        track.write(reversedBuf, 0, actualRead * ch, AudioTrack.WRITE_BLOCKING)
                        reverseFrameCursor = startFrame

                        val posMs = (reverseFrameCursor * 1000L) / info.sampleRate.coerceAtLeast(1)
                        _state.value = _state.value.copy(positionMs = posMs)

                        if (actualRead >= 1024) {
                            _playbackFft.value = fftProcessor.analyze(
                                monoFft.copyOfRange(0, actualRead),
                                info.sampleRate
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                logger.reportFailure(AppFeature.PLAYBACK_REVERSE, "Reverse playback error: ${e.message}", e)
            } finally {
                try {
                    track.pause()
                    track.flush()
                    track.release()
                } catch (_: Exception) {
                }
                reverseTrack = null
                _state.value = _state.value.copy(isPlaying = false)
            }
        }
    }

    private fun analyzeWavSliceForFft(file: File, info: WavInfo, posMs: Long) {
        try {
            val frameStart = ((posMs * info.sampleRate) / 1000L).coerceIn(0L, max(0L, info.totalFrames - 2048))
            val n = 2048
            val buf = FloatArray(n * info.channels)
            val mono = FloatArray(n)
            RandomAccessFile(file, "r").use { raf ->
                val read = WavFileHelper.readFramesAsFloat(raf, info, frameStart, n, buf)
                if (read >= 1024) {
                    for (i in 0 until read) {
                        var sum = 0f
                        for (c in 0 until info.channels) sum += buf[i * info.channels + c]
                        mono[i] = sum / info.channels
                    }
                    _playbackFft.value = fftProcessor.analyze(mono.copyOfRange(0, read), info.sampleRate)
                }
            }
        } catch (_: Exception) {
        }
    }

    /**
     * Exports a fully reversed WAV file (chunked from end to start) while keeping the original untouched.
     */
    suspend fun exportReversed(
        source: RecordingEntity,
        onProgress: (Float) -> Unit = {}
    ): Result<RecordingEntity> = withContext(Dispatchers.IO) {
        runCatching {
            val srcFile = storageLocationManager.resolveReadableFile(source)
                ?: throw IllegalStateException("Source WAV cannot be read (storage unavailable).")
            val info = WavFileHelper.parseWavInfo(srcFile)
                ?: throw IllegalStateException("Source WAV cannot be read.")

            val baseName = StorageHelper.sanitizeFileName(source.title).substringBeforeLast('.')
            val outName = "${baseName}_reversed.wav"
            val outFile = StorageHelper.createWorkingFile(context, FaintRecFolder.PROCESSED, outName)
            val chunkFrames = 8192
            val readBuf = FloatArray(chunkFrames * info.channels)
            val revBuf = FloatArray(chunkFrames * info.channels)

            var dataBytes = 0L
            RandomAccessFile(srcFile, "r").use { inRaf ->
                RandomAccessFile(outFile, "rw").use { outRaf ->
                    outRaf.setLength(0L)
                    WavFileHelper.writePlaceholderHeader(
                        outRaf, info.sampleRate, info.channels,
                        if (info.isFloat) 32 else 16, info.isFloat
                    )
                    var cursor = info.totalFrames
                    while (cursor > 0L) {
                        val toRead = min(chunkFrames.toLong(), cursor).toInt()
                        val startFrame = cursor - toRead
                        val read = WavFileHelper.readFramesAsFloat(inRaf, info, startFrame, toRead, readBuf)
                        if (read <= 0) break
                        for (f in 0 until read) {
                            val srcF = read - 1 - f
                            for (c in 0 until info.channels) {
                                revBuf[f * info.channels + c] = readBuf[srcF * info.channels + c]
                            }
                        }
                        dataBytes += WavFileHelper.writeFloatSamples(
                            outRaf, revBuf, read * info.channels, info.isFloat
                        )
                        cursor = startFrame
                        onProgress(1f - (cursor.toFloat() / info.totalFrames.coerceAtLeast(1L)))
                    }
                    WavFileHelper.patchWavHeaderSizes(outRaf, dataBytes)
                }
            }

            val mediaUri = StorageHelper.publishToMediaStore(
                context, outFile, FaintRecFolder.PROCESSED, "audio/wav", outFile.name
            )
            RecordingEntity(
                title = "${source.title} [Reversed]",
                filePath = outFile.absolutePath,
                mediaStoreUri = mediaUri,
                durationMs = info.durationMs,
                fileSizeBytes = outFile.length(),
                format = "WAV",
                sampleRate = info.sampleRate,
                channels = info.channels,
                bitsPerSample = if (info.isFloat) 32 else 16,
                isFloatPcm = info.isFloat,
                sourceTag = RecordingSourceTag.PROCESSED,
                audioSourceUsed = source.audioSourceUsed,
                inputDeviceName = source.inputDeviceName
            )
        }
    }

    /**
     * Exports a speed-adjusted WAV file using linear resampling (tape mode) or WSOLA-style
     * overlap-add time-scaling (preserve pitch mode).
     */
    suspend fun exportAtSpeed(
        source: RecordingEntity,
        speed: Float,
        preservePitch: Boolean,
        onProgress: (Float) -> Unit = {}
    ): Result<RecordingEntity> = withContext(Dispatchers.IO) {
        runCatching {
            val srcFile = storageLocationManager.resolveReadableFile(source)
                ?: throw IllegalStateException("Source WAV cannot be read (storage unavailable).")
            val info = WavFileHelper.parseWavInfo(srcFile)
                ?: throw IllegalStateException("Source WAV cannot be read.")
            val clampedSpeed = speed.coerceIn(0.25f, 4.0f)
            val speedTag = String.format(java.util.Locale.US, "%.2fx", clampedSpeed).replace('.', '_')
            val baseName = StorageHelper.sanitizeFileName(source.title).substringBeforeLast('.')
            val outName = "${baseName}_${speedTag}.wav"
            val outFile = StorageHelper.createWorkingFile(context, FaintRecFolder.PROCESSED, outName)

            var dataBytes = 0L
            RandomAccessFile(srcFile, "r").use { inRaf ->
                RandomAccessFile(outFile, "rw").use { outRaf ->
                    outRaf.setLength(0L)
                    WavFileHelper.writePlaceholderHeader(
                        outRaf, info.sampleRate, info.channels,
                        if (info.isFloat) 32 else 16, info.isFloat
                    )

                    if (!preservePitch) {
                        // Tape mode: high-precision linear interpolation resampling
                        val inChunk = 8192
                        val inBuf = FloatArray((inChunk + 2) * info.channels)
                        var srcPos = 0.0
                        val total = info.totalFrames.toDouble()
                        val outBuf = FloatArray(4096 * info.channels)

                        while (srcPos < total - 1.0) {
                            val baseFrame = srcPos.toLong()
                            val neededSpan = (4096 * clampedSpeed).toInt() + 4
                            val span = min(neededSpan, (info.totalFrames - baseFrame).toInt()).coerceAtLeast(2)
                            val readBufLocal = if (span * info.channels > inBuf.size) {
                                FloatArray(span * info.channels)
                            } else inBuf
                            val read = WavFileHelper.readFramesAsFloat(inRaf, info, baseFrame, span, readBufLocal)
                            if (read < 2) break

                            var outFrames = 0
                            while (outFrames < 4096) {
                                val rel = srcPos - baseFrame
                                val idx0 = rel.toInt()
                                if (idx0 >= read - 1) break
                                val frac = (rel - idx0).toFloat()
                                for (c in 0 until info.channels) {
                                    val s0 = readBufLocal[idx0 * info.channels + c]
                                    val s1 = readBufLocal[(idx0 + 1) * info.channels + c]
                                    outBuf[outFrames * info.channels + c] = s0 + (s1 - s0) * frac
                                }
                                outFrames++
                                srcPos += clampedSpeed
                            }
                            if (outFrames <= 0) break
                            dataBytes += WavFileHelper.writeFloatSamples(
                                outRaf, outBuf, outFrames * info.channels, info.isFloat
                            )
                            onProgress((srcPos / total).toFloat().coerceIn(0f, 1f))
                        }
                    } else {
                        // Preserve pitch: OLA (Overlap-Add) grain time-stretching
                        val winFrames = 2048
                        val synHop = winFrames / 2
                        val anaHop = max(64, (synHop * clampedSpeed).toInt())
                        val win = FloatArray(winFrames) { i ->
                            (0.5 * (1.0 - kotlin.math.cos(2.0 * Math.PI * i / (winFrames - 1)))).toFloat()
                        }
                        val grainBuf = FloatArray(winFrames * info.channels)
                        val prevHalf = FloatArray(synHop * info.channels)
                        val outHop = FloatArray(synHop * info.channels)

                        var pos = 0L
                        while (pos + winFrames <= info.totalFrames) {
                            val read = WavFileHelper.readFramesAsFloat(inRaf, info, pos, winFrames, grainBuf)
                            if (read < winFrames) break
                            for (f in 0 until synHop) {
                                for (c in 0 until info.channels) {
                                    val idx = f * info.channels + c
                                    val cur = grainBuf[idx] * win[f]
                                    outHop[idx] = (prevHalf[idx] + cur).coerceIn(-1f, 1f)
                                    prevHalf[idx] = grainBuf[(f + synHop) * info.channels + c] * win[f + synHop]
                                }
                            }
                            dataBytes += WavFileHelper.writeFloatSamples(
                                outRaf, outHop, synHop * info.channels, info.isFloat
                            )
                            pos += anaHop
                            onProgress((pos.toFloat() / info.totalFrames.coerceAtLeast(1L)).coerceIn(0f, 1f))
                        }
                    }

                    WavFileHelper.patchWavHeaderSizes(outRaf, dataBytes)
                }
            }

            val outInfo = WavFileHelper.parseWavInfo(outFile)
            val mediaUri = StorageHelper.publishToMediaStore(
                context, outFile, FaintRecFolder.PROCESSED, "audio/wav", outFile.name
            )
            RecordingEntity(
                title = "${source.title} [${String.format(java.util.Locale.US, "%.2fx", clampedSpeed)}]",
                filePath = outFile.absolutePath,
                mediaStoreUri = mediaUri,
                durationMs = outInfo?.durationMs ?: (source.durationMs / clampedSpeed).toLong(),
                fileSizeBytes = outFile.length(),
                format = "WAV",
                sampleRate = info.sampleRate,
                channels = info.channels,
                bitsPerSample = if (info.isFloat) 32 else 16,
                isFloatPcm = info.isFloat,
                sourceTag = RecordingSourceTag.PROCESSED,
                audioSourceUsed = source.audioSourceUsed,
                inputDeviceName = source.inputDeviceName
            )
        }
    }
}
