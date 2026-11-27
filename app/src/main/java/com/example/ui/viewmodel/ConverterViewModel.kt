package com.example.ui.viewmodel

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.converter.AudioConverterEngine
import com.example.converter.ConversionConfig
import com.example.converter.ConversionResultSummary
import com.example.converter.FormatSupportStatus
import com.example.converter.LameNativeBridge
import com.example.converter.OutputAudioFormat
import com.example.data.FaintRecRepository
import com.example.data.RecordingEntity
import com.example.data.SettingsDataStore
import com.example.service.ConversionForegroundService
import com.example.util.StorageHelper
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

data class ConverterUiState(
    val sourceRecordings: List<RecordingEntity> = emptyList(),
    val codecSupport: List<FormatSupportStatus> = emptyList(),
    val showMp3UnavailableNote: Boolean = false,
    val config: ConversionConfig = ConversionConfig(),
    val isConverting: Boolean = false,
    val currentFileIndex: Int = 0,
    val progress: Float = 0f,
    val results: List<ConversionResultSummary> = emptyList(),
    val errorMessage: String? = null
) {
    val hasLossySource: Boolean get() = sourceRecordings.any { it.isLossySource }
    val showLossyToLosslessWarning: Boolean get() = hasLossySource && config.outputFormat.isLossless
    val showLossyToLossyWarning: Boolean get() = hasLossySource && !config.outputFormat.isLossless
}

@HiltViewModel
class ConverterViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: FaintRecRepository,
    private val converterEngine: AudioConverterEngine,
    private val settingsDataStore: SettingsDataStore
) : ViewModel() {

    private val _state = MutableStateFlow(ConverterUiState())
    val state: StateFlow<ConverterUiState> = _state.asStateFlow()

    private var conversionJob: Job? = null

    private val cancelReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            if (intent?.action == ConversionForegroundService.ACTION_CANCEL_BROADCAST) {
                cancelConversion()
            }
        }
    }

    init {
        val allSupport = converterEngine.checkCodecSupport()
        val mp3Available = LameNativeBridge.isAvailable
        // Hide MP3 in converter list when LAME sources are not compiled into this build
        val visibleSupport = if (mp3Available) {
            allSupport
        } else {
            allSupport.filterNot { it.format == OutputAudioFormat.MP3 }
        }
        val firstSupported = visibleSupport.firstOrNull { it.isSupported && it.format == OutputAudioFormat.AAC_M4A }?.format
            ?: visibleSupport.firstOrNull { it.isSupported }?.format
            ?: OutputAudioFormat.WAV
        _state.value = _state.value.copy(
            codecSupport = visibleSupport,
            config = ConversionConfig(outputFormat = firstSupported)
        )
        if (!mp3Available) {
            viewModelScope.launch {
                settingsDataStore.settingsFlow.collect { s ->
                    _state.value = _state.value.copy(
                        showMp3UnavailableNote = !s.mp3UnavailableNoticeDismissed
                    )
                }
            }
        }
        try {
            val filter = IntentFilter(ConversionForegroundService.ACTION_CANCEL_BROADCAST)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(cancelReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                context.registerReceiver(cancelReceiver, filter)
            }
        } catch (_: Exception) {
        }
    }

    fun dismissMp3UnavailableNote() {
        _state.value = _state.value.copy(showMp3UnavailableNote = false)
        viewModelScope.launch {
            settingsDataStore.setMp3UnavailableNoticeDismissed(true)
        }
    }

    fun loadRecordingIds(csv: String) {
        val ids = csv.split(',').mapNotNull { it.trim().toLongOrNull() }
        viewModelScope.launch {
            val loaded = ids.mapNotNull { repository.getRecording(it) }
            _state.value = _state.value.copy(sourceRecordings = loaded, results = emptyList(), errorMessage = null)
        }
    }

    fun updateConfig(transform: (ConversionConfig) -> ConversionConfig) {
        _state.value = _state.value.copy(config = transform(_state.value.config), errorMessage = null)
    }

    fun startConversion() {
        val st = _state.value
        if (st.isConverting || st.sourceRecordings.isEmpty()) return

        conversionJob = viewModelScope.launch {
            _state.value = st.copy(
                isConverting = true,
                currentFileIndex = 0,
                progress = 0f,
                results = emptyList(),
                errorMessage = null
            )
            val completed = mutableListOf<ConversionResultSummary>()
            val total = st.sourceRecordings.size

            try {
                st.sourceRecordings.forEachIndexed { idx, rec ->
                    val (edlOps, _) = if (st.config.bakeEffects) {
                        repository.loadEdl(rec.id)
                    } else emptyList<com.example.data.EdlOperation>() to emptyList()

                    val res = converterEngine.convertRecording(rec, st.config, edlOps) { fileProg ->
                        val overall = (idx + fileProg) / total.toFloat()
                        _state.value = _state.value.copy(currentFileIndex = idx + 1, progress = overall)
                        ConversionForegroundService.updateProgress(
                            context,
                            "Converting ${idx + 1}/$total: ${rec.title}",
                            (overall * 100).toInt()
                        )
                    }
                    res.onSuccess { summary ->
                        val insertedId = repository.insertRecording(summary.outputRecording)
                        completed.add(summary.copy(outputRecording = summary.outputRecording.copy(id = insertedId)))
                    }.onFailure { err ->
                        _state.value = _state.value.copy(errorMessage = err.message ?: "Conversion error")
                    }
                }
            } finally {
                ConversionForegroundService.stop(context)
                _state.value = _state.value.copy(
                    isConverting = false,
                    progress = 1f,
                    results = completed
                )
            }
        }
    }

    fun cancelConversion() {
        conversionJob?.cancel()
        conversionJob = null
        ConversionForegroundService.stop(context)
        _state.value = _state.value.copy(isConverting = false, errorMessage = "Conversion cancelled.")
    }

    fun shareResult(summary: ConversionResultSummary) {
        val f = File(summary.outputRecording.filePath)
        StorageHelper.shareFile(context, f, StorageHelper.mimeTypeForExtension(f.extension), "Share converted audio")
    }

    fun openOutputFolder() {
        StorageHelper.openDocumentsFolder(context)
    }

    override fun onCleared() {
        try {
            context.unregisterReceiver(cancelReceiver)
        } catch (_: Exception) {
        }
        super.onCleared()
    }
}
