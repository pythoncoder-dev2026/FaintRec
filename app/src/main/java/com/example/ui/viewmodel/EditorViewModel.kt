package com.example.ui.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.audio.AudioImporter
import com.example.audio.WavFileHelper
import com.example.data.AppFeature
import com.example.data.EdlOpType
import com.example.data.EdlOperation
import com.example.data.FaintRecRepository
import com.example.data.NoiseAddParams
import com.example.data.NoiseLevelParams
import com.example.data.NoiseProfilePayload
import com.example.data.NoiseRemovalParams
import com.example.data.NoiseType
import com.example.data.OneTapNoiseTool
import com.example.data.RecordingEntity
import com.example.editor.EqPreset
import com.example.editor.LoudnessMetrics
import com.example.editor.LufsMeter
import com.example.editor.NoiseProfileLearner
import com.example.editor.NonDestructiveEditorEngine
import com.example.logging.AppLogger
import com.example.util.AudioMath
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.UUID
import javax.inject.Inject

data class EditorUiState(
    val recording: RecordingEntity? = null,
    val waveformPeaks: FloatArray = FloatArray(0),
    val selectionStartFraction: Float = 0.1f,
    val selectionEndFraction: Float = 0.9f,
    val operations: List<EdlOperation> = emptyList(),
    val redoStack: List<EdlOperation> = emptyList(),
    val eqBandsDb: List<Float> = EqPreset.FLAT.gainsDb,
    val eqPreset: EqPreset = EqPreset.FLAT,
    val eqBypass: Boolean = false,
    val hpfHz: Float = 0f,
    val hpfSlopeDb: Int = 12,
    val lpfHz: Float = 20000f,
    val lpfSlopeDb: Int = 12,
    val selectionGainDb: Float = 6f,
    val targetLufs: Float = -16f,
    val loudnessBefore: LoudnessMetrics? = null,
    val loudnessAfter: LoudnessMetrics? = null,
    val isMeasuringLufs: Boolean = false,
    val isRendering: Boolean = false,
    val renderProgress: Float = 0f,
    // Task 1: Non-Destructive Noise Tools state
    val noiseProfile: NoiseProfilePayload? = null,
    val isLearningNoiseProfile: Boolean = false,
    val noiseRemoval: NoiseRemovalParams = NoiseRemovalParams(),
    val oneTapNoiseTool: OneTapNoiseTool = OneTapNoiseTool.NONE,
    val noiseAdd: NoiseAddParams = NoiseAddParams(),
    val noiseLevel: NoiseLevelParams = NoiseLevelParams(),
    val noiseBypass: Boolean = false,
    val noiseOnlyMode: Boolean = false
) {
    val canUndo: Boolean get() = operations.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()
    val selectionStartMs: Long get() = ((recording?.durationMs ?: 0L) * selectionStartFraction).toLong()
    val selectionEndMs: Long get() = ((recording?.durationMs ?: 0L) * selectionEndFraction).toLong()
}

@HiltViewModel
class EditorViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: FaintRecRepository,
    private val editorEngine: NonDestructiveEditorEngine,
    private val audioImporter: AudioImporter,
    private val appLogger: AppLogger,
    private val storageLocationManager: com.example.storage.StorageLocationManager
) : ViewModel() {

    private val _state = MutableStateFlow(EditorUiState())
    val state: StateFlow<EditorUiState> = _state.asStateFlow()

    val isPreviewPlaying: StateFlow<Boolean> = editorEngine.isPreviewPlaying
    val previewPositionMs: StateFlow<Long> = editorEngine.previewPositionMs
    val beforeAfterDb: StateFlow<Pair<Float, Float>> = editorEngine.beforeAfterDb

    val allRecordings: StateFlow<List<RecordingEntity>> = repository.recordings.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList()
    )

    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val events: SharedFlow<String> = _events.asSharedFlow()

    private var workingWavFile: File? = null
    private var tempDecodedCacheFile: File? = null

    fun loadRecording(recordingId: Long) {
        viewModelScope.launch {
            val rec = repository.getRecording(recordingId) ?: return@launch
            val (ops, redo) = repository.loadEdl(recordingId)

            val rawFile = withContext(Dispatchers.IO) {
                storageLocationManager.resolveReadableFile(rec)
            } ?: run {
                _events.tryEmit("Recording file unavailable (check SD card or storage access).")
                return@launch
            }
            val editableFile = if (WavFileHelper.parseWavInfo(rawFile) != null) {
                rawFile
            } else {
                tempDecodedCacheFile?.delete()
                val cacheWav = File(context.cacheDir, "editor_work_${rec.id}.wav")
                val decoded = runCatching {
                    audioImporter.decodeFileToWav16Bit(rawFile, cacheWav)
                }.isSuccess
                if (decoded) {
                    tempDecodedCacheFile = cacheWav
                    cacheWav
                } else {
                    _events.tryEmit("Unable to decode ${rec.format} for editing.")
                    rawFile
                }
            }
            workingWavFile = editableFile

            val peaks = withContext(Dispatchers.Default) {
                WavFileHelper.extractWaveformPeaks(editableFile, 320)
            }
            val lastEq = ops.lastOrNull { it.type == EdlOpType.EQ }?.eqBandsDb ?: EqPreset.FLAT.gainsDb
            val lastFilt = ops.lastOrNull { it.type == EdlOpType.FILTER }
            val lastNoiseRem = ops.lastOrNull { it.type == EdlOpType.NOISE_REMOVAL }?.noiseRemoval ?: NoiseRemovalParams()
            val lastOneTap = ops.lastOrNull { it.type == EdlOpType.NOISE_ONE_TAP }?.oneTapNoiseTool ?: OneTapNoiseTool.NONE
            val lastNoiseAdd = ops.lastOrNull { it.type == EdlOpType.NOISE_ADD }?.noiseAdd ?: NoiseAddParams()
            val lastNoiseLvl = ops.lastOrNull { it.type == EdlOpType.NOISE_LEVEL_CONTROL }?.noiseLevel ?: NoiseLevelParams()
            val savedProfile = ops.lastOrNull { it.noiseProfile != null }?.noiseProfile
            val nyquist = (rec.sampleRate / 2f).coerceAtMost(20000f)

            _state.value = _state.value.copy(
                recording = rec,
                waveformPeaks = peaks,
                operations = ops,
                redoStack = redo,
                eqBandsDb = lastEq,
                hpfHz = lastFilt?.hpfHz ?: 0f,
                hpfSlopeDb = lastFilt?.hpfSlopeDb ?: 12,
                lpfHz = lastFilt?.lpfHz?.takeIf { it > 0f } ?: nyquist,
                lpfSlopeDb = lastFilt?.lpfSlopeDb ?: 12,
                noiseProfile = savedProfile,
                noiseRemoval = lastNoiseRem,
                oneTapNoiseTool = lastOneTap,
                noiseAdd = lastNoiseAdd,
                noiseLevel = lastNoiseLvl
            )
            syncLiveEngineAll()
            measureLoudness()
        }
    }

    fun setSelection(startFraction: Float, endFraction: Float) {
        val s = startFraction.coerceIn(0f, 0.98f)
        val e = endFraction.coerceIn(s + 0.02f, 1.0f)
        _state.value = _state.value.copy(selectionStartFraction = s, selectionEndFraction = e)
        syncLiveEngineNoise()
    }

    fun setSelectionGainDb(db: Float) {
        _state.value = _state.value.copy(selectionGainDb = db.coerceIn(-24f, 24f))
    }

    // --- Task 1: Noise Tools Controls ---
    fun learnNoiseProfileFromSelection() {
        val st = _state.value
        val file = workingWavFile ?: st.recording?.let { File(it.filePath) } ?: return
        viewModelScope.launch {
            _state.value = _state.value.copy(isLearningNoiseProfile = true)
            val profile = NoiseProfileLearner.learnFromRegion(file, st.selectionStartMs, st.selectionEndMs)
            _state.value = _state.value.copy(
                noiseProfile = profile,
                isLearningNoiseProfile = false
            )
            syncLiveEngineNoise()
            if (profile != null) {
                appLogger.reportSuccess(
                    AppFeature.NOISE_TOOLS,
                    "Learned noise profile (${profile.learnedStartMs}–${profile.learnedEndMs} ms)"
                )
                _events.tryEmit(
                    "Learned noise profile (${AudioMath.formatDuration(profile.learnedStartMs)}–${AudioMath.formatDuration(profile.learnedEndMs)})"
                )
            } else {
                appLogger.warn(AppFeature.NOISE_TOOLS, "Noise profile selection too short")
                _events.tryEmit("Select at least 0.1 s of noise-only audio to learn profile.")
            }
        }
    }

    fun autoDetectQuietestNoiseProfile() {
        val st = _state.value
        val file = workingWavFile ?: st.recording?.let { File(it.filePath) } ?: return
        val durMs = st.recording?.durationMs?.coerceAtLeast(1L) ?: 1L
        viewModelScope.launch {
            _state.value = _state.value.copy(isLearningNoiseProfile = true)
            val profile = NoiseProfileLearner.autoDetectQuietest1Second(file)
            if (profile != null) {
                val sFrac = (profile.learnedStartMs.toFloat() / durMs).coerceIn(0f, 0.95f)
                val eFrac = (profile.learnedEndMs.toFloat() / durMs).coerceIn(sFrac + 0.02f, 1f)
                _state.value = _state.value.copy(
                    noiseProfile = profile,
                    selectionStartFraction = sFrac,
                    selectionEndFraction = eFrac,
                    isLearningNoiseProfile = false
                )
                syncLiveEngineNoise()
                _events.tryEmit(
                    "Auto-detected quietest 1s (${AudioMath.formatDuration(profile.learnedStartMs)}–${AudioMath.formatDuration(profile.learnedEndMs)})"
                )
            } else {
                _state.value = _state.value.copy(isLearningNoiseProfile = false)
                _events.tryEmit("Could not detect quiet 1s noise profile.")
            }
        }
    }

    fun updateNoiseRemoval(transform: (NoiseRemovalParams) -> NoiseRemovalParams) {
        val next = transform(_state.value.noiseRemoval)
        _state.value = _state.value.copy(noiseRemoval = next)
        syncLiveEngineNoise()
    }

    fun selectOneTapNoiseTool(tool: OneTapNoiseTool, commitToEdl: Boolean = true) {
        _state.value = _state.value.copy(oneTapNoiseTool = tool)
        syncLiveEngineNoise()
        if (commitToEdl && tool != OneTapNoiseTool.NONE) {
            addOperation(EdlOpType.NOISE_ONE_TAP)
        }
    }

    fun updateNoiseAdd(transform: (NoiseAddParams) -> NoiseAddParams) {
        val next = transform(_state.value.noiseAdd)
        _state.value = _state.value.copy(noiseAdd = next)
        syncLiveEngineNoise()
    }

    fun updateNoiseLevel(transform: (NoiseLevelParams) -> NoiseLevelParams) {
        val next = transform(_state.value.noiseLevel)
        _state.value = _state.value.copy(noiseLevel = next)
        syncLiveEngineNoise()
    }

    fun toggleNoiseBypass() {
        val next = !_state.value.noiseBypass
        _state.value = _state.value.copy(noiseBypass = next)
        editorEngine.liveNoiseBypass = next
    }

    fun toggleNoiseOnlyMode() {
        val next = !_state.value.noiseOnlyMode
        _state.value = _state.value.copy(noiseOnlyMode = next)
        editorEngine.liveNoiseOnlyMode = next
    }

    fun resetNoiseTools() {
        _state.value = _state.value.copy(
            noiseRemoval = NoiseRemovalParams(),
            oneTapNoiseTool = OneTapNoiseTool.NONE,
            noiseAdd = NoiseAddParams(),
            noiseLevel = NoiseLevelParams(),
            noiseBypass = false,
            noiseOnlyMode = false
        )
        syncLiveEngineNoise()
        _events.tryEmit("Noise tools reset.")
    }

    fun addOperation(type: EdlOpType, mergeRecording: RecordingEntity? = null) {
        val st = _state.value
        val rec = st.recording ?: return
        val op = when (type) {
            EdlOpType.TRIM -> EdlOperation(
                id = UUID.randomUUID().toString(),
                type = type,
                label = "Trim (${st.selectionStartMs}–${st.selectionEndMs} ms)",
                startMs = st.selectionStartMs,
                endMs = st.selectionEndMs
            )
            EdlOpType.CUT -> EdlOperation(
                id = UUID.randomUUID().toString(),
                type = type,
                label = "Cut (${st.selectionStartMs}–${st.selectionEndMs} ms)",
                startMs = st.selectionStartMs,
                endMs = st.selectionEndMs
            )
            EdlOpType.FADE_IN -> EdlOperation(
                id = UUID.randomUUID().toString(),
                type = type,
                label = "Fade In (${st.selectionStartMs}–${st.selectionEndMs} ms)",
                startMs = st.selectionStartMs,
                endMs = st.selectionEndMs
            )
            EdlOpType.FADE_OUT -> EdlOperation(
                id = UUID.randomUUID().toString(),
                type = type,
                label = "Fade Out (${st.selectionStartMs}–${st.selectionEndMs} ms)",
                startMs = st.selectionStartMs,
                endMs = st.selectionEndMs
            )
            EdlOpType.SILENCE -> EdlOperation(
                id = UUID.randomUUID().toString(),
                type = type,
                label = "Silence (${st.selectionStartMs}–${st.selectionEndMs} ms)",
                startMs = st.selectionStartMs,
                endMs = st.selectionEndMs
            )
            EdlOpType.GAIN -> EdlOperation(
                id = UUID.randomUUID().toString(),
                type = type,
                label = "Gain ${if (st.selectionGainDb >= 0) "+" else ""}${st.selectionGainDb.toInt()} dB",
                startMs = st.selectionStartMs,
                endMs = st.selectionEndMs,
                gainDb = st.selectionGainDb
            )
            EdlOpType.EQ -> EdlOperation(
                id = UUID.randomUUID().toString(),
                type = type,
                label = "10-Band EQ (${st.eqPreset.displayName})",
                eqBandsDb = st.eqBandsDb
            )
            EdlOpType.FILTER -> EdlOperation(
                id = UUID.randomUUID().toString(),
                type = type,
                label = "HPF ${st.hpfHz.toInt()}Hz (${st.hpfSlopeDb}dB) / LPF ${st.lpfHz.toInt()}Hz (${st.lpfSlopeDb}dB)",
                hpfHz = st.hpfHz,
                hpfSlopeDb = st.hpfSlopeDb,
                lpfHz = st.lpfHz,
                lpfSlopeDb = st.lpfSlopeDb
            )
            EdlOpType.LUFS_NORMALIZE -> EdlOperation(
                id = UUID.randomUUID().toString(),
                type = type,
                label = "Loudness ${st.targetLufs.toInt()} LUFS (-1 dBTP)",
                targetLufs = st.targetLufs,
                truePeakCeilingDb = -1.0f
            )
            EdlOpType.MERGE_APPEND -> {
                val m = mergeRecording ?: return
                EdlOperation(
                    id = UUID.randomUUID().toString(),
                    type = type,
                    label = "Merge + ${m.title}",
                    mergeSourcePath = m.filePath
                )
            }
            EdlOpType.NOISE_REMOVAL -> {
                val p = st.noiseRemoval.copy(enabled = true)
                _state.value = st.copy(noiseRemoval = p)
                syncLiveEngineNoise()
                EdlOperation(
                    id = UUID.randomUUID().toString(),
                    type = type,
                    label = String.format(
                        Locale.US,
                        "Noise Removal (-%.0f dB, sens %.1f%s)",
                        p.reductionDb,
                        p.sensitivity,
                        if (p.keepFaintSounds) ", keep faint" else ""
                    ),
                    noiseProfile = st.noiseProfile,
                    noiseRemoval = p
                )
            }
            EdlOpType.NOISE_ONE_TAP -> {
                val tool = st.oneTapNoiseTool
                if (tool == OneTapNoiseTool.NONE) return
                val toolName = when (tool) {
                    OneTapNoiseTool.HISS_REMOVER -> "Hiss Remover"
                    OneTapNoiseTool.HUM_REMOVER_50HZ -> "Hum Remover (50 Hz + harmonics)"
                    OneTapNoiseTool.HUM_REMOVER_60HZ -> "Hum Remover (60 Hz + harmonics)"
                    OneTapNoiseTool.CLICK_POP_REMOVER -> "Click/Pop Remover"
                    OneTapNoiseTool.WIND_NOISE_CUT -> "Wind-Noise Cut (Adaptive HPF)"
                    OneTapNoiseTool.NONE -> "None"
                }
                EdlOperation(
                    id = UUID.randomUUID().toString(),
                    type = type,
                    label = "One-Tap: $toolName",
                    oneTapNoiseTool = tool
                )
            }
            EdlOpType.NOISE_ADD -> {
                val p = st.noiseAdd.copy(enabled = true)
                _state.value = st.copy(noiseAdd = p)
                syncLiveEngineNoise()
                val scopeLbl = if (p.applyToSelectionOnly) "selection" else "full file"
                val lvlLbl = if (p.fixedSnrMode) "SNR ${p.targetSnrDb.toInt()} dB" else "${p.volumeDb.toInt()} dBFS"
                EdlOperation(
                    id = UUID.randomUUID().toString(),
                    type = type,
                    label = "Add ${p.noiseType.name.replace('_', ' ')} ($lvlLbl, $scopeLbl)",
                    startMs = if (p.applyToSelectionOnly) st.selectionStartMs else 0L,
                    endMs = if (p.applyToSelectionOnly) st.selectionEndMs else rec.durationMs,
                    noiseProfile = if (p.noiseType == NoiseType.LEARNED_ROOM_TONE) st.noiseProfile else null,
                    noiseAdd = p
                )
            }
            EdlOpType.NOISE_LEVEL_CONTROL -> {
                val p = st.noiseLevel.copy(enabled = true)
                _state.value = st.copy(noiseLevel = p)
                syncLiveEngineNoise()
                EdlOperation(
                    id = UUID.randomUUID().toString(),
                    type = type,
                    label = String.format(
                        Locale.US,
                        "Noise Level (Exist %+.0fdB, L:%+.0f M:%+.0f H:%+.0f)",
                        p.existingNoiseDb, p.lowBandNoiseDb, p.midBandNoiseDb, p.highBandNoiseDb
                    ),
                    noiseProfile = st.noiseProfile,
                    noiseLevel = p
                )
            }
        }
        val nextOps = _state.value.operations + op
        _state.value = _state.value.copy(operations = nextOps, redoStack = emptyList())
        persistEdl(rec.id, nextOps, emptyList())
        val isNoiseOp = type in setOf(
            EdlOpType.NOISE_REMOVAL,
            EdlOpType.NOISE_ONE_TAP,
            EdlOpType.NOISE_ADD,
            EdlOpType.NOISE_LEVEL_CONTROL
        )
        appLogger.reportSuccess(
            if (isNoiseOp) AppFeature.NOISE_TOOLS else AppFeature.EDITOR,
            "Added EDL op: ${op.label}"
        )
        _events.tryEmit("Added to EDL: ${op.label}")
    }

    fun splitAtSelectionStart() {
        val st = _state.value
        val rec = st.recording ?: return
        val effectiveRec = workingWavFile?.let { rec.copy(filePath = it.absolutePath) } ?: rec
        viewModelScope.launch {
            _state.value = _state.value.copy(isRendering = true, renderProgress = 0f)
            val res = editorEngine.splitRecordingAt(effectiveRec, st.selectionStartMs) { p ->
                _state.value = _state.value.copy(renderProgress = p)
            }
            _state.value = _state.value.copy(isRendering = false)
            res.onSuccess { (p1, p2) ->
                repository.insertRecording(p1)
                repository.insertRecording(p2)
                _events.tryEmit("Split into 2 non-destructive files in Library.")
            }.onFailure { err ->
                _events.tryEmit("Split failed: ${err.message}")
            }
        }
    }

    fun undo() {
        val st = _state.value
        val rec = st.recording ?: return
        if (st.operations.isEmpty()) return
        val popped = st.operations.last()
        val nextOps = st.operations.dropLast(1)
        val nextRedo = st.redoStack + popped
        _state.value = st.copy(operations = nextOps, redoStack = nextRedo)
        restoreNoiseUiFromOps(nextOps)
        persistEdl(rec.id, nextOps, nextRedo)
    }

    fun redo() {
        val st = _state.value
        val rec = st.recording ?: return
        if (st.redoStack.isEmpty()) return
        val restored = st.redoStack.last()
        val nextOps = st.operations + restored
        val nextRedo = st.redoStack.dropLast(1)
        _state.value = st.copy(operations = nextOps, redoStack = nextRedo)
        restoreNoiseUiFromOps(nextOps)
        persistEdl(rec.id, nextOps, nextRedo)
    }

    private fun restoreNoiseUiFromOps(ops: List<EdlOperation>) {
        val lastRem = ops.lastOrNull { it.type == EdlOpType.NOISE_REMOVAL }?.noiseRemoval ?: NoiseRemovalParams()
        val lastOneTap = ops.lastOrNull { it.type == EdlOpType.NOISE_ONE_TAP }?.oneTapNoiseTool ?: OneTapNoiseTool.NONE
        val lastAdd = ops.lastOrNull { it.type == EdlOpType.NOISE_ADD }?.noiseAdd ?: NoiseAddParams()
        val lastLvl = ops.lastOrNull { it.type == EdlOpType.NOISE_LEVEL_CONTROL }?.noiseLevel ?: NoiseLevelParams()
        _state.value = _state.value.copy(
            noiseRemoval = lastRem,
            oneTapNoiseTool = lastOneTap,
            noiseAdd = lastAdd,
            noiseLevel = lastLvl
        )
        syncLiveEngineNoise()
    }

    fun resetToOriginal() {
        val rec = _state.value.recording ?: return
        editorEngine.stopPreview()
        _state.value = _state.value.copy(
            operations = emptyList(),
            redoStack = emptyList(),
            eqBandsDb = EqPreset.FLAT.gainsDb,
            eqPreset = EqPreset.FLAT,
            hpfHz = 0f,
            lpfHz = (rec.sampleRate / 2f).coerceAtMost(20000f),
            noiseRemoval = NoiseRemovalParams(),
            oneTapNoiseTool = OneTapNoiseTool.NONE,
            noiseAdd = NoiseAddParams(),
            noiseLevel = NoiseLevelParams(),
            noiseBypass = false,
            noiseOnlyMode = false
        )
        syncLiveEngineAll()
        viewModelScope.launch {
            repository.resetEdl(rec.id)
            _events.tryEmit("Reset EDL to original untouched file.")
        }
    }

    fun setEqBand(bandIndex: Int, gainDb: Float) {
        val list = _state.value.eqBandsDb.toMutableList()
        if (bandIndex in list.indices) {
            list[bandIndex] = gainDb.coerceIn(-12f, 12f)
            _state.value = _state.value.copy(eqBandsDb = list)
            syncLiveEngineEq()
        }
    }

    fun applyEqPreset(preset: EqPreset) {
        _state.value = _state.value.copy(eqBandsDb = preset.gainsDb, eqPreset = preset)
        syncLiveEngineEq()
        addOperation(EdlOpType.EQ)
    }

    fun toggleEqBypass() {
        val next = !_state.value.eqBypass
        _state.value = _state.value.copy(eqBypass = next)
        editorEngine.liveEqBypass = next
    }

    fun setFilters(hpfHz: Float, hpfSlope: Int, lpfHz: Float, lpfSlope: Int) {
        _state.value = _state.value.copy(
            hpfHz = hpfHz.coerceIn(0f, 4000f),
            hpfSlopeDb = hpfSlope,
            lpfHz = lpfHz.coerceIn(500f, 22000f),
            lpfSlopeDb = lpfSlope
        )
        syncLiveEngineEq()
    }

    fun setTargetLufs(lufs: Float) {
        val clamped = lufs.coerceIn(-30f, -6f)
        val before = _state.value.loudnessBefore
        val after = before?.let { LufsMeter.predictAfterNormalization(it, clamped, -1.0f) }
        _state.value = _state.value.copy(targetLufs = clamped, loudnessAfter = after)
    }

    fun measureLoudness() {
        val rec = _state.value.recording ?: return
        val file = workingWavFile ?: File(rec.filePath)
        viewModelScope.launch {
            _state.value = _state.value.copy(isMeasuringLufs = true)
            val before = LufsMeter.measureFile(file)
            val after = before?.let { LufsMeter.predictAfterNormalization(it, _state.value.targetLufs, -1.0f) }
            _state.value = _state.value.copy(
                loudnessBefore = before,
                loudnessAfter = after,
                isMeasuringLufs = false
            )
        }
    }

    fun togglePlaySelection(playFull: Boolean = false) {
        if (editorEngine.isPreviewPlaying.value) {
            editorEngine.stopPreview()
            return
        }
        val st = _state.value
        val rec = st.recording ?: return
        val file = workingWavFile ?: File(rec.filePath)
        syncLiveEngineAll()
        editorEngine.startLivePreview(
            originalFile = file,
            operations = st.operations,
            startMs = if (playFull) 0L else st.selectionStartMs,
            endMs = if (playFull) Long.MAX_VALUE else st.selectionEndMs
        )
    }

    fun renderAndExport(onExported: (RecordingEntity) -> Unit = {}) {
        val st = _state.value
        val rec = st.recording ?: return
        val effectiveRec = workingWavFile?.let { rec.copy(filePath = it.absolutePath) } ?: rec
        editorEngine.stopPreview()
        viewModelScope.launch {
            _state.value = _state.value.copy(isRendering = true, renderProgress = 0f)
            val res = editorEngine.renderEdlToWav(effectiveRec, st.operations) { p ->
                _state.value = _state.value.copy(renderProgress = p)
            }
            _state.value = _state.value.copy(isRendering = false)
            res.onSuccess { rendered ->
                val newId = repository.insertRecording(rendered)
                val saved = rendered.copy(id = newId)
                appLogger.reportSuccess(AppFeature.EDITOR, "Rendered edited WAV (${rendered.durationMs} ms, ${st.operations.size} ops)")
                if (st.operations.any {
                        it.type in setOf(
                            EdlOpType.NOISE_REMOVAL,
                            EdlOpType.NOISE_ONE_TAP,
                            EdlOpType.NOISE_ADD,
                            EdlOpType.NOISE_LEVEL_CONTROL
                        )
                    }
                ) {
                    appLogger.reportSuccess(AppFeature.NOISE_TOOLS, "Rendered Noise Tools ops into exported WAV")
                }
                _events.tryEmit("Rendered new WAV: ${rendered.title}")
                onExported(saved)
            }.onFailure { err ->
                appLogger.reportFailure(AppFeature.EDITOR, "Render failed: ${err.message}", err)
                _events.tryEmit("Render failed: ${err.message}")
            }
        }
    }

    private fun syncLiveEngineAll() {
        syncLiveEngineEq()
        syncLiveEngineNoise()
    }

    private fun syncLiveEngineEq() {
        val st = _state.value
        editorEngine.liveEqBypass = st.eqBypass
        editorEngine.liveEqBandsDb = st.eqBandsDb
        editorEngine.liveHpfHz = st.hpfHz
        editorEngine.liveHpfSlope = st.hpfSlopeDb
        editorEngine.liveLpfHz = st.lpfHz
        editorEngine.liveLpfSlope = st.lpfSlopeDb
    }

    private fun syncLiveEngineNoise() {
        val st = _state.value
        editorEngine.liveNoiseBypass = st.noiseBypass
        editorEngine.liveNoiseOnlyMode = st.noiseOnlyMode
        editorEngine.liveNoiseProfile = st.noiseProfile
        editorEngine.liveNoiseRemoval = st.noiseRemoval
        editorEngine.liveOneTapTool = st.oneTapNoiseTool
        editorEngine.liveNoiseAdd = st.noiseAdd
        editorEngine.liveNoiseLevel = st.noiseLevel
        editorEngine.liveSelectionStartMs = st.selectionStartMs
        editorEngine.liveSelectionEndMs = st.selectionEndMs
    }

    private fun persistEdl(recordingId: Long, ops: List<EdlOperation>, redo: List<EdlOperation>) {
        viewModelScope.launch {
            repository.saveEdl(recordingId, ops, redo)
        }
    }

    override fun onCleared() {
        editorEngine.stopPreview()
        tempDecodedCacheFile?.delete()
        tempDecodedCacheFile = null
        super.onCleared()
    }
}
