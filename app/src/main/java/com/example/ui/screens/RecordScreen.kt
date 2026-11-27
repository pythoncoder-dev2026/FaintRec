package com.example.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SdStorage
import androidx.compose.material.icons.filled.SettingsInputComponent
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.data.RecordingStorageType
import com.example.storage.StorageLocationManager
import com.example.ui.components.LiveOscilloscopeWaveform
import com.example.ui.components.LiveSpectrumCanvas
import com.example.ui.components.PrecisionDbMeter
import com.example.ui.viewmodel.FaintRecViewModel
import com.example.util.AudioMath
import com.example.util.StorageHelper
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordScreen(
    viewModel: FaintRecViewModel,
    hasMicPermission: Boolean,
    onRequestPermissions: () -> Unit,
    modifier: Modifier = Modifier
) {
    val telemetry by viewModel.telemetry.collectAsStateWithLifecycle()
    val liveFft by viewModel.liveRecordFft.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val inputDevices by viewModel.inputDevices.collectAsStateWithLifecycle()
    val selectedDevice by viewModel.selectedDevice.collectAsStateWithLifecycle()
    val storageInfo by viewModel.storageInfo.collectAsStateWithLifecycle()

    var showLiveFft by remember { mutableStateOf(false) }
    var showDeviceSheet by remember { mutableStateOf(false) }
    var showStorageSheet by remember { mutableStateOf(false) }
    var showMarkerDialog by remember { mutableStateOf(false) }
    var markerNote by remember { mutableStateOf("") }

    val safFolderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { treeUri ->
        viewModel.onSafFolderPicked(treeUri)
    }

    LaunchedEffect(Unit) {
        viewModel.refreshStorageInfo()
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Permission banner if missing
        if (!hasMicPermission) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    shape = MaterialTheme.shapes.medium
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            modifier = Modifier.weight(1f),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                            Spacer(Modifier.width(10.dp))
                            Text(
                                text = stringResource(R.string.perm_rationale_mic),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Button(
                            onClick = onRequestPermissions,
                            modifier = Modifier.testTag("grant_mic_permission_button")
                        ) {
                            Text(stringResource(R.string.perm_grant))
                        }
                    }
                }
            }
        }

        // UNPROCESSED unsupported warning or Bluetooth quality warning
        if (!viewModel.supportsUnprocessed || telemetry.unprocessedFallbackWarning) {
            item {
                WarningBannerCard(
                    text = stringResource(
                        R.string.unprocessed_unsupported_warn,
                        telemetry.audioSourceLabel
                    )
                )
            }
        }

        if (selectedDevice?.isBluetooth == true) {
            item {
                WarningBannerCard(text = stringResource(R.string.bluetooth_mic_warn))
            }
        }

        // Storage Unavailable or Low Space (< 200 MB) Banner
        if (!storageInfo.isAvailable) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(Icons.Default.Warning, contentDescription = "Storage Error", tint = MaterialTheme.colorScheme.error)
                        Text(
                            text = storageInfo.unavailableReason ?: "Selected save location is unavailable.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = { showStorageSheet = true }) {
                            Text(stringResource(R.string.storage_quick_change))
                        }
                    }
                }
            }
        } else if (storageInfo.isLowSpaceWarning || telemetry.lowSpaceWarning) {
            item {
                WarningBannerCard(
                    text = stringResource(
                        R.string.storage_low_banner,
                        StorageHelper.formatSizeBytes(storageInfo.freeBytes)
                    )
                )
            }
        }

        // Active Input Device Strip + Format Telemetry
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        viewModel.refreshInputDevices()
                        showDeviceSheet = true
                    }
                    .testTag("input_device_selector_card")
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.SettingsInputComponent,
                            contentDescription = "Input Device",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = selectedDevice?.name ?: "Built-in Mic",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        val srLabel = "${telemetry.actualSampleRate / 1000} kHz"
                        val bitLabel = if (telemetry.isFloatPcm) "32-bit Float" else "16-bit PCM"
                        val chLabel = if (settings.channels == 2) "Stereo" else "Mono"
                        Text(
                            text = "$srLabel • $bitLabel • $chLabel • ${telemetry.audioSourceLabel}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    AssistChip(
                        onClick = {
                            viewModel.refreshInputDevices()
                            showDeviceSheet = true
                        },
                        label = { Text("Switch") }
                    )
                }
            }
        }

        // Quick Save Location Selector Strip (path, free space, estimated recording time left)
        item {
            val storageIcon = when (storageInfo.storageType) {
                RecordingStorageType.APP_PRIVATE -> Icons.Default.Lock
                RecordingStorageType.PUBLIC_DOCUMENTS -> Icons.Default.Folder
                RecordingStorageType.EXTERNAL_SD_SAF -> Icons.Default.SdStorage
            }
            val storageTitle = when (storageInfo.storageType) {
                RecordingStorageType.APP_PRIVATE -> stringResource(R.string.storage_private_title)
                RecordingStorageType.PUBLIC_DOCUMENTS -> stringResource(R.string.storage_public_title)
                RecordingStorageType.EXTERNAL_SD_SAF -> stringResource(R.string.storage_sd_title)
            }
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                border = BorderStroke(
                    1.dp,
                    if (!storageInfo.isAvailable) MaterialTheme.colorScheme.error.copy(alpha = 0.6f)
                    else MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = !telemetry.isRecording) {
                        viewModel.refreshStorageInfo()
                        showStorageSheet = true
                    }
                    .testTag("record_save_location_card")
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.secondaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = storageIcon,
                            contentDescription = storageTitle,
                            tint = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = storageTitle,
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = storageInfo.displayPath,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (storageInfo.isAvailable) {
                            val freeStr = StorageHelper.formatSizeBytes(storageInfo.freeBytes)
                            val estStr = StorageLocationManager.formatEstimatedDuration(storageInfo.estimatedSecondsLeft)
                            Text(
                                text = stringResource(R.string.storage_free_and_est, freeStr, estStr),
                                style = MaterialTheme.typography.labelSmall,
                                color = if (storageInfo.isLowSpaceWarning) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.primary
                            )
                        } else {
                            Text(
                                text = storageInfo.unavailableReason ?: "Unavailable",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    AssistChip(
                        onClick = {
                            viewModel.refreshStorageInfo()
                            showStorageSheet = true
                        },
                        enabled = !telemetry.isRecording,
                        label = { Text(stringResource(R.string.storage_quick_change)) },
                        modifier = Modifier.testTag("record_change_storage_chip")
                    )
                }
            }
        }

        // Primary Studio Readout & Visualizer Console
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Status Row + Timer
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            val statusText = when {
                                telemetry.isRecording && telemetry.isPaused -> "PAUSED"
                                telemetry.isRecording && telemetry.isVarWaiting -> stringResource(R.string.var_waiting)
                                telemetry.isRecording -> "LIVE CAPTURE"
                                else -> "STANDBY • UNPROCESSED"
                            }
                            Text(
                                text = statusText,
                                style = MaterialTheme.typography.labelMedium,
                                color = when {
                                    telemetry.isRecording && !telemetry.isPaused -> MaterialTheme.colorScheme.error
                                    telemetry.isVarWaiting -> MaterialTheme.colorScheme.secondary
                                    else -> MaterialTheme.colorScheme.primary
                                }
                            )
                            Text(
                                text = AudioMath.formatDuration(telemetry.elapsedMs),
                                style = MaterialTheme.typography.displayLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.testTag("recording_timer_text")
                            )
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            FilterChip(
                                selected = showLiveFft,
                                onClick = { showLiveFft = !showLiveFft },
                                label = { Text(if (showLiveFft) "Live FFT" else "Waveform") },
                                leadingIcon = { Icon(Icons.Default.GraphicEq, contentDescription = null, modifier = Modifier.size(16.dp)) },
                                modifier = Modifier.testTag("toggle_live_fft_chip")
                            )
                            if (telemetry.droppedFrames > 0L) {
                                Text(
                                    text = "Dropped: ${telemetry.droppedFrames} fr",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }

                    // Waveform OR Live FFT Spectrum
                    if (showLiveFft) {
                        LiveSpectrumCanvas(
                            fft = liveFft,
                            logScale = settings.fftLogScale,
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        LiveOscilloscopeWaveform(
                            samples = telemetry.waveform,
                            isRecording = telemetry.isRecording && !telemetry.isPaused,
                            varThresholdDb = if (settings.varEnabled) settings.varThresholdDb else null,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(160.dp)
                        )
                    }

                    // Precision dBFS Meter + Peak Hold Reset
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        PrecisionDbMeter(
                            currentDb = telemetry.currentDb,
                            peakHoldDb = telemetry.peakHoldDb,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(
                            onClick = { viewModel.resetPeakHold() },
                            modifier = Modifier.testTag("reset_peak_button")
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = "Reset peak hold")
                        }
                    }

                    // Transport Controls: Pause/Resume, Record/Stop, Add Marker
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        FilledTonalIconButton(
                            onClick = { viewModel.togglePauseRecording() },
                            enabled = telemetry.isRecording,
                            modifier = Modifier
                                .size(56.dp)
                                .testTag("pause_resume_record_button")
                        ) {
                            Icon(
                                imageVector = if (telemetry.isPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                                contentDescription = if (telemetry.isPaused) stringResource(R.string.record_resume) else stringResource(R.string.record_pause)
                            )
                        }

                        Button(
                            onClick = {
                                if (!hasMicPermission) {
                                    onRequestPermissions()
                                } else if (telemetry.isRecording) {
                                    viewModel.stopRecording()
                                } else {
                                    viewModel.startRecording()
                                }
                            },
                            shape = CircleShape,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (telemetry.isRecording) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                                contentColor = if (telemetry.isRecording) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.onPrimary
                            ),
                            modifier = Modifier
                                .size(78.dp)
                                .testTag("primary_record_button"),
                            contentPadding = PaddingValues(0.dp)
                        ) {
                            Icon(
                                imageVector = if (telemetry.isRecording) Icons.Default.Stop else Icons.Default.Mic,
                                contentDescription = if (telemetry.isRecording) stringResource(R.string.record_stop) else stringResource(R.string.record_start),
                                modifier = Modifier.size(36.dp)
                            )
                        }

                        FilledTonalIconButton(
                            onClick = { showMarkerDialog = true },
                            enabled = telemetry.isRecording,
                            modifier = Modifier
                                .size(56.dp)
                                .testTag("add_live_marker_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.BookmarkAdd,
                                contentDescription = stringResource(R.string.add_marker)
                            )
                        }
                    }
                    if (telemetry.isRecording) {
                        Text(
                            text = "Tip: Press Volume Up/Down to drop an instant timestamp marker.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.align(Alignment.CenterHorizontally)
                        )
                    }
                }
            }
        }

        // Sensitivity & Software Gain Card (0 to +30 dB + Soft Limiter + Save Raw / With Gain)
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.input_gain_label),
                            style = MaterialTheme.typography.titleSmall
                        )
                        Text(
                            text = String.format(Locale.US, "+%.1f dB", settings.inputGainDb),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Slider(
                        value = settings.inputGainDb,
                        onValueChange = { viewModel.setInputGain(it) },
                        valueRange = 0f..30f,
                        modifier = Modifier.testTag("input_gain_slider")
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            selected = settings.saveRawNoGain,
                            onClick = { viewModel.setSaveRaw(true) },
                            label = { Text(stringResource(R.string.save_raw_label)) },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("save_raw_chip")
                        )
                        FilterChip(
                            selected = !settings.saveRawNoGain,
                            onClick = { viewModel.setSaveRaw(false) },
                            label = { Text(stringResource(R.string.save_with_gain_label)) },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("save_with_gain_chip")
                        )
                    }
                }
            }
        }

        // Voice-Activated Recording (VAR) Card
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.var_label),
                                style = MaterialTheme.typography.titleSmall
                            )
                            Text(
                                text = stringResource(R.string.var_preroll),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.secondary
                            )
                        }
                        Switch(
                            checked = settings.varEnabled,
                            onCheckedChange = { enabled ->
                                viewModel.setVarConfig(enabled, settings.varThresholdDb, settings.varHangTimeSec)
                            },
                            modifier = Modifier.testTag("var_toggle_switch")
                        )
                    }

                    AnimatedVisibility(visible = settings.varEnabled) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(stringResource(R.string.var_threshold), style = MaterialTheme.typography.bodySmall)
                                Text(
                                    "${settings.varThresholdDb.toInt()} dBFS",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.secondary
                                )
                            }
                            Slider(
                                value = settings.varThresholdDb,
                                onValueChange = { viewModel.setVarConfig(true, it, settings.varHangTimeSec) },
                                valueRange = -75f..-5f,
                                modifier = Modifier.testTag("var_threshold_slider")
                            )
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(stringResource(R.string.var_hang_time), style = MaterialTheme.typography.bodySmall)
                                Text(
                                    String.format(Locale.US, "%.1f s", settings.varHangTimeSec),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            Slider(
                                value = settings.varHangTimeSec,
                                onValueChange = { viewModel.setVarConfig(true, settings.varThresholdDb, it) },
                                valueRange = 0.5f..12f,
                                modifier = Modifier.testTag("var_hang_slider")
                            )
                        }
                    }
                }
            }
        }
    }

    // Input Device Selector Bottom Sheet
    if (showDeviceSheet) {
        ModalBottomSheet(onDismissRequest = { showDeviceSheet = false }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(stringResource(R.string.settings_audio_input), style = MaterialTheme.typography.titleLarge)
                    FilledTonalButton(onClick = { viewModel.requestUsbPermission() }) {
                        Text("USB Permission")
                    }
                }
                inputDevices.forEach { dev ->
                    val isSel = dev.id == selectedDevice?.id
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = if (isSel) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surfaceContainerHigh
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                viewModel.selectInputDevice(dev)
                                showDeviceSheet = false
                            }
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Text(
                                text = dev.name,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = if (isSel) FontWeight.Bold else FontWeight.Medium
                            )
                            Text(
                                text = "Rates: ${dev.sampleRates.joinToString { "${it / 1000}k" }} • Ch: ${dev.channelCounts.joinToString()}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "Encodings: ${dev.encodings.joinToString()}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }

    // Save Location Quick Selector Bottom Sheet
    if (showStorageSheet) {
        ModalBottomSheet(onDismissRequest = { showStorageSheet = false }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(stringResource(R.string.storage_section_title), style = MaterialTheme.typography.titleLarge)

                QuickStorageSheetItem(
                    icon = Icons.Default.Lock,
                    title = stringResource(R.string.storage_private_title),
                    subtitle = stringResource(R.string.storage_private_desc),
                    selected = settings.storageType == RecordingStorageType.APP_PRIVATE,
                    onClick = {
                        viewModel.selectStorageType(RecordingStorageType.APP_PRIVATE)
                        showStorageSheet = false
                    },
                    testTag = "quick_storage_private"
                )
                QuickStorageSheetItem(
                    icon = Icons.Default.Folder,
                    title = stringResource(R.string.storage_public_title),
                    subtitle = stringResource(R.string.storage_public_desc),
                    selected = settings.storageType == RecordingStorageType.PUBLIC_DOCUMENTS,
                    onClick = {
                        viewModel.selectStorageType(RecordingStorageType.PUBLIC_DOCUMENTS)
                        showStorageSheet = false
                    },
                    testTag = "quick_storage_public"
                )
                if (storageInfo.isSdCardDetected || settings.safTreeUri != null) {
                    QuickStorageSheetItem(
                        icon = Icons.Default.SdStorage,
                        title = stringResource(R.string.storage_sd_title),
                        subtitle = settings.safDisplayPath?.takeIf { it.isNotBlank() }
                            ?: stringResource(R.string.storage_sd_desc),
                        selected = settings.storageType == RecordingStorageType.EXTERNAL_SD_SAF,
                        onClick = {
                            if (settings.safTreeUri.isNullOrBlank()) {
                                safFolderLauncher.launch(null)
                            } else {
                                viewModel.selectStorageType(RecordingStorageType.EXTERNAL_SD_SAF)
                            }
                            showStorageSheet = false
                        },
                        testTag = "quick_storage_sd",
                        actionButton = {
                            OutlinedButton(onClick = {
                                showStorageSheet = false
                                safFolderLauncher.launch(null)
                            }) {
                                Text(
                                    if (settings.safTreeUri.isNullOrBlank()) stringResource(R.string.storage_pick_folder)
                                    else stringResource(R.string.storage_change_folder)
                                )
                            }
                        }
                    )
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }

    // Add Marker Dialog
    if (showMarkerDialog) {
        AlertDialog(
            onDismissRequest = { showMarkerDialog = false },
            title = { Text(stringResource(R.string.add_marker)) },
            text = {
                OutlinedTextField(
                    value = markerNote,
                    onValueChange = { markerNote = it },
                    label = { Text("Optional note (e.g. faint hum, tap)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.addLiveRecordingMarker(markerNote.trim())
                        markerNote = ""
                        showMarkerDialog = false
                    }
                ) { Text("Save Marker") }
            },
            dismissButton = {
                TextButton(onClick = { showMarkerDialog = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
fun WarningBannerCard(text: String, modifier: Modifier = Modifier) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        shape = RoundedCornerShape(12.dp),
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.Warning,
                contentDescription = "Warning",
                tint = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
        }
    }
}

@Composable
private fun QuickStorageSheetItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit,
    testTag: String,
    actionButton: (@Composable () -> Unit)? = null
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceContainerHigh
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .testTag(testTag)
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = selected, onClick = onClick)
                Icon(icon, contentDescription = title, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium)
                    Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (actionButton != null) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    actionButton()
                }
            }
        }
    }
}
