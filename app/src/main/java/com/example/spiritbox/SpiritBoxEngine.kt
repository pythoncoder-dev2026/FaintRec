package com.example.spiritbox

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.speech.tts.TextToSpeech
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.example.analysis.FftProcessor
import com.example.analysis.FftResult
import com.example.analysis.FftWindowType
import com.example.audio.AudioDeviceManager
import com.example.audio.WavCuePoint
import com.example.audio.WavFileHelper
import com.example.data.AppFeature
import com.example.data.FaintRecRepository
import com.example.data.MarkerEntity
import com.example.data.MarkerExportItem
import com.example.data.RecordingEntity
import com.example.data.RecordingSourceTag
import com.example.data.RecordingStorageType
import com.example.data.SettingsDataStore
import com.example.data.SpiritBoxHopLogItem
import com.example.data.SpiritBoxQuestionLogItem
import com.example.data.SpiritBoxSessionEntity
import com.example.editor.BiquadFilter
import com.example.logging.AppLogger
import com.example.storage.DirectWavTarget
import com.example.storage.StorageLocationManager
import com.example.util.AudioMath
import com.example.util.MarkerDebouncer
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
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.RandomAccessFile
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Simulated Spirit Box sweep engine (entertainment feature).
 * Android has no public FM/AM hardware tuner API, so hops mix:
 *  a) Pre-prepared Media3 ExoPlayer pool of user-editable Internet radio streams
 *  b) Random short slices from local WAV recordings/imported files
 *  c) Real-time synthesized static (White / Pink / Brown / Tuning Static + optional crackle)
 *  d) Optional offline TTS spoken-word fragments (English & Hindi) synthesized to cached WAVs
 */
@Singleton
class SpiritBoxEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: FaintRecRepository,
    private val storageLocationManager: StorageLocationManager,
    private val settingsDataStore: SettingsDataStore,
    private val deviceManager: AudioDeviceManager,
    private val localAudioSource: SpiritBoxLocalAudioSource,
    private val logger: AppLogger
) {
    companion object {
        private const val SAMPLE_RATE = 48000
        private const val MAX_RADIO_PLAYERS = 3
        private const val SAFE_MAX_VOLUME = 0.85f

        private val OFFLINE_WORDS_EN = listOf(
            "listen", "here", "wait", "door", "light", "name", "cold", "near",
            "stay", "echo", "room", "time", "look", "voice", "shadow", "hello"
        )
        private val OFFLINE_WORDS_HI = listOf(
            "सुनो", "यहाँ", "रुको", "आवाज़", "कमरा", "समय", "रोशनी", "नाम", "पास", "नमस्ते"
        )
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val json = Json { ignoreUnknownKeys = true }
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val markerDebouncer = MarkerDebouncer(500L)

    private val _liveState = MutableStateFlow(SpiritBoxLiveState())
    val liveState: StateFlow<SpiritBoxLiveState> = _liveState.asStateFlow()

    private val _liveFft = MutableStateFlow<FftResult?>(null)
    val liveFft: StateFlow<FftResult?> = _liveFft.asStateFlow()

    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val events: SharedFlow<String> = _events.asSharedFlow()

    @Volatile var config: SpiritBoxConfig = SpiritBoxConfig()
    @Volatile private var latestStorageType: RecordingStorageType = RecordingStorageType.APP_PRIVATE
    @Volatile private var latestSafTreeUri: String? = null

    private val isRunning = AtomicBoolean(false)
    private val isHeld = AtomicBoolean(false)
    private val focusPaused = AtomicBoolean(false)
    private val micCaptureRunning = AtomicBoolean(false)

    private var sweepThread: Thread? = null
    private var micThread: Thread? = null
    private var audioTrack: AudioTrack? = null
    private var focusRequest: AudioFocusRequest? = null

    private val radioPlayers = mutableListOf<ExoPlayer>()
    private val radioPlayerUrls = mutableListOf<String>()
    private val localWavPool = CopyOnWriteArrayList<ValidatedLocalWav>()
    private val ttsFragmentPool = CopyOnWriteArrayList<FloatArray>()

    private var ttsEngine: TextToSpeech? = null
    @Volatile private var ttsReady = false

    private val sessionMarkers = CopyOnWriteArrayList<MarkerExportItem>()
    private val sessionQuestions = CopyOnWriteArrayList<SpiritBoxQuestionLogItem>()
    private val sessionHopLog = CopyOnWriteArrayList<SpiritBoxHopLogItem>()

    private var sessionStartMillis = 0L

    // Thread-safe live-toggleable recorder state for sweep mix WAV
    private val mixRecorderLock = Any()
    private var activeMixTarget: DirectWavTarget? = null
    private var activeMixBytesWritten: Long = 0L
    private var activeMixStartElapsedMs: Long = 0L
    private var lastMixHeaderPatchMs: Long = 0L
    @Volatile private var lastSavedMixRecId: Long? = null
    @Volatile private var lastSavedMicRecId: Long? = null

    private val fftProcessor = FftProcessor(2048, FftWindowType.HANN)

    init {
        scope.launch {
            settingsDataStore.settingsFlow.collect { s ->
                latestStorageType = s.storageType
                latestSafTreeUri = s.safTreeUri
                config = config.copy(
                    streamUrls = s.spiritBoxStreamUrls,
                    recorderEnabled = s.spiritBoxRecorderEnabled,
                    recordSessionMix = s.spiritBoxRecordSweepOutput,
                    recordMicTrack = s.spiritBoxRecordMicTrack
                )
                _liveState.value = _liveState.value.copy(
                    isRecorderEnabled = s.spiritBoxRecorderEnabled
                )
            }
        }
    }

    private val focusChangeListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                focusPaused.set(true)
                muteAllRadioPlayers()
                _liveState.value = _liveState.value.copy(isPausedByAudioFocus = true)
                logger.info(AppFeature.SPIRIT_BOX_STATIC, "Spirit Box paused due to audio focus loss ($change)")
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                focusPaused.set(false)
                _liveState.value = _liveState.value.copy(isPausedByAudioFocus = false)
                logger.info(AppFeature.SPIRIT_BOX_STATIC, "Spirit Box resumed after regaining audio focus")
            }
        }
    }

    fun updateConfig(newConfig: SpiritBoxConfig) {
        config = newConfig
        _liveState.value = _liveState.value.copy(isRecorderEnabled = newConfig.recorderEnabled)
    }

    /**
     * Master ON/OFF toggle for Spirit Box recording (works both before and live during a sweep).
     * Persists to DataStore, logs state transitions, and safely starts or finalizes WAV files
     * in the user's chosen Save Location without ever creating empty files.
     */
    fun setRecorderEnabled(enabled: Boolean) {
        val prev = config.recorderEnabled
        config = config.copy(recorderEnabled = enabled)
        _liveState.value = _liveState.value.copy(isRecorderEnabled = enabled)

        scope.launch {
            settingsDataStore.setSpiritBoxRecorderEnabled(enabled)
        }

        if (prev != enabled) {
            val stateMsg = if (enabled) "Spirit Box recorder turned ON" else "Spirit Box recorder turned OFF"
            logger.info(AppFeature.SPIRIT_BOX_RECORDER, stateMsg)
            logger.reportSuccess(
                AppFeature.SPIRIT_BOX_RECORDER,
                if (enabled) "Recorder ON" else "Recorder OFF"
            )
        }

        if (!isRunning.get()) return

        if (enabled) {
            scope.launch {
                var startedAny = false
                if (config.recordSessionMix) {
                    startedAny = openMixTargetIfAllowed() || startedAny
                }
                if (config.recordMicTrack) {
                    startedAny = startMicCompanionIfAllowed() || startedAny
                }
                refreshRecordingFlag()
                if (startedAny) {
                    _events.tryEmit("Spirit Box recorder ON — recording started")
                } else {
                    _events.tryEmit("Spirit Box recorder ON (enable sweep or mic track to save audio)")
                }
            }
        } else {
            val hadMix = synchronized(mixRecorderLock) { activeMixTarget != null }
            val hadMic = micCaptureRunning.get()
            detachAndFinalizeMixSegmentAsync(sessionMarkers.toList(), config.bandMode.displayName)
            stopMicCompanionLive()
            refreshRecordingFlag()
            if (hadMix || hadMic) {
                _events.tryEmit("Spirit Box recorder OFF — recording saved to Library")
            } else {
                _events.tryEmit("Recording is off. Nothing is saved.")
            }
        }
    }

    fun setRecordSweepOutput(enabled: Boolean) {
        config = config.copy(recordSessionMix = enabled)
        scope.launch {
            settingsDataStore.setSpiritBoxRecordSweepOutput(enabled)
        }
        if (isRunning.get() && config.recorderEnabled) {
            if (enabled) {
                scope.launch {
                    openMixTargetIfAllowed()
                    refreshRecordingFlag()
                }
            } else {
                detachAndFinalizeMixSegmentAsync(sessionMarkers.toList(), config.bandMode.displayName)
            }
        }
    }

    fun setRecordMicTrack(enabled: Boolean) {
        config = config.copy(recordMicTrack = enabled)
        scope.launch {
            settingsDataStore.setSpiritBoxRecordMicTrack(enabled)
        }
        if (isRunning.get() && config.recorderEnabled) {
            if (enabled) {
                startMicCompanionIfAllowed()
            } else {
                stopMicCompanionLive()
            }
            refreshRecordingFlag()
        }
    }

    fun toggleHold() {
        if (!isRunning.get()) return
        val next = !isHeld.get()
        isHeld.set(next)
        _liveState.value = _liveState.value.copy(isHeld = next)
    }

    fun addMarker(label: String = "", note: String = "", bypassDebounce: Boolean = false): Boolean {
        if (!isRunning.get()) return false
        if (!bypassDebounce && !markerDebouncer.tryAcquire()) return false
        val ts = currentElapsedMs()
        val idx = sessionMarkers.size + 1
        val item = MarkerExportItem(
            timestampMs = ts,
            label = label.ifBlank { "SB Marker #$idx" },
            note = note
        )
        sessionMarkers.add(item)
        _liveState.value = _liveState.value.copy(markersCount = sessionMarkers.size)
        logger.reportSuccess(AppFeature.MARKERS, "Spirit Box marker added at ${AudioMath.formatDuration(ts)}")
        _events.tryEmit("Marked ${AudioMath.formatDuration(ts)}")
        return true
    }

    fun addQuestion(questionText: String, inputMode: String = "TEXT") {
        val clean = questionText.trim()
        if (clean.isEmpty()) return
        val ts = if (isRunning.get()) currentElapsedMs() else 0L
        val q = SpiritBoxQuestionLogItem(timestampMs = ts, question = clean, inputMode = inputMode)
        sessionQuestions.add(q)
        _liveState.value = _liveState.value.copy(questions = sessionQuestions.toList())
        if (isRunning.get()) {
            addMarker(label = "Question", note = clean, bypassDebounce = true)
        }
        _events.tryEmit("Question logged")
    }

    fun dismissBlindReveal() {
        _liveState.value = _liveState.value.copy(showBlindReveal = false)
    }

    fun startSession(initialConfig: SpiritBoxConfig): Boolean {
        if (isRunning.get()) return true
        config = initialConfig

        if (!requestAudioFocus()) {
            logger.warn(AppFeature.SPIRIT_BOX_STATIC, "Could not obtain audio focus for Spirit Box")
        }

        sessionStartMillis = System.currentTimeMillis()
        lastSavedMixRecId = null
        lastSavedMicRecId = null
        markerDebouncer.reset()
        sessionMarkers.clear()
        sessionQuestions.clear()
        sessionHopLog.clear()

        // Prepare optional offline TTS fragments asynchronously (local WAV pool is loaded before Hop #1 on sweepThread)
        if (config.enableOfflineTts) {
            scope.launch {
                prepareOfflineTtsFragments()
            }
        }

        // Pre-prepare Internet radio ExoPlayer pool on main thread
        if (config.enableInternetRadio && config.streamUrls.isNotEmpty()) {
            prepareRadioPool(config.streamUrls.take(MAX_RADIO_PLAYERS))
        }

        isRunning.set(true)
        isHeld.set(false)
        focusPaused.set(false)

        // Open direct WAV targets ONLY when Recorder is ON (respecting chosen Save Location).
        // When Recorder is OFF, NEVER open files or touch the microphone.
        var recordingStarted = false
        if (config.recorderEnabled) {
            if (config.recordSessionMix) {
                recordingStarted = openMixTargetIfAllowed() || recordingStarted
            }
            if (config.recordMicTrack) {
                recordingStarted = startMicCompanionIfAllowed() || recordingStarted
            }
            logger.reportSuccess(
                AppFeature.SPIRIT_BOX_RECORDER,
                if (recordingStarted) "Recording active (${latestStorageType.name})" else "Recorder ON (no track selected)"
            )
        } else {
            logger.reportSuccess(AppFeature.SPIRIT_BOX_RECORDER, "Recorder OFF — sweep without saving audio")
        }

        val startFreq = SpiritBoxDialMath.formatFrequency(config.bandMode, config.rangeStartFraction)
        _liveState.value = SpiritBoxLiveState(
            isRunning = true,
            isHeld = false,
            isRecorderEnabled = config.recorderEnabled,
            isActivelyRecording = recordingStarted,
            dialFraction = config.rangeStartFraction,
            currentFrequencyLabel = startFreq,
            activeSourceLabel = if (config.blindTestMode) "Blind Test Active" else "Sweeping",
            showBlindReveal = false
        )

        startAudioSweepLoop()
        logger.reportSuccess(AppFeature.SPIRIT_BOX_STATIC, "Spirit Box sweep session started")
        return true
    }

    fun stopSession() {
        if (!isRunning.compareAndSet(true, false)) return
        isHeld.set(false)
        focusPaused.set(false)
        micCaptureRunning.set(false)

        releaseRadioPool()
        abandonAudioFocus()

        val reveal = config.blindTestMode && sessionHopLog.isNotEmpty()
        _liveState.value = _liveState.value.copy(
            isRunning = false,
            isHeld = false,
            isPausedByAudioFocus = false,
            isActivelyRecording = false,
            activeSourceLabel = "Stopped",
            blindTestRevealHops = if (reveal) sessionHopLog.toList() else emptyList(),
            showBlindReveal = reveal
        )
    }

    private fun refreshRecordingFlag() {
        val activeMix = synchronized(mixRecorderLock) { activeMixTarget != null }
        val activeMic = micCaptureRunning.get()
        _liveState.value = _liveState.value.copy(
            isRecorderEnabled = config.recorderEnabled,
            isActivelyRecording = isRunning.get() && (activeMix || activeMic)
        )
    }

    private fun openMixTargetIfAllowed(): Boolean {
        if (!config.recorderEnabled || !config.recordSessionMix) return false
        synchronized(mixRecorderLock) {
            if (activeMixTarget != null) return true
        }
        val storageType = latestStorageType
        val safUri = latestSafTreeUri
        val free = storageLocationManager.getCurrentFreeBytes(storageType, safUri)
        if (free in 0L until StorageLocationManager.CRITICAL_STOP_BYTES) {
            logger.warn(AppFeature.SPIRIT_BOX_RECORDER, "Insufficient storage space to record Spirit Box mix")
            _events.tryEmit("Storage nearly full — Spirit Box audio not recorded.")
            return false
        }
        val fileName = AudioMath.timestampFileName("SpiritBox_Mix", "wav")
        val targetRes = storageLocationManager.openDirectWavTarget(
            storageType = storageType,
            safTreeUri = safUri,
            fileName = fileName,
            sampleRate = SAMPLE_RATE,
            channels = 1,
            bitsPerSample = 16,
            isFloat = false,
            deviceName = "Spirit Box (${config.bandMode.displayName})",
            sourceLabel = "SPIRIT_BOX_MIX",
            saveRaw = false,
            gainDb = 0f
        )
        val target = targetRes.getOrElse { err ->
            logger.reportFailure(AppFeature.SPIRIT_BOX_RECORDER, "Cannot open Spirit Box WAV target: ${err.message}", err)
            _events.tryEmit("Save location unavailable for Spirit Box recording: ${err.message}")
            return false
        }
        return try {
            target.writePlaceholderHeader(SAMPLE_RATE, 1, 16, isFloat = false)
            synchronized(mixRecorderLock) {
                activeMixTarget = target
                activeMixBytesWritten = 0L
                activeMixStartElapsedMs = currentElapsedMs()
                lastMixHeaderPatchMs = System.currentTimeMillis()
            }
            true
        } catch (e: Exception) {
            target.abortAndDelete()
            logger.reportFailure(AppFeature.SPIRIT_BOX_RECORDER, "WAV header write failed: ${e.message}", e)
            false
        }
    }

    private fun detachAndFinalizeMixSegmentAsync(
        markersSnapshot: List<MarkerExportItem>,
        bandName: String
    ) {
        val detached = synchronized(mixRecorderLock) {
            val t = activeMixTarget ?: return
            val bytes = activeMixBytesWritten
            val startMs = activeMixStartElapsedMs
            activeMixTarget = null
            activeMixBytesWritten = 0L
            Triple(t, bytes, startMs)
        }
        refreshRecordingFlag()
        scope.launch {
            finalizeSingleMixTarget(detached.first, detached.second, detached.third, markersSnapshot, bandName)
        }
    }

    private suspend fun finalizeSingleMixTarget(
        target: DirectWavTarget,
        dataBytes: Long,
        segmentStartElapsedMs: Long,
        markersSnapshot: List<MarkerExportItem>,
        bandName: String
    ): Long? {
        val segmentMarkers = markersSnapshot
            .filter { it.timestampMs >= segmentStartElapsedMs }
            .map { it.copy(timestampMs = (it.timestampMs - segmentStartElapsedMs).coerceAtLeast(0L)) }

        val cues = segmentMarkers.mapIndexed { idx, m ->
            WavCuePoint(
                id = idx + 1,
                sampleOffset = (m.timestampMs * SAMPLE_RATE) / 1000L,
                label = if (m.note.isNotBlank()) "${m.label}: ${m.note}" else m.label
            )
        }
        val summary = try {
            target.finalizeAndClose(
                dataBytes = dataBytes,
                cues = cues,
                fallbackSampleRate = SAMPLE_RATE,
                fallbackChannels = 1,
                fallbackIsFloat = false
            )
        } catch (e: Exception) {
            logger.reportFailure(AppFeature.SPIRIT_BOX_RECORDER, "Finalize Spirit Box WAV failed: ${e.message}", e)
            return null
        } ?: return null // dataBytes <= 0 -> already deleted cleanly, never create empty files

        val entity = RecordingEntity(
            title = target.fileName.substringBeforeLast('.'),
            filePath = target.filePath,
            mediaStoreUri = if (target.storageType == RecordingStorageType.PUBLIC_DOCUMENTS) target.contentUri else null,
            storageType = target.storageType,
            storageUri = target.contentUri,
            durationMs = summary.durationMs,
            fileSizeBytes = summary.fileSizeBytes,
            format = "WAV",
            sampleRate = summary.sampleRate,
            channels = summary.channels,
            bitsPerSample = summary.bitsPerSample,
            isFloatPcm = summary.isFloat,
            sourceTag = RecordingSourceTag.SPIRIT_BOX,
            audioSourceUsed = "SPIRIT_BOX_MIX",
            inputDeviceName = "Spirit Box ($bandName)"
        )
        val recId = repository.insertRecording(entity)
        lastSavedMixRecId = recId
        if (segmentMarkers.isNotEmpty()) {
            repository.insertMarkers(
                segmentMarkers.map { m ->
                    MarkerEntity(
                        recordingId = recId,
                        timestampMs = m.timestampMs,
                        label = m.label,
                        note = m.note
                    )
                }
            )
        }
        _liveState.value = _liveState.value.copy(lastSavedMixTitle = entity.title)
        logger.reportSuccess(AppFeature.SPIRIT_BOX_RECORDER, "Saved Spirit Box WAV (${entity.title})")
        _events.tryEmit("Saved Spirit Box mix to Library: ${entity.title}")
        return recId
    }

    private fun startAudioSweepLoop() {
        sweepThread = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            val minBuf = AudioTrack.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_FLOAT
            ).coerceAtLeast(4096)

            val track = try {
                AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                            .setSampleRate(SAMPLE_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(minBuf * 3)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
            } catch (e: Exception) {
                logger.reportFailure(AppFeature.SPIRIT_BOX_STATIC, "AudioTrack init failed: ${e.message}", e)
                isRunning.set(false)
                return@Thread
            }

            audioTrack = track

            val chunkFrames = 1024
            val mixBuf = FloatArray(chunkFrames)
            val waveRing = FloatArray(100)
            var waveIdx = 0
            val fftAccum = FloatArray(2048)
            var fftPos = 0
            var lastFftMs = 0L

            val rng = Random(System.currentTimeMillis().toInt())
            val hpf = BiquadFilter()
            val lpf = BiquadFilter()
            var lastHpfHz = -1f
            var lastLpfHz = -1f

            // Static synthesis state
            var p0 = 0.0; var p1 = 0.0; var p2 = 0.0; var p3 = 0.0; var p4 = 0.0; var p5 = 0.0; var p6 = 0.0
            var brown = 0.0
            var tuneWhistlePhase = 0.0

            // Hop state
            var dialFrac = config.rangeStartFraction
            var hopCounter = 0
            var framesUntilNextHop = 0
            var prevFragBuf = FloatArray(0)
            var prevFragPos = 0
            var curFragBuf = FloatArray(0)
            var curFragPos = 0
            var xfadeTotalFrames = 0
            var xfadeCursor = 0

            try {
                // Ensure the local WAV pool from Library (all storage types) is loaded before Hop #1
                if (config.enableLocalAudio) {
                    runBlocking { refreshLocalWavPool() }
                }
                track.play()
                while (isRunning.get()) {
                    if (focusPaused.get()) {
                        Thread.sleep(40L)
                        continue
                    }

                    val cfg = config
                    // Update voice-range HPF & LPF filters live
                    if (abs(cfg.voiceHpfHz - lastHpfHz) > 2f) {
                        lastHpfHz = cfg.voiceHpfHz
                        hpf.setHighPass(SAMPLE_RATE.toDouble(), cfg.voiceHpfHz.toDouble().coerceIn(50.0, 1200.0))
                    }
                    if (abs(cfg.voiceLpfHz - lastLpfHz) > 20f) {
                        lastLpfHz = cfg.voiceLpfHz
                        lpf.setLowPass(SAMPLE_RATE.toDouble(), cfg.voiceLpfHz.toDouble().coerceIn(1200.0, 12000.0))
                    }

                    // Trigger hop when countdown finishes (unless Held)
                    if (!isHeld.get() && framesUntilNextHop <= 0) {
                        hopCounter++
                        dialFrac = SpiritBoxDialMath.nextDialFraction(
                            currentFraction = dialFrac,
                            direction = cfg.sweepDirection,
                            band = cfg.bandMode,
                            rangeStart = cfg.rangeStartFraction,
                            rangeEnd = cfg.rangeEndFraction,
                            rng = rng
                        )
                        val freqLabel = SpiritBoxDialMath.formatFrequency(cfg.bandMode, dialFrac)
                        val hopMs = SpiritBoxDialMath.computeNextHopDurationMs(cfg.scanSpeedMs, cfg.jitterEnabled, rng)
                        framesUntilNextHop = ((hopMs * SAMPLE_RATE) / 1000L).toInt().coerceAtLeast(chunkFrames)

                        prevFragBuf = curFragBuf
                        prevFragPos = curFragPos
                        xfadeTotalFrames = ((cfg.crossfadeMs.coerceIn(0, 100) * SAMPLE_RATE) / 1000L).toInt()
                        xfadeCursor = 0

                        val chosen = pickHopSource(cfg, rng)
                        var sourceDetail = ""
                        when (chosen) {
                            "INTERNET_RADIO" -> {
                                curFragBuf = FloatArray(0)
                                curFragPos = 0
                                val switched = activateRandomRadioPlayer(cfg.sourceLevel * cfg.outputVolume.coerceAtMost(SAFE_MAX_VOLUME), rng)
                                sourceDetail = switched.second
                            }
                            "LOCAL_AUDIO" -> {
                                muteAllRadioPlayers()
                                val slice = readRandomLocalFragment(rng)
                                curFragBuf = slice.first
                                curFragPos = 0
                                sourceDetail = slice.second
                            }
                            "OFFLINE_TTS" -> {
                                muteAllRadioPlayers()
                                val ttsBuf = ttsFragmentPool.takeIf { it.isNotEmpty() }?.let {
                                    it[rng.nextInt(it.size)]
                                } ?: FloatArray(0)
                                curFragBuf = ttsBuf
                                curFragPos = 0
                                sourceDetail = "Offline word fragment"
                            }
                            else -> {
                                muteAllRadioPlayers()
                                curFragBuf = FloatArray(0)
                                curFragPos = 0
                                sourceDetail = cfg.staticNoiseType.displayName
                            }
                        }

                        if (sessionHopLog.size < 1500) {
                            sessionHopLog.add(
                                SpiritBoxHopLogItem(
                                    hopIndex = hopCounter,
                                    timestampMs = currentElapsedMs(),
                                    freqLabel = freqLabel,
                                    actualSource = chosen,
                                    detail = sourceDetail
                                )
                            )
                        }

                        val uiSource = if (cfg.blindTestMode) "Blind Test — Hidden" else chosen.replace('_', ' ')
                        _liveState.value = _liveState.value.copy(
                            hopCount = hopCounter,
                            dialFraction = dialFrac,
                            currentFrequencyLabel = freqLabel,
                            activeSourceLabel = uiSource,
                            activeSourceDetail = if (cfg.blindTestMode) "" else sourceDetail
                        )
                    }

                    // Synthesize & mix this chunk (calibrated so default staticLevel=0.55f yields ~-30 dBFS RMS)
                    val staticGain = if (cfg.enableGeneratedStatic) cfg.staticLevel.coerceIn(0f, 1f) * 1.15f else 0f
                    val srcGain = cfg.sourceLevel.coerceIn(0f, 1f)
                    val masterVol = cfg.outputVolume.coerceIn(0f, SAFE_MAX_VOLUME)
                    val tuneFreqHz = 620.0 + dialFrac * 1100.0

                    var maxAbs = 0f
                    var sumSq = 0.0

                    for (i in 0 until chunkFrames) {
                        // 1. Static source
                        var staticSample = 0f
                        if (staticGain > 0.001f) {
                            val w = rng.nextDouble() * 2.0 - 1.0
                            staticSample = when (cfg.staticNoiseType) {
                                StaticNoiseType.WHITE -> (w * 0.55).toFloat()
                                StaticNoiseType.PINK -> {
                                    p0 = 0.99886 * p0 + w * 0.0555179
                                    p1 = 0.99332 * p1 + w * 0.0750759
                                    p2 = 0.96900 * p2 + w * 0.1538520
                                    p3 = 0.86650 * p3 + w * 0.3104856
                                    p4 = 0.55000 * p4 + w * 0.5329522
                                    p5 = -0.7616 * p5 - w * 0.0168980
                                    val pink = p0 + p1 + p2 + p3 + p4 + p5 + p6 + w * 0.5362
                                    p6 = w * 0.115926
                                    (pink * 0.14).toFloat()
                                }
                                StaticNoiseType.BROWN -> {
                                    brown = (brown + 0.025 * w) / 1.022
                                    (brown * 3.0).toFloat().coerceIn(-1f, 1f)
                                }
                                StaticNoiseType.TUNING_STATIC -> {
                                    tuneWhistlePhase += 2.0 * PI * tuneFreqHz / SAMPLE_RATE
                                    val carrier = sin(tuneWhistlePhase).toFloat() * 0.08f
                                    val sweepBurst = if (xfadeCursor < xfadeTotalFrames) 1.35f else 0.75f
                                    (w.toFloat() * 0.42f + carrier) * sweepBurst
                                }
                            }
                            if (cfg.staticCrackleEnabled && rng.nextFloat() < 0.0045f) {
                                staticSample += (rng.nextFloat() * 2f - 1f) * 0.9f
                            }
                        }

                        // 2. Fragment source (Local Audio or Offline TTS) with smooth crossfade
                        val curS = if (curFragPos < curFragBuf.size) curFragBuf[curFragPos++] else 0f
                        val prevS = if (prevFragPos < prevFragBuf.size) prevFragBuf[prevFragPos++] else 0f
                        val fragSample = if (xfadeCursor < xfadeTotalFrames && xfadeTotalFrames > 0) {
                            val alpha = xfadeCursor.toFloat() / xfadeTotalFrames.toFloat()
                            xfadeCursor++
                            prevS * (1f - alpha) + curS * alpha
                        } else {
                            curS
                        }

                        // Combine & filter through voice-range HPF/LPF + hearing-safe soft limiter
                        var combined = staticSample * staticGain + fragSample * srcGain
                        combined = lpf.process(hpf.process(combined))
                        val limited = AudioMath.softLimit(combined * masterVol, ceiling = SAFE_MAX_VOLUME)
                        mixBuf[i] = limited

                        val a = abs(limited)
                        if (a > maxAbs) maxAbs = a
                        sumSq += (limited * limited).toDouble()
                    }

                    if (!isHeld.get()) {
                        framesUntilNextHop -= chunkFrames
                    }

                    track.write(mixBuf, 0, chunkFrames, AudioTrack.WRITE_BLOCKING)

                    // Direct-to-destination WAV write when Recorder & Sweep Output are ON
                    val now = System.currentTimeMillis()
                    synchronized(mixRecorderLock) {
                        val target = activeMixTarget
                        if (target != null) {
                            try {
                                activeMixBytesWritten += target.writeFloatSamples(mixBuf, chunkFrames, writeAsFloat32 = false)
                                if (now - lastMixHeaderPatchMs >= 5000L) {
                                    lastMixHeaderPatchMs = now
                                    target.patchHeaderPeriodic(activeMixBytesWritten)
                                }
                            } catch (e: Exception) {
                                logger.reportFailure(AppFeature.SPIRIT_BOX_RECORDER, "Spirit Box WAV write failed: ${e.message}", e)
                                runCatching { target.abortAndDelete() }
                                activeMixTarget = null
                                activeMixBytesWritten = 0L
                            }
                        }
                    }

                    // Update live FFT & waveform telemetry
                    val copyFft = min(chunkFrames, fftAccum.size)
                    if (fftPos + copyFft <= fftAccum.size) {
                        System.arraycopy(mixBuf, 0, fftAccum, fftPos, copyFft)
                        fftPos += copyFft
                    } else {
                        val shift = copyFft
                        System.arraycopy(fftAccum, shift, fftAccum, 0, fftAccum.size - shift)
                        System.arraycopy(mixBuf, 0, fftAccum, fftAccum.size - shift, shift)
                        fftPos = fftAccum.size
                    }
                    if (now - lastFftMs >= 80L && fftPos >= 1024) {
                        lastFftMs = now
                        val snap = fftAccum.copyOfRange(0, fftPos)
                        scope.launch(Dispatchers.Default) {
                            _liveFft.value = fftProcessor.analyze(snap, SAMPLE_RATE)
                        }
                    }

                    waveRing[waveIdx % waveRing.size] = maxAbs.coerceIn(0f, 1f)
                    waveIdx++
                    val orderedWave = FloatArray(waveRing.size) { idx ->
                        waveRing[(waveIdx + idx) % waveRing.size]
                    }
                    val rmsDb = AudioMath.linearToDb(sqrt(sumSq / chunkFrames).toFloat())
                    val recordingNow = synchronized(mixRecorderLock) { activeMixTarget != null } || micCaptureRunning.get()
                    _liveState.value = _liveState.value.copy(
                        elapsedMs = currentElapsedMs(),
                        outputDb = rmsDb,
                        waveform = orderedWave,
                        isRecorderEnabled = cfg.recorderEnabled,
                        isActivelyRecording = recordingNow
                    )
                }
            } catch (e: Exception) {
                logger.reportFailure(AppFeature.SPIRIT_BOX_STATIC, "Spirit Box audio loop error: ${e.message}", e)
            } finally {
                try {
                    track.pause()
                    track.flush()
                    track.release()
                } catch (_: Exception) {
                }
                audioTrack = null

                micCaptureRunning.set(false)
                try {
                    micThread?.join(1500L)
                } catch (_: Exception) {
                }
                micThread = null

                finalizeSession()
            }
        }, "FaintRec-SpiritBox").apply { start() }
    }

    @SuppressLint("MissingPermission")
    private fun startMicCompanionIfAllowed(): Boolean {
        if (!isRunning.get() || !config.recorderEnabled || !config.recordMicTrack) return false
        val hasMicPerm = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (!hasMicPerm) return false
        if (!micCaptureRunning.compareAndSet(false, true)) return true

        val storageType = latestStorageType
        val safUri = latestSafTreeUri
        val free = storageLocationManager.getCurrentFreeBytes(storageType, safUri)
        if (free in 0L until StorageLocationManager.CRITICAL_STOP_BYTES) {
            micCaptureRunning.set(false)
            logger.warn(AppFeature.SPIRIT_BOX_RECORDER, "Insufficient storage for Spirit Box mic track")
            return false
        }

        micThread = Thread({
            val minBuf = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            if (minBuf <= 0) {
                micCaptureRunning.set(false)
                return@Thread
            }

            // Gated source fallback: UNPROCESSED only when property is "true", then VOICE_RECOGNITION, then MIC
            val candidates = mutableListOf<Pair<Int, String>>()
            if (deviceManager.supportsUnprocessedSource()) {
                candidates.add(MediaRecorder.AudioSource.UNPROCESSED to "UNPROCESSED")
            }
            candidates.add(MediaRecorder.AudioSource.VOICE_RECOGNITION to "VOICE_RECOGNITION")
            candidates.add(MediaRecorder.AudioSource.MIC to "MIC")

            var recorder: AudioRecord? = null
            var chosenSource = "MIC"
            for ((src, label) in candidates) {
                val r = runCatching {
                    AudioRecord.Builder()
                        .setAudioSource(src)
                        .setAudioFormat(
                            AudioFormat.Builder()
                                .setSampleRate(SAMPLE_RATE)
                                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                .build()
                        )
                        .setBufferSizeInBytes(max(minBuf * 3, 16384))
                        .build()
                }.getOrNull()
                if (r != null && r.state == AudioRecord.STATE_INITIALIZED) {
                    recorder = r
                    chosenSource = label
                    break
                } else {
                    runCatching { r?.release() }
                }
            }
            val activeRecorder = recorder ?: run {
                micCaptureRunning.set(false)
                return@Thread
            }
            AudioDeviceManager.disableAndReleaseAutoAttachedEffects(activeRecorder.audioSessionId)

            val fileName = AudioMath.timestampFileName("SpiritBox_Mic", "wav")
            val target = storageLocationManager.openDirectWavTarget(
                storageType = storageType,
                safTreeUri = safUri,
                fileName = fileName,
                sampleRate = SAMPLE_RATE,
                channels = 1,
                bitsPerSample = 16,
                isFloat = false,
                deviceName = "Spirit Box Mic Track",
                sourceLabel = chosenSource,
                saveRaw = true,
                gainDb = 0f
            ).getOrNull()

            if (target == null) {
                runCatching { activeRecorder.release() }
                micCaptureRunning.set(false)
                return@Thread
            }

            val shorts = ShortArray(2048)
            val floats = FloatArray(2048)
            var bytesWritten = 0L
            var lastPatchMs = System.currentTimeMillis()

            try {
                target.writePlaceholderHeader(SAMPLE_RATE, 1, 16, isFloat = false)
                activeRecorder.startRecording()
                while (isRunning.get() && micCaptureRunning.get()) {
                    val n = activeRecorder.read(shorts, 0, shorts.size)
                    if (n > 0) {
                        for (i in 0 until n) floats[i] = shorts[i] / 32768f
                        bytesWritten += target.writeFloatSamples(floats, n, writeAsFloat32 = false)
                        val now = System.currentTimeMillis()
                        if (now - lastPatchMs >= 5000L) {
                            lastPatchMs = now
                            target.patchHeaderPeriodic(bytesWritten)
                        }
                    }
                }
            } catch (e: Exception) {
                logger.warn(AppFeature.SPIRIT_BOX_RECORDER, "Spirit Box mic track error: ${e.message}")
            } finally {
                micCaptureRunning.set(false)
                runCatching { activeRecorder.stop() }
                runCatching { activeRecorder.release() }
                val summary = runCatching {
                    target.finalizeAndClose(
                        dataBytes = bytesWritten,
                        cues = emptyList(),
                        fallbackSampleRate = SAMPLE_RATE,
                        fallbackChannels = 1,
                        fallbackIsFloat = false
                    )
                }.getOrNull()
                if (summary != null) {
                    scope.launch {
                        val micEntity = RecordingEntity(
                            title = target.fileName.substringBeforeLast('.'),
                            filePath = target.filePath,
                            mediaStoreUri = if (target.storageType == RecordingStorageType.PUBLIC_DOCUMENTS) target.contentUri else null,
                            storageType = target.storageType,
                            storageUri = target.contentUri,
                            durationMs = summary.durationMs,
                            fileSizeBytes = summary.fileSizeBytes,
                            format = "WAV",
                            sampleRate = summary.sampleRate,
                            channels = summary.channels,
                            bitsPerSample = summary.bitsPerSample,
                            isFloatPcm = summary.isFloat,
                            sourceTag = RecordingSourceTag.SPIRIT_BOX,
                            audioSourceUsed = chosenSource,
                            inputDeviceName = "Spirit Box Mic Track"
                        )
                        lastSavedMicRecId = repository.insertRecording(micEntity)
                        logger.reportSuccess(AppFeature.SPIRIT_BOX_RECORDER, "Saved Spirit Box mic track (${micEntity.title})")
                    }
                }
                refreshRecordingFlag()
            }
        }, "FaintRec-SBMic").apply { start() }
        return true
    }

    private fun stopMicCompanionLive() {
        micCaptureRunning.set(false)
    }

    private fun finalizeSession() {
        val durationMs = currentElapsedMs()
        val cfg = config
        val markersCopy = sessionMarkers.toList()
        val questionsCopy = sessionQuestions.toList()
        val hopsCopy = sessionHopLog.toList()

        val remainingMix = synchronized(mixRecorderLock) {
            val t = activeMixTarget
            val bytes = activeMixBytesWritten
            val startMs = activeMixStartElapsedMs
            activeMixTarget = null
            activeMixBytesWritten = 0L
            if (t != null) Triple(t, bytes, startMs) else null
        }

        scope.launch {
            var mixRecId = lastSavedMixRecId
            if (remainingMix != null) {
                val finalizedId = finalizeSingleMixTarget(
                    target = remainingMix.first,
                    dataBytes = remainingMix.second,
                    segmentStartElapsedMs = remainingMix.third,
                    markersSnapshot = markersCopy,
                    bandName = cfg.bandMode.displayName
                )
                if (finalizedId != null) mixRecId = finalizedId
            }

            val sourcesUsed = buildList {
                if (cfg.enableInternetRadio && cfg.streamUrls.isNotEmpty()) add("Internet Radio")
                if (cfg.enableLocalAudio) add("Local Audio")
                if (cfg.enableGeneratedStatic) add("Static (${cfg.staticNoiseType.displayName})")
                if (cfg.enableOfflineTts) add("Offline TTS")
            }.joinToString(", ")

            val session = SpiritBoxSessionEntity(
                startedAtMillis = sessionStartMillis,
                durationMs = durationMs,
                recordingId = mixRecId,
                micRecordingId = lastSavedMicRecId,
                bandMode = cfg.bandMode.name,
                scanSpeedMs = cfg.scanSpeedMs,
                jitterEnabled = cfg.jitterEnabled,
                direction = cfg.sweepDirection.name,
                sourcesUsed = sourcesUsed,
                blindTestEnabled = cfg.blindTestMode,
                hopCount = hopsCopy.size,
                questionsJson = json.encodeToString(questionsCopy),
                markersJson = json.encodeToString(markersCopy),
                hopLogJson = json.encodeToString(hopsCopy)
            )
            repository.insertSpiritBoxSession(session)
        }
    }

    private fun pickHopSource(cfg: SpiritBoxConfig, rng: Random): String {
        val candidates = mutableListOf<String>()
        if (cfg.enableInternetRadio && radioPlayers.isNotEmpty()) candidates.add("INTERNET_RADIO")
        if (cfg.enableLocalAudio && localWavPool.isNotEmpty()) candidates.add("LOCAL_AUDIO")
        if (cfg.enableOfflineTts && ttsFragmentPool.isNotEmpty() && rng.nextFloat() < 0.35f) {
            candidates.add("OFFLINE_TTS")
        }
        if (cfg.enableGeneratedStatic || candidates.isEmpty()) candidates.add("GENERATED_STATIC")
        return candidates[rng.nextInt(candidates.size)]
    }

    private suspend fun refreshLocalWavPool() {
        runCatching {
            val report = localAudioSource.scanLibraryWavs()
            localWavPool.clear()
            localWavPool.addAll(report.validWavs)
            val breakdown = report.formatPerStorageSummary()
            if (report.validWavs.isNotEmpty()) {
                logger.reportSuccess(
                    AppFeature.SPIRIT_BOX_LOCAL,
                    "Loaded ${report.validWavs.size} valid WAV file(s) ($breakdown)"
                )
            } else {
                logger.reportNotSupported(
                    AppFeature.SPIRIT_BOX_LOCAL,
                    "Not supported: no recordings yet ($breakdown)"
                )
            }
        }.onFailure { e ->
            logger.reportFailure(AppFeature.SPIRIT_BOX_LOCAL, "Local WAV scan failed: ${e.message}", e)
        }
    }

    private fun readRandomLocalFragment(rng: Random): Pair<FloatArray, String> {
        val pool = localWavPool.toList()
        if (pool.isEmpty()) return FloatArray(0) to "No local WAV"
        return runCatching {
            val fragment = localAudioSource.readRandomFragment(
                pool = pool,
                targetSampleRate = SAMPLE_RATE,
                minDurationSec = SpiritBoxLocalAudioSource.MIN_FRAGMENT_SEC,
                maxDurationSec = SpiritBoxLocalAudioSource.MAX_FRAGMENT_SEC,
                rng = rng
            )
            if (fragment != null && fragment.samples.isNotEmpty()) {
                val durLabel = String.format(Locale.US, "%.2fs", fragment.durationSec)
                logger.reportSuccess(AppFeature.SPIRIT_BOX_LOCAL)
                fragment.samples to "${fragment.title} ($durLabel)"
            } else {
                logger.warn(AppFeature.SPIRIT_BOX_LOCAL, "Skipped missing/corrupt local WAV while picking fragment")
                FloatArray(0) to "Unavailable WAV"
            }
        }.getOrElse { e ->
            logger.reportFailure(AppFeature.SPIRIT_BOX_LOCAL, "Failed reading local fragment: ${e.message}", e)
            FloatArray(0) to "Local WAV error"
        }
    }

    private fun prepareRadioPool(urls: List<String>) {
        mainHandler.post {
            releaseRadioPoolInternal()
            urls.forEach { url ->
                runCatching {
                    val player = ExoPlayer.Builder(context).build().apply {
                        setMediaItem(MediaItem.fromUri(url))
                        volume = 0f
                        repeatMode = Player.REPEAT_MODE_ALL
                        addListener(object : Player.Listener {
                            override fun onPlayerError(error: PlaybackException) {
                                logger.warn(AppFeature.SPIRIT_BOX_RADIO, "Radio stream error ($url): ${error.errorCodeName}")
                            }
                            override fun onPlaybackStateChanged(playbackState: Int) {
                                if (playbackState == Player.STATE_READY) {
                                    logger.reportSuccess(AppFeature.SPIRIT_BOX_RADIO, "Radio stream ready: $url")
                                }
                            }
                        })
                        prepare()
                        playWhenReady = true
                    }
                    radioPlayers.add(player)
                    radioPlayerUrls.add(url)
                }.onFailure { e ->
                    logger.reportFailure(AppFeature.SPIRIT_BOX_RADIO, "Failed preparing stream $url: ${e.message}", e)
                }
            }
        }
    }

    private fun activateRandomRadioPlayer(volumeLevel: Float, rng: Random): Pair<Int, String> {
        val count = radioPlayers.size
        if (count == 0) return -1 to "No radio streams"
        val chosenIdx = rng.nextInt(count)
        val url = radioPlayerUrls.getOrElse(chosenIdx) { "Stream #${chosenIdx + 1}" }
        val clampedVol = volumeLevel.coerceIn(0f, SAFE_MAX_VOLUME)
        mainHandler.post {
            radioPlayers.forEachIndexed { idx, player ->
                runCatching {
                    if (idx == chosenIdx) {
                        if (player.isCurrentMediaItemSeekable && player.duration > 5000L) {
                            val jumpMs = (rng.nextDouble() * (player.duration - 2000L)).toLong()
                            player.seekTo(jumpMs)
                        }
                        player.volume = clampedVol
                        if (!player.isPlaying) player.play()
                    } else {
                        player.volume = 0f
                    }
                }
            }
        }
        return chosenIdx to url
    }

    private fun muteAllRadioPlayers() {
        mainHandler.post {
            radioPlayers.forEach { p -> runCatching { p.volume = 0f } }
        }
    }

    private fun releaseRadioPool() {
        mainHandler.post { releaseRadioPoolInternal() }
    }

    private fun releaseRadioPoolInternal() {
        radioPlayers.forEach { p ->
            runCatching {
                p.stop()
                p.release()
            }
        }
        radioPlayers.clear()
        radioPlayerUrls.clear()
    }

    private fun prepareOfflineTtsFragments() {
        if (ttsFragmentPool.isNotEmpty()) return
        mainHandler.post {
            if (ttsEngine == null) {
                ttsEngine = TextToSpeech(context) { status ->
                    if (status == TextToSpeech.SUCCESS) {
                        ttsReady = true
                        synthesizeWordBankAsync()
                    } else {
                        logger.reportFailure(AppFeature.SPIRIT_BOX_TTS, "Offline TTS initialization failed (status=$status)")
                    }
                }
            } else if (ttsReady) {
                synthesizeWordBankAsync()
            }
        }
    }

    private fun synthesizeWordBankAsync() {
        scope.launch {
            val engine = ttsEngine ?: return@launch
            val cacheDir = File(context.cacheDir, "spiritbox_tts").apply { mkdirs() }
            val enSample = OFFLINE_WORDS_EN.shuffled().take(5).map { it to Locale.US }
            val hiSample = if (config.ttsHindiAndEnglish) {
                OFFLINE_WORDS_HI.shuffled().take(4).map { it to Locale.forLanguageTag("hi-IN") }
            } else emptyList()

            val words = enSample + hiSample
            var synthesizedCount = 0
            for ((idx, pair) in words.withIndex()) {
                val (word, locale) = pair
                val outFile = File(cacheDir, "sb_tts_$idx.wav")
                runCatching {
                    engine.language = locale
                    engine.setSpeechRate(1.05f)
                    val res = engine.synthesizeToFile(word, null, outFile, "sb_word_$idx")
                    if (res == TextToSpeech.SUCCESS) {
                        var tries = 0
                        while ((!outFile.exists() || outFile.length() <= 44L) && tries < 18) {
                            Thread.sleep(50L)
                            tries++
                        }
                        if (outFile.exists() && outFile.length() > 44L) {
                            val info = WavFileHelper.parseWavInfo(outFile)
                            if (info != null && info.totalFrames > 0) {
                                val frames = info.totalFrames.toInt().coerceAtMost(info.sampleRate * 2)
                                val raw = FloatArray(frames * info.channels)
                                val read = RandomAccessFile(outFile, "r").use { raf ->
                                    WavFileHelper.readFramesAsFloat(raf, info, 0L, frames, raw)
                                }
                                if (read > 0) {
                                    val ratio = info.sampleRate.toDouble() / SAMPLE_RATE.toDouble()
                                    val outFrames = ((read / ratio).toInt()).coerceIn(1, SAMPLE_RATE * 2)
                                    val mono = FloatArray(outFrames) { f ->
                                        val srcIdx = (f * ratio).toInt().coerceIn(0, read - 1)
                                        raw[srcIdx * info.channels] * 0.42f
                                    }
                                    ttsFragmentPool.add(mono)
                                    synthesizedCount++
                                }
                            }
                        }
                    }
                }
            }
            if (synthesizedCount > 0) {
                logger.reportSuccess(AppFeature.SPIRIT_BOX_TTS, "Prepared $synthesizedCount offline EN/HI TTS fragments")
            }
        }
    }

    private fun requestAudioFocus(): Boolean {
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build()
                    )
                    .setOnAudioFocusChangeListener(focusChangeListener)
                    .build()
                focusRequest = req
                audioManager.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            } else {
                @Suppress("DEPRECATION")
                audioManager.requestAudioFocus(
                    focusChangeListener,
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN
                ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            }
        }.getOrDefault(false)
    }

    private fun abandonAudioFocus() {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
                focusRequest = null
            } else {
                @Suppress("DEPRECATION")
                audioManager.abandonAudioFocus(focusChangeListener)
            }
        }
    }

    private fun currentElapsedMs(): Long =
        if (sessionStartMillis > 0L) (System.currentTimeMillis() - sessionStartMillis).coerceAtLeast(0L) else 0L
}
