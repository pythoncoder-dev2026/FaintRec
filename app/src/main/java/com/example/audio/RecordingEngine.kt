package com.example.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import com.example.analysis.FftProcessor
import com.example.analysis.FftResult
import com.example.analysis.FftWindowType
import com.example.data.AppFeature
import com.example.data.FaintRecRepository
import com.example.data.MarkerEntity
import com.example.data.RecordingEntity
import com.example.data.RecordingSourceTag
import com.example.data.RecordingStorageType
import com.example.logging.AppLogger
import com.example.storage.DirectWavTarget
import com.example.storage.StorageLocationManager
import com.example.util.AudioMath
import com.example.util.MarkerDebouncer
import com.example.util.StorageHelper
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.max

data class RecordingConfig(
    val sampleRate: Int = 48000,
    val channels: Int = 1,
    val preferFloat: Boolean = true,
    val inputGainDb: Float = 6f,
    val saveRawNoGain: Boolean = true,
    val varEnabled: Boolean = false,
    val varThresholdDb: Float = -45f,
    val varHangTimeSec: Float = 3f,
    val preferredDevice: AudioDeviceInfo? = null,
    val deviceName: String = "Built-in Mic",
    val storageType: RecordingStorageType = RecordingStorageType.APP_PRIVATE,
    val safTreeUri: String? = null
)

data class LiveRecordingTelemetry(
    val isRecording: Boolean = false,
    val isPaused: Boolean = false,
    val isVarWaiting: Boolean = false,
    val elapsedMs: Long = 0L,
    val currentDb: Float = AudioMath.MIN_DB,
    val peakHoldDb: Float = AudioMath.MIN_DB,
    val waveform: FloatArray = FloatArray(120),
    val actualSampleRate: Int = 48000,
    val actualChannels: Int = 1,
    val isFloatPcm: Boolean = true,
    val audioSourceLabel: String = "UNPROCESSED",
    val unprocessedFallbackWarning: Boolean = false,
    val droppedFrames: Long = 0L,
    val lowSpaceWarning: Boolean = false,
    val stoppedDueToFullStorage: Boolean = false
)

data class PendingMarker(
    val timestampMs: Long,
    val label: String,
    val note: String = ""
)

@Singleton
class RecordingEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: FaintRecRepository,
    private val logger: AppLogger,
    private val storageLocationManager: StorageLocationManager,
    private val deviceManager: AudioDeviceManager
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _telemetry = MutableStateFlow(
        LiveRecordingTelemetry(
            audioSourceLabel = deviceManager.getRecommendedAudioSourceLabel(),
            unprocessedFallbackWarning = !deviceManager.supportsUnprocessedSource()
        )
    )
    val telemetry: StateFlow<LiveRecordingTelemetry> = _telemetry.asStateFlow()

    private val _liveFft = MutableStateFlow<FftResult?>(null)
    val liveFft: StateFlow<FftResult?> = _liveFft.asStateFlow()

    private val _completedRecordings = MutableSharedFlow<Pair<RecordingEntity, List<PendingMarker>>>(
        extraBufferCapacity = 4
    )
    val completedRecordings: SharedFlow<Pair<RecordingEntity, List<PendingMarker>>> =
        _completedRecordings.asSharedFlow()

    private val _errors = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val errors: SharedFlow<String> = _errors.asSharedFlow()

    private val isRunning = AtomicBoolean(false)
    private val isPaused = AtomicBoolean(false)
    private val framesWritten = AtomicLong(0L)
    private val markerDebouncer = MarkerDebouncer()

    @Volatile var liveGainDb: Float = 6f
    @Volatile var saveRawNoGain: Boolean = true
    @Volatile var varEnabled: Boolean = false
    @Volatile var varThresholdDb: Float = -45f
    @Volatile var varHangTimeSec: Float = 3f

    private val fftProcessor = FftProcessor(2048, FftWindowType.HANN)
    private val pendingMarkers = mutableListOf<PendingMarker>()

    private var captureThread: Thread? = null
    private var writerThread: Thread? = null
    private var activeAudioRecord: AudioRecord? = null
    private var activeConfig: RecordingConfig = RecordingConfig()
    private var activeSampleRate = 48000
    private var activeChannels = 1
    private var activeIsFloat = true
    private var activeSourceLabel = deviceManager.getRecommendedAudioSourceLabel()

    fun configureFft(size: Int, window: FftWindowType) {
        fftProcessor.configure(size, window)
    }

    fun resetPeakHold() {
        fftProcessor.resetPeakHold()
        _telemetry.value = _telemetry.value.copy(peakHoldDb = AudioMath.MIN_DB)
    }

    fun switchInputDeviceLive(device: AudioDeviceInfo?, newDeviceName: String) {
        try {
            activeAudioRecord?.preferredDevice = device
            activeConfig = activeConfig.copy(preferredDevice = device, deviceName = newDeviceName)
        } catch (_: Exception) {
        }
    }

    fun addLiveMarker(label: String, note: String = "", bypassDebounce: Boolean = false): PendingMarker? {
        if (!isRunning.get()) return null
        if (!bypassDebounce && !markerDebouncer.tryAcquire()) return null
        val ts = (framesWritten.get() * 1000L) / activeSampleRate.coerceAtLeast(1)
        val marker = PendingMarker(ts, label, note)
        synchronized(pendingMarkers) {
            pendingMarkers.add(marker)
        }
        logger.reportSuccess(AppFeature.MARKERS, "Live marker added at ${marker.timestampMs} ms")
        return marker
    }

    fun togglePause() {
        if (!isRunning.get()) return
        val next = !isPaused.get()
        isPaused.set(next)
        _telemetry.value = _telemetry.value.copy(isPaused = next)
    }

    fun setPaused(paused: Boolean) {
        if (!isRunning.get()) return
        isPaused.set(paused)
        _telemetry.value = _telemetry.value.copy(isPaused = paused)
    }

    @SuppressLint("MissingPermission")
    fun startRecording(config: RecordingConfig): Boolean {
        if (isRunning.get()) return true

        // 1) Pre-recording storage availability & free-space check on the chosen storage
        val storageInfo = storageLocationManager.getStorageInfo(
            storageType = config.storageType,
            safTreeUri = config.safTreeUri,
            safDisplayPath = null,
            sampleRate = config.sampleRate,
            preferFloatPcm = config.preferFloat,
            channels = config.channels
        )
        if (!storageInfo.isAvailable) {
            val msg = storageInfo.unavailableReason ?: "Selected save location is unavailable."
            logger.reportFailure(AppFeature.WAV_SAVING, "Recording blocked: $msg")
            _errors.tryEmit(msg)
            return false
        }
        if (storageInfo.isCriticallyFull) {
            val msg = "Storage nearly full (${StorageHelper.formatSizeBytes(storageInfo.freeBytes)} free). Free up space or switch save location."
            logger.reportFailure(AppFeature.WAV_SAVING, msg)
            _errors.tryEmit(msg)
            return false
        }
        val initialLowSpace = storageInfo.isLowSpaceWarning
        if (initialLowSpace) {
            val warnMsg = "Low storage warning: only ${StorageHelper.formatSizeBytes(storageInfo.freeBytes)} free on ${storageInfo.displayPath}."
            logger.warn("storage", warnMsg)
            _errors.tryEmit(warnMsg)
        }

        activeConfig = config
        liveGainDb = config.inputGainDb
        saveRawNoGain = config.saveRawNoGain
        varEnabled = config.varEnabled
        varThresholdDb = config.varThresholdDb
        varHangTimeSec = config.varHangTimeSec

        val channelMask = if (config.channels == 2) {
            AudioFormat.CHANNEL_IN_STEREO
        } else {
            AudioFormat.CHANNEL_IN_MONO
        }

        val (record, chosenSourceLabel, isFallback, chosenFloat, actualRate) =
            createBestAudioRecord(config.sampleRate, channelMask, config.preferFloat, config.preferredDevice)
                ?: run {
                    val err = "Unable to initialize microphone hardware (check permission or sample rate ${config.sampleRate} Hz)."
                    logger.reportFailure(AppFeature.RECORDING_UNPROCESSED, err)
                    _errors.tryEmit(err)
                    return false
                }

        // 2) Open direct-to-destination WAV file handle BEFORE starting capture (never writes to cache first)
        val fileName = AudioMath.timestampFileName("FaintRec", "wav")
        val targetResult = storageLocationManager.openDirectWavTarget(
            storageType = config.storageType,
            safTreeUri = config.safTreeUri,
            fileName = fileName,
            sampleRate = actualRate,
            channels = config.channels,
            bitsPerSample = if (chosenFloat) 32 else 16,
            isFloat = chosenFloat,
            deviceName = config.deviceName,
            sourceLabel = chosenSourceLabel,
            saveRaw = config.saveRawNoGain,
            gainDb = config.inputGainDb
        )
        val directTarget = targetResult.getOrElse { err ->
            try { record.release() } catch (_: Exception) {}
            val msg = err.message ?: "Unable to create WAV file at chosen save location."
            _errors.tryEmit(msg)
            return false
        }

        if (isFallback) {
            logger.reportFallback(
                AppFeature.RECORDING_UNPROCESSED,
                "Fallback active ($chosenSourceLabel)"
            )
        } else {
            logger.reportSuccess(
                AppFeature.RECORDING_UNPROCESSED,
                "Started UNPROCESSED capture @ $actualRate Hz (${if (chosenFloat) "32f" else "16-bit"}) -> ${config.storageType.name}"
            )
        }
        if (config.inputGainDb > 0.05f) {
            logger.reportSuccess(AppFeature.GAIN_LIMITER, "Active gain +${config.inputGainDb.toInt()} dB with soft limiter")
        }
        if (config.varEnabled) {
            logger.reportSuccess(AppFeature.VAR, "VAR armed (threshold ${config.varThresholdDb.toInt()} dBFS, 2s pre-roll)")
        }

        activeAudioRecord = record
        activeSampleRate = actualRate
        activeChannels = config.channels
        activeIsFloat = chosenFloat
        activeSourceLabel = chosenSourceLabel
        framesWritten.set(0L)
        markerDebouncer.reset()
        synchronized(pendingMarkers) { pendingMarkers.clear() }

        // Large ring buffer (4 seconds capacity) to prevent dropped frames
        val ringCapacity = actualRate * config.channels * 4
        val ringBuffer = FloatRingBuffer(ringCapacity)
        val preRollBuffer = PreRollBuffer(actualRate * config.channels * 2) // 2-second VAR pre-roll

        isRunning.set(true)
        isPaused.set(false)
        _telemetry.value = LiveRecordingTelemetry(
            isRecording = true,
            isPaused = false,
            isVarWaiting = config.varEnabled,
            elapsedMs = 0L,
            currentDb = AudioMath.MIN_DB,
            peakHoldDb = AudioMath.MIN_DB,
            waveform = FloatArray(120),
            actualSampleRate = actualRate,
            actualChannels = config.channels,
            isFloatPcm = chosenFloat,
            audioSourceLabel = chosenSourceLabel,
            unprocessedFallbackWarning = isFallback,
            droppedFrames = 0L,
            lowSpaceWarning = initialLowSpace,
            stoppedDueToFullStorage = false
        )

        // Start dedicated high-priority audio capture thread
        captureThread = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            val readFrames = if (actualRate >= 96000) 4096 else 2048
            val readSamples = readFrames * config.channels
            val floatChunk = FloatArray(readSamples)
            val shortChunk = ShortArray(readSamples)
            val monoFftChunk = FloatArray(readFrames)
            val waveHistory = FloatArray(120)
            var waveIdx = 0
            var peakHold = AudioMath.MIN_DB
            var fftAccum = FloatArray(16384)
            var fftAccumPos = 0
            var lastFftDispatchMs = 0L

            try {
                record.startRecording()
                while (isRunning.get()) {
                    val readCount = if (chosenFloat) {
                        record.read(floatChunk, 0, readSamples, AudioRecord.READ_BLOCKING)
                    } else {
                        val sr = record.read(shortChunk, 0, readSamples, AudioRecord.READ_BLOCKING)
                        if (sr > 0) {
                            for (i in 0 until sr) floatChunk[i] = shortChunk[i] / 32768f
                        }
                        sr
                    }

                    if (readCount < 0) {
                        _errors.tryEmit("Audio hardware read error ($readCount). Stopping capture.")
                        break
                    }
                    if (readCount == 0) continue

                    // Push raw samples into lock-free ring buffer for disk writer thread
                    if (!isPaused.get()) {
                        ringBuffer.write(floatChunk, readCount)
                    }

                    // Apply live monitor gain on a copy for meters, FFT, waveform, and ultrasonic headphone monitor
                    val framesRead = readCount / config.channels
                    var maxAbs = 0f
                    var sumSq = 0.0
                    val monitorGainLin = AudioMath.dbToLinear(liveGainDb)

                    for (f in 0 until framesRead) {
                        var mono = 0f
                        for (c in 0 until config.channels) {
                            mono += floatChunk[f * config.channels + c]
                        }
                        mono /= config.channels
                        val boosted = AudioMath.softLimit(mono * monitorGainLin)
                        monoFftChunk[f] = boosted
                        val a = abs(boosted)
                        if (a > maxAbs) maxAbs = a
                        sumSq += (boosted * boosted).toDouble()
                    }

                    // Accumulate samples for background FFT
                    val spaceLeft = fftAccum.size - fftAccumPos
                    if (framesRead <= spaceLeft) {
                        System.arraycopy(monoFftChunk, 0, fftAccum, fftAccumPos, framesRead)
                        fftAccumPos += framesRead
                    } else {
                        val shift = framesRead.coerceAtMost(fftAccum.size)
                        System.arraycopy(fftAccum, shift, fftAccum, 0, fftAccum.size - shift)
                        val copyLen = framesRead.coerceAtMost(fftAccum.size)
                        System.arraycopy(monoFftChunk, framesRead - copyLen, fftAccum, fftAccum.size - copyLen, copyLen)
                        fftAccumPos = fftAccum.size
                    }

                    val nowMs = System.currentTimeMillis()
                    if (nowMs - lastFftDispatchMs >= 65L && fftAccumPos >= 1024) {
                        lastFftDispatchMs = nowMs
                        val snap = fftAccum.copyOfRange(0, fftAccumPos)
                        scope.launch {
                            _liveFft.value = fftProcessor.analyze(snap, actualRate)
                        }
                    }

                    val rmsDb = AudioMath.linearToDb(kotlin.math.sqrt(sumSq / framesRead.coerceAtLeast(1)).toFloat())
                    val peakDb = AudioMath.linearToDb(maxAbs)
                    peakHold = max(peakDb, peakHold - 0.18f)

                    waveHistory[waveIdx % waveHistory.size] = maxAbs.coerceIn(0f, 1f)
                    waveIdx++
                    val orderedWave = FloatArray(waveHistory.size) { i ->
                        waveHistory[(waveIdx + i) % waveHistory.size]
                    }

                    val elapsed = (framesWritten.get() * 1000L) / actualRate.coerceAtLeast(1)
                    _telemetry.value = _telemetry.value.copy(
                        elapsedMs = elapsed,
                        currentDb = rmsDb,
                        peakHoldDb = peakHold,
                        waveform = orderedWave,
                        droppedFrames = ringBuffer.droppedSamples / config.channels.coerceAtLeast(1)
                    )
                }
            } catch (e: Exception) {
                logger.reportFailure(AppFeature.RECORDING_UNPROCESSED, "Capture error: ${e.message}", e)
                _errors.tryEmit("Capture error: ${e.message ?: "Hardware interrupted"}")
            } finally {
                isRunning.set(false)
                isPaused.set(false)
                _telemetry.value = _telemetry.value.copy(
                    isRecording = false,
                    isPaused = false,
                    isVarWaiting = false
                )
                try {
                    record.stop()
                } catch (_: Exception) {
                }
                try {
                    record.release()
                } catch (_: Exception) {
                }
                activeAudioRecord = null
            }
        }, "FaintRec-Capture").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }

        // Start disk writer thread draining ring buffer directly -> chosen storage WAV channel
        writerThread = Thread({
            var dataBytesWritten = 0L
            val drainBuf = FloatArray(4096 * config.channels)
            var varTriggered = !config.varEnabled
            var lastAboveThreshMs = System.currentTimeMillis()
            var lastSpaceCheckMs = System.currentTimeMillis()
            var lastHeaderPatchMs = System.currentTimeMillis()
            var warnedLowSpace = initialLowSpace

            try {
                directTarget.writePlaceholderHeader(
                    sampleRate = actualRate,
                    channels = config.channels,
                    bitsPerSample = if (chosenFloat) 32 else 16,
                    isFloat = chosenFloat
                )

                while (isRunning.get() || ringBuffer.availableToRead() > 0) {
                    val n = ringBuffer.read(drainBuf, drainBuf.size)
                    if (n <= 0) {
                        Thread.sleep(8L)
                        continue
                    }

                    // Apply software gain only when "Save with gain" is selected
                    val shouldBakeGain = !saveRawNoGain && liveGainDb > 0.05f
                    if (shouldBakeGain) {
                        AudioMath.applyGainWithSoftLimiter(drainBuf, n, liveGainDb)
                    }

                    if (varEnabled) {
                        val blockDb = AudioMath.computePeakDb(drainBuf, n)
                        val now = System.currentTimeMillis()
                        if (blockDb >= varThresholdDb) {
                            lastAboveThreshMs = now
                            if (!varTriggered) {
                                varTriggered = true
                                _telemetry.value = _telemetry.value.copy(isVarWaiting = false)
                                // Flush 2-second pre-roll buffer first!
                                val preRoll = preRollBuffer.drainChronological()
                                if (preRoll.isNotEmpty()) {
                                    dataBytesWritten += directTarget.writeFloatSamples(
                                        preRoll, preRoll.size, writeAsFloat32 = chosenFloat
                                    )
                                    framesWritten.addAndGet((preRoll.size / config.channels).toLong())
                                }
                            }
                        } else if (varTriggered && (now - lastAboveThreshMs) > (varHangTimeSec * 1000f).toLong()) {
                            varTriggered = false
                            _telemetry.value = _telemetry.value.copy(isVarWaiting = true)
                        }

                        if (!varTriggered) {
                            preRollBuffer.push(drainBuf, n)
                            continue
                        }
                    } else if (!varTriggered) {
                        varTriggered = true
                        _telemetry.value = _telemetry.value.copy(isVarWaiting = false)
                    }

                    dataBytesWritten += directTarget.writeFloatSamples(
                        drainBuf, n, writeAsFloat32 = chosenFloat
                    )
                    framesWritten.addAndGet((n / config.channels).toLong())

                    val nowMs = System.currentTimeMillis()
                    // Periodically patch WAV header so file stays recoverable even if service is killed
                    if (nowMs - lastHeaderPatchMs >= 5000L && dataBytesWritten > 0L) {
                        lastHeaderPatchMs = nowMs
                        directTarget.patchHeaderPeriodic(dataBytesWritten)
                    }

                    // Mid-recording free space check every 2 seconds
                    if (isRunning.get() && nowMs - lastSpaceCheckMs >= 2000L) {
                        lastSpaceCheckMs = nowMs
                        val freeNow = storageLocationManager.getCurrentFreeBytes(config.storageType, config.safTreeUri)
                        if (freeNow < 0L) {
                            val errMsg = "Storage became unavailable during recording (SD card removed or permission lost). Finalizing WAV."
                            logger.reportFailure(AppFeature.WAV_SAVING, errMsg)
                            _errors.tryEmit(errMsg)
                            _telemetry.value = _telemetry.value.copy(stoppedDueToFullStorage = true)
                            isRunning.set(false)
                            break
                        } else if (freeNow < StorageLocationManager.CRITICAL_STOP_BYTES) {
                            val stopMsg = "Storage nearly full (${StorageHelper.formatSizeBytes(freeNow)} left). Stopping and finalizing WAV safely."
                            logger.warn("storage", stopMsg)
                            _errors.tryEmit(stopMsg)
                            _telemetry.value = _telemetry.value.copy(
                                lowSpaceWarning = true,
                                stoppedDueToFullStorage = true
                            )
                            isRunning.set(false)
                            break
                        } else if (freeNow < StorageLocationManager.LOW_SPACE_WARNING_BYTES && !warnedLowSpace) {
                            warnedLowSpace = true
                            val warnMsg = "Low storage: under 200 MB remaining (${StorageHelper.formatSizeBytes(freeNow)} left)."
                            logger.warn("storage", warnMsg)
                            _errors.tryEmit(warnMsg)
                            _telemetry.value = _telemetry.value.copy(lowSpaceWarning = true)
                        }
                    }
                }
            } catch (e: Exception) {
                logger.reportFailure(AppFeature.WAV_SAVING, "Disk write error: ${e.message}", e)
                _errors.tryEmit("Disk write error: ${e.message ?: "Storage error"}")
                isRunning.set(false)
            } finally {
                finalizeSavedRecording(directTarget, dataBytesWritten, config, actualRate, chosenFloat, chosenSourceLabel)
            }
        }, "FaintRec-Writer").apply {
            start()
        }

        return true
    }

    fun stopRecording() {
        if (!isRunning.compareAndSet(true, false)) return
        isPaused.set(false)
        _telemetry.value = _telemetry.value.copy(
            isRecording = false,
            isPaused = false,
            isVarWaiting = false
        )
    }

    private fun finalizeSavedRecording(
        target: DirectWavTarget,
        dataBytesWritten: Long,
        config: RecordingConfig,
        actualRate: Int,
        isFloat: Boolean,
        sourceLabel: String
    ) {
        val markersCopy = synchronized(pendingMarkers) { pendingMarkers.toList() }
        val cues = markersCopy.mapIndexed { idx, m ->
            WavCuePoint(
                id = idx + 1,
                sampleOffset = (m.timestampMs * actualRate) / 1000L,
                label = if (m.note.isNotBlank()) "${m.label}: ${m.note}" else m.label
            )
        }

        val summary = try {
            target.finalizeAndClose(
                dataBytes = dataBytesWritten,
                cues = cues,
                fallbackSampleRate = actualRate,
                fallbackChannels = config.channels,
                fallbackIsFloat = isFloat
            )
        } catch (e: Exception) {
            logger.reportFailure(AppFeature.WAV_SAVING, "Failed to finalize WAV header: ${e.message}", e)
            _errors.tryEmit("Failed to finalize WAV header: ${e.message}")
            null
        } ?: return

        val entity = RecordingEntity(
            title = target.fileName.substringBeforeLast('.'),
            filePath = target.filePath,
            mediaStoreUri = if (target.storageType == RecordingStorageType.PUBLIC_DOCUMENTS) target.contentUri else null,
            durationMs = summary.durationMs,
            fileSizeBytes = summary.fileSizeBytes,
            format = "WAV",
            sampleRate = summary.sampleRate,
            channels = summary.channels,
            bitsPerSample = summary.bitsPerSample,
            isFloatPcm = summary.isFloat,
            sourceTag = RecordingSourceTag.RECORDED,
            audioSourceUsed = sourceLabel,
            inputDeviceName = config.deviceName,
            gainAppliedDb = if (config.saveRawNoGain) 0f else liveGainDb,
            savedRaw = config.saveRawNoGain,
            storageType = target.storageType,
            storageUri = target.contentUri
        )

        scope.launch(Dispatchers.IO) {
            try {
                val recId = repository.insertRecording(entity)
                if (markersCopy.isNotEmpty()) {
                    repository.insertMarkers(
                        markersCopy.map { m ->
                            MarkerEntity(
                                recordingId = recId,
                                timestampMs = m.timestampMs,
                                label = m.label,
                                note = m.note
                            )
                        }
                    )
                }
                logger.reportSuccess(
                    AppFeature.WAV_SAVING,
                    "Saved WAV (${entity.storageType.name}, ${entity.durationMs} ms, ${entity.sampleRate} Hz, ${entity.fileSizeBytes} B)"
                )
                _completedRecordings.tryEmit(entity.copy(id = recId) to markersCopy)
            } catch (e: Exception) {
                logger.reportFailure(AppFeature.WAV_SAVING, "Failed to save recording metadata: ${e.message}", e)
                _errors.tryEmit("Failed to save recording metadata: ${e.message}")
            }
        }
    }

    private data class RecordInitResult(
        val audioRecord: AudioRecord,
        val sourceLabel: String,
        val isFallback: Boolean,
        val isFloat: Boolean,
        val actualSampleRate: Int
    )

    @SuppressLint("MissingPermission")
    private fun createBestAudioRecord(
        desiredSampleRate: Int,
        channelMask: Int,
        preferFloat: Boolean,
        preferredDevice: AudioDeviceInfo?
    ): RecordInitResult? {
        // Try UNPROCESSED only if HAL advertises PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED == "true";
        // otherwise fall back to VOICE_RECOGNITION, then MIC.
        val supportsUnprocessed = deviceManager.supportsUnprocessedSource()
        val sources = buildList {
            if (supportsUnprocessed) {
                add(MediaRecorder.AudioSource.UNPROCESSED to "UNPROCESSED")
            }
            add(MediaRecorder.AudioSource.VOICE_RECOGNITION to "VOICE_RECOGNITION")
            add(MediaRecorder.AudioSource.MIC to "MIC")
        }
        val encodings = if (preferFloat) {
            listOf(AudioFormat.ENCODING_PCM_FLOAT to true, AudioFormat.ENCODING_PCM_16BIT to false)
        } else {
            listOf(AudioFormat.ENCODING_PCM_16BIT to false, AudioFormat.ENCODING_PCM_FLOAT to true)
        }
        val ratesToTry = if (desiredSampleRate > 48000) {
            listOf(desiredSampleRate, 96000, 48000)
        } else {
            listOf(desiredSampleRate, 48000, 44100)
        }.distinct()

        for (rate in ratesToTry) {
            for ((encoding, isFloat) in encodings) {
                val minBuf = AudioRecord.getMinBufferSize(rate, channelMask, encoding)
                if (minBuf <= 0) continue
                val bufferBytes = max(minBuf * 4, if (rate >= 96000) 131072 else 32768)

                for ((sourceId, label) in sources) {
                    try {
                        val format = AudioFormat.Builder()
                            .setSampleRate(rate)
                            .setEncoding(encoding)
                            .setChannelMask(channelMask)
                            .build()
                        val rec = AudioRecord.Builder()
                            .setAudioSource(sourceId)
                            .setAudioFormat(format)
                            .setBufferSizeInBytes(bufferBytes)
                            .build()
                        if (rec.state == AudioRecord.STATE_INITIALIZED) {
                            // Never enable NoiseSuppressor, AutomaticGainControl, or AcousticEchoCanceler;
                            // if any was auto-attached by the HAL, disable and release it immediately.
                            AudioDeviceManager.disableAndReleaseAutoAttachedEffects(rec.audioSessionId)
                            if (preferredDevice != null) {
                                rec.preferredDevice = preferredDevice
                            }
                            val actualRate = rec.sampleRate.takeIf { it > 0 } ?: rate
                            return RecordInitResult(
                                audioRecord = rec,
                                sourceLabel = label,
                                isFallback = sourceId != MediaRecorder.AudioSource.UNPROCESSED,
                                isFloat = isFloat,
                                actualSampleRate = actualRate
                            )
                        } else {
                            rec.release()
                        }
                    } catch (_: Exception) {
                    }
                }
            }
        }
        return null
    }
}
