package com.example.analysis

import com.example.util.AudioMath
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

enum class FftWindowType { HANN, BLACKMAN }

data class FftResult(
    val magnitudesDb: FloatArray,
    val peakHoldDb: FloatArray,
    val sampleRate: Int,
    val fftSize: Int,
    val peakFrequencyHz: Double,
    val peakMagnitudeDb: Float,
    val musicalNote: String?
) {
    val nyquistHz: Double get() = sampleRate / 2.0
    val binWidthHz: Double get() = sampleRate.toDouble() / fftSize.toDouble()
}

/**
 * High-speed radix-2 Cooley-Tukey FFT processor supporting 1024..16384 points,
 * Hann/Blackman windowing, exponential smoothing, peak hold, and parabolic peak interpolation.
 */
class FftProcessor(
    initialSize: Int = 2048,
    initialWindow: FftWindowType = FftWindowType.HANN
) {
    private var size: Int = validatePowerOfTwo(initialSize)
    private var windowType: FftWindowType = initialWindow
    private var window = buildWindow(size, windowType)
    private var real = DoubleArray(size)
    private var imag = DoubleArray(size)
    private var smoothedDb = FloatArray(size / 2) { AudioMath.MIN_DB }
    private var peakHoldDb = FloatArray(size / 2) { AudioMath.MIN_DB }

    var averagingFactor: Float = 0.45f
    var peakDecayDbPerFrame: Float = 0.35f

    @Synchronized
    fun configure(fftSize: Int, windowType: FftWindowType) {
        val valid = validatePowerOfTwo(fftSize)
        if (valid != this.size || windowType != this.windowType) {
            this.size = valid
            this.windowType = windowType
            this.window = buildWindow(valid, windowType)
            this.real = DoubleArray(valid)
            this.imag = DoubleArray(valid)
            this.smoothedDb = FloatArray(valid / 2) { AudioMath.MIN_DB }
            this.peakHoldDb = FloatArray(valid / 2) { AudioMath.MIN_DB }
        }
    }

    @Synchronized
    fun resetPeakHold() {
        peakHoldDb.fill(AudioMath.MIN_DB)
        smoothedDb.fill(AudioMath.MIN_DB)
    }

    @Synchronized
    fun analyze(monoSamples: FloatArray, sampleRate: Int): FftResult {
        val n = size
        val half = n / 2
        val copyLen = minOf(monoSamples.size, n)
        val offset = max(0, monoSamples.size - copyLen)

        for (i in 0 until n) {
            val s = if (i < copyLen) monoSamples[offset + i].toDouble() else 0.0
            real[i] = s * window[i]
            imag[i] = 0.0
        }

        fftInPlace(real, imag, inverse = false)

        val normFactor = 2.0 / n.toDouble()
        val alpha = averagingFactor.coerceIn(0.05f, 1.0f)

        var peakBin = 1
        var peakValDb = AudioMath.MIN_DB

        for (k in 0 until half) {
            val mag = sqrt(real[k] * real[k] + imag[k] * imag[k]) * normFactor
            val db = max(AudioMath.MIN_DB, (20.0 * log10(max(mag, 1e-6))).toFloat())
            smoothedDb[k] = if (smoothedDb[k] <= AudioMath.MIN_DB + 0.1f) {
                db
            } else {
                smoothedDb[k] * (1f - alpha) + db * alpha
            }
            peakHoldDb[k] = max(smoothedDb[k], peakHoldDb[k] - peakDecayDbPerFrame)
            if (k >= 1 && smoothedDb[k] > peakValDb) {
                peakValDb = smoothedDb[k]
                peakBin = k
            }
        }

        // Parabolic interpolation around peakBin for accurate sub-bin frequency estimation
        val interpBin = if (peakBin in 1 until (half - 1)) {
            val y1 = smoothedDb[peakBin - 1].toDouble()
            val y2 = smoothedDb[peakBin].toDouble()
            val y3 = smoothedDb[peakBin + 1].toDouble()
            val denom = (y1 - 2.0 * y2 + y3)
            if (kotlin.math.abs(denom) > 1e-9) {
                peakBin + 0.5 * (y1 - y3) / denom
            } else peakBin.toDouble()
        } else {
            peakBin.toDouble()
        }

        val peakFreqHz = (interpBin * sampleRate.toDouble()) / n.toDouble()
        val note = AudioMath.frequencyToNote(peakFreqHz)

        return FftResult(
            magnitudesDb = smoothedDb.copyOf(),
            peakHoldDb = peakHoldDb.copyOf(),
            sampleRate = sampleRate,
            fftSize = n,
            peakFrequencyHz = peakFreqHz,
            peakMagnitudeDb = peakValDb,
            musicalNote = note
        )
    }

    companion object {
        fun validatePowerOfTwo(n: Int): Int {
            return when {
                n <= 1024 -> 1024
                n <= 2048 -> 2048
                n <= 4096 -> 4096
                n <= 8192 -> 8192
                else -> 16384
            }
        }

        fun buildWindow(n: Int, type: FftWindowType): DoubleArray {
            val w = DoubleArray(n)
            val denom = (n - 1).coerceAtLeast(1).toDouble()
            for (i in 0 until n) {
                w[i] = when (type) {
                    FftWindowType.HANN -> 0.5 * (1.0 - cos(2.0 * PI * i / denom))
                    FftWindowType.BLACKMAN ->
                        0.42 - 0.5 * cos(2.0 * PI * i / denom) + 0.08 * cos(4.0 * PI * i / denom)
                }
            }
            return w
        }

        /**
         * In-place iterative radix-2 Cooley-Tukey FFT (forward and inverse).
         */
        fun fftInPlace(real: DoubleArray, imag: DoubleArray, inverse: Boolean) {
            val n = real.size
            if (n <= 1) return

            // Bit-reversal permutation
            var j = 0
            for (i in 1 until n) {
                var bit = n shr 1
                while (j and bit != 0) {
                    j = j xor bit
                    bit = bit shr 1
                }
                j = j xor bit
                if (i < j) {
                    val tr = real[i]; real[i] = real[j]; real[j] = tr
                    val ti = imag[i]; imag[i] = imag[j]; imag[j] = ti
                }
            }

            var len = 2
            while (len <= n) {
                val halfLen = len shr 1
                val angle = (if (inverse) 2.0 else -2.0) * PI / len
                val wLenR = cos(angle)
                val wLenI = sin(angle)
                var i = 0
                while (i < n) {
                    var wR = 1.0
                    var wI = 0.0
                    for (k in 0 until halfLen) {
                        val uR = real[i + k]
                        val uI = imag[i + k]
                        val vR = real[i + k + halfLen] * wR - imag[i + k + halfLen] * wI
                        val vI = real[i + k + halfLen] * wI + imag[i + k + halfLen] * wR
                        real[i + k] = uR + vR
                        imag[i + k] = uI + vI
                        real[i + k + halfLen] = uR - vR
                        imag[i + k + halfLen] = uI - vI
                        val nextWR = wR * wLenR - wI * wLenI
                        wI = wR * wLenI + wI * wLenR
                        wR = nextWR
                    }
                    i += len
                }
                len = len shl 1
            }

            if (inverse) {
                val invN = 1.0 / n.toDouble()
                for (i in 0 until n) {
                    real[i] *= invN
                    imag[i] *= invN
                }
            }
        }
    }
}
