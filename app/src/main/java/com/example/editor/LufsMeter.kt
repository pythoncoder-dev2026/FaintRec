package com.example.editor

import com.example.audio.WavFileHelper
import com.example.util.AudioMath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min

data class LoudnessMetrics(
    val integratedLufs: Float,
    val loudnessRangeLu: Float,
    val truePeakDbTp: Float
)

/**
 * ITU-R BS.1770-4 Loudness Meter & Normalizer:
 * - Stage 1 K-weighting pre-filter (+4 dB high-shelf around 1681 Hz)
 * - Stage 2 RLB high-pass filter (38 Hz)
 * - 400 ms gated blocks with 75% overlap (100 ms step)
 * - Absolute gate (-70 LUFS) and relative gate (-10 LU)
 * - Loudness Range (LRA, 10th to 95th percentile)
 * - 4x oversampled cubic True Peak detector (dBTP)
 */
object LufsMeter {

    suspend fun measureFile(wavFile: File): LoudnessMetrics? = withContext(Dispatchers.Default) {
        val info = WavFileHelper.parseWavInfo(wavFile) ?: return@withContext null
        if (info.totalFrames <= 0) return@withContext null

        val sr = info.sampleRate.toDouble()
        val ch = info.channels
        val shelf = Array(ch) { BiquadFilter().apply { setHighShelf(sr, 1681.974, 4.0, 1.0) } }
        val rlb = Array(ch) { BiquadFilter().apply { setHighPass(sr, 38.135, 0.5003) } }

        val stepFrames = max(1, (info.sampleRate * 0.100).toInt()) // 100 ms
        val subBlockEnergies = ArrayList<Double>()

        val buf = FloatArray(stepFrames * ch)
        var maxTruePeak = 1e-6f
        val prevSample = FloatArray(ch)
        val prev2Sample = FloatArray(ch)
        val prev3Sample = FloatArray(ch)

        RandomAccessFile(wavFile, "r").use { raf ->
            var framePos = 0L
            while (framePos < info.totalFrames) {
                val read = WavFileHelper.readFramesAsFloat(raf, info, framePos, stepFrames, buf)
                if (read <= 0) break

                var blockMeanSqSum = 0.0
                for (f in 0 until read) {
                    for (c in 0 until ch) {
                        val x = buf[f * ch + c]
                        // 4x oversampled cubic Hermite interpolation for True Peak
                        val p0 = prev3Sample[c]
                        val p1 = prev2Sample[c]
                        val p2 = prevSample[c]
                        val p3 = x
                        maxTruePeak = max(maxTruePeak, abs(x))
                        for (sub in 1..3) {
                            val t = sub * 0.25f
                            val interp = cubicHermite(p0, p1, p2, p3, t)
                            maxTruePeak = max(maxTruePeak, abs(interp))
                        }
                        prev3Sample[c] = p1
                        prev2Sample[c] = p2
                        prevSample[c] = p3

                        val kWeighted = rlb[c].process(shelf[c].process(x))
                        blockMeanSqSum += (kWeighted * kWeighted).toDouble()
                    }
                }
                // Mean square across frames for this 100ms slice (summed across channels)
                subBlockEnergies.add(blockMeanSqSum / read.toDouble())
                framePos += read
            }
        }

        val metrics = computeMetricsFromSubBlocks(subBlockEnergies, maxTruePeak)
        metrics
    }

    fun computeMetricsFromSubBlocks(
        subBlock100msEnergies: List<Double>,
        maxTruePeakLinear: Float
    ): LoudnessMetrics {
        if (subBlock100msEnergies.isEmpty()) {
            return LoudnessMetrics(-70f, 0f, AudioMath.MIN_DB)
        }
        // Build 400ms blocks (4 consecutive 100ms slices)
        val blockPowers = ArrayList<Double>()
        if (subBlock100msEnergies.size < 4) {
            blockPowers.add(subBlock100msEnergies.average())
        } else {
            for (i in 0..(subBlock100msEnergies.size - 4)) {
                val avg4 = (subBlock100msEnergies[i] +
                    subBlock100msEnergies[i + 1] +
                    subBlock100msEnergies[i + 2] +
                    subBlock100msEnergies[i + 3]) / 4.0
                blockPowers.add(avg4)
            }
        }

        // Absolute gate: -70 LUFS
        val absGated = blockPowers.filter { powerToLufs(it) > -70.0 }
        if (absGated.isEmpty()) {
            return LoudnessMetrics(-70f, 0f, AudioMath.linearToDb(maxTruePeakLinear))
        }
        val ungatedAvgLufs = powerToLufs(absGated.average())

        // Relative gate: -10 LU below ungated average
        val relThreshold = ungatedAvgLufs - 10.0
        val relGated = absGated.filter { powerToLufs(it) >= relThreshold }
        val integratedLufs = if (relGated.isEmpty()) ungatedAvgLufs else powerToLufs(relGated.average())

        // Loudness Range (LRA): 95th percentile - 10th percentile of gated blocks
        val sortedLufs = relGated.map { powerToLufs(it) }.sorted()
        val lra = if (sortedLufs.size >= 2) {
            val p10 = sortedLufs[(sortedLufs.size * 0.10).toInt().coerceIn(0, sortedLufs.lastIndex)]
            val p95 = sortedLufs[(sortedLufs.size * 0.95).toInt().coerceIn(0, sortedLufs.lastIndex)]
            max(0.0, p95 - p10)
        } else 0.0

        return LoudnessMetrics(
            integratedLufs = integratedLufs.toFloat().coerceIn(-70f, 0f),
            loudnessRangeLu = lra.toFloat(),
            truePeakDbTp = AudioMath.linearToDb(maxTruePeakLinear)
        )
    }

    fun predictAfterNormalization(
        before: LoudnessMetrics,
        targetLufs: Float,
        truePeakCeilingDb: Float = -1.0f
    ): LoudnessMetrics {
        val gainDb = (targetLufs - before.integratedLufs).coerceIn(-36f, 36f)
        val newPeak = min(truePeakCeilingDb, before.truePeakDbTp + gainDb)
        return LoudnessMetrics(
            integratedLufs = targetLufs,
            loudnessRangeLu = before.loudnessRangeLu,
            truePeakDbTp = newPeak
        )
    }

    private fun powerToLufs(power: Double): Double = -0.691 + 10.0 * log10(max(power, 1e-12))

    private fun cubicHermite(y0: Float, y1: Float, y2: Float, y3: Float, t: Float): Float {
        val c0 = y1
        val c1 = 0.5f * (y2 - y0)
        val c2 = y0 - 2.5f * y1 + 2f * y2 - 0.5f * y3
        val c3 = 0.5f * (y3 - y0) + 1.5f * (y1 - y2)
        return ((c3 * t + c2) * t + c1) * t + c0
    }
}
