package com.example.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DriveFileMove
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.HealthAndSafety
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.SdStorage
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.data.RecordingStorageType
import com.example.storage.StorageLocationManager
import com.example.ui.theme.ThemePreference
import com.example.ui.viewmodel.FaintRecViewModel
import com.example.util.StorageHelper

@Composable
fun SettingsScreen(
    viewModel: FaintRecViewModel,
    hasMicPermission: Boolean,
    hasNotifPermission: Boolean,
    onRequestPermissions: () -> Unit,
    onOpenAppSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val inputDevices by viewModel.inputDevices.collectAsStateWithLifecycle()
    val externalInputDevices by viewModel.externalInputDevices.collectAsStateWithLifecycle()
    val selectedDevice by viewModel.selectedDevice.collectAsStateWithLifecycle()
    val usbStatus by viewModel.usbPermissionStatus.collectAsStateWithLifecycle()
    val storageInfo by viewModel.storageInfo.collectAsStateWithLifecycle()
    val isMoving by viewModel.isMovingRecordings.collectAsStateWithLifecycle()
    val moveProgressText by viewModel.moveProgressText.collectAsStateWithLifecycle()
    val recordings by viewModel.recordings.collectAsStateWithLifecycle()

    var showMoveAllDialog by remember { mutableStateOf(false) }

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
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text(stringResource(R.string.nav_settings), style = MaterialTheme.typography.headlineMedium)
        }

        // Permissions Rationale Card
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Security, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.perm_title), style = MaterialTheme.typography.titleMedium)
                    }
                    Text(
                        "• Microphone (RECORD_AUDIO): ${if (hasMicPermission) "Granted" else "Required"} — ${stringResource(R.string.perm_rationale_mic)}",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        "• Notifications (POST_NOTIFICATIONS): ${if (hasNotifPermission) "Granted" else "Recommended"} — ${stringResource(R.string.perm_rationale_notif)}",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onRequestPermissions, modifier = Modifier.weight(1f).testTag("settings_grant_perms_btn")) {
                            Text(stringResource(R.string.perm_grant))
                        }
                        OutlinedButton(onClick = onOpenAppSettings, modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.perm_open_settings))
                        }
                    }
                }
            }
        }

        // Save Location Card (App-Private Internal, Public Documents/FaintRec, External SD SAF)
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Storage, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.storage_section_title), style = MaterialTheme.typography.titleMedium)
                    }

                    // 1) App-Private Internal (Default)
                    StorageOptionRow(
                        icon = Icons.Default.Lock,
                        title = stringResource(R.string.storage_private_title),
                        description = stringResource(R.string.storage_private_desc),
                        selected = settings.storageType == RecordingStorageType.APP_PRIVATE,
                        onClick = { viewModel.selectStorageType(RecordingStorageType.APP_PRIVATE) },
                        testTag = "storage_option_private"
                    )

                    // 2) Public Internal (Documents/FaintRec via MediaStore)
                    StorageOptionRow(
                        icon = Icons.Default.Folder,
                        title = stringResource(R.string.storage_public_title),
                        description = stringResource(R.string.storage_public_desc),
                        selected = settings.storageType == RecordingStorageType.PUBLIC_DOCUMENTS,
                        onClick = { viewModel.selectStorageType(RecordingStorageType.PUBLIC_DOCUMENTS) },
                        testTag = "storage_option_public"
                    )

                    // 3) SD Card / External Storage (only shown when detected or already configured)
                    if (storageInfo.isSdCardDetected || settings.safTreeUri != null) {
                        StorageOptionRow(
                            icon = Icons.Default.SdStorage,
                            title = stringResource(R.string.storage_sd_title),
                            description = settings.safDisplayPath?.takeIf { it.isNotBlank() }
                                ?: stringResource(R.string.storage_sd_desc),
                            selected = settings.storageType == RecordingStorageType.EXTERNAL_SD_SAF,
                            onClick = {
                                if (settings.safTreeUri.isNullOrBlank()) {
                                    safFolderLauncher.launch(null)
                                } else {
                                    viewModel.selectStorageType(RecordingStorageType.EXTERNAL_SD_SAF)
                                }
                            },
                            testTag = "storage_option_sd",
                            trailingContent = {
                                OutlinedButton(
                                    onClick = { safFolderLauncher.launch(null) },
                                    modifier = Modifier.testTag("storage_pick_sd_folder_btn")
                                ) {
                                    Text(
                                        if (settings.safTreeUri.isNullOrBlank()) stringResource(R.string.storage_pick_folder)
                                        else stringResource(R.string.storage_change_folder)
                                    )
                                }
                            }
                        )
                    }

                    // Current Path, Free Space & Estimated Recording Time Left
                    Surface(
                        color = MaterialTheme.colorScheme.surface,
                        shape = MaterialTheme.shapes.small,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = stringResource(R.string.storage_current_path, storageInfo.displayPath),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            if (storageInfo.isAvailable) {
                                val freeStr = StorageHelper.formatSizeBytes(storageInfo.freeBytes)
                                val estStr = StorageLocationManager.formatEstimatedDuration(storageInfo.estimatedSecondsLeft)
                                val fmtTag = "${if (settings.preferFloatPcm) "32f" else "16-bit"} ${if (settings.channels == 2) "Stereo" else "Mono"} 48 kHz"
                                Text(
                                    text = stringResource(R.string.storage_free_and_est, freeStr, estStr),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (storageInfo.isLowSpaceWarning) MaterialTheme.colorScheme.error
                                    else MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = "Estimate based on $fmtTag",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            } else {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
                                    Text(
                                        text = storageInfo.unavailableReason ?: "Storage unavailable.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                        }
                    }

                    if (storageInfo.isLowSpaceWarning) {
                        WarningBannerCard(
                            text = stringResource(
                                R.string.storage_low_banner,
                                StorageHelper.formatSizeBytes(storageInfo.freeBytes)
                            )
                        )
                    }

                    // Optional "Move all existing recordings" button & progress
                    val movableCount = recordings.count { it.storageType != settings.storageType }
                    if (isMoving && moveProgressText != null) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(moveProgressText!!, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                    } else if (movableCount > 0) {
                        OutlinedButton(
                            onClick = { showMoveAllDialog = true },
                            enabled = storageInfo.isAvailable && !isMoving,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("settings_move_all_recordings_btn")
                        ) {
                            Icon(Icons.Default.DriveFileMove, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("${stringResource(R.string.storage_move_all_btn)} ($movableCount)")
                        }
                    }
                }
            }
        }

        // Theme & Language (Dark/Light/System + English/Hindi)
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(R.string.settings_theme), style = MaterialTheme.typography.titleSmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ThemePreference.entries.forEach { pref ->
                            FilterChip(
                                selected = settings.themePreference == pref,
                                onClick = { viewModel.setTheme(pref) },
                                label = { Text(pref.name.lowercase().replaceFirstChar { it.uppercase() }) },
                                modifier = Modifier.testTag("theme_chip_${pref.name}")
                            )
                        }
                    }

                    Text(stringResource(R.string.settings_language), style = MaterialTheme.typography.titleSmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = settings.languageTag == "en",
                            onClick = { viewModel.setLanguage("en") },
                            label = { Text("English") },
                            modifier = Modifier.testTag("lang_en_chip")
                        )
                        FilterChip(
                            selected = settings.languageTag == "hi",
                            onClick = { viewModel.setLanguage("hi") },
                            label = { Text("हिन्दी (Hindi)") },
                            modifier = Modifier.testTag("lang_hi_chip")
                        )
                    }
                }
            }
        }

        // Audio Capture Format (Mono/Stereo, 32-bit Float / 16-bit PCM)
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(R.string.settings_channels), style = MaterialTheme.typography.titleSmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = settings.channels == 1, onClick = { viewModel.setChannels(1) }, label = { Text("Mono (Recommended)") })
                        FilterChip(selected = settings.channels == 2, onClick = { viewModel.setChannels(2) }, label = { Text("Stereo") })
                    }

                    Text(stringResource(R.string.settings_encoding), style = MaterialTheme.typography.titleSmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = settings.preferFloatPcm, onClick = { viewModel.setPreferFloat(true) }, label = { Text("32-bit IEEE Float") })
                        FilterChip(selected = !settings.preferFloatPcm, onClick = { viewModel.setPreferFloat(false) }, label = { Text("16-bit PCM") })
                    }
                }
            }
        }

        // Spirit Box Recorder Settings (synced live with Spirit Box screen & DataStore)
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Radio, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Column {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text(
                                        "${stringResource(R.string.spirit_box_title)} ${stringResource(R.string.spirit_box_recorder_title)}",
                                        style = MaterialTheme.typography.titleMedium
                                    )
                                    if (settings.spiritBoxRecorderEnabled) {
                                        Box(
                                            modifier = Modifier
                                                .size(8.dp)
                                                .clip(CircleShape)
                                                .background(MaterialTheme.colorScheme.error)
                                        )
                                        Text(
                                            "REC",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.error
                                        )
                                    }
                                }
                                Text(
                                    text = if (settings.spiritBoxRecorderEnabled) {
                                        stringResource(R.string.spirit_box_recorder_desc)
                                    } else {
                                        stringResource(R.string.spirit_box_recording_off_notice)
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Switch(
                            checked = settings.spiritBoxRecorderEnabled,
                            onCheckedChange = { enabled -> viewModel.setSpiritBoxRecorderEnabled(enabled) },
                            modifier = Modifier.testTag("settings_spirit_box_recorder_switch")
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.spirit_box_record_sweep),
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (settings.spiritBoxRecorderEnabled) MaterialTheme.colorScheme.onSurface
                            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            modifier = Modifier.weight(1f)
                        )
                        Switch(
                            checked = settings.spiritBoxRecordSweepOutput,
                            enabled = settings.spiritBoxRecorderEnabled,
                            onCheckedChange = { viewModel.setSpiritBoxRecordSweepOutput(it) },
                            modifier = Modifier.testTag("settings_spirit_box_sweep_switch")
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.spirit_box_record_mic_track),
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (settings.spiritBoxRecorderEnabled) MaterialTheme.colorScheme.onSurface
                                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                            )
                            Text(
                                text = "Microphone is never accessed when OFF",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                                    alpha = if (settings.spiritBoxRecorderEnabled) 1f else 0.6f
                                )
                            )
                        }
                        Switch(
                            checked = settings.spiritBoxRecordMicTrack,
                            enabled = settings.spiritBoxRecorderEnabled,
                            onCheckedChange = { enabled ->
                                if (enabled && settings.spiritBoxRecorderEnabled && !hasMicPermission) {
                                    onRequestPermissions()
                                }
                                viewModel.setSpiritBoxRecordMicTrack(enabled)
                            },
                            modifier = Modifier.testTag("settings_spirit_box_mic_switch")
                        )
                    }
                }
            }
        }

        // External Microphone Devices & USB Permission
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.settings_audio_input), style = MaterialTheme.typography.titleSmall)
                        OutlinedButton(onClick = {
                            viewModel.refreshInputDevices()
                            viewModel.requestUsbPermission()
                        }) {
                            Icon(Icons.Default.Usb, null)
                            Spacer(Modifier.width(4.dp))
                            Text("USB Scan")
                        }
                    }
                    if (externalInputDevices.isEmpty()) {
                        Text(
                            text = stringResource(R.string.no_external_mic_detected),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.testTag("settings_no_external_mic_text")
                        )
                    }
                    usbStatus?.let {
                        Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                    }
                    inputDevices.forEach { dev ->
                        val isSel = dev.id == selectedDevice?.id
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = if (isSel) MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surface
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { viewModel.selectInputDevice(dev) }
                        ) {
                            Column(Modifier.padding(10.dp)) {
                                Text(dev.name, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    "Sample rates: ${dev.sampleRates.joinToString { "${it / 1000} kHz" }} | Channels: ${dev.channelCounts.joinToString()}",
                                    style = MaterialTheme.typography.labelSmall
                                )
                                Text(
                                    "Encodings: ${dev.encodings.joinToString()}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }

        // Background Recording, Tile, Widget & Hearing Safety Note
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.HealthAndSafety, null, tint = MaterialTheme.colorScheme.secondary)
                        Spacer(Modifier.width(8.dp))
                        Text("Hearing Safety & Background Capture", style = MaterialTheme.typography.titleSmall)
                    }
                    Text(
                        "• Playback boost is capped at +24 dB with a soft limiter and warns above +12 dB to protect your hearing.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        "• Home Screen Widget (Glance), Quick Settings Tile, and Long-Press App Icon Shortcut let you start/stop recording and add markers rapidly. On Android 14+, starting mic capture from a tile or widget opens FaintRec briefly to satisfy foreground microphone security rules.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(4.dp))
                }
            }
        }
    }

    if (showMoveAllDialog) {
        AlertDialog(
            onDismissRequest = { showMoveAllDialog = false },
            title = { Text(stringResource(R.string.storage_move_all_title)) },
            text = {
                Text(stringResource(R.string.storage_move_all_body, storageInfo.displayPath))
            },
            confirmButton = {
                Button(
                    onClick = {
                        showMoveAllDialog = false
                        viewModel.moveAllRecordingsToCurrentLocation()
                    },
                    modifier = Modifier.testTag("confirm_move_all_btn")
                ) {
                    Text(stringResource(R.string.storage_action_move))
                }
            },
            dismissButton = {
                TextButton(onClick = { showMoveAllDialog = false }) {
                    Text(stringResource(R.string.converter_cancel))
                }
            }
        )
    }
}

@Composable
private fun StorageOptionRow(
    icon: ImageVector,
    title: String,
    description: String,
    selected: Boolean,
    onClick: () -> Unit,
    testTag: String,
    trailingContent: (@Composable () -> Unit)? = null
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f)
            else MaterialTheme.colorScheme.surface
        ),
        border = BorderStroke(
            width = if (selected) 1.5.dp else 1.dp,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .testTag(testTag)
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = selected, onClick = onClick)
                Icon(
                    icon,
                    contentDescription = title,
                    tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleSmall)
                    Text(
                        description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (trailingContent != null) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    trailingContent()
                }
            }
        }
    }
}
