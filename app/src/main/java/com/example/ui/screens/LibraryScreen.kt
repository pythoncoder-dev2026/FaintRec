package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileMove
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SdStorage
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Transform
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.analysis.PostProcessOptions
import com.example.audio.PitchMode
import com.example.audio.PlaybackDirection
import com.example.data.RecordingEntity
import com.example.data.RecordingSourceTag
import com.example.data.RecordingStorageType
import com.example.ui.viewmodel.FaintRecViewModel
import com.example.util.AudioMath
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    viewModel: FaintRecViewModel,
    onImportAudioClick: () -> Unit,
    onOpenEditor: (Long) -> Unit,
    onOpenConverter: (List<Long>) -> Unit,
    onOpenAnalyze: (RecordingEntity) -> Unit,
    modifier: Modifier = Modifier
) {
    val recordings by viewModel.recordings.collectAsStateWithLifecycle()
    val playback by viewModel.playbackState.collectAsStateWithLifecycle()
    val importProgress by viewModel.importProgress.collectAsStateWithLifecycle()
    val processingMsg by viewModel.processingMessage.collectAsStateWithLifecycle()
    val moveProgressText by viewModel.moveProgressText.collectAsStateWithLifecycle()
    val storageInfo by viewModel.storageInfo.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val selectedBatch by viewModel.selectedBatchIds.collectAsStateWithLifecycle()

    var batchMode by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<RecordingEntity?>(null) }
    var renameText by remember { mutableStateOf("") }
    var postProcessTarget by remember { mutableStateOf<RecordingEntity?>(null) }
    var ppOptions by remember { mutableStateOf(PostProcessOptions()) }
    var moveTarget by remember { mutableStateOf<RecordingEntity?>(null) }
    var moveModeIsMove by remember { mutableStateOf(true) }

    val dateFormatter = remember { SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()) }

    Box(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Top Action Row (Batch Convert toggle)
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${recordings.size} Recordings",
                        style = MaterialTheme.typography.titleLarge
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (batchMode && selectedBatch.isNotEmpty()) {
                            Button(
                                onClick = {
                                    onOpenConverter(selectedBatch.toList())
                                    batchMode = false
                                    viewModel.clearBatchSelection()
                                },
                                modifier = Modifier.testTag("batch_convert_go_button")
                            ) {
                                Text("Convert (${selectedBatch.size})")
                            }
                        }
                        FilterChip(
                            selected = batchMode,
                            onClick = {
                                batchMode = !batchMode
                                if (!batchMode) viewModel.clearBatchSelection()
                            },
                            label = { Text(stringResource(R.string.batch_convert)) },
                            leadingIcon = { Icon(Icons.Default.Transform, contentDescription = null, modifier = Modifier.size(16.dp)) },
                            modifier = Modifier.testTag("toggle_batch_mode_chip")
                        )
                    }
                }
            }

            // Progress banner for Import or Post-Processing
            if (importProgress != null) {
                item {
                    val prog = importProgress!!
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                "Importing ${prog.currentIndex}/${prog.totalCount}: ${prog.fileName}",
                                style = MaterialTheme.typography.titleSmall
                            )
                            LinearProgressIndicator(
                                progress = { prog.progress },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }

            if (processingMsg != null || moveProgressText != null) {
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(moveProgressText ?: processingMsg!!, style = MaterialTheme.typography.titleSmall)
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            }

            // Active Playback Deck (Forward/Reverse, Speed Chips & Slider, Preserve Pitch / Tape Mode, Boost, Export)
            if (playback.activeRecording != null) {
                item {
                    val activeRec = playback.activeRecording!!
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                IconButton(
                                    onClick = { viewModel.togglePlayPause() },
                                    modifier = Modifier.testTag("player_play_pause_button")
                                ) {
                                    Icon(
                                        imageVector = if (playback.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                        contentDescription = "Play or Pause",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(32.dp)
                                    )
                                }
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = activeRec.title,
                                        style = MaterialTheme.typography.titleMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = "${AudioMath.formatDuration(playback.positionMs)} / ${AudioMath.formatDuration(playback.durationMs)} • ${playback.direction.name}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                                IconButton(onClick = { postProcessTarget = activeRec }) {
                                    Icon(Icons.Default.AutoFixHigh, contentDescription = "Post-Process")
                                }
                            }

                            // Seek bar
                            Slider(
                                value = if (playback.durationMs > 0) playback.positionMs.toFloat() / playback.durationMs else 0f,
                                onValueChange = { frac -> viewModel.seekTo((frac * playback.durationMs).toLong()) },
                                modifier = Modifier.testTag("player_seek_slider")
                            )

                            // Forward / Reverse + Pitch Mode toggles
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                FilterChip(
                                    selected = playback.direction == PlaybackDirection.FORWARD,
                                    onClick = { viewModel.setPlaybackDirection(PlaybackDirection.FORWARD) },
                                    label = { Text(stringResource(R.string.playback_forward)) },
                                    modifier = Modifier.testTag("dir_forward_chip")
                                )
                                FilterChip(
                                    selected = playback.direction == PlaybackDirection.REVERSE,
                                    onClick = { viewModel.setPlaybackDirection(PlaybackDirection.REVERSE) },
                                    label = { Text(stringResource(R.string.playback_reverse)) },
                                    modifier = Modifier.testTag("dir_reverse_chip")
                                )
                                Spacer(Modifier.weight(1f))
                                FilterChip(
                                    selected = playback.pitchMode == PitchMode.PRESERVE_PITCH,
                                    onClick = {
                                        val next = if (playback.pitchMode == PitchMode.PRESERVE_PITCH)
                                            PitchMode.TAPE_MODE else PitchMode.PRESERVE_PITCH
                                        viewModel.setPitchMode(next)
                                    },
                                    label = {
                                        Text(
                                            if (playback.pitchMode == PitchMode.PRESERVE_PITCH)
                                                stringResource(R.string.pitch_preserve)
                                            else stringResource(R.string.pitch_tape)
                                        )
                                    },
                                    modifier = Modifier.testTag("pitch_mode_chip")
                                )
                            }

                            // Speed Chips (0.25x..4x) + Live Speed Slider
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                listOf(0.25f, 0.5f, 1.0f, 1.5f, 2.0f, 3.0f, 4.0f).forEach { spd ->
                                    val selected = kotlin.math.abs(playback.activeSpeed - spd) < 0.04f
                                    FilterChip(
                                        selected = selected,
                                        onClick = { viewModel.setPlaybackSpeed(spd) },
                                        label = { Text("${spd}x") }
                                    )
                                }
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = String.format(Locale.US, "Speed %.2fx", playback.activeSpeed),
                                    style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier.width(84.dp)
                                )
                                Slider(
                                    value = playback.activeSpeed,
                                    onValueChange = { viewModel.setPlaybackSpeed(it) },
                                    valueRange = 0.25f..4.0f,
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("playback_speed_slider")
                                )
                            }

                            // Playback Gain Boost Slider + Hearing Safety Warning
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = String.format(Locale.US, "Boost +%.0f dB", playback.gainBoostDb),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (playback.showHearingWarning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary,
                                    modifier = Modifier.width(84.dp)
                                )
                                Slider(
                                    value = playback.gainBoostDb,
                                    onValueChange = { viewModel.setPlaybackGain(it) },
                                    valueRange = 0f..AudioMath.SAFE_MAX_PLAYBACK_BOOST_DB,
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("playback_boost_slider")
                                )
                            }
                            if (playback.showHearingWarning) {
                                WarningBannerCard(text = stringResource(R.string.hearing_safety_warn))
                            }

                            // Export Reversed & Export at Changed Speed Buttons
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedButton(
                                    onClick = { viewModel.exportReversed(activeRec) },
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("export_reversed_button")
                                ) {
                                    Text(stringResource(R.string.export_reversed), maxLines = 1)
                                }
                                OutlinedButton(
                                    onClick = { viewModel.exportAtCurrentSpeed(activeRec) },
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("export_speed_button")
                                ) {
                                    Text(
                                        "${stringResource(R.string.export_speed)} (${String.format(Locale.US, "%.2fx", playback.activeSpeed)})",
                                        maxLines = 1
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Empty State with generated acoustic hero artwork
            if (recordings.isEmpty()) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(160.dp)
                            ) {
                                Image(
                                    painter = painterResource(id = R.drawable.img_acoustic_hero_1795690882907),
                                    contentDescription = "Acoustic waveform studio visual",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(
                                            Brush.verticalGradient(
                                                listOf(Color.Transparent, MaterialTheme.colorScheme.surfaceContainer)
                                            )
                                        )
                                )
                            }
                            Column(
                                modifier = Modifier.padding(18.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = stringResource(R.string.library_empty_title),
                                    style = MaterialTheme.typography.titleLarge
                                )
                                Text(
                                    text = stringResource(R.string.library_empty_subtitle),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            // Recordings List
            items(recordings, key = { it.id }) { rec ->
                val isPlayingThis = playback.activeRecording?.id == rec.id && playback.isPlaying
                val isBatchChecked = rec.id in selectedBatch
                var menuOpen by remember { mutableStateOf(false) }

                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = if (playback.activeRecording?.id == rec.id)
                            MaterialTheme.colorScheme.surfaceContainerHigh
                        else MaterialTheme.colorScheme.surfaceContainer
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            if (batchMode) viewModel.toggleBatchSelection(rec.id)
                            else viewModel.playRecording(rec)
                        }
                        .testTag("recording_card_${rec.id}")
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (batchMode) {
                            IconButton(onClick = { viewModel.toggleBatchSelection(rec.id) }) {
                                Icon(
                                    if (isBatchChecked) Icons.Default.CheckBox else Icons.Default.CheckBoxOutlineBlank,
                                    contentDescription = "Select for batch convert",
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        } else {
                            IconButton(onClick = {
                                if (playback.activeRecording?.id == rec.id) viewModel.togglePlayPause()
                                else viewModel.playRecording(rec)
                            }) {
                                Icon(
                                    imageVector = if (isPlayingThis) Icons.Default.Pause else Icons.Default.PlayArrow,
                                    contentDescription = "Play recording",
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }

                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = rec.title,
                                    style = MaterialTheme.typography.titleSmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false)
                                )
                                StorageLocationIconBadge(rec.storageType)
                                SourceTagBadge(rec.sourceTag)
                            }
                            Text(
                                text = "${AudioMath.formatDurationShort(rec.durationMs)} • ${AudioMath.formatFileSize(rec.fileSizeBytes)} • ${rec.format} ${rec.sampleRate / 1000}kHz",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "${dateFormatter.format(Date(rec.createdAtMillis))} • ${rec.inputDeviceName}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Box {
                            IconButton(
                                onClick = { menuOpen = true },
                                modifier = Modifier.testTag("recording_menu_${rec.id}")
                            ) {
                                Icon(Icons.Default.MoreVert, contentDescription = "More actions")
                            }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                DropdownMenuItem(
                                    text = { Text("Non-Destructive Editor") },
                                    leadingIcon = { Icon(Icons.Default.Edit, null) },
                                    onClick = { menuOpen = false; onOpenEditor(rec.id) }
                                )
                                DropdownMenuItem(
                                    text = { Text("Convert Format") },
                                    leadingIcon = { Icon(Icons.Default.SwapHoriz, null) },
                                    onClick = { menuOpen = false; onOpenConverter(listOf(rec.id)) }
                                )
                                DropdownMenuItem(
                                    text = { Text("Spectrogram & Analysis") },
                                    leadingIcon = { Icon(Icons.Default.GraphicEq, null) },
                                    onClick = { menuOpen = false; onOpenAnalyze(rec) }
                                )
                                DropdownMenuItem(
                                    text = { Text("Post-Process (Norm / Denoise / Comp)") },
                                    leadingIcon = { Icon(Icons.Default.AutoFixHigh, null) },
                                    onClick = { menuOpen = false; postProcessTarget = rec }
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.storage_move_copy_menu)) },
                                    leadingIcon = { Icon(Icons.Default.DriveFileMove, null) },
                                    onClick = {
                                        menuOpen = false
                                        moveModeIsMove = true
                                        moveTarget = rec
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Rename") },
                                    leadingIcon = { Icon(Icons.Default.Edit, null) },
                                    onClick = {
                                        menuOpen = false
                                        renameTarget = rec
                                        renameText = rec.title
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Share") },
                                    leadingIcon = { Icon(Icons.Default.Share, null) },
                                    onClick = { menuOpen = false; viewModel.shareRecording(rec) }
                                )
                                DropdownMenuItem(
                                    text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                                    leadingIcon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) },
                                    onClick = { menuOpen = false; viewModel.deleteRecording(rec) }
                                )
                            }
                        }
                    }
                }
            }
        }

        // Import FAB (SAF OpenMultipleDocuments audio/*)
        ExtendedFloatingActionButton(
            onClick = onImportAudioClick,
            icon = { Icon(Icons.Default.FileOpen, contentDescription = stringResource(R.string.import_audio)) },
            text = { Text(stringResource(R.string.import_audio)) },
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp)
                .testTag("import_audio_fab")
        )
    }

    // Rename Dialog
    renameTarget?.let { rec ->
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("Rename Recording") },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(onClick = {
                    viewModel.renameRecording(rec, renameText)
                    renameTarget = null
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) { Text("Cancel") }
            }
        )
    }

    // Post-Processing Bottom Sheet (Normalize -1 dBFS, Spectral Gate Denoise, Compressor)
    postProcessTarget?.let { rec ->
        ModalBottomSheet(onDismissRequest = { postProcessTarget = null }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(stringResource(R.string.post_process_title), style = MaterialTheme.typography.titleLarge)
                Text(
                    "Original file stays completely untouched. A new WAV copy is saved to Library.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.post_normalize), style = MaterialTheme.typography.titleSmall)
                    Switch(
                        checked = ppOptions.normalizeToMinus1Db,
                        onCheckedChange = { ppOptions = ppOptions.copy(normalizeToMinus1Db = it) }
                    )
                }

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.post_noise_reduction), style = MaterialTheme.typography.titleSmall)
                    Switch(
                        checked = ppOptions.spectralNoiseReduction,
                        onCheckedChange = { ppOptions = ppOptions.copy(spectralNoiseReduction = it) }
                    )
                }
                if (ppOptions.spectralNoiseReduction) {
                    Slider(
                        value = ppOptions.noiseReductionStrength,
                        onValueChange = { ppOptions = ppOptions.copy(noiseReductionStrength = it) },
                        valueRange = 0.2f..0.92f
                    )
                }

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.post_compressor), style = MaterialTheme.typography.titleSmall)
                    Switch(
                        checked = ppOptions.enableCompressor,
                        onCheckedChange = { ppOptions = ppOptions.copy(enableCompressor = it) }
                    )
                }

                Button(
                    onClick = {
                        viewModel.runPostProcessing(rec, ppOptions)
                        postProcessTarget = null
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("run_post_process_button")
                ) {
                    Text(stringResource(R.string.post_export_new))
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }

    // Move or Copy Recording Dialog
    moveTarget?.let { rec ->
        AlertDialog(
            onDismissRequest = { moveTarget = null },
            title = { Text(stringResource(R.string.storage_move_copy_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = rec.title,
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = moveModeIsMove,
                            onClick = { moveModeIsMove = true },
                            label = { Text(stringResource(R.string.storage_action_move)) },
                            modifier = Modifier.weight(1f)
                        )
                        FilterChip(
                            selected = !moveModeIsMove,
                            onClick = { moveModeIsMove = false },
                            label = { Text(stringResource(R.string.storage_action_copy)) },
                            modifier = Modifier.weight(1f)
                        )
                    }

                    val destinations = buildList {
                        add(Triple(RecordingStorageType.APP_PRIVATE, Icons.Default.Lock, stringResource(R.string.storage_private_title)))
                        add(Triple(RecordingStorageType.PUBLIC_DOCUMENTS, Icons.Default.Folder, stringResource(R.string.storage_public_title)))
                        if (storageInfo.isSdCardDetected || settings.safTreeUri != null) {
                            add(Triple(RecordingStorageType.EXTERNAL_SD_SAF, Icons.Default.SdStorage, settings.safDisplayPath ?: stringResource(R.string.storage_sd_title)))
                        }
                    }

                    destinations.forEach { (destType, icon, label) ->
                        val isSameLocation = rec.storageType == destType
                        val enabled = !(moveModeIsMove && isSameLocation)
                        OutlinedButton(
                            onClick = {
                                viewModel.moveOrCopyRecording(rec, destType, isMove = moveModeIsMove)
                                moveTarget = null
                            },
                            enabled = enabled,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("move_dest_${destType.name}")
                        ) {
                            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = if (isSameLocation) "$label (Current)" else label,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { moveTarget = null }) {
                    Text(stringResource(R.string.converter_cancel))
                }
            }
        )
    }
}

@Composable
private fun StorageLocationIconBadge(storageType: RecordingStorageType) {
    val (icon, desc, tint) = when (storageType) {
        RecordingStorageType.APP_PRIVATE -> Triple(
            Icons.Default.Lock,
            stringResource(R.string.storage_badge_private),
            MaterialTheme.colorScheme.onSurfaceVariant
        )
        RecordingStorageType.PUBLIC_DOCUMENTS -> Triple(
            Icons.Default.Folder,
            stringResource(R.string.storage_badge_public),
            MaterialTheme.colorScheme.primary
        )
        RecordingStorageType.EXTERNAL_SD_SAF -> Triple(
            Icons.Default.SdStorage,
            stringResource(R.string.storage_badge_sd),
            MaterialTheme.colorScheme.secondary
        )
    }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        shape = RoundedCornerShape(6.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = desc,
                tint = tint,
                modifier = Modifier.size(12.dp)
            )
            Text(
                text = desc,
                style = MaterialTheme.typography.labelSmall,
                color = tint
            )
        }
    }
}

@Composable
private fun SourceTagBadge(tag: RecordingSourceTag) {
    val (label, bg, fg) = when (tag) {
        RecordingSourceTag.IMPORTED -> Triple(
            stringResource(R.string.badge_imported),
            MaterialTheme.colorScheme.primaryContainer,
            MaterialTheme.colorScheme.onPrimaryContainer
        )
        RecordingSourceTag.CONVERTED -> Triple(
            stringResource(R.string.badge_converted),
            MaterialTheme.colorScheme.tertiaryContainer,
            MaterialTheme.colorScheme.onTertiaryContainer
        )
        RecordingSourceTag.SPIRIT_BOX -> Triple(
            stringResource(R.string.badge_spirit_box),
            MaterialTheme.colorScheme.secondaryContainer,
            MaterialTheme.colorScheme.onSecondaryContainer
        )
        RecordingSourceTag.PROCESSED -> Triple(
            "Processed",
            MaterialTheme.colorScheme.surfaceContainerHigh,
            MaterialTheme.colorScheme.primary
        )
        RecordingSourceTag.RECORDED -> return
    }
    Surface(
        color = bg,
        shape = RoundedCornerShape(6.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = fg,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}
