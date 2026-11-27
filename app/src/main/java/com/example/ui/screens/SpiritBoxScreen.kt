package com.example.ui.screens

import android.app.Activity
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QuestionAnswer
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.spiritbox.SpiritBandMode
import com.example.spiritbox.StaticNoiseType
import com.example.spiritbox.SweepDirection
import com.example.ui.components.LiveOscilloscopeWaveform
import com.example.ui.components.LiveSpectrumCanvas
import com.example.ui.viewmodel.SpiritBoxViewModel
import com.example.util.AudioMath
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpiritBoxScreen(
    hasMicPermission: Boolean,
    onRequestMicPermission: () -> Unit,
    onShowSnackbar: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SpiritBoxViewModel = hiltViewModel()
) {
    val live by viewModel.liveState.collectAsStateWithLifecycle()
    val liveFft by viewModel.liveFft.collectAsStateWithLifecycle()
    val cfg by viewModel.config.collectAsStateWithLifecycle()
    val sessions by viewModel.sessions.collectAsStateWithLifecycle()

    var showQuestionDialog by remember { mutableStateOf(false) }
    var questionText by remember { mutableStateOf("") }
    var showStreamsSheet by remember { mutableStateOf(false) }
    var newStreamUrl by remember { mutableStateOf("") }
    var showSessionsSheet by remember { mutableStateOf(false) }
    var showFftInsteadOfWave by remember { mutableStateOf(false) }

    val speechLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { res ->
        if (res.resultCode == Activity.RESULT_OK) {
            val spoken = res.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
            if (!spoken.isNullOrBlank()) {
                viewModel.addQuestion(spoken, inputMode = "SPEECH")
            }
        }
    }

    LaunchedEffect(Unit) {
        viewModel.messages.collect { onShowSnackbar(it) }
    }

    val animatedDial by animateFloatAsState(
        targetValue = live.dialFraction.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = cfg.scanSpeedMs.coerceIn(45, 220)),
        label = "spirit_box_dial"
    )

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text(
                text = stringResource(R.string.spirit_box_fun_line),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .testTag("spirit_box_fun_text")
            )
        }

        // Animated FM/AM Dial + Live Waveform/FFT + Hop Counter + Primary Transport
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "${cfg.bandMode.displayName} SIMULATED SWEEP",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = live.currentFrequencyLabel,
                                style = MaterialTheme.typography.headlineMedium,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.testTag("spirit_box_freq_label")
                            )
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = if (cfg.recorderEnabled) {
                                    MaterialTheme.colorScheme.error.copy(alpha = 0.16f)
                                } else {
                                    MaterialTheme.colorScheme.surfaceContainerHighest
                                },
                                modifier = Modifier.testTag("spirit_box_rec_badge")
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .clip(CircleShape)
                                            .background(
                                                if (cfg.recorderEnabled) MaterialTheme.colorScheme.error
                                                else MaterialTheme.colorScheme.outline
                                            )
                                    )
                                    Text(
                                        text = if (cfg.recorderEnabled) "REC" else "REC OFF",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = if (cfg.recorderEnabled) MaterialTheme.colorScheme.error
                                        else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = "HOPS: ${live.hopCount}",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.secondary,
                                modifier = Modifier.testTag("spirit_box_hop_counter")
                            )
                            Text(
                                text = AudioMath.formatDuration(live.elapsedMs),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    // Clearly visible Recorder ON/OFF bar (live-toggleable before or during sweep)
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = if (cfg.recorderEnabled) {
                            MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f)
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerHigh
                        },
                        border = BorderStroke(
                            1.dp,
                            if (cfg.recorderEnabled) MaterialTheme.colorScheme.error.copy(alpha = 0.55f)
                            else MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
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
                                    Box(
                                        modifier = Modifier
                                            .size(10.dp)
                                            .clip(CircleShape)
                                            .background(
                                                if (cfg.recorderEnabled) MaterialTheme.colorScheme.error
                                                else MaterialTheme.colorScheme.outline
                                            )
                                    )
                                    Column {
                                        Text(
                                            text = if (cfg.recorderEnabled) {
                                                "${stringResource(R.string.spirit_box_recorder_title)} • REC"
                                            } else {
                                                "${stringResource(R.string.spirit_box_recorder_title)} • OFF"
                                            },
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = if (cfg.recorderEnabled) MaterialTheme.colorScheme.error
                                            else MaterialTheme.colorScheme.onSurface
                                        )
                                        Text(
                                            text = if (cfg.recorderEnabled) {
                                                stringResource(R.string.spirit_box_recorder_desc)
                                            } else {
                                                stringResource(R.string.spirit_box_recording_off_notice)
                                            },
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.testTag("spirit_box_recorder_status_text")
                                        )
                                    }
                                }
                                Switch(
                                    checked = cfg.recorderEnabled,
                                    onCheckedChange = { enabled ->
                                        if (enabled && cfg.recordMicTrack && !hasMicPermission) {
                                            onRequestMicPermission()
                                        }
                                        viewModel.setRecorderEnabled(enabled)
                                    },
                                    modifier = Modifier.testTag("spirit_box_recorder_switch")
                                )
                            }
                        }
                    }

                    // Animated Dial & Needle
                    AnimatedRadioDialCanvas(
                        dialFraction = animatedDial,
                        rangeStart = cfg.rangeStartFraction,
                        rangeEnd = cfg.rangeEndFraction,
                        bandMode = cfg.bandMode,
                        isRunning = live.isRunning,
                        isHeld = live.isHeld,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(74.dp)
                            .testTag("spirit_box_dial_canvas")
                    )

                    // Active source readout (respecting Blind Test Mode!)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            if (cfg.blindTestMode && live.isRunning) {
                                Icon(
                                    Icons.Default.VisibilityOff,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.tertiary,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(Modifier.width(6.dp))
                            }
                            Text(
                                text = if (cfg.blindTestMode && live.isRunning) {
                                    stringResource(R.string.spirit_box_blind_hidden)
                                } else {
                                    "Source: ${live.activeSourceLabel}${if (live.activeSourceDetail.isNotBlank()) " • ${live.activeSourceDetail}" else ""}"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        FilterChip(
                            selected = showFftInsteadOfWave,
                            onClick = { showFftInsteadOfWave = !showFftInsteadOfWave },
                            label = { Text(if (showFftInsteadOfWave) "FFT" else "Wave") }
                        )
                    }

                    // Live Waveform or FFT Spectrum
                    if (showFftInsteadOfWave) {
                        LiveSpectrumCanvas(
                            fft = liveFft,
                            logScale = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        LiveOscilloscopeWaveform(
                            samples = live.waveform,
                            isRecording = live.isRunning && !live.isHeld,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(100.dp)
                        )
                    }

                    // Big Start/Stop + Hold + Mark + Question Row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedButton(
                            onClick = { viewModel.toggleHold() },
                            enabled = live.isRunning,
                            modifier = Modifier.testTag("spirit_box_hold_btn")
                        ) {
                            Icon(if (live.isHeld) Icons.Default.PlayArrow else Icons.Default.Pause, null)
                            Spacer(Modifier.width(4.dp))
                            Text(if (live.isHeld) stringResource(R.string.spirit_box_resume) else stringResource(R.string.spirit_box_hold))
                        }

                        Button(
                            onClick = {
                                if (live.isRunning) {
                                    viewModel.stopSpiritBox()
                                } else {
                                    if (cfg.recorderEnabled && cfg.recordMicTrack && !hasMicPermission) {
                                        onRequestMicPermission()
                                    } else {
                                        viewModel.startSpiritBox()
                                    }
                                }
                            },
                            shape = CircleShape,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (live.isRunning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                            ),
                            modifier = Modifier
                                .size(74.dp)
                                .testTag("spirit_box_start_stop_btn"),
                            contentPadding = PaddingValues(0.dp)
                        ) {
                            Icon(
                                imageVector = if (live.isRunning) Icons.Default.Stop else Icons.Default.Radio,
                                contentDescription = if (live.isRunning) stringResource(R.string.spirit_box_stop) else stringResource(R.string.spirit_box_start),
                                modifier = Modifier.size(34.dp)
                            )
                        }

                        FilledTonalButton(
                            onClick = { viewModel.addMarker() },
                            enabled = live.isRunning,
                            modifier = Modifier.testTag("spirit_box_mark_btn")
                        ) {
                            Icon(Icons.Default.BookmarkAdd, null)
                            Spacer(Modifier.width(4.dp))
                            Text("${stringResource(R.string.spirit_box_mark)} (${live.markersCount})")
                        }
                    }

                    // Question Button + Sessions Log Button
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { showQuestionDialog = true },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("spirit_box_question_btn")
                        ) {
                            Icon(Icons.Default.QuestionAnswer, null)
                            Spacer(Modifier.width(6.dp))
                            Text("${stringResource(R.string.spirit_box_question)} (${live.questions.size})")
                        }
                        OutlinedButton(
                            onClick = { showSessionsSheet = true },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("spirit_box_sessions_btn")
                        ) {
                            Icon(Icons.Default.History, null)
                            Spacer(Modifier.width(6.dp))
                            Text("${stringResource(R.string.spirit_box_sessions)} (${sessions.size})")
                        }
                    }
                    if (live.isRunning) {
                        Text(
                            text = "Tip: Press Volume Up/Down to add an instant timestamp marker.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.align(Alignment.CenterHorizontally)
                        )
                    }
                }
            }
        }

        // Blind Test Reveal Card (shown after stopping a Blind Test session)
        if (live.showBlindReveal && live.blindTestRevealHops.isNotEmpty()) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("Blind Test Source Reveal (${live.blindTestRevealHops.size} hops)", style = MaterialTheme.typography.titleSmall)
                            TextButton(onClick = { viewModel.dismissBlindReveal() }) { Text("Hide") }
                        }
                        live.blindTestRevealHops.takeLast(12).forEach { hop ->
                            Text(
                                text = "#${hop.hopIndex} [${AudioMath.formatDuration(hop.timestampMs)}] ${hop.freqLabel} → ${hop.actualSource} ${if (hop.detail.isNotBlank()) "(${hop.detail})" else ""}",
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }

        // Selectable Sources Card
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Sweep Sources", style = MaterialTheme.typography.titleSmall)

                    SourceToggleRow(
                        title = "Internet Radio Streams (${cfg.streamUrls.size})",
                        subtitle = "Pre-prepared ExoPlayer pool jumping across streams",
                        checked = cfg.enableInternetRadio,
                        onCheckedChange = { v -> viewModel.updateConfig { it.copy(enableInternetRadio = v) } }
                    )
                    OutlinedButton(
                        onClick = { showStreamsSheet = true },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("spirit_box_edit_streams_btn")
                    ) {
                        Text("Edit Radio Stream URLs (${cfg.streamUrls.size})")
                    }

                    SourceToggleRow(
                        title = "Local Audio Fragments",
                        subtitle = "Random short slices from your recordings & imported WAVs",
                        checked = cfg.enableLocalAudio,
                        onCheckedChange = { v -> viewModel.updateConfig { it.copy(enableLocalAudio = v) } }
                    )

                    SourceToggleRow(
                        title = "Generated Static",
                        subtitle = "Continuous white/pink/brown or tuning static",
                        checked = cfg.enableGeneratedStatic,
                        onCheckedChange = { v -> viewModel.updateConfig { it.copy(enableGeneratedStatic = v) } }
                    )
                    if (cfg.enableGeneratedStatic) {
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            StaticNoiseType.entries.forEach { st ->
                                FilterChip(
                                    selected = cfg.staticNoiseType == st,
                                    onClick = { viewModel.updateConfig { it.copy(staticNoiseType = st) } },
                                    label = { Text(st.displayName) }
                                )
                            }
                            FilterChip(
                                selected = cfg.staticCrackleEnabled,
                                onClick = { viewModel.updateConfig { it.copy(staticCrackleEnabled = !it.staticCrackleEnabled) } },
                                label = { Text("Crackle") }
                            )
                        }
                    }

                    SourceToggleRow(
                        title = "Offline Word Fragments (TTS EN/HI)",
                        subtitle = "Optional low-volume spoken words from offline word list",
                        checked = cfg.enableOfflineTts,
                        onCheckedChange = { v -> viewModel.updateConfig { it.copy(enableOfflineTts = v) } }
                    )
                }
            }
        }

        // Sweep Controls Card (Band, Direction, Speed 50-2000ms, Jitter, Range)
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Sweep & Dial Controls", style = MaterialTheme.typography.titleSmall)

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SpiritBandMode.entries.forEach { band ->
                            FilterChip(
                                selected = cfg.bandMode == band,
                                onClick = { viewModel.updateConfig { it.copy(bandMode = band) } },
                                label = { Text("${band.displayName} (${band.minFreq.toInt()}–${band.maxFreq.toInt()} ${band.unit})") }
                            )
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        SweepDirection.entries.forEach { dir ->
                            FilterChip(
                                selected = cfg.sweepDirection == dir,
                                onClick = { viewModel.updateConfig { it.copy(sweepDirection = dir) } },
                                label = { Text(dir.displayName) }
                            )
                        }
                    }

                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Scan Speed", style = MaterialTheme.typography.bodySmall)
                        Text("${cfg.scanSpeedMs} ms / hop", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    }
                    Slider(
                        value = cfg.scanSpeedMs.toFloat(),
                        onValueChange = { v -> viewModel.updateConfig { it.copy(scanSpeedMs = v.toInt()) } },
                        valueRange = 50f..2000f,
                        modifier = Modifier.testTag("spirit_box_speed_slider")
                    )

                    SourceToggleRow(
                        title = "Random Hop Jitter",
                        subtitle = "Varies hop interval ±35% around scan speed",
                        checked = cfg.jitterEnabled,
                        onCheckedChange = { v -> viewModel.updateConfig { it.copy(jitterEnabled = v) } }
                    )

                    Text(
                        "Sweep Range: ${(cfg.rangeStartFraction * 100).toInt()}% – ${(cfg.rangeEndFraction * 100).toInt()}%",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Slider(
                            value = cfg.rangeStartFraction,
                            onValueChange = { s ->
                                viewModel.updateConfig { it.copy(rangeStartFraction = s.coerceAtMost(it.rangeEndFraction - 0.1f)) }
                            },
                            valueRange = 0f..0.85f,
                            modifier = Modifier.weight(1f)
                        )
                        Slider(
                            value = cfg.rangeEndFraction,
                            onValueChange = { e ->
                                viewModel.updateConfig { it.copy(rangeEndFraction = e.coerceAtLeast(it.rangeStartFraction + 0.1f)) }
                            },
                            valueRange = 0.15f..1f,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }

        // Mixing, Filters, Blind Test & Session Recording Card
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Mix, Voice Filter & Session Recording", style = MaterialTheme.typography.titleSmall)

                    LabeledSlider("Static Level", cfg.staticLevel, 0f..1f, "${(cfg.staticLevel * 100).toInt()}%") { v ->
                        viewModel.updateConfig { it.copy(staticLevel = v) }
                    }
                    LabeledSlider("Source Level", cfg.sourceLevel, 0f..1f, "${(cfg.sourceLevel * 100).toInt()}%") { v ->
                        viewModel.updateConfig { it.copy(sourceLevel = v) }
                    }
                    LabeledSlider("Hop Crossfade", cfg.crossfadeMs.toFloat(), 0f..100f, "${cfg.crossfadeMs} ms") { v ->
                        viewModel.updateConfig { it.copy(crossfadeMs = v.toInt()) }
                    }
                    LabeledSlider("Voice HPF", cfg.voiceHpfHz, 80f..800f, "${cfg.voiceHpfHz.toInt()} Hz") { v ->
                        viewModel.updateConfig { it.copy(voiceHpfHz = v) }
                    }
                    LabeledSlider("Voice LPF", cfg.voiceLpfHz, 1500f..8000f, "${cfg.voiceLpfHz.toInt()} Hz") { v ->
                        viewModel.updateConfig { it.copy(voiceLpfHz = v) }
                    }
                    LabeledSlider("Safe Output", cfg.outputVolume, 0.05f..0.85f, "${(cfg.outputVolume * 100).toInt()}% (Limited)") { v ->
                        viewModel.updateConfig { it.copy(outputVolume = v) }
                    }

                    SourceToggleRow(
                        title = stringResource(R.string.spirit_box_blind_mode),
                        subtitle = "Hides active source during session; reveals real log at the end",
                        checked = cfg.blindTestMode,
                        onCheckedChange = { v -> viewModel.updateConfig { it.copy(blindTestMode = v) } }
                    )

                    SourceToggleRow(
                        title = stringResource(R.string.spirit_box_record_sweep),
                        subtitle = if (cfg.recorderEnabled) {
                            "Saves sweep mix WAV to your chosen Save Location & Library"
                        } else {
                            stringResource(R.string.spirit_box_recording_off_notice)
                        },
                        checked = cfg.recordSessionMix,
                        enabled = cfg.recorderEnabled,
                        onCheckedChange = { v -> viewModel.setRecordSweepOutput(v) },
                        switchTestTag = "spirit_box_record_sweep_switch"
                    )

                    SourceToggleRow(
                        title = stringResource(R.string.spirit_box_record_mic_track),
                        subtitle = "Requests microphone permission only when Recorder & this option are ON",
                        checked = cfg.recordMicTrack,
                        enabled = cfg.recorderEnabled,
                        onCheckedChange = { v ->
                            if (v && cfg.recorderEnabled && !hasMicPermission) {
                                onRequestMicPermission()
                            }
                            viewModel.setRecordMicTrack(v)
                        },
                        switchTestTag = "spirit_box_record_mic_switch"
                    )
                }
            }
        }
    }

    // Question Dialog (Text + Speech Recognition)
    if (showQuestionDialog) {
        AlertDialog(
            onDismissRequest = { showQuestionDialog = false },
            title = { Text("Log Session Question") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = questionText,
                        onValueChange = { questionText = it },
                        label = { Text("Type your question…") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedButton(
                        onClick = {
                            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                                putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak your question")
                            }
                            runCatching { speechLauncher.launch(intent) }
                                .onFailure { onShowSnackbar("Speech recognition not available on this device.") }
                            showQuestionDialog = false
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Mic, null)
                        Spacer(Modifier.width(6.dp))
                        Text("Dictate via Speech Recognition")
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    viewModel.addQuestion(questionText, "TEXT")
                    questionText = ""
                    showQuestionDialog = false
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { showQuestionDialog = false }) { Text("Cancel") }
            }
        )
    }

    // Editable Radio Streams Bottom Sheet
    if (showStreamsSheet) {
        ModalBottomSheet(onDismissRequest = { showStreamsSheet = false }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("Internet Radio Streams", style = MaterialTheme.typography.titleLarge)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = newStreamUrl,
                        onValueChange = { newStreamUrl = it },
                        label = { Text("https://stream-url") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    Button(onClick = {
                        viewModel.addStreamUrl(newStreamUrl)
                        newStreamUrl = ""
                    }) {
                        Icon(Icons.Default.Add, contentDescription = "Add stream")
                    }
                }
                cfg.streamUrls.forEach { url ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = url,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = { viewModel.removeStreamUrl(url) }) {
                            Icon(Icons.Default.Delete, contentDescription = "Remove stream")
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }

    // Session History & Export Sheet
    if (showSessionsSheet) {
        val fmt = remember { SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()) }
        ModalBottomSheet(onDismissRequest = { showSessionsSheet = false }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("Spirit Box Session Logs (${sessions.size})", style = MaterialTheme.typography.titleLarge)
                if (sessions.isEmpty()) {
                    Text("No sessions logged yet. Start and stop a sweep to create a log.", style = MaterialTheme.typography.bodyMedium)
                } else {
                    sessions.take(15).forEach { s ->
                        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text(
                                        "${fmt.format(Date(s.startedAtMillis))} • ${s.bandMode} (${s.hopCount} hops)",
                                        style = MaterialTheme.typography.titleSmall
                                    )
                                    Text(AudioMath.formatDurationShort(s.durationMs), style = MaterialTheme.typography.labelSmall)
                                }
                                Text(
                                    "Sources: ${s.sourcesUsed.ifBlank { "Static" }} • ${s.scanSpeedMs}ms ${s.direction}${if (s.blindTestEnabled) " • Blind" else ""}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton(onClick = { viewModel.exportSession(s, asJson = false, shareAfter = true) }) {
                                        Text("Export TXT")
                                    }
                                    OutlinedButton(onClick = { viewModel.exportSession(s, asJson = true, shareAfter = true) }) {
                                        Text("Export JSON")
                                    }
                                    Spacer(Modifier.weight(1f))
                                    IconButton(onClick = { viewModel.deleteSession(s.id) }) {
                                        Icon(Icons.Default.Delete, contentDescription = "Delete session")
                                    }
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun AnimatedRadioDialCanvas(
    dialFraction: Float,
    rangeStart: Float,
    rangeEnd: Float,
    bandMode: SpiritBandMode,
    isRunning: Boolean,
    isHeld: Boolean,
    modifier: Modifier = Modifier
) {
    val primary = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.secondary
    val errorCol = MaterialTheme.colorScheme.error
    val trackBg = MaterialTheme.colorScheme.surfaceContainerHigh
    val tickCol = MaterialTheme.colorScheme.onSurfaceVariant

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(trackBg)
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height

            // Active sweep range highlight
            val rStartX = w * rangeStart.coerceIn(0f, 1f)
            val rEndX = w * rangeEnd.coerceIn(0f, 1f)
            drawRect(
                color = primary.copy(alpha = 0.14f),
                topLeft = Offset(rStartX, 4f),
                size = androidx.compose.ui.geometry.Size((rEndX - rStartX).coerceAtLeast(4f), h - 8f)
            )

            // Scale ticks
            val tickCount = if (bandMode == SpiritBandMode.FM) 20 else 24
            for (i in 0..tickCount) {
                val x = w * (i.toFloat() / tickCount.toFloat())
                val major = i % 5 == 0
                val topY = if (major) h * 0.18f else h * 0.36f
                drawLine(
                    color = if (major) tickCol.copy(alpha = 0.85f) else tickCol.copy(alpha = 0.4f),
                    start = Offset(x, topY),
                    end = Offset(x, h * 0.78f),
                    strokeWidth = if (major) 2.5f else 1.5f
                )
            }

            // Glowing needle
            val needleX = w * dialFraction.coerceIn(0f, 1f)
            val needleColor = when {
                !isRunning -> primary.copy(alpha = 0.6f)
                isHeld -> secondary
                else -> errorCol
            }
            drawLine(
                color = needleColor.copy(alpha = 0.35f),
                start = Offset(needleX, 0f),
                end = Offset(needleX, h),
                strokeWidth = 10f,
                cap = StrokeCap.Round
            )
            drawLine(
                color = needleColor,
                start = Offset(needleX, 0f),
                end = Offset(needleX, h),
                strokeWidth = 3.5f,
                cap = StrokeCap.Round
            )
        }
    }
}

@Composable
private fun SourceToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
    switchTestTag: String? = null
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = if (enabled) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (enabled) 1f else 0.6f)
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            modifier = if (switchTestTag != null) Modifier.testTag(switchTestTag) else Modifier
        )
    }
}

@Composable
private fun LabeledSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    valueLabel: String,
    onValueChange: (Float) -> Unit
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(90.dp))
        Slider(value = value.coerceIn(range.start, range.endInclusive), onValueChange = onValueChange, valueRange = range, modifier = Modifier.weight(1f))
        Text(valueLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.width(74.dp))
    }
}
