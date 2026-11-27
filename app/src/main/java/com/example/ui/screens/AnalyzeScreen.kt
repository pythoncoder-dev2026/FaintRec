package com.example.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.analysis.FftWindowType
import com.example.analysis.SpectrogramColorMap
import com.example.ui.components.InteractiveSpectrogramCanvas
import com.example.ui.components.LiveOscilloscopeWaveform
import com.example.ui.components.LiveSpectrumCanvas
import com.example.ui.viewmodel.FaintRecViewModel
import com.example.util.AudioMath

@Composable
fun AnalyzeScreen(
    viewModel: FaintRecViewModel,
    modifier: Modifier = Modifier
) {
    val recordings by viewModel.recordings.collectAsStateWithLifecycle()
    val analyze by viewModel.analyzeState.collectAsStateWithLifecycle()
    val telemetry by viewModel.telemetry.collectAsStateWithLifecycle()
    val liveRecFft by viewModel.liveRecordFft.collectAsStateWithLifecycle()
    val playbackFft by viewModel.playbackFft.collectAsStateWithLifecycle()
    val playback by viewModel.playbackState.collectAsStateWithLifecycle()
    val markers by viewModel.activeMarkers.collectAsStateWithLifecycle()

    var selectedTab by remember { mutableIntStateOf(0) }
    var specZoom by remember { mutableFloatStateOf(1f) }
    var newMarkerLabel by remember { mutableStateOf("") }
    var newMarkerNote by remember { mutableStateOf("") }

    LaunchedEffect(recordings) {
        if (analyze.selectedRecording == null && recordings.isNotEmpty()) {
            viewModel.selectRecordingForAnalysis(recordings.first())
        }
        if (analyze.compareRecordingB == null && recordings.size >= 2) {
            viewModel.selectCompareRecordingB(recordings[1])
        }
    }

    val activeFft = if (telemetry.isRecording) liveRecFft else (playbackFft ?: liveRecFft)

    Column(modifier = modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = selectedTab) {
            listOf(
                stringResource(R.string.analyze_fft),
                stringResource(R.string.analyze_spectrogram),
                stringResource(R.string.analyze_ab),
                stringResource(R.string.analyze_markers)
            ).forEachIndexed { idx, label ->
                Tab(
                    selected = selectedTab == idx,
                    onClick = { selectedTab = idx },
                    text = { Text(label, maxLines = 1) },
                    modifier = Modifier.testTag("analyze_tab_$idx")
                )
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            when (selectedTab) {
                // TAB 0: Live & Playback FFT Frequency Analyzer
                0 -> {
                    item {
                        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text(
                                    text = if (telemetry.isRecording) "Live Microphone FFT" else "Playback / Active FFT",
                                    style = MaterialTheme.typography.titleMedium
                                )
                                LiveSpectrumCanvas(
                                    fft = activeFft,
                                    logScale = analyze.fftLogScale,
                                    maxFreqFraction = analyze.maxFreqFraction,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Text(
                                    "Tap anywhere on the spectrum to inspect exact frequency, dB level, and musical note.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    item {
                        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text("FFT Size", style = MaterialTheme.typography.titleSmall)
                                Row(
                                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    listOf(1024, 2048, 4096, 8192, 16384).forEach { sz ->
                                        FilterChip(
                                            selected = analyze.fftSize == sz,
                                            onClick = { viewModel.setFftConfig(sz, analyze.fftWindow, analyze.fftLogScale) },
                                            label = { Text("$sz") }
                                        )
                                    }
                                }

                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    FilterChip(
                                        selected = analyze.fftWindow == FftWindowType.HANN,
                                        onClick = { viewModel.setFftConfig(analyze.fftSize, FftWindowType.HANN, analyze.fftLogScale) },
                                        label = { Text("Hann Window") }
                                    )
                                    FilterChip(
                                        selected = analyze.fftWindow == FftWindowType.BLACKMAN,
                                        onClick = { viewModel.setFftConfig(analyze.fftSize, FftWindowType.BLACKMAN, analyze.fftLogScale) },
                                        label = { Text("Blackman Window") }
                                    )
                                }

                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    FilterChip(
                                        selected = analyze.fftLogScale,
                                        onClick = { viewModel.setFftConfig(analyze.fftSize, analyze.fftWindow, true) },
                                        label = { Text("Logarithmic Axis") }
                                    )
                                    FilterChip(
                                        selected = !analyze.fftLogScale,
                                        onClick = { viewModel.setFftConfig(analyze.fftSize, analyze.fftWindow, false) },
                                        label = { Text("Linear Axis") }
                                    )
                                }

                                Text(
                                    "Frequency Span: ${(analyze.maxFreqFraction * 100).toInt()}% of Nyquist",
                                    style = MaterialTheme.typography.bodySmall
                                )
                                Slider(
                                    value = analyze.maxFreqFraction,
                                    onValueChange = { viewModel.setAnalyzeFreqRangeFraction(it) },
                                    valueRange = 0.1f..1.0f
                                )
                            }
                        }
                    }
                }

                // TAB 1: Interactive Spectrogram (Scrollable, Zoomable, Color Maps, Tap to Seek)
                1 -> {
                    item {
                        RecordingPickerRow(
                            label = "Recording",
                            recordings = recordings,
                            selectedId = analyze.selectedRecording?.id,
                            onSelect = { viewModel.selectRecordingForAnalysis(it) }
                        )
                    }
                    item {
                        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                val rec = analyze.selectedRecording
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        rec?.title ?: "Select a recording",
                                        style = MaterialTheme.typography.titleSmall,
                                        modifier = Modifier.weight(1f)
                                    )
                                    if (rec != null) {
                                        IconButton(onClick = {
                                            if (playback.activeRecording?.id == rec.id) viewModel.togglePlayPause()
                                            else viewModel.playRecording(rec)
                                        }) {
                                            Icon(
                                                if (playback.activeRecording?.id == rec.id && playback.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                                contentDescription = "Play"
                                            )
                                        }
                                    }
                                }
                                if (analyze.isComputingSpectrogram) {
                                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                                }
                                val playheadFrac = if (rec != null && playback.activeRecording?.id == rec.id && rec.durationMs > 0) {
                                    playback.positionMs.toFloat() / rec.durationMs
                                } else 0f

                                InteractiveSpectrogramCanvas(
                                    data = analyze.spectrogramA,
                                    colorMap = analyze.colorMap,
                                    maxFreqFraction = analyze.maxFreqFraction,
                                    playheadFraction = playheadFrac,
                                    markers = markers,
                                    zoomLevel = specZoom,
                                    onTapSeekFraction = { frac ->
                                        rec?.let {
                                            val targetMs = (frac * it.durationMs).toLong()
                                            if (playback.activeRecording?.id == it.id) viewModel.seekTo(targetMs)
                                            else viewModel.playRecording(it, targetMs)
                                        }
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(220.dp)
                                        .testTag("spectrogram_canvas")
                                )

                                Row(
                                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    SpectrogramColorMap.entries.forEach { map ->
                                        FilterChip(
                                            selected = analyze.colorMap == map,
                                            onClick = { viewModel.setSpectrogramColorMap(map) },
                                            label = { Text(map.name.replace('_', ' ')) }
                                        )
                                    }
                                }

                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Text("Zoom ${String.format(java.util.Locale.US, "%.1fx", specZoom)}", style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(64.dp))
                                    Slider(value = specZoom, onValueChange = { specZoom = it }, valueRange = 1f..6f, modifier = Modifier.weight(1f))
                                }
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Text("Freq ${(analyze.maxFreqFraction * 100).toInt()}%", style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(64.dp))
                                    Slider(value = analyze.maxFreqFraction, onValueChange = { viewModel.setAnalyzeFreqRangeFraction(it) }, valueRange = 0.1f..1.0f, modifier = Modifier.weight(1f))
                                }
                            }
                        }
                    }
                }

                // TAB 2: A/B Side-by-Side Comparison
                2 -> {
                    item {
                        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text("Synchronized A/B Comparison", style = MaterialTheme.typography.titleMedium)
                                RecordingPickerRow("Track A", recordings, analyze.selectedRecording?.id) {
                                    viewModel.selectRecordingForAnalysis(it)
                                }
                                RecordingPickerRow("Track B", recordings, analyze.compareRecordingB?.id) {
                                    viewModel.selectCompareRecordingB(it)
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Button(
                                        onClick = { viewModel.switchAbChannel('A') },
                                        modifier = Modifier
                                            .weight(1f)
                                            .testTag("ab_switch_a_button")
                                    ) {
                                        Text(if (analyze.activeAbChannel == 'A') "● Listening A" else "Switch to A")
                                    }
                                    Button(
                                        onClick = { viewModel.switchAbChannel('B') },
                                        modifier = Modifier
                                            .weight(1f)
                                            .testTag("ab_switch_b_button")
                                    ) {
                                        Text(if (analyze.activeAbChannel == 'B') "● Listening B" else "Switch to B")
                                    }
                                }

                                val currentAbRec = if (analyze.activeAbChannel == 'A') analyze.selectedRecording else analyze.compareRecordingB
                                OutlinedButton(
                                    onClick = {
                                        currentAbRec?.let {
                                            if (playback.activeRecording?.id == it.id) viewModel.togglePlayPause()
                                            else viewModel.playRecording(it, playback.positionMs)
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(if (playback.isPlaying) "Pause Sync Playback" else "Play Sync (${analyze.activeAbChannel})")
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Text("A: ${analyze.selectedRecording?.title ?: "-"}", style = MaterialTheme.typography.labelSmall, maxLines = 1)
                                        LiveOscilloscopeWaveform(analyze.waveformA, isRecording = false, modifier = Modifier.fillMaxWidth().height(72.dp))
                                        InteractiveSpectrogramCanvas(
                                            data = analyze.spectrogramA,
                                            colorMap = analyze.colorMap,
                                            maxFreqFraction = analyze.maxFreqFraction,
                                            playheadFraction = if ((analyze.selectedRecording?.durationMs ?: 0L) > 0) playback.positionMs.toFloat() / analyze.selectedRecording!!.durationMs else 0f,
                                            onTapSeekFraction = { f -> analyze.selectedRecording?.let { viewModel.seekTo((f * it.durationMs).toLong()) } },
                                            modifier = Modifier.fillMaxWidth().height(110.dp)
                                        )
                                    }
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Text("B: ${analyze.compareRecordingB?.title ?: "-"}", style = MaterialTheme.typography.labelSmall, maxLines = 1)
                                        LiveOscilloscopeWaveform(analyze.waveformB, isRecording = false, modifier = Modifier.fillMaxWidth().height(72.dp))
                                        InteractiveSpectrogramCanvas(
                                            data = analyze.spectrogramB,
                                            colorMap = analyze.colorMap,
                                            maxFreqFraction = analyze.maxFreqFraction,
                                            playheadFraction = if ((analyze.compareRecordingB?.durationMs ?: 0L) > 0) playback.positionMs.toFloat() / analyze.compareRecordingB!!.durationMs else 0f,
                                            onTapSeekFraction = { f -> analyze.compareRecordingB?.let { viewModel.seekTo((f * it.durationMs).toLong()) } },
                                            modifier = Modifier.fillMaxWidth().height(110.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // TAB 3: Timestamp Markers List & Export (Sidecar JSON + WAV Cue Chunks)
                3 -> {
                    item {
                        RecordingPickerRow("Recording", recordings, analyze.selectedRecording?.id) {
                            viewModel.selectRecordingForAnalysis(it)
                        }
                    }
                    item {
                        val rec = analyze.selectedRecording
                        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("Add Marker at Current Playhead (${AudioMath.formatDuration(playback.positionMs)})", style = MaterialTheme.typography.titleSmall)
                                OutlinedTextField(
                                    value = newMarkerLabel,
                                    onValueChange = { newMarkerLabel = it },
                                    label = { Text("Marker label") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                OutlinedTextField(
                                    value = newMarkerNote,
                                    onValueChange = { newMarkerNote = it },
                                    label = { Text("Optional note") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(
                                        enabled = rec != null,
                                        onClick = {
                                            rec?.let {
                                                viewModel.addPlaybackMarker(it.id, playback.positionMs, newMarkerLabel, newMarkerNote)
                                                newMarkerLabel = ""
                                                newMarkerNote = ""
                                            }
                                        },
                                        modifier = Modifier.weight(1f).testTag("add_playback_marker_btn")
                                    ) {
                                        Icon(Icons.Default.BookmarkAdd, null)
                                        Spacer(Modifier.width(6.dp))
                                        Text("Add Marker")
                                    }
                                    OutlinedButton(
                                        enabled = rec != null && markers.isNotEmpty(),
                                        onClick = { rec?.let { viewModel.exportMarkers(it) } },
                                        modifier = Modifier.weight(1f).testTag("export_markers_btn")
                                    ) {
                                        Icon(Icons.Default.Download, null)
                                        Spacer(Modifier.width(6.dp))
                                        Text("Export JSON/WAV")
                                    }
                                }
                            }
                        }
                    }

                    items(markers, key = { it.id }) { m ->
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    analyze.selectedRecording?.let { rec ->
                                        viewModel.playRecording(rec, m.timestampMs)
                                    }
                                }
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        "${AudioMath.formatDuration(m.timestampMs)} • ${m.label}",
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.secondary
                                    )
                                    if (m.note.isNotBlank()) {
                                        Text(m.note, style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                                IconButton(onClick = { viewModel.deleteMarker(m.id) }) {
                                    Icon(Icons.Default.Delete, contentDescription = "Delete marker")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RecordingPickerRow(
    label: String,
    recordings: List<com.example.data.RecordingEntity>,
    selectedId: Long?,
    onSelect: (com.example.data.RecordingEntity) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            recordings.forEach { rec ->
                FilterChip(
                    selected = rec.id == selectedId,
                    onClick = { onSelect(rec) },
                    label = { Text(rec.title, maxLines = 1) }
                )
            }
        }
    }
}
