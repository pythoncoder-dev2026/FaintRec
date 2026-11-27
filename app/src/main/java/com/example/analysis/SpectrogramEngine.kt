package com.example.analysis

import com.example.audio.WavFileHelper
import com.example.util.AudioMath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

enum class SpectrogramColorMap {
    STUDIO_CYAN_AMBER,
    INFERNO,
    VIRIDIS,
    GRAYSCALE
}

data class SpectrogramData(
    val frames: List<FloatArray>, // Each frame has numBins normalized magnitudes [0f..1f]
    val sampleRate: Int,
    val fftSize: Int,
    val durationMs: Long
) {
    val nyquistHz: Float get() = sampleRate / 2f
    val numBins: Int get() = fftSize / 2
}

object SpectrogramEngine {

    /**
     * Generates an STFT spectrogram in chunks on a background dispatcher without loading
     * the entire audio file into RAM. Scales up to Nyquist (including 96 kHz / 192 kHz ultrasonic).
     */
    suspend fun computeSpectrogram(
        wavFile: File,
        fftSize: Int = 1024,
        maxTimeColumns: Int = 360,
        minDb: Float = -90f,
        maxDb: Float = -6f
    ): SpectrogramData? = withContext(Dispatchers.Default) {
        val info = WavFileHelper.parseWavInfo(wavFile) ?: return@withContext null
        if (info.totalFrames <= 0L) return@withContext null

        val n = FftProcessor.validatePowerOfTwo(fftSize.coerceIn(1024, 4096))
        val half = n / 2
        val window = FftProcessor.buildWindow(n, FftWindowType.HANN)
        val real = DoubleArray(n)
        val imag = DoubleArray(n)
        val interleaveBuf = FloatArray(n * info.channels)

        val totalCols = max(1, min(maxTimeColumns, (info.totalFrames / (n / 2)).toInt().coerceAtLeast(1)))
        val hopFrames = max(1L, (info.totalFrames - n).coerceAtLeast(1L) / totalCols)
        val frames = ArrayList<FloatArray>(totalCols)
        val dbRange = max(1f, maxDb - minDb)
        val norm = 2.0 / n.toDouble()

        RandomAccessFile(wavFile, "r").use { raf ->
            for (col in 0 until totalCols) {
                val startFrame = (col * hopFrames).coerceAtMost((info.totalFrames - 1).coerceAtLeast(0))
                val read = WavFileHelper.readFramesAsFloat(raf, info, startFrame, n, interleaveBuf)
                for (i in 0 until n) {
                    val mono = if (i < read) {
                        if (info.channels == 1) interleaveBuf[i]
                        else {
                            var sum = 0f
                            for (c in 0 until info.channels) sum += interleaveBuf[i * info.channels + c]
                            sum / info.channels
                        }
                    } else 0f
                    real[i] = mono.toDouble() * window[i]
                    imag[i] = 0.0
                }
                FftProcessor.fftInPlace(real, imag, inverse = false)
                val magCol = FloatArray(half)
                for (k in 0 until half) {
                    val mag = sqrt(real[k] * real[k] + imag[k] * imag[k]) * norm
                    val db = max(AudioMath.MIN_DB, (20.0 * log10(max(mag, 1e-6))).toFloat())
                    magCol[k] = ((db - minDb) / dbRange).coerceIn(0f, 1f)
                }
                frames.add(magCol)
            }
        }

        SpectrogramData(
            frames = frames,
            sampleRate = info.sampleRate,
            fftSize = n,
            durationMs = info.durationMs
        )
    }

    /**
     * Maps normalized intensity [0f..1f] to packed ARGB color for fast Canvas rendering.
     */
    fun colorForValue(value: Float, map: SpectrogramColorMap): Int {
        val v = value.coerceIn(0f, 1f)
        val r: Int
        val g: Int
        val b: Int
        when (map) {
            SpectrogramColorMap.STUDIO_CYAN_AMBER -> {
                when {
                    v < 0.25f -> {
                        val t = v / 0.25f
                        r = (9 + t * 5).toInt()
                        g = (12 + t * 48).toInt()
                        b = (20 + t * 85).toInt()
                    }
                    v < 0.60f -> {
                        val t = (v - 0.25f) / 0.35f
                        r = (14 + t * 10).toInt()
                        g = (60 + t * 170).toInt()
                        b = (105 + t * 150).toInt()
                    }
                    v < 0.85f -> {
                        val t = (v - 0.60f) / 0.25f
                        r = (24 + t * 231).toInt()
                        g = (230 - t * 50).toInt()
                        b = (255 - t * 225).toInt()
                    }
                    else -> {
                        val t = (v - 0.85f) / 0.15f
                        r = 255
                        g = (180 + t * 75).toInt()
                        b = (30 + t * 200).toInt()
                    }
                }
            }
            SpectrogramColorMap.INFERNO -> {
                when {
                    v < 0.33f -> {
                        val t = v / 0.33f
                        r = (t * 110).toInt()
                        g = (t * 20).toInt()
                        b = (15 + t * 120).toInt()
                    }
                    v < 0.66f -> {
                        val t = (v - 0.33f) / 0.33f
                        r = (110 + t * 130).toInt()
                        g = (20 + t * 90).toInt()
                        b = (135 - t * 105).toInt()
                    }
                    else -> {
                        val t = (v - 0.66f) / 0.34f
                        r = (240 + t * 15).toInt()
                        g = (110 + t * 142).toInt()
                        b = (30 + t * 140).toInt()
                    }
                }
            }
            SpectrogramColorMap.VIRIDIS -> {
                when {
                    v < 0.35f -> {
                        val t = v / 0.35f
                        r = (68 - t * 18).toInt()
                        g = (1 + t * 90).toInt()
                        b = (84 + t * 56).toInt()
                    }
                    v < 0.70f -> {
                        val t = (v - 0.35f) / 0.35f
                        r = (50 - t * 18).toInt()
                        g = (91 + t * 95).toInt()
                        b = (140 - t * 22).toInt()
                    }
                    else -> {
                        val t = (v - 0.70f) / 0.30f
                        r = (32 + t * 221).toInt()
                        g = (186 + t * 45).toInt()
                        b = (118 - t * 80).toInt()
                    }
                }
            }
            SpectrogramColorMap.GRAYSCALE -> {
                val c = (v * 255f).toInt()
                r = c; g = c; b = c
            }
        }
        return (0xFF shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)
    }
}
