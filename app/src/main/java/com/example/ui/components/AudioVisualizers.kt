package com.example.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.example.analysis.FftResult
import com.example.analysis.SpectrogramColorMap
import com.example.analysis.SpectrogramData
import com.example.analysis.SpectrogramEngine
import com.example.data.MarkerEntity
import com.example.util.AudioMath
import java.util.Locale
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

@Composable
fun LiveOscilloscopeWaveform(
    samples: FloatArray,
    isRecording: Boolean,
    varThresholdDb: Float? = null,
    modifier: Modifier = Modifier
) {
    val primary = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.secondary
    val error = MaterialTheme.colorScheme.error
    val gridColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
    val bgTop = MaterialTheme.colorScheme.surfaceContainerLowest
    val bgBottom = MaterialTheme.colorScheme.surfaceContainer

    Canvas(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Brush.verticalGradient(listOf(bgTop, bgBottom)))
    ) {
        val w = size.width
        val h = size.height
        val midY = h / 2f

        // Precision studio grid
        for (i in 1..3) {
            val y = h * i / 4f
            drawLine(gridColor, Offset(0f, y), Offset(w, y), strokeWidth = 1f)
        }
        for (i in 1..7) {
            val x = w * i / 8f
            drawLine(gridColor, Offset(x, 0f), Offset(x, h), strokeWidth = 1f)
        }

        // VAR threshold line if enabled
        if (varThresholdDb != null) {
            val linThresh = AudioMath.dbToLinear(varThresholdDb).coerceIn(0.02f, 0.95f)
            val offsetY = linThresh * (h * 0.44f)
            val dash = PathEffect.dashPathEffect(floatArrayOf(10f, 8f), 0f)
            drawLine(secondary.copy(alpha = 0.7f), Offset(0f, midY - offsetY), Offset(w, midY - offsetY), 2f, pathEffect = dash)
            drawLine(secondary.copy(alpha = 0.7f), Offset(0f, midY + offsetY), Offset(w, midY + offsetY), 2f, pathEffect = dash)
        }

        if (samples.isEmpty()) return@Canvas

        val barCount = samples.size
        val stepX = w / barCount.coerceAtLeast(1)
        val barWidth = max(2f, stepX * 0.58f)

        for (i in 0 until barCount) {
            val amp = samples[i].coerceIn(0f, 1f)
            val barHalf = max(2f, amp * (h * 0.45f))
            val x = i * stepX + stepX * 0.2f
            val color = when {
                amp > 0.90f -> error
                amp > 0.65f -> secondary
                isRecording -> primary
                else -> primary.copy(alpha = 0.55f)
            }
            drawRoundRect(
                color = color,
                topLeft = Offset(x, midY - barHalf),
                size = Size(barWidth, barHalf * 2f),
                cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f)
            )
        }
    }
}

@Composable
fun PrecisionDbMeter(
    currentDb: Float,
    peakHoldDb: Float,
    modifier: Modifier = Modifier
) {
    val tertiary = MaterialTheme.colorScheme.tertiary
    val primary = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.secondary
    val error = MaterialTheme.colorScheme.error
    val trackBg = MaterialTheme.colorScheme.surfaceContainerHigh

    val normCurrent = ((currentDb - AudioMath.MIN_DB) / (0f - AudioMath.MIN_DB)).coerceIn(0f, 1f)
    val normPeak = ((peakHoldDb - AudioMath.MIN_DB) / (0f - AudioMath.MIN_DB)).coerceIn(0f, 1f)

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = String.format(Locale.US, "%.1f dBFS", currentDb),
                style = MaterialTheme.typography.labelMedium,
                color = if (currentDb > -2f) error else primary
            )
            Text(
                text = String.format(Locale.US, "PEAK %.1f dB", peakHoldDb),
                style = MaterialTheme.typography.labelSmall,
                color = if (peakHoldDb > -1f) error else secondary
            )
        }

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(18.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(trackBg)
        ) {
            val segments = 48
            val segGap = 2.dp.toPx()
            val segW = (size.width - segGap * (segments - 1)) / segments

            for (i in 0 until segments) {
                val frac = (i + 1) / segments.toFloat()
                val isLit = frac <= normCurrent
                val segColor = when {
                    frac > 0.92f -> error
                    frac > 0.78f -> secondary
                    frac > 0.50f -> primary
                    else -> tertiary
                }
                drawRoundRect(
                    color = if (isLit) segColor else segColor.copy(alpha = 0.14f),
                    topLeft = Offset(i * (segW + segGap), 2.dp.toPx()),
                    size = Size(segW, size.height - 4.dp.toPx()),
                    cornerRadius = CornerRadius(2f, 2f)
                )
            }

            // Peak-hold marker bar
            val peakX = (normPeak * size.width).coerceIn(2f, size.width - 3f)
            drawRect(
                color = if (peakHoldDb > -1.5f) error else Color.White,
                topLeft = Offset(peakX - 1.5.dp.toPx(), 0f),
                size = Size(3.dp.toPx(), size.height)
            )
        }
    }
}

@Composable
fun LiveSpectrumCanvas(
    fft: FftResult?,
    logScale: Boolean,
    maxFreqFraction: Float = 1.0f,
    targetMarkerFreqHz: Float? = null,
    modifier: Modifier = Modifier
) {
    val primary = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.secondary
    val outline = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
    val bg = MaterialTheme.colorScheme.surfaceContainerLowest

    var cursorFraction by remember { mutableStateOf<Float?>(null) }

    val nyquist = (fft?.nyquistHz ?: 24000.0) * maxFreqFraction.coerceIn(0.1f, 1.0f)
    val minLogFreq = 20.0

    fun fractionToFreq(frac: Float): Double {
        val f = frac.coerceIn(0f, 1f).toDouble()
        return if (logScale && nyquist > minLogFreq) {
            minLogFreq * (nyquist / minLogFreq).pow(f)
        } else {
            f * nyquist
        }
    }

    fun freqToFraction(freqHz: Double): Float {
        if (nyquist <= minLogFreq) return 0f
        return if (logScale) {
            (ln(max(freqHz, minLogFreq) / minLogFreq) / ln(nyquist / minLogFreq)).toFloat().coerceIn(0f, 1f)
        } else {
            (freqHz / nyquist).toFloat().coerceIn(0f, 1f)
        }
    }

    val cursorReadout: String? = remember(cursorFraction, fft, logScale, maxFreqFraction) {
        val cf = cursorFraction ?: return@remember null
        val data = fft ?: return@remember null
        val freq = fractionToFreq(cf)
        val bin = ((freq / data.nyquistHz) * data.magnitudesDb.size).toInt()
            .coerceIn(0, (data.magnitudesDb.size - 1).coerceAtLeast(0))
        val db = data.magnitudesDb.getOrElse(bin) { AudioMath.MIN_DB }
        val note = AudioMath.frequencyToNote(freq)?.let { " • $it" } ?: ""
        "Cursor: ${AudioMath.formatFrequency(freq)} @ ${String.format(Locale.US, "%.1f dB", db)}$note"
    }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            val peakText = if (fft != null && fft.peakMagnitudeDb > -85f) {
                val notePart = fft.musicalNote?.let { " ($it)" } ?: ""
                "Peak: ${AudioMath.formatFrequency(fft.peakFrequencyHz)} • ${String.format(Locale.US, "%.1f dB", fft.peakMagnitudeDb)}$notePart"
            } else {
                "Peak: -- Hz"
            }
            Text(text = peakText, style = MaterialTheme.typography.labelMedium, color = primary)
            Text(
                text = "0–${AudioMath.formatFrequency(nyquist)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (cursorReadout != null) {
            Text(text = cursorReadout, style = MaterialTheme.typography.labelSmall, color = secondary)
        }

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f, fill = false)
                .height(175.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(bg)
                .pointerInput(Unit) {
                    detectTapGestures { offset ->
                        cursorFraction = (offset.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f)
                    }
                }
        ) {
            val w = size.width
            val h = size.height

            // dB grid lines (-20, -40, -60, -80)
            for (dbStep in 1..4) {
                val y = h * (dbStep / 5f)
                drawLine(outline, Offset(0f, y), Offset(w, y), 1f)
            }
            for (vStep in 1..5) {
                val x = w * (vStep / 6f)
                drawLine(outline, Offset(x, 0f), Offset(x, h), 1f)
            }

            if (fft != null && fft.magnitudesDb.isNotEmpty()) {
                val bins = fft.magnitudesDb
                val peaks = fft.peakHoldDb
                val points = 160
                val specPath = Path()
                val fillPath = Path()
                val peakPath = Path()

                fillPath.moveTo(0f, h)
                for (px in 0..points) {
                    val frac = px / points.toFloat()
                    val freq = fractionToFreq(frac)
                    val binIdx = ((freq / fft.nyquistHz) * bins.size).toInt().coerceIn(0, bins.lastIndex)
                    val db = bins[binIdx]
                    val peakDb = peaks.getOrElse(binIdx) { db }
                    val normY = 1f - ((db - AudioMath.MIN_DB) / (0f - AudioMath.MIN_DB)).coerceIn(0f, 1f)
                    val peakNormY = 1f - ((peakDb - AudioMath.MIN_DB) / (0f - AudioMath.MIN_DB)).coerceIn(0f, 1f)

                    val x = frac * w
                    val y = normY * h
                    val py = peakNormY * h

                    if (px == 0) {
                        specPath.moveTo(x, y)
                        peakPath.moveTo(x, py)
                    } else {
                        specPath.lineTo(x, y)
                        peakPath.lineTo(x, py)
                    }
                    fillPath.lineTo(x, y)
                }
                fillPath.lineTo(w, h)
                fillPath.close()

                drawPath(
                    path = fillPath,
                    brush = Brush.verticalGradient(
                        listOf(primary.copy(alpha = 0.42f), primary.copy(alpha = 0.02f))
                    )
                )
                drawPath(path = peakPath, color = secondary.copy(alpha = 0.7f), style = Stroke(width = 1.6f))
                drawPath(path = specPath, color = primary, style = Stroke(width = 2.4f, cap = StrokeCap.Round))
            }

            // Ultrasonic target frequency marker
            if (targetMarkerFreqHz != null && targetMarkerFreqHz <= nyquist) {
                val tx = freqToFraction(targetMarkerFreqHz.toDouble()) * w
                drawLine(
                    color = secondary,
                    start = Offset(tx, 0f),
                    end = Offset(tx, h),
                    strokeWidth = 2.5f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 6f), 0f)
                )
            }

            // Tap cursor line
            cursorFraction?.let { cf ->
                val cx = cf * w
                drawLine(Color.White.copy(alpha = 0.85f), Offset(cx, 0f), Offset(cx, h), strokeWidth = 2f)
            }
        }
    }
}

@Composable
fun InteractiveSpectrogramCanvas(
    data: SpectrogramData?,
    colorMap: SpectrogramColorMap,
    maxFreqFraction: Float,
    playheadFraction: Float,
    markers: List<MarkerEntity> = emptyList(),
    targetMarkerFreqHz: Float? = null,
    zoomLevel: Float = 1f,
    onTapSeekFraction: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val secondary = MaterialTheme.colorScheme.secondary
    val bg = MaterialTheme.colorScheme.surfaceContainerLowest
    var scrollOffsetFrac by remember { mutableFloatStateOf(0f) }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(bg)
            .pointerInput(zoomLevel) {
                detectHorizontalDragGestures { _, dragAmount ->
                    if (zoomLevel > 1f) {
                        val maxScroll = (1f - 1f / zoomLevel).coerceAtLeast(0f)
                        val delta = -dragAmount / size.width.coerceAtLeast(1) / zoomLevel
                        scrollOffsetFrac = (scrollOffsetFrac + delta).coerceIn(0f, maxScroll)
                    }
                }
            }
            .pointerInput(zoomLevel, scrollOffsetFrac) {
                detectTapGestures { offset ->
                    val localFrac = (offset.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f)
                    val globalFrac = (scrollOffsetFrac + localFrac / zoomLevel).coerceIn(0f, 1f)
                    onTapSeekFraction(globalFrac)
                }
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val spec = data ?: return@Canvas
            if (spec.frames.isEmpty()) return@Canvas

            val visibleWindow = (1f / zoomLevel.coerceAtLeast(1f)).coerceIn(0.1f, 1f)
            val startCol = (scrollOffsetFrac * spec.frames.size).toInt().coerceIn(0, spec.frames.lastIndex)
            val endCol = min(spec.frames.size, ((scrollOffsetFrac + visibleWindow) * spec.frames.size).toInt().coerceAtLeast(startCol + 1))
            val colCount = (endCol - startCol).coerceAtLeast(1)

            val maxBin = (spec.numBins * maxFreqFraction.coerceIn(0.1f, 1.0f)).toInt().coerceIn(8, spec.numBins)
            val rowBands = 64
            val cellW = size.width / colCount
            val cellH = size.height / rowBands

            for (c in 0 until colCount) {
                val frame = spec.frames[startCol + c]
                val x = c * cellW
                for (r in 0 until rowBands) {
                    val binIdx = ((r.toFloat() / rowBands) * maxBin).toInt().coerceIn(0, frame.lastIndex)
                    val mag = frame[binIdx]
                    val y = size.height - (r + 1) * cellH
                    drawRect(
                        color = Color(SpectrogramEngine.colorForValue(mag, colorMap)),
                        topLeft = Offset(x, y),
                        size = Size(cellW + 0.8f, cellH + 0.8f)
                    )
                }
            }

            // Ultrasonic target frequency horizontal marker
            if (targetMarkerFreqHz != null) {
                val maxHz = spec.nyquistHz * maxFreqFraction.coerceIn(0.1f, 1.0f)
                if (targetMarkerFreqHz <= maxHz) {
                    val y = size.height * (1f - targetMarkerFreqHz / maxHz)
                    drawLine(
                        color = secondary,
                        start = Offset(0f, y),
                        end = Offset(size.width, y),
                        strokeWidth = 2.5f,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 6f), 0f)
                    )
                }
            }

            // Timestamp marker pins
            if (spec.durationMs > 0) {
                markers.forEach { m ->
                    val mf = m.timestampMs.toFloat() / spec.durationMs.toFloat()
                    if (mf in scrollOffsetFrac..(scrollOffsetFrac + visibleWindow)) {
                        val mx = ((mf - scrollOffsetFrac) / visibleWindow) * size.width
                        drawLine(secondary, Offset(mx, 0f), Offset(mx, size.height), strokeWidth = 2.5f)
                    }
                }
            }

            // Playhead line
            if (playheadFraction in scrollOffsetFrac..(scrollOffsetFrac + visibleWindow)) {
                val px = ((playheadFraction - scrollOffsetFrac) / visibleWindow) * size.width
                drawLine(Color.White, Offset(px, 0f), Offset(px, size.height), strokeWidth = 2.5f)
            }
        }
    }
}

@Composable
fun EditorWaveformCanvas(
    peaks: FloatArray,
    selectionStart: Float,
    selectionEnd: Float,
    playheadFraction: Float,
    zoomLevel: Float,
    onSelectionChanged: (Float, Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val primary = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.secondary
    val bg = MaterialTheme.colorScheme.surfaceContainerLowest
    val outline = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)

    val safeZoom = zoomLevel.coerceIn(1f, 8f)
    val visibleWindow = 1f / safeZoom
    val maxScroll = (1f - visibleWindow).coerceAtLeast(0f)

    var scrollOffsetFrac by remember { mutableFloatStateOf(0f) }
    val clampedScroll = scrollOffsetFrac.coerceIn(0f, maxScroll)
    var draggingMode by remember { mutableStateOf<Char?>(null) } // 'S' = start, 'E' = end, 'P' = pan

    Canvas(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(bg)
            .pointerInput(selectionStart, selectionEnd, safeZoom, clampedScroll) {
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        val localFrac = (offset.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f)
                        val globalFrac = (clampedScroll + localFrac * visibleWindow).coerceIn(0f, 1f)
                        val handleThreshold = 0.08f * visibleWindow
                        val distStart = kotlin.math.abs(globalFrac - selectionStart)
                        val distEnd = kotlin.math.abs(globalFrac - selectionEnd)
                        draggingMode = when {
                            distStart <= distEnd && (distStart <= handleThreshold || safeZoom <= 1.05f) -> 'S'
                            distEnd < distStart && (distEnd <= handleThreshold || safeZoom <= 1.05f) -> 'E'
                            safeZoom > 1.05f -> 'P'
                            distStart <= distEnd -> 'S'
                            else -> 'E'
                        }
                    },
                    onDragEnd = { draggingMode = null },
                    onHorizontalDrag = { change, dragAmount ->
                        val widthF = size.width.coerceAtLeast(1).toFloat()
                        if (draggingMode == 'P') {
                            val delta = (-dragAmount / widthF) * visibleWindow
                            scrollOffsetFrac = (scrollOffsetFrac + delta).coerceIn(0f, maxScroll)
                        } else {
                            val localFrac = (change.position.x / widthF).coerceIn(0f, 1f)
                            val globalFrac = (clampedScroll + localFrac * visibleWindow).coerceIn(0f, 1f)
                            if (draggingMode == 'S') {
                                onSelectionChanged(min(globalFrac, selectionEnd - 0.02f), selectionEnd)
                            } else if (draggingMode == 'E') {
                                onSelectionChanged(selectionStart, max(globalFrac, selectionStart + 0.02f))
                            }
                        }
                    }
                )
            }
    ) {
        val w = size.width
        val h = size.height
        val midY = h / 2f

        fun globalToX(frac: Float): Float = ((frac - clampedScroll) / visibleWindow) * w

        drawLine(outline, Offset(0f, midY), Offset(w, midY), 1.5f)

        // Selection shading (clipped to visible viewport)
        val rawSelX1 = globalToX(selectionStart)
        val rawSelX2 = globalToX(selectionEnd)
        val visSelX1 = rawSelX1.coerceIn(0f, w)
        val visSelX2 = rawSelX2.coerceIn(0f, w)
        if (visSelX2 > visSelX1) {
            drawRect(
                color = primary.copy(alpha = 0.16f),
                topLeft = Offset(visSelX1, 0f),
                size = Size(max(2f, visSelX2 - visSelX1), h)
            )
        }

        // Zoomed waveform bars
        if (peaks.isNotEmpty()) {
            val startIdx = (clampedScroll * peaks.size).toInt().coerceIn(0, peaks.lastIndex)
            val endIdx = ((clampedScroll + visibleWindow) * peaks.size).toInt().coerceIn(startIdx + 1, peaks.size)
            val visibleCount = (endIdx - startIdx).coerceAtLeast(1)
            val step = w / visibleCount
            val barW = max(2f, step * 0.65f)
            for (k in 0 until visibleCount) {
                val i = startIdx + k
                val f = i.toFloat() / peaks.size
                val inSel = f in selectionStart..selectionEnd
                val halfH = max(2f, peaks[i].coerceIn(0f, 1f) * (h * 0.44f))
                drawRoundRect(
                    color = if (inSel) primary else primary.copy(alpha = 0.35f),
                    topLeft = Offset(k * step, midY - halfH),
                    size = Size(barW, halfH * 2f),
                    cornerRadius = CornerRadius(barW / 2f, barW / 2f)
                )
            }
        }

        // Draggable Start / End handles (drawn when visible)
        if (rawSelX1 in -12f..(w + 12f)) {
            drawLine(secondary, Offset(rawSelX1, 0f), Offset(rawSelX1, h), strokeWidth = 4f)
            drawCircle(secondary, radius = 9.dp.toPx(), center = Offset(rawSelX1, 14.dp.toPx()))
        }
        if (rawSelX2 in -12f..(w + 12f)) {
            drawLine(secondary, Offset(rawSelX2, 0f), Offset(rawSelX2, h), strokeWidth = 4f)
            drawCircle(secondary, radius = 9.dp.toPx(), center = Offset(rawSelX2, h - 14.dp.toPx()))
        }

        // Playhead
        val px = globalToX(playheadFraction.coerceIn(0f, 1f))
        if (px in 0f..w) {
            drawLine(Color.White, Offset(px, 0f), Offset(px, h), strokeWidth = 2.5f)
        }
    }
}
