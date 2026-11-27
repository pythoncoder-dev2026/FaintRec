package com.example.ui.viewmodel

import android.content.Context
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.analysis.FftResult
import com.example.data.FaintRecRepository
import com.example.data.SettingsDataStore
import com.example.data.SpiritBoxSessionEntity
import com.example.service.SpiritBoxForegroundService
import com.example.spiritbox.SpiritBoxConfig
import com.example.spiritbox.SpiritBoxEngine
import com.example.spiritbox.SpiritBoxLiveState
import com.example.util.StorageHelper
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SpiritBoxViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val engine: SpiritBoxEngine,
    private val repository: FaintRecRepository,
    private val settingsDataStore: SettingsDataStore
) : ViewModel() {

    val liveState: StateFlow<SpiritBoxLiveState> = engine.liveState
    val liveFft: StateFlow<FftResult?> = engine.liveFft

    val sessions: StateFlow<List<SpiritBoxSessionEntity>> = repository.spiritBoxSessions.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList()
    )

    private val _config = MutableStateFlow(engine.config)
    val config: StateFlow<SpiritBoxConfig> = _config.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 12)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    init {
        viewModelScope.launch {
            settingsDataStore.settingsFlow.collect { s ->
                val updated = _config.value.copy(
                    streamUrls = s.spiritBoxStreamUrls,
                    recorderEnabled = s.spiritBoxRecorderEnabled,
                    recordSessionMix = s.spiritBoxRecordSweepOutput,
                    recordMicTrack = s.spiritBoxRecordMicTrack
                )
                _config.value = updated
                engine.updateConfig(updated)
            }
        }
        viewModelScope.launch {
            engine.events.collect { msg -> _messages.tryEmit(msg) }
        }
    }

    fun updateConfig(transform: (SpiritBoxConfig) -> SpiritBoxConfig) {
        val next = transform(_config.value)
        _config.value = next
        engine.updateConfig(next)
    }

    fun setRecorderEnabled(enabled: Boolean) {
        _config.value = _config.value.copy(recorderEnabled = enabled)
        engine.setRecorderEnabled(enabled)
    }

    fun setRecordSweepOutput(enabled: Boolean) {
        _config.value = _config.value.copy(recordSessionMix = enabled)
        engine.setRecordSweepOutput(enabled)
    }

    fun setRecordMicTrack(enabled: Boolean) {
        _config.value = _config.value.copy(recordMicTrack = enabled)
        engine.setRecordMicTrack(enabled)
    }

    fun addStreamUrl(rawUrl: String) {
        val url = rawUrl.trim()
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            _messages.tryEmit("Stream URL must start with http:// or https://")
            return
        }
        val nextList = (_config.value.streamUrls + url).distinct()
        saveStreamUrls(nextList)
    }

    fun removeStreamUrl(url: String) {
        val nextList = _config.value.streamUrls.filterNot { it == url }
        saveStreamUrls(nextList)
    }

    private fun saveStreamUrls(urls: List<String>) {
        val next = _config.value.copy(streamUrls = urls)
        _config.value = next
        engine.updateConfig(next)
        viewModelScope.launch {
            settingsDataStore.setSpiritBoxStreamUrls(urls)
        }
    }

    fun startSpiritBox() {
        val cfg = _config.value
        engine.updateConfig(cfg)
        val withMic = cfg.recorderEnabled && cfg.recordMicTrack
        val intent = SpiritBoxForegroundService.startIntent(context, withMic = withMic)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        } catch (e: Exception) {
            _messages.tryEmit("Failed starting Spirit Box service: ${e.message}")
            engine.startSession(cfg)
        }
    }

    fun stopSpiritBox() {
        try {
            context.startService(SpiritBoxForegroundService.stopIntent(context))
        } catch (_: Exception) {
            engine.stopSession()
        }
    }

    fun toggleHold() = engine.toggleHold()

    fun addMarker(note: String = "") = engine.addMarker(note = note)

    fun addQuestion(question: String, inputMode: String = "TEXT") = engine.addQuestion(question, inputMode)

    fun dismissBlindReveal() = engine.dismissBlindReveal()

    fun exportSession(session: SpiritBoxSessionEntity, asJson: Boolean, shareAfter: Boolean = false) {
        viewModelScope.launch {
            runCatching {
                val file = repository.exportSpiritBoxSession(session, asJson)
                _messages.tryEmit("Exported ${file.name} to Documents/FaintRec/SpiritBox")
                if (shareAfter) {
                    StorageHelper.shareFile(
                        context,
                        file,
                        if (asJson) "application/json" else "text/plain",
                        "Share Spirit Box Session"
                    )
                }
            }.onFailure { err ->
                _messages.tryEmit("Export failed: ${err.message}")
            }
        }
    }

    fun deleteSession(id: Long) = viewModelScope.launch {
        repository.deleteSpiritBoxSession(id)
    }
}
