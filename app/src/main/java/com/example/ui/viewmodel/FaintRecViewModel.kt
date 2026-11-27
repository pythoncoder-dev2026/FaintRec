package com.example.ui.viewmodel

import android.content.Context
import android.net.Uri
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.analysis.FftResult
import com.example.analysis.FftWindowType
import com.example.analysis.PostProcessOptions
import com.example.analysis.PostProcessor
import com.example.analysis.SpectrogramColorMap
import com.example.analysis.SpectrogramData
import com.example.analysis.SpectrogramEngine
import com.example.audio.AudioDeviceManager
import com.example.audio.AudioImporter
import com.example.audio.AudioInputDeviceUi
import com.example.audio.ImportProgress
import com.example.audio.LiveRecordingTelemetry
import com.example.audio.PitchMode
import com.example.audio.PlaybackController
import com.example.audio.PlaybackDirection
import com.example.audio.PlaybackUiState
import com.example.audio.RecordingEngine
import com.example.audio.WavFileHelper
import com.example.data.AppFeature
import com.example.data.FaintRecRepository
import com.example.data.FaintRecSettings
import com.example.data.MarkerEntity
import com.example.data.RecordingEntity
import com.example.data.RecordingStorageType
import com.example.data.SettingsDataStore
import com.example.logging.AppLogger
import com.example.service.RecordingForegroundService
import com.example.spiritbox.SpiritBoxEngine
import com.example.storage.StorageLocationInfo
import com.example.storage.StorageLocationManager
import com.example.ui.theme.ThemePreference
import com.example.util.StorageHelper
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
import javax.inject.Inject

data class AnalyzeUiState(
    val selectedRecording: RecordingEntity? = null,
    val compareRecordingB: RecordingEntity? = null,
    val activeAbChannel: Char = 'A', // 'A' or 'B'
    val spectrogramA: SpectrogramData? = null,
    val spectrogramB: SpectrogramData? = null,
    val waveformA: FloatArray = FloatArray(0),
    val waveformB: FloatArray = FloatArray(0),
    val isComputingSpectrogram: Boolean = false,
    val colorMap: SpectrogramColorMap = SpectrogramColorMap.STUDIO_CYAN_AMBER,
    val fftSize: Int = 2048,
    val fftWindow: FftWindowType = FftWindowType.HANN,
    val fftLogScale: Boolean = true,
    val maxFreqFraction: Float = 1.0f // 0.1..1.0 of Nyquist
)

@HiltViewModel
class FaintRecViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: FaintRecRepository,
    private val settingsDataStore: SettingsDataStore,
    private val deviceManager: AudioDeviceManager,
    private val recordingEngine: RecordingEngine,
    private val playbackController: PlaybackController,
    private val audioImporter: AudioImporter,
    private val postProcessor: PostProcessor,
    private val appLogger: AppLogger,
    private val storageLocationManager: StorageLocationManager,
    private val spiritBoxEngine: SpiritBoxEngine
) : ViewModel() {

    val settings: StateFlow<FaintRecSettings> = settingsDataStore.settingsFlow.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), FaintRecSettings()
    )

    val recordings: StateFlow<List<RecordingEntity>> = repository.recordings.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList()
    )

    val telemetry: StateFlow<LiveRecordingTelemetry> = recordingEngine.telemetry
    val liveRecordFft: StateFlow<FftResult?> = recordingEngine.liveFft
    val playbackState: StateFlow<PlaybackUiState> = playbackController.state
    val playbackFft: StateFlow<FftResult?> = playbackController.playbackFft

    val inputDevices: StateFlow<List<AudioInputDeviceUi>> = deviceManager.inputDevices
    val externalInputDevices: StateFlow<List<AudioInputDeviceUi>> = deviceManager.externalInputDevices
    val selectedDevice: StateFlow<AudioInputDeviceUi?> = deviceManager.selectedDevice
    val usbPermissionStatus: StateFlow<String?> = deviceManager.usbPermissionStatus
    val supportsUnprocessed: Boolean get() = deviceManager.supportsUnprocessedSource()
    val recommendedAudioSourceLabel: String get() = deviceManager.getRecommendedAudioSourceLabel()

    private val _importProgress = MutableStateFlow<ImportProgress?>(null)
    val importProgress: StateFlow<ImportProgress?> = _importProgress.asStateFlow()

    private val _processingMessage = MutableStateFlow<String?>(null)
    val processingMessage: StateFlow<String?> = _processingMessage.asStateFlow()

    private val _snackbarEvents = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val snackbarEvents: SharedFlow<String> = _snackbarEvents.asSharedFlow()

    private val _analyzeState = MutableStateFlow(AnalyzeUiState())
    val analyzeState: StateFlow<AnalyzeUiState> = _analyzeState.asStateFlow()

    private val _activeMarkers = MutableStateFlow<List<MarkerEntity>>(emptyList())
    val activeMarkers: StateFlow<List<MarkerEntity>> = _activeMarkers.asStateFlow()

    private val _selectedBatchIds = MutableStateFlow<Set<Long>>(emptySet())
    val selectedBatchIds: StateFlow<Set<Long>> = _selectedBatchIds.asStateFlow()

    private val _storageInfo = MutableStateFlow(
        storageLocationManager.getStorageInfo(
            storageType = RecordingStorageType.APP_PRIVATE,
            safTreeUri = null,
            safDisplayPath = null
        )
    )
    val storageInfo: StateFlow<StorageLocationInfo> = _storageInfo.asStateFlow()

    private val _isMovingRecordings = MutableStateFlow(false)
    val isMovingRecordings: StateFlow<Boolean> = _isMovingRecordings.asStateFlow()

    private val _moveProgressText = MutableStateFlow<String?>(null)
    val moveProgressText: StateFlow<String?> = _moveProgressText.asStateFlow()

    private var markerObserverJob: kotlinx.coroutines.Job? = null
    private var liveMarkerCounter = 0

    init {
        viewModelScope.launch {
            settingsDataStore.settingsFlow.collect { s ->
                recordingEngine.liveGainDb = s.inputGainDb
                recordingEngine.saveRawNoGain = s.saveRawNoGain
                recordingEngine.varEnabled = s.varEnabled
                recordingEngine.varThresholdDb = s.varThresholdDb
                recordingEngine.varHangTimeSec = s.varHangTimeSec
                val win = if (s.fftWindow == "BLACKMAN") FftWindowType.BLACKMAN else FftWindowType.HANN
                recordingEngine.configureFft(s.fftSize, win)
                playbackController.configureFft(s.fftSize, win)
                _analyzeState.value = _analyzeState.value.copy(
                    fftSize = s.fftSize,
                    fftWindow = win,
                    fftLogScale = s.fftLogScale
                )
                if (s.preferredDeviceId > 0) {
                    deviceManager.refreshDevices(s.preferredDeviceId)
                }
                refreshStorageInfoFor(s)
            }
        }
        viewModelScope.launch {
            recordingEngine.completedRecordings.collect {
                refreshStorageInfo()
            }
        }
        viewModelScope.launch {
            recordingEngine.errors.collect { err -> _snackbarEvents.tryEmit(err) }
        }
        viewModelScope.launch {
            deviceManager.deviceDisconnectedEvents.collect { removed ->
                _snackbarEvents.tryEmit("$removed disconnected — switched to built-in mic.")
            }
        }
    }

    private fun refreshStorageInfoFor(s: FaintRecSettings) {
        viewModelScope.launch(Dispatchers.IO) {
            _storageInfo.value = storageLocationManager.getStorageInfo(
                storageType = s.storageType,
                safTreeUri = s.safTreeUri,
                safDisplayPath = s.safDisplayPath,
                sampleRate = 48000,
                preferFloatPcm = s.preferFloatPcm,
                channels = s.channels
            )
        }
    }

    fun refreshStorageInfo() {
        refreshStorageInfoFor(settings.value)
    }

    fun selectStorageType(type: RecordingStorageType) = viewModelScope.launch {
        if (type == RecordingStorageType.EXTERNAL_SD_SAF) {
            val uri = settings.value.safTreeUri
            if (uri.isNullOrBlank()) {
                _snackbarEvents.tryEmit("Choose an SD card / external folder first.")
                return@launch
            }
            if (!storageLocationManager.hasValidSafPermission(uri)) {
                appLogger.warn("storage", "SD card folder permission missing or SD card removed when selecting EXTERNAL_SD_SAF")
                _snackbarEvents.tryEmit("SD card folder unavailable or permission lost. Please pick the folder again.")
                refreshStorageInfo()
                return@launch
            }
        }
        settingsDataStore.setStorageType(type)
        appLogger.info("storage", "Changed save location to ${type.name}")
        refreshStorageInfo()
    }

    fun onSafFolderPicked(treeUri: Uri?) = viewModelScope.launch {
        if (treeUri == null) return@launch
        val result = withContext(Dispatchers.IO) {
            storageLocationManager.takePersistableSafPermission(treeUri)
        }
        result.onSuccess { displayPath ->
            settingsDataStore.setSafTreeLocation(treeUri.toString(), displayPath)
            _snackbarEvents.tryEmit("Save location set to $displayPath")
            refreshStorageInfo()
        }.onFailure { err ->
            _snackbarEvents.tryEmit("Could not keep folder access: ${err.message ?: "Permission denied"}")
        }
    }

    fun moveOrCopyRecording(
        recording: RecordingEntity,
        targetType: RecordingStorageType,
        isMove: Boolean
    ) = viewModelScope.launch {
        if (_isMovingRecordings.value) return@launch
        if (targetType == RecordingStorageType.EXTERNAL_SD_SAF && settings.value.safTreeUri.isNullOrBlank()) {
            _snackbarEvents.tryEmit("Pick an SD card folder in Settings first.")
            return@launch
        }
        if (isMove && playbackState.value.activeRecording?.id == recording.id) {
            playbackController.stopAll()
        }
        _isMovingRecordings.value = true
        _moveProgressText.value = "${if (isMove) "Moving" else "Copying"} ${recording.title}…"
        val res = storageLocationManager.moveOrCopyRecording(
            recording = recording,
            targetType = targetType,
            safTreeUri = settings.value.safTreeUri,
            isMove = isMove
        )
        _isMovingRecordings.value = false
        _moveProgressText.value = null
        refreshStorageInfo()
        res.onSuccess {
            val verb = if (isMove) "Moved" else "Copied"
            val destLabel = when (targetType) {
                RecordingStorageType.APP_PRIVATE -> "App-Private Internal"
                RecordingStorageType.PUBLIC_DOCUMENTS -> "Documents/FaintRec"
                RecordingStorageType.EXTERNAL_SD_SAF -> settings.value.safDisplayPath ?: "SD Card"
            }
            _snackbarEvents.tryEmit("$verb \"${recording.title}\" to $destLabel")
        }.onFailure { err ->
            _snackbarEvents.tryEmit("${if (isMove) "Move" else "Copy"} failed: ${err.message ?: "Storage error"}")
        }
    }

    fun moveAllRecordingsToCurrentLocation() = viewModelScope.launch {
        if (_isMovingRecordings.value) return@launch
        val curSettings = settings.value
        val target = curSettings.storageType
        if (target == RecordingStorageType.EXTERNAL_SD_SAF && !storageLocationManager.hasValidSafPermission(curSettings.safTreeUri)) {
            _snackbarEvents.tryEmit("SD card folder is unavailable. Please select it again first.")
            return@launch
        }
        playbackController.stopAll()
        _isMovingRecordings.value = true
        _moveProgressText.value = "Preparing to move recordings…"
        val res = storageLocationManager.moveAllRecordingsTo(
            targetType = target,
            safTreeUri = curSettings.safTreeUri
        ) { done, total, title ->
            _moveProgressText.value = if (total > 0) "Moving $done / $total: $title" else "Checking recordings…"
        }
        _isMovingRecordings.value = false
        _moveProgressText.value = null
        refreshStorageInfo()
        val msg = when {
            res.movedCount == 0 && res.failedCount == 0 -> "All recordings are already in this save location."
            res.failedCount == 0 -> "Moved ${res.movedCount} recording(s) safely."
            else -> "Moved ${res.movedCount}, failed ${res.failedCount}: ${res.lastError ?: "Storage error"}"
        }
        _snackbarEvents.tryEmit(msg)
    }

    fun emitSnackbar(message: String) {
        _snackbarEvents.tryEmit(message)
    }

    fun reportMicPermissionStatus(granted: Boolean) {
        if (granted) {
            appLogger.reportSuccess(AppFeature.MIC_PERMISSION, "RECORD_AUDIO permission active")
        } else {
            appLogger.reportFailure(AppFeature.MIC_PERMISSION, "RECORD_AUDIO permission not granted")
        }
    }

    // --- Recording Controls ---
    fun startRecording() {
        liveMarkerCounter = 0
        playbackController.stopAll()
        val intent = RecordingForegroundService.startIntent(context)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        } catch (e: Exception) {
            appLogger.reportFailure(AppFeature.FOREGROUND_SERVICE, "Failed to start recording service: ${e.message}", e)
            _snackbarEvents.tryEmit("Cannot start recording service: ${e.message}")
        }
    }

    fun stopRecording() {
        try {
            context.startService(RecordingForegroundService.stopIntent(context))
        } catch (_: Exception) {
            recordingEngine.stopRecording()
        }
    }

    fun togglePauseRecording() {
        try {
            context.startService(RecordingForegroundService.togglePauseIntent(context))
        } catch (_: Exception) {
            recordingEngine.togglePause()
        }
    }

    fun addLiveRecordingMarker(note: String = "") {
        if (!telemetry.value.isRecording) return
        val nextIdx = liveMarkerCounter + 1
        val m = recordingEngine.addLiveMarker("Marker #$nextIdx", note)
        if (m != null) {
            liveMarkerCounter = nextIdx
            _snackbarEvents.tryEmit("Marker #$nextIdx added at ${com.example.util.AudioMath.formatDuration(m.timestampMs)}")
        }
    }

    fun resetPeakHold() = recordingEngine.resetPeakHold()

    // --- Sensitivity, VAR & Device Controls ---
    fun setInputGain(db: Float) = viewModelScope.launch {
        recordingEngine.liveGainDb = db
        settingsDataStore.setInputGainDb(db)
    }

    fun setSaveRaw(saveRaw: Boolean) = viewModelScope.launch {
        recordingEngine.saveRawNoGain = saveRaw
        settingsDataStore.setSaveRawNoGain(saveRaw)
    }

    fun setVarConfig(enabled: Boolean, thresholdDb: Float, hangSec: Float) = viewModelScope.launch {
        recordingEngine.varEnabled = enabled
        recordingEngine.varThresholdDb = thresholdDb
        recordingEngine.varHangTimeSec = hangSec
        settingsDataStore.setVarConfig(enabled, thresholdDb, hangSec)
    }

    fun selectInputDevice(device: AudioInputDeviceUi) = viewModelScope.launch {
        deviceManager.selectDevice(device.id)
        settingsDataStore.setPreferredDevice(device.id, device.name)
        if (device.category != com.example.audio.InputDeviceCategory.BUILT_IN) {
            appLogger.reportSuccess(AppFeature.EXTERNAL_MIC, "Selected external mic: ${device.name}")
        }
        if (telemetry.value.isRecording) {
            recordingEngine.switchInputDeviceLive(device.deviceInfo, device.name)
            recordingEngine.addLiveMarker("Mic changed", "Switched to ${device.name}", bypassDebounce = true)
            _snackbarEvents.tryEmit("Switched live input to ${device.name}")
        }
    }

    fun refreshInputDevices() {
        deviceManager.refreshDevices(settings.value.preferredDeviceId)
    }

    fun requestUsbPermission() {
        val already = deviceManager.requestUsbAudioPermissionIfNeeded()
        if (already) {
            _snackbarEvents.tryEmit("USB audio device permission is active.")
        } else if (deviceManager.findConnectedUsbAudioDevices().isEmpty()) {
            _snackbarEvents.tryEmit("No USB Audio Class device detected. Connect a USB-C mic.")
        }
    }

    // --- Library & Import ---
    fun importAudioUris(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            var successCount = 0
            uris.forEachIndexed { idx, uri ->
                val result = audioImporter.importUri(uri) { p ->
                    _importProgress.value = ImportProgress(
                        fileName = uri.lastPathSegment ?: "Audio ${idx + 1}",
                        progress = p,
                        currentIndex = idx + 1,
                        totalCount = uris.size
                    )
                }
                result.onSuccess { entity ->
                    repository.insertRecording(entity)
                    successCount++
                }.onFailure { err ->
                    _snackbarEvents.tryEmit("Import failed: ${err.message ?: "Unsupported or corrupt file"}")
                }
            }
            _importProgress.value = null
            if (successCount > 0) {
                _snackbarEvents.tryEmit("Imported $successCount audio file(s) to Documents/FaintRec/Imported")
            }
        }
    }

    fun renameRecording(recording: RecordingEntity, newTitle: String) = viewModelScope.launch {
        val clean = newTitle.trim()
        if (clean.isNotEmpty()) {
            repository.renameRecording(recording.id, clean)
            _snackbarEvents.tryEmit("Renamed to $clean")
        }
    }

    fun deleteRecording(recording: RecordingEntity) = viewModelScope.launch {
        if (playbackState.value.activeRecording?.id == recording.id) {
            playbackController.stopAll()
        }
        repository.deleteRecording(recording)
        _selectedBatchIds.value = _selectedBatchIds.value - recording.id
        refreshStorageInfo()
        _snackbarEvents.tryEmit("Deleted ${recording.title}")
    }

    fun shareRecording(recording: RecordingEntity) {
        val uri = storageLocationManager.resolveShareUri(recording)
        if (uri == null) {
            _snackbarEvents.tryEmit("Unable to share: recording file unavailable on storage.")
            return
        }
        val ext = recording.filePath.substringAfterLast('.', recording.format.lowercase())
        val mime = StorageHelper.mimeTypeForExtension(ext)
        StorageHelper.shareUri(context, uri, mime, "Share ${recording.title}")
    }

    fun toggleBatchSelection(recordingId: Long) {
        val cur = _selectedBatchIds.value
        _selectedBatchIds.value = if (recordingId in cur) cur - recordingId else cur + recordingId
    }

    fun clearBatchSelection() {
        _selectedBatchIds.value = emptySet()
    }

    // --- Playback Controls ---
    fun playRecording(recording: RecordingEntity, startMs: Long = 0L) {
        playbackController.playRecording(recording, startMs)
        observeMarkersFor(recording.id)
    }

    fun togglePlayPause() = playbackController.togglePlayPause()
    fun seekTo(ms: Long) = playbackController.seekTo(ms)
    fun setPlaybackDirection(dir: PlaybackDirection) = playbackController.setDirection(dir)
    fun setPlaybackSpeed(speed: Float) = playbackController.setSpeed(speed)
    fun setPitchMode(mode: PitchMode) = playbackController.setPitchMode(mode)
    fun setPlaybackGain(db: Float) = playbackController.setGainBoostDb(db)

    fun exportReversed(recording: RecordingEntity) = viewModelScope.launch {
        _processingMessage.value = "Exporting reversed WAV…"
        val res = playbackController.exportReversed(recording)
        _processingMessage.value = null
        res.onSuccess { entity ->
            repository.insertRecording(entity)
            _snackbarEvents.tryEmit("Exported reversed copy: ${entity.title}")
        }.onFailure { err ->
            _snackbarEvents.tryEmit("Reverse export failed: ${err.message}")
        }
    }

    fun exportAtCurrentSpeed(recording: RecordingEntity) = viewModelScope.launch {
        val st = playbackState.value
        val speed = st.activeSpeed
        val preservePitch = st.pitchMode == PitchMode.PRESERVE_PITCH
        _processingMessage.value = "Rendering ${String.format(java.util.Locale.US, "%.2fx", speed)} WAV…"
        val res = playbackController.exportAtSpeed(recording, speed, preservePitch)
        _processingMessage.value = null
        res.onSuccess { entity ->
            repository.insertRecording(entity)
            _snackbarEvents.tryEmit("Exported speed copy: ${entity.title}")
        }.onFailure { err ->
            _snackbarEvents.tryEmit("Speed export failed: ${err.message}")
        }
    }

    fun runPostProcessing(recording: RecordingEntity, options: PostProcessOptions) = viewModelScope.launch {
        _processingMessage.value = "Running post-processing pipeline…"
        val res = postProcessor.processAndExport(recording, options)
        _processingMessage.value = null
        res.onSuccess { entity ->
            repository.insertRecording(entity)
            _snackbarEvents.tryEmit("Saved processed copy: ${entity.title}")
        }.onFailure { err ->
            _snackbarEvents.tryEmit("Post-processing failed: ${err.message}")
        }
    }

    // --- Analysis: FFT, Spectrogram, A/B Compare, Markers ---
    fun selectRecordingForAnalysis(recording: RecordingEntity) {
        _analyzeState.value = _analyzeState.value.copy(
            selectedRecording = recording,
            isComputingSpectrogram = true
        )
        observeMarkersFor(recording.id)
        viewModelScope.launch {
            runCatching {
                val file = withContext(Dispatchers.IO) { storageLocationManager.resolveReadableFile(recording) }
                    ?: throw IllegalStateException("Recording file unavailable on storage.")
                val spec = SpectrogramEngine.computeSpectrogram(file, fftSize = 1024)
                val wave = withContext(Dispatchers.Default) { WavFileHelper.extractWaveformPeaks(file, 200) }
                _analyzeState.value = _analyzeState.value.copy(
                    spectrogramA = spec,
                    waveformA = wave,
                    isComputingSpectrogram = false
                )
                if (spec != null) {
                    appLogger.reportSuccess(AppFeature.SPECTROGRAM_FFT, "Computed spectrogram (${spec.frames.size} slices, ${spec.sampleRate} Hz)")
                } else {
                    appLogger.reportFailure(AppFeature.SPECTROGRAM_FFT, "Unable to compute spectrogram for ${recording.format}")
                }
            }.onFailure { err ->
                _analyzeState.value = _analyzeState.value.copy(isComputingSpectrogram = false)
                appLogger.reportFailure(AppFeature.SPECTROGRAM_FFT, "Spectrogram error: ${err.message}", err)
                _snackbarEvents.tryEmit("Analysis error: ${err.message ?: "Unable to read recording"}")
            }
        }
    }

    fun selectCompareRecordingB(recording: RecordingEntity) {
        _analyzeState.value = _analyzeState.value.copy(
            compareRecordingB = recording,
            isComputingSpectrogram = true
        )
        viewModelScope.launch {
            val file = withContext(Dispatchers.IO) { storageLocationManager.resolveReadableFile(recording) }
            if (file == null) {
                _analyzeState.value = _analyzeState.value.copy(isComputingSpectrogram = false)
                _snackbarEvents.tryEmit("Compare recording unavailable on storage.")
                return@launch
            }
            val spec = SpectrogramEngine.computeSpectrogram(file, fftSize = 1024)
            val wave = withContext(Dispatchers.Default) { WavFileHelper.extractWaveformPeaks(file, 200) }
            _analyzeState.value = _analyzeState.value.copy(
                spectrogramB = spec,
                waveformB = wave,
                isComputingSpectrogram = false
            )
        }
    }

    fun switchAbChannel(channel: Char) {
        val st = _analyzeState.value
        val target = if (channel == 'A') st.selectedRecording else st.compareRecordingB
        if (target == null) return
        val currentPos = playbackState.value.positionMs
        val wasPlaying = playbackState.value.isPlaying
        _analyzeState.value = st.copy(activeAbChannel = channel)
        if (wasPlaying) {
            playbackController.playRecording(target, currentPos.coerceAtMost(target.durationMs))
        }
    }

    fun setSpectrogramColorMap(map: SpectrogramColorMap) {
        _analyzeState.value = _analyzeState.value.copy(colorMap = map)
    }

    fun setAnalyzeFreqRangeFraction(frac: Float) {
        _analyzeState.value = _analyzeState.value.copy(maxFreqFraction = frac.coerceIn(0.1f, 1.0f))
    }

    fun setFftConfig(size: Int, window: FftWindowType, logScale: Boolean) = viewModelScope.launch {
        settingsDataStore.setFftConfig(size, window.name, logScale)
    }

    private fun observeMarkersFor(recordingId: Long) {
        markerObserverJob?.cancel()
        markerObserverJob = viewModelScope.launch {
            repository.observeMarkers(recordingId).collect { list ->
                _activeMarkers.value = list
            }
        }
    }

    fun addPlaybackMarker(recordingId: Long, timestampMs: Long, label: String, note: String) =
        viewModelScope.launch {
            runCatching {
                repository.addMarker(recordingId, timestampMs, label.ifBlank { "Marker" }, note)
                appLogger.reportSuccess(AppFeature.MARKERS, "Playback marker saved at $timestampMs ms")
                _snackbarEvents.tryEmit("Marker saved at ${com.example.util.AudioMath.formatDuration(timestampMs)}")
            }.onFailure { err ->
                appLogger.reportFailure(AppFeature.MARKERS, "Save marker failed: ${err.message}", err)
            }
        }

    fun deleteMarker(markerId: Long) = viewModelScope.launch {
        repository.deleteMarker(markerId)
    }

    fun exportMarkers(recording: RecordingEntity) = viewModelScope.launch {
        runCatching {
            val (jsonFile, cueOk) = repository.exportMarkersSidecarAndCue(recording)
            appLogger.reportSuccess(AppFeature.MARKERS, "Exported markers (cue=$cueOk)")
            val msg = buildString {
                append("Exported markers JSON")
                if (cueOk) append(" + embedded WAV cue chunks")
                if (jsonFile != null) append(" (${jsonFile.name})")
            }
            _snackbarEvents.tryEmit(msg)
        }.onFailure { err ->
            appLogger.reportFailure(AppFeature.MARKERS, "Export markers failed: ${err.message}", err)
        }
    }

    // --- Settings ---
    fun setTheme(theme: ThemePreference) = viewModelScope.launch { settingsDataStore.setTheme(theme) }
    fun setLanguage(lang: String) = viewModelScope.launch { settingsDataStore.setLanguage(lang) }
    fun setChannels(ch: Int) = viewModelScope.launch { settingsDataStore.setChannels(ch) }
    fun setPreferFloat(preferFloat: Boolean) = viewModelScope.launch { settingsDataStore.setPreferFloatPcm(preferFloat) }

    // --- Spirit Box Recorder Settings (synced live with SpiritBoxEngine & DataStore) ---
    fun setSpiritBoxRecorderEnabled(enabled: Boolean) = spiritBoxEngine.setRecorderEnabled(enabled)
    fun setSpiritBoxRecordSweepOutput(enabled: Boolean) = spiritBoxEngine.setRecordSweepOutput(enabled)
    fun setSpiritBoxRecordMicTrack(enabled: Boolean) = spiritBoxEngine.setRecordMicTrack(enabled)

    override fun onCleared() {
        playbackController.stopAll()
        super.onCleared()
    }
}
