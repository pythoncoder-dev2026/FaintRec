package com.example.spiritbox

import com.example.data.SpiritBoxHopLogItem
import com.example.data.SpiritBoxQuestionLogItem
import com.example.util.AudioMath
import java.util.Locale
import kotlin.random.Random

enum class SpiritBandMode(
    val displayName: String,
    val minFreq: Float,
    val maxFreq: Float,
    val unit: String,
    val stepSize: Float
) {
    FM("FM", 88.0f, 108.0f, "MHz", 0.2f),
    AM("AM", 530f, 1700f, "kHz", 10f)
}

enum class SweepDirection(val displayName: String) {
    FORWARD("Forward"),
    BACKWARD("Backward"),
    RANDOM("Random")
}

enum class StaticNoiseType(val displayName: String) {
    WHITE("White"),
    PINK("Pink"),
    BROWN("Brown"),
    TUNING_STATIC("Tuning Static")
}

data class SpiritBoxConfig(
    val enableInternetRadio: Boolean = true,
    val enableLocalAudio: Boolean = true,
    val enableGeneratedStatic: Boolean = true,
    val enableOfflineTts: Boolean = false,
    val streamUrls: List<String> = emptyList(),
    val staticNoiseType: StaticNoiseType = StaticNoiseType.TUNING_STATIC,
    val staticCrackleEnabled: Boolean = true,
    val ttsHindiAndEnglish: Boolean = true,
    val bandMode: SpiritBandMode = SpiritBandMode.FM,
    val sweepDirection: SweepDirection = SweepDirection.FORWARD,
    val scanSpeedMs: Int = 250, // 50..2000 ms per hop
    val jitterEnabled: Boolean = true,
    val rangeStartFraction: Float = 0.0f,
    val rangeEndFraction: Float = 1.0f,
    val staticLevel: Float = 0.35f,      // 0..1
    val sourceLevel: Float = 0.65f,      // 0..1
    val crossfadeMs: Int = 25,           // 0..100 ms
    val voiceHpfHz: Float = 260f,        // 80..800 Hz
    val voiceLpfHz: Float = 3800f,       // 1500..8000 Hz
    val outputVolume: Float = 0.70f,     // capped <= 0.85 for hearing safety
    val recordSessionMix: Boolean = true,
    val recordMicTrack: Boolean = false,
    val blindTestMode: Boolean = false
)

data class SpiritBoxLiveState(
    val isRunning: Boolean = false,
    val isHeld: Boolean = false,
    val isPausedByAudioFocus: Boolean = false,
    val elapsedMs: Long = 0L,
    val hopCount: Int = 0,
    val dialFraction: Float = 0.0f,
    val currentFrequencyLabel: String = "88.0 MHz",
    val activeSourceLabel: String = "Standby",
    val activeSourceDetail: String = "",
    val outputDb: Float = AudioMath.MIN_DB,
    val waveform: FloatArray = FloatArray(100),
    val markersCount: Int = 0,
    val questions: List<SpiritBoxQuestionLogItem> = emptyList(),
    val blindTestRevealHops: List<SpiritBoxHopLogItem> = emptyList(),
    val showBlindReveal: Boolean = false,
    val lastSavedMixTitle: String? = null
)

object SpiritBoxDialMath {

    fun formatFrequency(band: SpiritBandMode, fraction: Float): String {
        val clamped = fraction.coerceIn(0f, 1f)
        val freq = band.minFreq + (band.maxFreq - band.minFreq) * clamped
        return if (band == SpiritBandMode.FM) {
            String.format(Locale.US, "%.1f %s", freq, band.unit)
        } else {
            String.format(Locale.US, "%.0f %s", freq, band.unit)
        }
    }

    fun nextDialFraction(
        currentFraction: Float,
        direction: SweepDirection,
        band: SpiritBandMode,
        rangeStart: Float,
        rangeEnd: Float,
        rng: Random = Random.Default
    ): Float {
        val lo = rangeStart.coerceIn(0f, 0.95f)
        val hi = rangeEnd.coerceIn(lo + 0.05f, 1.0f)
        val span = (band.maxFreq - band.minFreq).coerceAtLeast(1f)
        val stepFrac = (band.stepSize / span).coerceAtLeast(0.005f)

        return when (direction) {
            SweepDirection.FORWARD -> {
                val next = currentFraction + stepFrac
                if (next > hi) lo else next.coerceIn(lo, hi)
            }
            SweepDirection.BACKWARD -> {
                val next = currentFraction - stepFrac
                if (next < lo) hi else next.coerceIn(lo, hi)
            }
            SweepDirection.RANDOM -> {
                lo + rng.nextFloat() * (hi - lo)
            }
        }
    }

    fun computeNextHopDurationMs(baseSpeedMs: Int, jitterEnabled: Boolean, rng: Random = Random.Default): Long {
        val clamped = baseSpeedMs.coerceIn(50, 2000)
        if (!jitterEnabled) return clamped.toLong()
        val factor = 0.65f + rng.nextFloat() * 0.70f // 0.65x..1.35x jitter
        return (clamped * factor).toLong().coerceIn(50L, 2400L)
    }
}
