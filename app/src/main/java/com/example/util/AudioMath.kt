package com.example.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.math.tanh

object AudioMath {

    const val MIN_DB = -96f
    const val SAFE_MAX_PLAYBACK_BOOST_DB = 24f
    const val HEARING_WARNING_THRESHOLD_DB = 12f

    fun dbToLinear(db: Float): Float = 10f.pow(db / 20f)

    fun linearToDb(linear: Float): Float {
        val clamped = max(abs(linear), 1e-6f)
        return max(MIN_DB, 20f * log10(clamped))
    }

    /**
     * Soft-knee limiter using hyperbolic tangent above -3 dBFS (0.707 linear)
     * so ultra-high software gain (+30 dB) amplifies faint sounds cleanly without harsh digital clipping.
     */
    fun softLimit(sample: Float, ceiling: Float = 0.98f): Float {
        val threshold = 0.70f
        val a = abs(sample)
        if (a <= threshold) return sample
        val sign = if (sample >= 0f) 1f else -1f
        val excess = (a - threshold) / (ceiling - threshold)
        val compressed = threshold + (ceiling - threshold) * tanh(excess.toDouble()).toFloat()
        return sign * min(compressed, ceiling)
    }

    fun applyGainWithSoftLimiter(samples: FloatArray, count: Int, gainDb: Float) {
        if (gainDb == 0f) return
        val gain = dbToLinear(gainDb)
        for (i in 0 until count) {
            samples[i] = softLimit(samples[i] * gain)
        }
    }

    fun computeRmsDb(samples: FloatArray, count: Int): Float {
        if (count <= 0) return MIN_DB
        var sumSq = 0.0
        for (i in 0 until count) {
            val s = samples[i].toDouble()
            sumSq += s * s
        }
        val rms = sqrt(sumSq / count).toFloat()
        return linearToDb(rms)
    }

    fun computePeakDb(samples: FloatArray, count: Int): Float {
        if (count <= 0) return MIN_DB
        var peak = 0f
        for (i in 0 until count) {
            val a = abs(samples[i])
            if (a > peak) peak = a
        }
        return linearToDb(peak)
    }

    private val NOTE_NAMES = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")

    /**
     * Converts an audible frequency (16 Hz – 16000 Hz) to nearest musical note and cents offset.
     * Returns null for sub-audible or ultrasonic frequencies.
     */
    fun frequencyToNote(freqHz: Double): String? {
        if (freqHz < 16.0 || freqHz > 16000.0) return null
        val midiFloat = 69.0 + 12.0 * (ln(freqHz / 440.0) / ln(2.0))
        val midi = midiFloat.roundToInt()
        if (midi < 0 || midi > 127) return null
        val noteIndex = ((midi % 12) + 12) % 12
        val octave = (midi / 12) - 1
        val cents = ((midiFloat - midi) * 100.0).roundToInt()
        val centsSign = if (cents >= 0) "+" else ""
        return "${NOTE_NAMES[noteIndex]}$octave ($centsSign${cents}c)"
    }

    fun formatFrequency(freqHz: Double): String {
        return if (freqHz >= 1000.0) {
            String.format(Locale.US, "%.2f kHz", freqHz / 1000.0)
        } else {
            String.format(Locale.US, "%.1f Hz", freqHz)
        }
    }

    fun formatDuration(durationMs: Long): String {
        val totalSec = max(0L, durationMs) / 1000L
        val hours = totalSec / 3600L
        val mins = (totalSec % 3600L) / 60L
        val secs = totalSec % 60L
        val tenths = (max(0L, durationMs) % 1000L) / 100L
        return if (hours > 0) {
            String.format(Locale.US, "%d:%02d:%02d.%d", hours, mins, secs, tenths)
        } else {
            String.format(Locale.US, "%02d:%02d.%d", mins, secs, tenths)
        }
    }

    fun formatDurationShort(durationMs: Long): String {
        val totalSec = max(0L, durationMs) / 1000L
        val mins = totalSec / 60L
        val secs = totalSec % 60L
        return String.format(Locale.US, "%02d:%02d", mins, secs)
    }

    fun formatFileSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
            bytes < 1024L * 1024L * 1024L -> String.format(Locale.US, "%.2f MB", bytes / (1024.0 * 1024.0))
            else -> String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
        }
    }

    fun timestampFileName(prefix: String = "FaintRec", ext: String = "wav"): String {
        val fmt = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
        return "${prefix}_${fmt.format(Date())}.$ext"
    }
}
