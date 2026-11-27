package com.example.editor

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Direct Form II Transposed Biquad IIR filter (Robert Bristow-Johnson Audio EQ Cookbook).
 */
class BiquadFilter {
    private var b0 = 1.0
    private var b1 = 0.0
    private var b2 = 0.0
    private var a1 = 0.0
    private var a2 = 0.0

    private var z1 = 0.0
    private var z2 = 0.0

    fun reset() {
        z1 = 0.0
        z2 = 0.0
    }

    fun setPeakingEq(sampleRate: Double, freqHz: Double, q: Double, gainDb: Double) {
        if (kotlin.math.abs(gainDb) < 0.05 || freqHz >= sampleRate * 0.48) {
            setBypass()
            return
        }
        val a = 10.0.pow(gainDb / 40.0)
        val w0 = 2.0 * PI * freqHz / sampleRate
        val cosW0 = cos(w0)
        val alpha = sin(w0) / (2.0 * q)

        val a0 = 1.0 + alpha / a
        b0 = (1.0 + alpha * a) / a0
        b1 = (-2.0 * cosW0) / a0
        b2 = (1.0 - alpha * a) / a0
        a1 = (-2.0 * cosW0) / a0
        a2 = (1.0 - alpha / a) / a0
    }

    fun setHighPass(sampleRate: Double, freqHz: Double, q: Double = 0.70710678) {
        if (freqHz <= 15.0 || freqHz >= sampleRate * 0.48) {
            setBypass()
            return
        }
        val w0 = 2.0 * PI * freqHz / sampleRate
        val cosW0 = cos(w0)
        val alpha = sin(w0) / (2.0 * q)

        val a0 = 1.0 + alpha
        b0 = ((1.0 + cosW0) / 2.0) / a0
        b1 = (-(1.0 + cosW0)) / a0
        b2 = ((1.0 + cosW0) / 2.0) / a0
        a1 = (-2.0 * cosW0) / a0
        a2 = (1.0 - alpha) / a0
    }

    fun setLowPass(sampleRate: Double, freqHz: Double, q: Double = 0.70710678) {
        if (freqHz <= 20.0 || freqHz >= sampleRate * 0.48) {
            setBypass()
            return
        }
        val w0 = 2.0 * PI * freqHz / sampleRate
        val cosW0 = cos(w0)
        val alpha = sin(w0) / (2.0 * q)

        val a0 = 1.0 + alpha
        b0 = ((1.0 - cosW0) / 2.0) / a0
        b1 = (1.0 - cosW0) / a0
        b2 = ((1.0 - cosW0) / 2.0) / a0
        a1 = (-2.0 * cosW0) / a0
        a2 = (1.0 - alpha) / a0
    }

    fun setHighShelf(sampleRate: Double, freqHz: Double, gainDb: Double, slope: Double = 1.0) {
        val a = 10.0.pow(gainDb / 40.0)
        val w0 = 2.0 * PI * freqHz / sampleRate
        val cosW0 = cos(w0)
        val sinW0 = sin(w0)
        val alpha = sinW0 / 2.0 * sqrt((a + 1.0 / a) * (1.0 / slope - 1.0) + 2.0)
        val twoSqrtAAlpha = 2.0 * sqrt(a) * alpha

        val a0 = (a + 1.0) - (a - 1.0) * cosW0 + twoSqrtAAlpha
        b0 = (a * ((a + 1.0) + (a - 1.0) * cosW0 + twoSqrtAAlpha)) / a0
        b1 = (-2.0 * a * ((a - 1.0) + (a + 1.0) * cosW0)) / a0
        b2 = (a * ((a + 1.0) + (a - 1.0) * cosW0 - twoSqrtAAlpha)) / a0
        a1 = (2.0 * ((a - 1.0) - (a + 1.0) * cosW0)) / a0
        a2 = ((a + 1.0) - (a - 1.0) * cosW0 - twoSqrtAAlpha) / a0
    }

    fun setNotch(sampleRate: Double, freqHz: Double, q: Double = 20.0) {
        if (freqHz <= 10.0 || freqHz >= sampleRate * 0.48) {
            setBypass()
            return
        }
        val w0 = 2.0 * PI * freqHz / sampleRate
        val cosW0 = cos(w0)
        val alpha = sin(w0) / (2.0 * q.coerceAtLeast(0.5))

        val a0 = 1.0 + alpha
        b0 = 1.0 / a0
        b1 = (-2.0 * cosW0) / a0
        b2 = 1.0 / a0
        a1 = (-2.0 * cosW0) / a0
        a2 = (1.0 - alpha) / a0
    }

    fun setBandPass(sampleRate: Double, freqHz: Double, q: Double = 1.2) {
        if (freqHz <= 15.0 || freqHz >= sampleRate * 0.48) {
            setBypass()
            return
        }
        val w0 = 2.0 * PI * freqHz / sampleRate
        val cosW0 = cos(w0)
        val alpha = sin(w0) / (2.0 * q.coerceAtLeast(0.2))

        val a0 = 1.0 + alpha
        b0 = alpha / a0
        b1 = 0.0
        b2 = (-alpha) / a0
        a1 = (-2.0 * cosW0) / a0
        a2 = (1.0 - alpha) / a0
    }

    fun setBypass() {
        b0 = 1.0; b1 = 0.0; b2 = 0.0; a1 = 0.0; a2 = 0.0
    }

    fun process(input: Float): Float {
        val x = input.toDouble()
        val y = b0 * x + z1
        z1 = b1 * x - a1 * y + z2
        z2 = b2 * x - a2 * y
        return y.toFloat()
    }
}

enum class EqPreset(val displayName: String, val gainsDb: List<Float>) {
    FLAT("Flat", listOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)),
    SPEECH_BOOST("Speech Boost 1–4 kHz", listOf(-4f, -3f, -1f, 0f, 1.5f, 4.5f, 6.0f, 5.5f, 1.5f, -2f)),
    VOICE_CLARITY("Voice Clarity", listOf(-6f, -4f, -1.5f, 1f, 2f, 3.5f, 4.5f, 4f, 2.5f, 0f)),
    BASS_CUT("Bass Cut", listOf(-12f, -10f, -6f, -2f, 0f, 0f, 0f, 0f, 0f, 0f))
}

/**
 * 10-band Graphic Equalizer + 12/24 dB/oct High-Pass & Low-Pass filter chain per channel.
 */
class GraphicEqAndFilterChain(
    private val sampleRate: Int,
    private val channels: Int
) {
    companion object {
        val BAND_FREQUENCIES_HZ = floatArrayOf(
            31f, 62f, 125f, 250f, 500f, 1000f, 2000f, 4000f, 8000f, 16000f
        )
    }

    private val eqFilters = Array(channels) { Array(10) { BiquadFilter() } }
    private val hpfStage1 = Array(channels) { BiquadFilter() }
    private val hpfStage2 = Array(channels) { BiquadFilter() }
    private val lpfStage1 = Array(channels) { BiquadFilter() }
    private val lpfStage2 = Array(channels) { BiquadFilter() }

    private var hpfActive = false
    private var hpf24Db = false
    private var lpfActive = false
    private var lpf24Db = false
    private var eqActive = false

    fun configure(
        bandsDb: List<Float>,
        hpfHz: Float,
        hpfSlopeDb: Int,
        lpfHz: Float,
        lpfSlopeDb: Int
    ) {
        val sr = sampleRate.toDouble()
        eqActive = bandsDb.any { kotlin.math.abs(it) >= 0.05f }
        hpfActive = hpfHz > 20f
        hpf24Db = hpfSlopeDb >= 24
        lpfActive = lpfHz > 100f && lpfHz < sampleRate * 0.47f
        lpf24Db = lpfSlopeDb >= 24

        for (c in 0 until channels) {
            for (b in 0 until 10) {
                val g = bandsDb.getOrElse(b) { 0f }
                eqFilters[c][b].setPeakingEq(sr, BAND_FREQUENCIES_HZ[b].toDouble(), 1.41, g.toDouble())
            }
            if (hpfActive) {
                hpfStage1[c].setHighPass(sr, hpfHz.toDouble())
                if (hpf24Db) hpfStage2[c].setHighPass(sr, hpfHz.toDouble()) else hpfStage2[c].setBypass()
            } else {
                hpfStage1[c].setBypass()
                hpfStage2[c].setBypass()
            }
            if (lpfActive) {
                lpfStage1[c].setLowPass(sr, lpfHz.toDouble())
                if (lpf24Db) lpfStage2[c].setLowPass(sr, lpfHz.toDouble()) else lpfStage2[c].setBypass()
            } else {
                lpfStage1[c].setBypass()
                lpfStage2[c].setBypass()
            }
        }
    }

    fun processInterleaved(samples: FloatArray, frameCount: Int) {
        if (!eqActive && !hpfActive && !lpfActive) return
        for (f in 0 until frameCount) {
            for (c in 0 until channels) {
                val idx = f * channels + c
                var s = samples[idx]
                if (hpfActive) {
                    s = hpfStage1[c].process(s)
                    if (hpf24Db) s = hpfStage2[c].process(s)
                }
                if (lpfActive) {
                    s = lpfStage1[c].process(s)
                    if (lpf24Db) s = lpfStage2[c].process(s)
                }
                if (eqActive) {
                    val bank = eqFilters[c]
                    for (b in 0 until 10) {
                        s = bank[b].process(s)
                    }
                }
                samples[idx] = s
            }
        }
    }
}
