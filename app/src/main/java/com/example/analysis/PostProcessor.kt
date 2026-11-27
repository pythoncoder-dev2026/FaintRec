package com.example.analysis

import android.content.Context
import com.example.audio.WavFileHelper
import com.example.data.RecordingEntity
import com.example.data.RecordingSourceTag
import com.example.storage.StorageLocationManager
import com.example.util.AudioMath
import com.example.util.FaintRecFolder
import com.example.util.StorageHelper
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

data class PostProcessOptions(
    val normalizeToMinus1Db: Boolean = true,
    val spectralNoiseReduction: Boolean = false,
    val noiseReductionStrength: Float = 0.65f,
    val enableCompressor: Boolean = false,
    val compressorThresholdDb: Float = -24f,
    val compressorRatio: Float = 4f,
    val compressorMakeupDb: Float = 6f
)

@Singleton
class PostProcessor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val storageLocationManager: StorageLocationManager
) {
    /**
     * Non-destructively processes a WAV recording (Normalize to -1 dBFS, Spectral Gate Denoise,
     * Dynamic Compressor) and exports a brand-new WAV copy while leaving the original untouched.
     */
    suspend fun processAndExport(
        source: RecordingEntity,
        options: PostProcessOptions,
        onProgress: (Float) -> Unit = {}
    ): Result<RecordingEntity> = withContext(Dispatchers.IO) {
        runCatching {
            val srcFile = storageLocationManager.resolveReadableFile(source)
                ?: throw IllegalStateException("Source file is unavailable on storage.")
            val info = WavFileHelper.parseWavInfo(srcFile)
                ?: throw IllegalStateException("Source file is not a readable WAV file.")

            val baseName = StorageHelper.sanitizeFileName(source.title).substringBeforeLast('.')
            val outName = "${baseName}_processed_${System.currentTimeMillis() % 10000}.wav"
            val outFile = StorageHelper.createWorkingFile(context, FaintRecFolder.PROCESSED, outName)

            val totalFrames = info.totalFrames
            val channels = info.channels
            val sampleRate = info.sampleRate

            // Pass 1: estimate noise profile (lowest energy 15% frames) & find peak if normalizing
            val chunkFrames = 4096
            val buf = FloatArray(chunkFrames * channels)
            var peakAbs = 1e-6f

            val fftSize = 1024
            val half = fftSize / 2 + 1
            val noiseFloorMag = DoubleArray(half) { 1e-5 }
            if (options.spectralNoiseReduction) {
                estimateNoiseSpectrum(srcFile, info, fftSize, noiseFloorMag)
            }

            if (options.normalizeToMinus1Db) {
                RandomAccessFile(srcFile, "r").use { raf ->
                    var framePos = 0L
                    while (framePos < totalFrames) {
                        val read = WavFileHelper.readFramesAsFloat(raf, info, framePos, chunkFrames, buf)
                        if (read <= 0) break
                        val n = read * channels
                        for (i in 0 until n) {
                            val a = abs(buf[i])
                            if (a > peakAbs) peakAbs = a
                        }
                        framePos += read
                        onProgress((framePos.toFloat() / totalFrames.coerceAtLeast(1L)) * 0.25f)
                    }
                }
            }

            val normGain = if (options.normalizeToMinus1Db && peakAbs > 1e-5f) {
                AudioMath.dbToLinear(-1.0f) / peakAbs
            } else 1f

            // Compressor state
            val attackCoeff = exp(-1.0 / (0.008 * sampleRate)).toFloat()
            val releaseCoeff = exp(-1.0 / (0.120 * sampleRate)).toFloat()
            var env = 0f
            val compThreshLin = AudioMath.dbToLinear(options.compressorThresholdDb)
            val compMakeup = AudioMath.dbToLinear(options.compressorMakeupDb)

            // Pass 2: stream chunks, apply spectral gate + compressor + normalize, write WAV
            var dataBytesWritten = 0L
            RandomAccessFile(srcFile, "r").use { inRaf ->
                RandomAccessFile(outFile, "rw").use { outRaf ->
                    outRaf.setLength(0L)
                    WavFileHelper.writePlaceholderHeader(
                        outRaf,
                        sampleRate = sampleRate,
                        channels = channels,
                        bitsPerSample = if (info.isFloat) 32 else 16,
                        isFloat = info.isFloat
                    )

                    // Overlap-add buffers per channel for spectral gating
                    val hop = fftSize / 2
                    val window = FftProcessor.buildWindow(fftSize, FftWindowType.HANN)
                    val olaPrevIn = Array(channels) { FloatArray(hop) }
                    val olaPrevOut = Array(channels) { FloatArray(hop) }

                    var framePos = 0L
                    while (framePos < totalFrames) {
                        val read = WavFileHelper.readFramesAsFloat(inRaf, info, framePos, chunkFrames, buf)
                        if (read <= 0) break

                        if (options.spectralNoiseReduction) {
                            applySpectralGateInPlace(
                                interleaved = buf,
                                frames = read,
                                channels = channels,
                                fftSize = fftSize,
                                window = window,
                                noiseFloor = noiseFloorMag,
                                strength = options.noiseReductionStrength,
                                prevIn = olaPrevIn,
                                prevOut = olaPrevOut
                            )
                        }

                        val totalSamples = read * channels
                        if (options.enableCompressor) {
                            for (f in 0 until read) {
                                var framePeak = 0f
                                for (c in 0 until channels) {
                                    val a = abs(buf[f * channels + c])
                                    if (a > framePeak) framePeak = a
                                }
                                val coeff = if (framePeak > env) attackCoeff else releaseCoeff
                                env = coeff * env + (1f - coeff) * framePeak

                                val gainReduction = if (env > compThreshLin && env > 1e-6f) {
                                    val overDb = AudioMath.linearToDb(env) - options.compressorThresholdDb
                                    val reducedDb = overDb * (1f - 1f / options.compressorRatio.coerceAtLeast(1f))
                                    AudioMath.dbToLinear(-reducedDb)
                                } else 1f

                                val totalCompGain = gainReduction * compMakeup
                                for (c in 0 until channels) {
                                    val idx = f * channels + c
                                    buf[idx] = AudioMath.softLimit(buf[idx] * totalCompGain)
                                }
                            }
                        }

                        if (options.normalizeToMinus1Db) {
                            for (i in 0 until totalSamples) {
                                buf[i] = AudioMath.softLimit(buf[i] * normGain, ceiling = 0.8913f) // -1 dBFS
                            }
                        }

                        dataBytesWritten += WavFileHelper.writeFloatSamples(
                            outRaf,
                            buf,
                            totalSamples,
                            writeAsFloat32 = info.isFloat
                        )
                        framePos += read
                        onProgress(0.25f + (framePos.toFloat() / totalFrames.coerceAtLeast(1L)) * 0.75f)
                    }

                    WavFileHelper.patchWavHeaderSizes(outRaf, dataBytesWritten)
                }
            }

            val mediaUri = StorageHelper.publishToMediaStore(
                context = context,
                sourceFile = outFile,
                folder = FaintRecFolder.PROCESSED,
                mimeType = "audio/wav",
                displayName = outFile.name
            )

            val suffix = buildList {
                if (options.normalizeToMinus1Db) add("Norm")
                if (options.spectralNoiseReduction) add("Denoise")
                if (options.enableCompressor) add("Comp")
            }.joinToString("+").ifBlank { "Processed" }

            RecordingEntity(
                title = "${source.title} [$suffix]",
                filePath = outFile.absolutePath,
                mediaStoreUri = mediaUri,
                durationMs = info.durationMs,
                fileSizeBytes = outFile.length(),
                format = "WAV",
                sampleRate = info.sampleRate,
                channels = info.channels,
                bitsPerSample = if (info.isFloat) 32 else 16,
                isFloatPcm = info.isFloat,
                sourceTag = RecordingSourceTag.PROCESSED,
                isLossySource = source.isLossySource,
                audioSourceUsed = source.audioSourceUsed,
                inputDeviceName = source.inputDeviceName,
                savedRaw = false
            )
        }
    }

    private fun estimateNoiseSpectrum(
        wavFile: File,
        info: com.example.audio.WavInfo,
        fftSize: Int,
        outNoiseFloor: DoubleArray
    ) {
        val window = FftProcessor.buildWindow(fftSize, FftWindowType.HANN)
        val real = DoubleArray(fftSize)
        val imag = DoubleArray(fftSize)
        val buf = FloatArray(fftSize * info.channels)
        val samplesToCheck = min(40, max(4, (info.totalFrames / fftSize).toInt()))
        val step = max(1L, info.totalFrames / samplesToCheck)
        val frameSpectra = ArrayList<Pair<Double, DoubleArray>>(samplesToCheck)

        RandomAccessFile(wavFile, "r").use { raf ->
            for (s in 0 until samplesToCheck) {
                val read = WavFileHelper.readFramesAsFloat(raf, info, s * step, fftSize, buf)
                if (read < fftSize / 2) break
                var energy = 0.0
                for (i in 0 until fftSize) {
                    val sample = if (i < read) buf[i * info.channels].toDouble() else 0.0
                    energy += sample * sample
                    real[i] = sample * window[i]
                    imag[i] = 0.0
                }
                FftProcessor.fftInPlace(real, imag, inverse = false)
                val mag = DoubleArray(outNoiseFloor.size)
                for (k in mag.indices) {
                    mag[k] = sqrt(real[k] * real[k] + imag[k] * imag[k])
                }
                frameSpectra.add(energy to mag)
            }
        }
        if (frameSpectra.isEmpty()) return
        frameSpectra.sortBy { it.first }
        val quietCount = max(1, (frameSpectra.size * 0.20).toInt())
        for (k in outNoiseFloor.indices) {
            var sum = 0.0
            for (q in 0 until quietCount) sum += frameSpectra[q].second[k]
            outNoiseFloor[k] = (sum / quietCount) * 1.4
        }
    }

    private fun applySpectralGateInPlace(
        interleaved: FloatArray,
        frames: Int,
        channels: Int,
        fftSize: Int,
        window: DoubleArray,
        noiseFloor: DoubleArray,
        strength: Float,
        prevIn: Array<FloatArray>,
        prevOut: Array<FloatArray>
    ) {
        val hop = fftSize / 2
        val real = DoubleArray(fftSize)
        val imag = DoubleArray(fftSize)
        val attenFloor = (1.0 - strength.coerceIn(0.1f, 0.95f)).coerceAtLeast(0.05)

        for (c in 0 until channels) {
            var pos = 0
            while (pos + hop <= frames) {
                for (i in 0 until hop) {
                    real[i] = prevIn[c][i].toDouble() * window[i]
                    imag[i] = 0.0
                }
                for (i in 0 until hop) {
                    val cur = interleaved[(pos + i) * channels + c]
                    prevIn[c][i] = cur
                    real[hop + i] = cur.toDouble() * window[hop + i]
                    imag[hop + i] = 0.0
                }

                FftProcessor.fftInPlace(real, imag, inverse = false)

                for (k in 0..hop) {
                    val mag = sqrt(real[k] * real[k] + imag[k] * imag[k])
                    val nf = noiseFloor[k]
                    val gain = if (mag < nf) {
                        attenFloor
                    } else if (mag < nf * 2.5) {
                        val t = (mag - nf) / (nf * 1.5)
                        attenFloor + (1.0 - attenFloor) * t.pow(1.5)
                    } else 1.0

                    real[k] *= gain
                    imag[k] *= gain
                    if (k in 1 until hop) {
                        real[fftSize - k] *= gain
                        imag[fftSize - k] *= gain
                    }
                }

                FftProcessor.fftInPlace(real, imag, inverse = true)

                for (i in 0 until hop) {
                    interleaved[(pos + i) * channels + c] =
                        (prevOut[c][i] + real[i].toFloat()).coerceIn(-1f, 1f)
                    prevOut[c][i] = real[hop + i].toFloat()
                }
                pos += hop
            }
        }
    }
}
