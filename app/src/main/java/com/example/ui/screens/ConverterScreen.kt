package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.converter.OutputAudioFormat
import com.example.data.RecordingEntity
import com.example.ui.viewmodel.ConverterViewModel
import com.example.util.AudioMath
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConverterScreen(
    recordingIdsCsv: String,
    onNavigateBack: () -> Unit,
    onPlayConverted: (RecordingEntity) -> Unit,
    viewModel: ConverterViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(recordingIdsCsv) {
        viewModel.loadRecordingIds(recordingIdsCsv)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.converter_title), style = MaterialTheme.typography.titleMedium)
                        Text(
                            "${state.sourceRecordings.size} file(s) queued",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack, modifier = Modifier.testTag("converter_back_btn")) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Lossy-to-Lossless & Lossy-to-Lossy Quality Warnings
            if (state.showLossyToLosslessWarning) {
                item { WarningBannerCard(text = stringResource(R.string.converter_lossy_to_lossless_warn)) }
            }
            if (state.showLossyToLossyWarning) {
                item { WarningBannerCard(text = stringResource(R.string.converter_lossy_to_lossy_warn)) }
            }

            // One-time note when MP3 (LAME) is unavailable in this build
            if (state.showMp3UnavailableNote) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("mp3_unavailable_note_banner")
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                Icons.Default.Info,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.secondary
                            )
                            Text(
                                text = stringResource(R.string.converter_mp3_unavailable_note),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(
                                onClick = { viewModel.dismissMp3UnavailableNote() },
                                modifier = Modifier.testTag("dismiss_mp3_unavailable_note_btn")
                            ) {
                                Text("OK")
                            }
                        }
                    }
                }
            }

            // Output Format Codec Matrix (MediaCodecList + LAME JNI availability check)
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Output Format (Device Codec Verification)", style = MaterialTheme.typography.titleSmall)
                        state.codecSupport.forEach { codec ->
                            val isSelected = state.config.outputFormat == codec.format
                            Card(
                                colors = CardDefaults.cardColors(
                                    containerColor = when {
                                        !codec.isSupported -> MaterialTheme.colorScheme.surfaceContainerLowest
                                        isSelected -> MaterialTheme.colorScheme.primaryContainer
                                        else -> MaterialTheme.colorScheme.surface
                                    }
                                ),
                                border = if (isSelected) BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else null,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(enabled = codec.isSupported) {
                                        viewModel.updateConfig { it.copy(outputFormat = codec.format) }
                                    }
                                    .testTag("codec_option_${codec.format.name}")
                            ) {
                                Column(Modifier.padding(12.dp)) {
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        Text(
                                            codec.format.displayName,
                                            style = MaterialTheme.typography.titleSmall,
                                            color = if (codec.isSupported) MaterialTheme.colorScheme.onSurface
                                            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                        )
                                        Text(
                                            if (codec.isSupported) (if (codec.format.isLossless) "Lossless" else "Lossy") else "Unavailable",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = if (codec.isSupported) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                                        )
                                    }
                                    if (!codec.isSupported && codec.reason != null) {
                                        Text(
                                            codec.reason,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Format-Specific Settings (Bitrate, VBR, Sample Rate, Channels, WAV Bit Depth, FLAC Level)
            item {
                val cfg = state.config
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Encoding Parameters", style = MaterialTheme.typography.titleSmall)

                        if (cfg.outputFormat in setOf(OutputAudioFormat.AAC_M4A, OutputAudioFormat.OGG_OPUS, OutputAudioFormat.MP3)) {
                            if (cfg.outputFormat == OutputAudioFormat.MP3) {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                    Text("Use VBR (Variable Bitrate)", style = MaterialTheme.typography.bodySmall)
                                    Switch(checked = cfg.mp3UseVbr, onCheckedChange = { v -> viewModel.updateConfig { it.copy(mp3UseVbr = v) } })
                                }
                            }
                            if (cfg.outputFormat == OutputAudioFormat.MP3 && cfg.mp3UseVbr) {
                                Text("LAME VBR Quality: V${cfg.mp3VbrQuality} (0 = highest)", style = MaterialTheme.typography.bodySmall)
                                Slider(
                                    value = cfg.mp3VbrQuality.toFloat(),
                                    onValueChange = { q -> viewModel.updateConfig { it.copy(mp3VbrQuality = q.toInt()) } },
                                    valueRange = 0f..9f,
                                    steps = 8
                                )
                            } else {
                                Text("Bitrate: ${cfg.bitrateKbps} kbps", style = MaterialTheme.typography.bodySmall)
                                Row(
                                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    listOf(64, 96, 128, 192, 256, 320).forEach { br ->
                                        FilterChip(
                                            selected = cfg.bitrateKbps == br,
                                            onClick = { viewModel.updateConfig { it.copy(bitrateKbps = br) } },
                                            label = { Text("${br}k") }
                                        )
                                    }
                                }
                            }
                        }

                        if (cfg.outputFormat == OutputAudioFormat.WAV) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilterChip(
                                    selected = !cfg.wavFloat32,
                                    onClick = { viewModel.updateConfig { it.copy(wavFloat32 = false) } },
                                    label = { Text("16-bit PCM") }
                                )
                                FilterChip(
                                    selected = cfg.wavFloat32,
                                    onClick = { viewModel.updateConfig { it.copy(wavFloat32 = true) } },
                                    label = { Text("32-bit Float PCM") }
                                )
                            }
                        }

                        if (cfg.outputFormat == OutputAudioFormat.FLAC) {
                            Text("FLAC Compression Level: ${cfg.flacCompressionLevel}", style = MaterialTheme.typography.bodySmall)
                            Slider(
                                value = cfg.flacCompressionLevel.toFloat(),
                                onValueChange = { lv -> viewModel.updateConfig { it.copy(flacCompressionLevel = lv.toInt()) } },
                                valueRange = 0f..8f,
                                steps = 7
                            )
                        }

                        // Sample Rate
                        Text("Sample Rate", style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(0 to "Original", 44100 to "44.1 kHz", 48000 to "48 kHz").forEach { (sr, label) ->
                                FilterChip(
                                    selected = cfg.targetSampleRate == sr,
                                    onClick = { viewModel.updateConfig { it.copy(targetSampleRate = sr) } },
                                    label = { Text(label) }
                                )
                            }
                        }

                        // Channels
                        Text("Channels", style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(0 to "Original", 1 to "Mono", 2 to "Stereo").forEach { (ch, label) ->
                                FilterChip(
                                    selected = cfg.targetChannels == ch,
                                    onClick = { viewModel.updateConfig { it.copy(targetChannels = ch) } },
                                    label = { Text(label) }
                                )
                            }
                        }
                    }
                }
            }

            // Bake Effects into Export Option
            item {
                val cfg = state.config
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.converter_bake_effects), style = MaterialTheme.typography.titleSmall)
                            Switch(
                                checked = cfg.bakeEffects,
                                onCheckedChange = { b -> viewModel.updateConfig { it.copy(bakeEffects = b) } },
                                modifier = Modifier.testTag("bake_effects_switch")
                            )
                        }
                        if (cfg.bakeEffects) {
                            Text("Gain: ${String.format(Locale.US, "%+.0f dB", cfg.gainDb)}", style = MaterialTheme.typography.bodySmall)
                            Slider(
                                value = cfg.gainDb,
                                onValueChange = { g -> viewModel.updateConfig { it.copy(gainDb = g) } },
                                valueRange = -12f..24f
                            )
                            Text("Speed: ${String.format(Locale.US, "%.2fx", cfg.speed)}", style = MaterialTheme.typography.bodySmall)
                            Slider(
                                value = cfg.speed,
                                onValueChange = { s -> viewModel.updateConfig { it.copy(speed = s) } },
                                valueRange = 0.25f..4.0f
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilterChip(
                                    selected = cfg.normalizeMinus1Db,
                                    onClick = { viewModel.updateConfig { it.copy(normalizeMinus1Db = !it.normalizeMinus1Db) } },
                                    label = { Text("Normalize -1 dBFS") }
                                )
                                FilterChip(
                                    selected = cfg.reverse,
                                    onClick = { viewModel.updateConfig { it.copy(reverse = !it.reverse) } },
                                    label = { Text("Reverse") }
                                )
                            }
                        }
                    }
                }
            }

            // Start / Cancel + Progress
            item {
                if (state.isConverting) {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                "Converting file ${state.currentFileIndex} / ${state.sourceRecordings.size} (${(state.progress * 100).toInt()}%)",
                                style = MaterialTheme.typography.titleSmall
                            )
                            LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth())
                            OutlinedButton(
                                onClick = { viewModel.cancelConversion() },
                                modifier = Modifier.fillMaxWidth().testTag("cancel_conversion_btn")
                            ) {
                                Icon(Icons.Default.Cancel, null)
                                Spacer(Modifier.width(6.dp))
                                Text(stringResource(R.string.converter_cancel))
                            }
                        }
                    }
                } else {
                    Button(
                        enabled = state.sourceRecordings.isNotEmpty(),
                        onClick = { viewModel.startConversion() },
                        modifier = Modifier.fillMaxWidth().testTag("start_conversion_btn")
                    ) {
                        Icon(Icons.Default.SwapHoriz, null)
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.converter_start))
                    }
                }

                state.errorMessage?.let { err ->
                    Text(err, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }

            // Conversion Results Summary Cards (Sizes, Format, Bitrate, Duration + Play, Share, Open Folder)
            items(state.results) { res ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.tertiary)
                ) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Converted: ${res.outputRecording.title}", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.tertiary)
                        Text(
                            "Size: ${AudioMath.formatFileSize(res.originalSizeBytes)} → ${AudioMath.formatFileSize(res.outputSizeBytes)}",
                            style = MaterialTheme.typography.labelMedium
                        )
                        Text(
                            "Format: ${res.formatName} • ${res.bitrateDescription} • Duration: ${AudioMath.formatDuration(res.durationMs)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { onPlayConverted(res.outputRecording) }, modifier = Modifier.weight(1f)) {
                                Icon(Icons.Default.PlayArrow, null)
                                Spacer(Modifier.width(4.dp))
                                Text("Play")
                            }
                            OutlinedButton(onClick = { viewModel.shareResult(res) }, modifier = Modifier.weight(1f)) {
                                Icon(Icons.Default.Share, null)
                                Spacer(Modifier.width(4.dp))
                                Text("Share")
                            }
                            OutlinedButton(onClick = { viewModel.openOutputFolder() }, modifier = Modifier.weight(1f)) {
                                Icon(Icons.Default.FolderOpen, null)
                                Spacer(Modifier.width(4.dp))
                                Text("Folder")
                            }
                        }
                    }
                }
            }
        }
    }
}
