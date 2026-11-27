package com.example.service

import android.content.ComponentName
import android.content.Context
import android.service.quicksettings.TileService
import androidx.glance.appwidget.updateAll
import com.example.util.AudioMath
import com.example.widget.FaintRecWidget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ServiceRecordingSnapshot(
    val isRecording: Boolean = false,
    val isPaused: Boolean = false,
    val elapsedMs: Long = 0L,
    val currentDb: Float = AudioMath.MIN_DB,
    val markerCount: Int = 0
)

object RecordingStateBus {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _snapshot = MutableStateFlow(ServiceRecordingSnapshot())
    val snapshot: StateFlow<ServiceRecordingSnapshot> = _snapshot.asStateFlow()

    private var lastWidgetUpdateMs = 0L

    fun update(context: Context, snapshot: ServiceRecordingSnapshot, forceUiSync: Boolean = false) {
        _snapshot.value = snapshot
        val now = System.currentTimeMillis()
        if (forceUiSync || now - lastWidgetUpdateMs >= 1000L) {
            lastWidgetUpdateMs = now
            scope.launch {
                try {
                    FaintRecWidget().updateAll(context)
                } catch (_: Exception) {
                }
                try {
                    TileService.requestListeningState(
                        context,
                        ComponentName(context, FaintRecTileService::class.java)
                    )
                } catch (_: Exception) {
                }
            }
        }
    }
}
