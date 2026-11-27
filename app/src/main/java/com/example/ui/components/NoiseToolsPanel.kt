package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.EdlOpType
import com.example.data.NoiseType
import com.example.data.OneTapNoiseTool
import com.example.ui.viewmodel.EditorUiState
import com.example.ui.viewmodel.EditorViewModel
import com.example.util.AudioMath
import java.util.Locale

private data class NumericDbPrompt(
    val title: String,
    val currentValue: Float,
    val minValue: Float,
    val maxValue: Float,
    val unit: String,
    val onConfirm: (Float) -> Unit
)

@Composable
fun NoiseToolsPanel(
    state: EditorUiState,
    beforeAfterDb: Pair<Float, Float>,
    viewModel: EditorViewModel,
    modifier: Modifier = Modifier
) {
    var activeSubTab by remember { mutableIntStateOf(0) }
    var dbPrompt by remember { mutableStateOf<NumericDbPrompt?>(null) }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Header + Common Controls (A/B Bypass, Noise Only, Reset)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.noise_tools_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                TextButton(
                    onClick = { viewModel.resetNoiseTools() },
                    modifier = Modifier.testTag("noise_reset_btn")
                ) {
                    Icon(Icons.Default.RestartAlt, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.noise_reset))
                }
            }

            // Common A/B Bypass + Noise Only listen toggles
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = state.noiseBypass,
                    onClick = { viewModel.toggleNoiseBypass() },
                    label = { Text(if (state.noiseBypass) "BYPASSED" else stringResource(R.string.noise_bypass)) },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("noise_bypass_chip")
                )
                FilterChip(
                    selected = state.noiseOnlyMode,
                    onClick = { viewModel.toggleNoiseOnlyMode() },
                    leadingIcon = { Icon(Icons.Default.Hearing, contentDescription = null) },
                    label = { Text(stringResource(R.string.noise_only_listen)) },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("noise_only_chip")
                )
            }

            // Before / After dBFS Meter
            BeforeAfterNoiseMeterRow(
                beforeDb = beforeAfterDb.first,
                afterDb = beforeAfterDb.second
            )

            // Sub-tabs: Remove | Add Noise | Level Control
            TabRow(selectedTabIndex = activeSubTab) {
                listOf(
                    stringResource(R.string.noise_tab_remove),
                    stringResource(R.string.noise_tab_add),
                    stringResource(R.string.noise_tab_level)
                ).forEachIndexed { idx, title ->
                    Tab(
                        selected = activeSubTab == idx,
                        onClick = { activeSubTab = idx },
                        text = { Text(title, maxLines = 1) },
                        modifier = Modifier.testTag("noise_subtab_$idx")
                    )
                }
            }

            when (activeSubTab) {
                // 1) NOISE REMOVAL
                0 -> {
                    NoiseRemovalSection(
                        state = state,
                        viewModel = viewModel,
                        onOpenNumericEntry = { dbPrompt = it }
                    )
                }
                // 2) NOISE ADD
                1 -> {
                    NoiseAddSection(
                        state = state,
                        viewModel = viewModel,
                        onOpenNumericEntry = { dbPrompt = it }
                    )
                }
                // 3) NOISE LEVEL CONTROL
                2 -> {
                    NoiseLevelSection(
                        state = state,
                        viewModel = viewModel,
                        onOpenNumericEntry = { dbPrompt = it }
                    )
                }
            }
        }
    }

    // Numeric dB Entry Dialog
    dbPrompt?.let { prompt ->
        var textVal by remember(prompt) {
            mutableStateOf(String.format(Locale.US, "%.1f", prompt.currentValue))
        }
        AlertDialog(
            onDismissRequest = { dbPrompt = null },
            title = { Text("Enter ${prompt.title} (${prompt.unit})") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "Range: ${prompt.minValue.toInt()} to ${prompt.maxValue.toInt()} ${prompt.unit}",
                        style = MaterialTheme.typography.bodySmall
                    )
                    OutlinedTextField(
                        value = textVal,
                        onValueChange = { textVal = it },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    val parsed = textVal.toFloatOrNull()
                    if (parsed != null) {
                        prompt.onConfirm(parsed.coerceIn(prompt.minValue, prompt.maxValue))
                    }
                    dbPrompt = null
                }) { Text("Set") }
            },
            dismissButton = {
                TextButton(onClick = { dbPrompt = null }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun NoiseRemovalSection(
    state: EditorUiState,
    viewModel: EditorViewModel,
    onOpenNumericEntry: (NumericDbPrompt) -> Unit
) {
    val rem = state.noiseRemoval
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // Noise Profile Learning
        Text("Noise Profile", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        if (state.isLearningNoiseProfile) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = { viewModel.learnNoiseProfileFromSelection() },
                modifier = Modifier
                    .weight(1f)
                    .testTag("noise_learn_selection_btn")
            ) {
                Text(stringResource(R.string.noise_learn_selection), maxLines = 1)
            }
            OutlinedButton(
                onClick = { viewModel.autoDetectQuietestNoiseProfile() },
                modifier = Modifier
                    .weight(1f)
                    .testTag("noise_auto_detect_btn")
            ) {
                Text(stringResource(R.string.noise_auto_detect), maxLines = 1)
            }
        }
        state.noiseProfile?.let { prof ->
            Text(
                text = "Learned profile: ${AudioMath.formatDuration(prof.learnedStartMs)} – ${AudioMath.formatDuration(prof.learnedEndMs)} (${prof.fftSize}-pt FFT)",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.secondary
            )
        }

        // Enable live spectral gate toggle
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Spectral Gating / Subtraction", style = MaterialTheme.typography.titleSmall)
            Switch(
                checked = rem.enabled,
                onCheckedChange = { enabled -> viewModel.updateNoiseRemoval { it.copy(enabled = enabled) } },
                modifier = Modifier.testTag("noise_removal_enable_switch")
            )
        }

        // Reduction Amount (0-40 dB) with tappable numeric entry
        NumericSliderRow(
            label = "Reduction",
            value = rem.reductionDb,
            valueRange = 0f..40f,
            displayText = String.format(Locale.US, "%.1f dB", rem.reductionDb),
            onValueChange = { v -> viewModel.updateNoiseRemoval { it.copy(enabled = true, reductionDb = v) } },
            onBadgeClick = {
                onOpenNumericEntry(
                    NumericDbPrompt("Reduction Amount", rem.reductionDb, 0f, 40f, "dB") { v ->
                        viewModel.updateNoiseRemoval { it.copy(enabled = true, reductionDb = v) }
                    }
                )
            }
        )

        // Sensitivity (0.5 - 3.0)
        NumericSliderRow(
            label = "Sensitivity",
            value = rem.sensitivity,
            valueRange = 0.5f..3.0f,
            displayText = String.format(Locale.US, "%.2fx", rem.sensitivity),
            onValueChange = { v -> viewModel.updateNoiseRemoval { it.copy(enabled = true, sensitivity = v) } },
            onBadgeClick = {
                onOpenNumericEntry(
                    NumericDbPrompt("Sensitivity", rem.sensitivity, 0.5f, 3.0f, "x") { v ->
                        viewModel.updateNoiseRemoval { it.copy(enabled = true, sensitivity = v) }
                    }
                )
            }
        )

        // Frequency Smoothing (0 - 8 bins)
        NumericSliderRow(
            label = "Freq Smooth",
            value = rem.freqSmoothingBins.toFloat(),
            valueRange = 0f..8f,
            displayText = "${rem.freqSmoothingBins} bins",
            onValueChange = { v -> viewModel.updateNoiseRemoval { it.copy(enabled = true, freqSmoothingBins = v.toInt()) } },
            onBadgeClick = null
        )

        // Keep Faint Sounds toggle
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.noise_keep_faint), style = MaterialTheme.typography.bodyMedium)
                Text(
                    "Soft-knee spectral preservation protects quiet voices & weak signals.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = rem.keepFaintSounds,
                onCheckedChange = { keep -> viewModel.updateNoiseRemoval { it.copy(keepFaintSounds = keep) } },
                modifier = Modifier.testTag("noise_keep_faint_switch")
            )
        }

        Button(
            onClick = { viewModel.addOperation(EdlOpType.NOISE_REMOVAL) },
            modifier = Modifier
                .fillMaxWidth()
                .testTag("commit_noise_removal_btn")
        ) {
            Icon(Icons.Default.AutoFixHigh, contentDescription = null)
            Spacer(Modifier.width(6.dp))
            Text("Commit Noise Removal to EDL")
        }

        Spacer(Modifier.height(4.dp))
        Text("One-Tap Cleanup Tools", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            val oneTapItems = listOf(
                OneTapNoiseTool.HISS_REMOVER to "Hiss Remover",
                OneTapNoiseTool.HUM_REMOVER_50HZ to "Hum 50 Hz",
                OneTapNoiseTool.HUM_REMOVER_60HZ to "Hum 60 Hz",
                OneTapNoiseTool.CLICK_POP_REMOVER to "Click/Pop",
                OneTapNoiseTool.WIND_NOISE_CUT to "Wind Cut"
            )
            oneTapItems.forEach { (tool, label) ->
                FilterChip(
                    selected = state.oneTapNoiseTool == tool,
                    onClick = { viewModel.selectOneTapNoiseTool(tool, commitToEdl = true) },
                    label = { Text(label) },
                    modifier = Modifier.testTag("onetap_${tool.name.lowercase()}")
                )
            }
        }
    }
}

@Composable
private fun NoiseAddSection(
    state: EditorUiState,
    viewModel: EditorViewModel,
    onOpenNumericEntry: (NumericDbPrompt) -> Unit
) {
    val add = state.noiseAdd
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Add Generated / Room Tone Noise", style = MaterialTheme.typography.titleSmall)
            Switch(
                checked = add.enabled,
                onCheckedChange = { en -> viewModel.updateNoiseAdd { it.copy(enabled = en) } },
                modifier = Modifier.testTag("noise_add_enable_switch")
            )
        }

        // Noise Type chips
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            val types = listOf(
                NoiseType.WHITE to "White",
                NoiseType.PINK to "Pink",
                NoiseType.BROWN to "Brown",
                NoiseType.RAIN to "Rain",
                NoiseType.WIND to "Wind",
                NoiseType.FAN_ROOM_TONE to "Fan/Room",
                NoiseType.STATIC to "Static",
                NoiseType.LEARNED_ROOM_TONE to "Learned Room Tone"
            )
            types.forEach { (type, label) ->
                FilterChip(
                    selected = add.noiseType == type,
                    onClick = { viewModel.updateNoiseAdd { it.copy(enabled = true, noiseType = type) } },
                    label = { Text(label) }
                )
            }
        }
        if (add.noiseType == NoiseType.LEARNED_ROOM_TONE && state.noiseProfile == null) {
            Text(
                "Tip: Learn a noise profile in the Remove tab first to synthesize matching room tone.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.secondary
            )
        }

        // Scope: Whole File vs Selection Only
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = !add.applyToSelectionOnly,
                onClick = { viewModel.updateNoiseAdd { it.copy(applyToSelectionOnly = false) } },
                label = { Text("Whole File") },
                modifier = Modifier.weight(1f)
            )
            FilterChip(
                selected = add.applyToSelectionOnly,
                onClick = { viewModel.updateNoiseAdd { it.copy(applyToSelectionOnly = true) } },
                label = { Text("Selection Only") },
                modifier = Modifier.weight(1f)
            )
        }

        // Fixed SNR mode toggle
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Fixed SNR Mode", style = MaterialTheme.typography.bodyMedium)
            Switch(
                checked = add.fixedSnrMode,
                onCheckedChange = { fixed -> viewModel.updateNoiseAdd { it.copy(fixedSnrMode = fixed) } }
            )
        }

        if (add.fixedSnrMode) {
            NumericSliderRow(
                label = "Target SNR",
                value = add.targetSnrDb,
                valueRange = 0f..40f,
                displayText = String.format(Locale.US, "%.1f dB", add.targetSnrDb),
                onValueChange = { v -> viewModel.updateNoiseAdd { it.copy(enabled = true, targetSnrDb = v) } },
                onBadgeClick = {
                    onOpenNumericEntry(
                        NumericDbPrompt("Target SNR", add.targetSnrDb, 0f, 40f, "dB") { v ->
                            viewModel.updateNoiseAdd { it.copy(enabled = true, targetSnrDb = v) }
                        }
                    )
                }
            )
        } else {
            NumericSliderRow(
                label = "Volume",
                value = add.volumeDb,
                valueRange = -60f..0f,
                displayText = String.format(Locale.US, "%.1f dBFS", add.volumeDb),
                onValueChange = { v -> viewModel.updateNoiseAdd { it.copy(enabled = true, volumeDb = v) } },
                onBadgeClick = {
                    onOpenNumericEntry(
                        NumericDbPrompt("Added Noise Volume", add.volumeDb, -60f, 0f, "dBFS") { v ->
                            viewModel.updateNoiseAdd { it.copy(enabled = true, volumeDb = v) }
                        }
                    )
                }
            )
        }

        // Optional High-Pass & Low-Pass filters on added noise
        NumericSliderRow(
            label = "Noise HPF",
            value = add.hpfHz,
            valueRange = 0f..4000f,
            displayText = if (add.hpfHz <= 20f) "Off" else "${add.hpfHz.toInt()} Hz",
            onValueChange = { v -> viewModel.updateNoiseAdd { it.copy(hpfHz = v) } },
            onBadgeClick = null
        )
        NumericSliderRow(
            label = "Noise LPF",
            value = add.lpfHz,
            valueRange = 500f..20000f,
            displayText = if (add.lpfHz >= 19500f) "Off" else "${add.lpfHz.toInt()} Hz",
            onValueChange = { v -> viewModel.updateNoiseAdd { it.copy(lpfHz = v) } },
            onBadgeClick = null
        )

        // Fade In / Out ms
        NumericSliderRow(
            label = "Fade In",
            value = add.fadeInMs.toFloat(),
            valueRange = 0f..2000f,
            displayText = "${add.fadeInMs} ms",
            onValueChange = { v -> viewModel.updateNoiseAdd { it.copy(fadeInMs = v.toLong()) } },
            onBadgeClick = null
        )
        NumericSliderRow(
            label = "Fade Out",
            value = add.fadeOutMs.toFloat(),
            valueRange = 0f..2000f,
            displayText = "${add.fadeOutMs} ms",
            onValueChange = { v -> viewModel.updateNoiseAdd { it.copy(fadeOutMs = v.toLong()) } },
            onBadgeClick = null
        )

        Button(
            onClick = { viewModel.addOperation(EdlOpType.NOISE_ADD) },
            modifier = Modifier
                .fillMaxWidth()
                .testTag("commit_noise_add_btn")
        ) {
            Text("Commit Added Noise to EDL")
        }
    }
}

@Composable
private fun NoiseLevelSection(
    state: EditorUiState,
    viewModel: EditorViewModel,
    onOpenNumericEntry: (NumericDbPrompt) -> Unit
) {
    val lvl = state.noiseLevel
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("Spectral Noise Level Control", style = MaterialTheme.typography.titleSmall)
                Text(
                    "output = clean + noise × gain (spectral separation)",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = lvl.enabled,
                onCheckedChange = { en -> viewModel.updateNoiseLevel { it.copy(enabled = en) } },
                modifier = Modifier.testTag("noise_level_enable_switch")
            )
        }

        // Existing Noise Slider (-40 dB .. 0 dB .. +12 dB)
        NumericSliderRow(
            label = "Existing",
            value = lvl.existingNoiseDb,
            valueRange = -40f..12f,
            displayText = String.format(Locale.US, "%+.1f dB", lvl.existingNoiseDb),
            onValueChange = { v -> viewModel.updateNoiseLevel { it.copy(enabled = true, existingNoiseDb = v) } },
            onBadgeClick = {
                onOpenNumericEntry(
                    NumericDbPrompt("Existing Noise Gain", lvl.existingNoiseDb, -40f, 12f, "dB") { v ->
                        viewModel.updateNoiseLevel { it.copy(enabled = true, existingNoiseDb = v) }
                    }
                )
            }
        )

        // Added Noise Slider (-60 dBFS .. 0 dBFS)
        NumericSliderRow(
            label = "Added",
            value = lvl.addedNoiseDb,
            valueRange = -60f..0f,
            displayText = String.format(Locale.US, "%.1f dBFS", lvl.addedNoiseDb),
            onValueChange = { v -> viewModel.updateNoiseLevel { it.copy(enabled = true, addedNoiseDb = v) } },
            onBadgeClick = {
                onOpenNumericEntry(
                    NumericDbPrompt("Added Noise Level", lvl.addedNoiseDb, -60f, 0f, "dBFS") { v ->
                        viewModel.updateNoiseLevel { it.copy(enabled = true, addedNoiseDb = v) }
                    }
                )
            }
        )

        Text("3-Band Noise Shaping", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)

        // Low (< 300 Hz)
        NumericSliderRow(
            label = "Low <300Hz",
            value = lvl.lowBandNoiseDb,
            valueRange = -40f..12f,
            displayText = String.format(Locale.US, "%+.1f dB", lvl.lowBandNoiseDb),
            onValueChange = { v -> viewModel.updateNoiseLevel { it.copy(enabled = true, lowBandNoiseDb = v) } },
            onBadgeClick = {
                onOpenNumericEntry(
                    NumericDbPrompt("Low Band Noise (<300 Hz)", lvl.lowBandNoiseDb, -40f, 12f, "dB") { v ->
                        viewModel.updateNoiseLevel { it.copy(enabled = true, lowBandNoiseDb = v) }
                    }
                )
            }
        )

        // Mid (300 Hz - 4 kHz)
        NumericSliderRow(
            label = "Mid 0.3–4k",
            value = lvl.midBandNoiseDb,
            valueRange = -40f..12f,
            displayText = String.format(Locale.US, "%+.1f dB", lvl.midBandNoiseDb),
            onValueChange = { v -> viewModel.updateNoiseLevel { it.copy(enabled = true, midBandNoiseDb = v) } },
            onBadgeClick = {
                onOpenNumericEntry(
                    NumericDbPrompt("Mid Band Noise (300Hz–4kHz)", lvl.midBandNoiseDb, -40f, 12f, "dB") { v ->
                        viewModel.updateNoiseLevel { it.copy(enabled = true, midBandNoiseDb = v) }
                    }
                )
            }
        )

        // High (> 4 kHz)
        NumericSliderRow(
            label = "High >4kHz",
            value = lvl.highBandNoiseDb,
            valueRange = -40f..12f,
            displayText = String.format(Locale.US, "%+.1f dB", lvl.highBandNoiseDb),
            onValueChange = { v -> viewModel.updateNoiseLevel { it.copy(enabled = true, highBandNoiseDb = v) } },
            onBadgeClick = {
                onOpenNumericEntry(
                    NumericDbPrompt("High Band Noise (>4 kHz)", lvl.highBandNoiseDb, -40f, 12f, "dB") { v ->
                        viewModel.updateNoiseLevel { it.copy(enabled = true, highBandNoiseDb = v) }
                    }
                )
            }
        )

        Button(
            onClick = { viewModel.addOperation(EdlOpType.NOISE_LEVEL_CONTROL) },
            modifier = Modifier
                .fillMaxWidth()
                .testTag("commit_noise_level_btn")
        ) {
            Icon(Icons.Default.GraphicEq, contentDescription = null)
            Spacer(Modifier.width(6.dp))
            Text("Commit Noise Level Control to EDL")
        }
    }
}

@Composable
private fun NumericSliderRow(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    displayText: String,
    onValueChange: (Float) -> Unit,
    onBadgeClick: (() -> Unit)?
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.width(78.dp)
        )
        Slider(
            value = value.coerceIn(valueRange.start, valueRange.endInclusive),
            onValueChange = onValueChange,
            valueRange = valueRange,
            modifier = Modifier.weight(1f)
        )
        Surface(
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)),
            modifier = if (onBadgeClick != null) {
                Modifier.clickable { onBadgeClick() }
            } else Modifier
        ) {
            Text(
                text = displayText,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp)
            )
        }
    }
}

@Composable
private fun BeforeAfterNoiseMeterRow(
    beforeDb: Float,
    afterDb: Float
) {
    val beforeNorm = ((beforeDb - AudioMath.MIN_DB) / -AudioMath.MIN_DB).coerceIn(0f, 1f)
    val afterNorm = ((afterDb - AudioMath.MIN_DB) / -AudioMath.MIN_DB).coerceIn(0f, 1f)
    val deltaDb = afterDb - beforeDb

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    "BEFORE: ${String.format(Locale.US, "%.1f dBFS", beforeDb)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "AFTER: ${String.format(Locale.US, "%.1f dBFS", afterDb)} (${String.format(Locale.US, "%+.1f dB", deltaDb)})",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            LinearProgressIndicator(
                progress = { beforeNorm },
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(5.dp)
            )
            LinearProgressIndicator(
                progress = { afterNorm },
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(5.dp)
            )
        }
    }
}
