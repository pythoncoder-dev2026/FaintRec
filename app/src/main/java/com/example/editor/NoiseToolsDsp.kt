package com.example.editor

import com.example.analysis.FftProcessor
import com.example.analysis.FftWindowType
import com.example.audio.WavFileHelper
import com.example.data.NoiseAddParams
import com.example.data.NoiseLevelParams
import com.example.data.NoiseProfilePayload
import com.example.data.NoiseRemovalParams
import com.example.data.NoiseType
import com.example.data.OneTapNoiseTool
import com.example.util.AudioMath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Learns per-bin noise profiles (mean & std magnitude) from a user-selected region
 * or automatically detects the quietest 1-second window in a WAV recording.
 */
object NoiseProfileLearner {

    suspend fun learnFromRegion(
        wavFile: File,
        startMs: Long,
        endMs: Long,
        fftSize: Int = 1024
    ): NoiseProfilePayload? = withContext(Dispatchers.Default) {
        val info = WavFileHelper.parseWavInfo(wavFile) ?: return@withContext null
        val sr = info.sampleRate
        val ch = info.channels.coerceIn(1, 2)
        val sFrame = ((startMs.coerceAtLeast(0L) * sr) / 1000L).coerceIn(0L, info.totalFrames - 1)
        val eFrame = ((endMs.coerceAtLeast(startMs + 100L) * sr) / 1000L).coerceIn(sFrame + fftSize, info.totalFrames)
        val totalLearnFrames = (eFrame - sFrame).toInt().coerceAtMost(sr * 15) // cap at 15s for speed
        if (totalLearnFrames < fftSize) return@withContext null

        val raw = FloatArray(totalLearnFrames * ch)
        val readFrames = RandomAccessFile(wavFile, "r").use { raf ->
            WavFileHelper.readFramesAsFloat(raf, info, sFrame, totalLearnFrames, raw)
        }
        if (readFrames < fftSize) return@withContext null

        val mono = FloatArray(readFrames) { f ->
            var sum = 0f
            for (c in 0 until ch) sum += raw[f * ch + c]
            sum / ch
        }
        computeProfileFromMono(mono, sr, fftSize, startMs, (sFrame + readFrames) * 1000L / sr)
    }

    suspend fun autoDetectQuietest1Second(
        wavFile: File,
        fftSize: Int = 1024
    ): NoiseProfilePayload? = withContext(Dispatchers.Default) {
        val info = WavFileHelper.parseWavInfo(wavFile) ?: return@withContext null
        val sr = info.sampleRate
        val ch = info.channels.coerceIn(1, 2)
        val windowFrames = sr.coerceAtMost(info.totalFrames.toInt()).coerceAtLeast(fftSize)
        val hopFrames = (sr / 4).coerceAtLeast(fftSize / 2)

        var bestStartFrame = 0L
        var bestRmsSq = Double.MAX_VALUE
        val scanBuf = FloatArray(windowFrames * ch)

        RandomAccessFile(wavFile, "r").use { raf ->
            var pos = 0L
            while (pos + windowFrames <= info.totalFrames) {
                val n = WavFileHelper.readFramesAsFloat(raf, info, pos, windowFrames, scanBuf)
                if (n < fftSize) break
                var sumSq = 0.0
                val totalS = n * ch
                for (i in 0 until totalS) {
                    val v = scanBuf[i].toDouble()
                    sumSq += v * v
                }
                val meanSq = sumSq / totalS.coerceAtLeast(1)
                // Ignore pure digital zero regions (e.g. silenced gaps) so we learn real ambient floor
                if (meanSq > 1e-11 && meanSq < bestRmsSq) {
                    bestRmsSq = meanSq
                    bestStartFrame = pos
                }
                pos += hopFrames
            }
        }

        val startMs = (bestStartFrame * 1000L) / sr
        val endMs = ((bestStartFrame + windowFrames) * 1000L) / sr
        learnFromRegion(wavFile, startMs, endMs, fftSize)
    }

    fun computeProfileFromMono(
        mono: FloatArray,
        sampleRate: Int,
        fftSize: Int = 1024,
        startMs: Long = 0L,
        endMs: Long = 0L
    ): NoiseProfilePayload {
        val half = fftSize / 2 + 1
        val hop = fftSize / 2
        val window = FftProcessor.buildWindow(fftSize, FftWindowType.HANN)
        val real = DoubleArray(fftSize)
        val imag = DoubleArray(fftSize)

        val sumMag = DoubleArray(half)
        val sumSqMag = DoubleArray(half)
        var frameCount = 0

        var pos = 0
        while (pos + fftSize <= mono.size) {
            for (i in 0 until fftSize) {
                real[i] = mono[pos + i].toDouble() * window[i]
                imag[i] = 0.0
            }
            FftProcessor.fftInPlace(real, imag, inverse = false)
            for (k in 0 until half) {
                val mag = sqrt(real[k] * real[k] + imag[k] * imag[k])
                sumMag[k] += mag
                sumSqMag[k] += mag * mag
            }
            frameCount++
            pos += hop
        }

        val n = frameCount.coerceAtLeast(1).toDouble()
        val meanList = MutableList(half) { k -> (sumMag[k] / n).toFloat() }
        val stdList = MutableList(half) { k ->
            val mean = sumMag[k] / n
            val variance = max(0.0, (sumSqMag[k] / n) - mean * mean)
            sqrt(variance).toFloat()
        }

        return NoiseProfilePayload(
            fftSize = fftSize,
            sampleRate = sampleRate,
            meanMag = meanList,
            stdMag = stdList,
            learnedStartMs = startMs,
            learnedEndMs = endMs
        )
    }
}

/**
 * Overlap-add STFT processor (FFT 1024, Hann window, 50% overlap) performing:
 *  - Spectral gating / spectral subtraction with reduction (0-40 dB), sensitivity, frequency smoothing,
 *    and "Keep faint sounds" soft-knee harmonic preservation.
 *  - Spectral noise separation for Noise Level Control: `output = clean + noise * gain`
 *    with global existing-noise slider (-40..+12 dB) and Low (<300 Hz), Mid (300 Hz-4 kHz), High (>4 kHz) sliders.
 *  - "Noise only" listen mode returning only the separated noise residual.
 */
class SpectralNoiseProcessor(
    private val sampleRate: Int,
    private val channels: Int,
    private val fftSize: Int = 1024
) {
    private val hop = fftSize / 2
    private val half = fftSize / 2 + 1
    private val window = FftProcessor.buildWindow(fftSize, FftWindowType.HANN)

    // Per-channel overlap-add buffers
    private val inputRing = Array(channels) { FloatArray(fftSize) }
    private val inputFill = IntArray(channels)
    private val overlapTail = Array(channels) { FloatArray(hop) }
    private val outQueue = Array(channels) { FloatArray(fftSize * 8) }
    private val outReadPos = IntArray(channels)
    private val outWritePos = IntArray(channels)

    // Adaptive fallback noise floor per channel if user hasn't learned a profile yet
    private val adaptiveFloor = Array(channels) { FloatArray(half) { 0.002f } }
    private val adaptiveInitialized = BooleanArray(channels)

    private val real = DoubleArray(fftSize)
    private val imag = DoubleArray(fftSize)
    private val cleanReal = DoubleArray(fftSize)
    private val cleanImag = DoubleArray(fftSize)
    private val rawGainMask = FloatArray(half)
    private val smoothGainMask = FloatArray(half)

    fun reset() {
        for (c in 0 until channels) {
            inputRing[c].fill(0f)
            inputFill[c] = 0
            overlapTail[c].fill(0f)
            outReadPos[c] = 0
            outWritePos[c] = 0
            adaptiveInitialized[c] = false
        }
    }

    fun processInterleaved(
        buffer: FloatArray,
        frames: Int,
        profile: NoiseProfilePayload?,
        removal: NoiseRemovalParams,
        level: NoiseLevelParams,
        noiseOnlyMode: Boolean
    ) {
        val removalActive = removal.enabled && removal.reductionDb > 0.1f
        val levelActive = level.enabled && (
            abs(level.existingNoiseDb) > 0.1f ||
                abs(level.lowBandNoiseDb) > 0.1f ||
                abs(level.midBandNoiseDb) > 0.1f ||
                abs(level.highBandNoiseDb) > 0.1f
            )
        if (!removalActive && !levelActive && !noiseOnlyMode) return

        val binHz = sampleRate.toFloat() / fftSize.toFloat()
        val profileMatches = profile != null && profile.meanMag.size >= half

        for (c in 0 until channels) {
            // Feed samples into per-channel STFT buffer
            for (f in 0 until frames) {
                val sample = buffer[f * channels + c]
                inputRing[c][inputFill[c]] = sample
                inputFill[c]++

                if (inputFill[c] == fftSize) {
                    processStftFrame(
                        ch = c,
                        binHz = binHz,
                        profile = if (profileMatches) profile else null,
                        removal = removal,
                        level = level,
                        removalActive = removalActive,
                        levelActive = levelActive,
                        noiseOnlyMode = noiseOnlyMode
                    )
                    // 50% overlap shift
                    System.arraycopy(inputRing[c], hop, inputRing[c], 0, hop)
                    inputFill[c] = hop
                }
            }

            // Drain processed samples back into interleaved buffer (latency-compensated fallback during warmup)
            val avail = outWritePos[c] - outReadPos[c]
            for (f in 0 until frames) {
                if (outReadPos[c] < outWritePos[c]) {
                    buffer[f * channels + c] = outQueue[c][outReadPos[c] % outQueue[c].size]
                    outReadPos[c]++
                } else if (avail == 0 && noiseOnlyMode) {
                    buffer[f * channels + c] = 0f
                }
            }
        }
    }

    private fun processStftFrame(
        ch: Int,
        binHz: Float,
        profile: NoiseProfilePayload?,
        removal: NoiseRemovalParams,
        level: NoiseLevelParams,
        removalActive: Boolean,
        levelActive: Boolean,
        noiseOnlyMode: Boolean
    ) {
        val frame = inputRing[ch]
        for (i in 0 until fftSize) {
            real[i] = frame[i].toDouble() * window[i]
            imag[i] = 0.0
        }
        FftProcessor.fftInPlace(real, imag, inverse = false)

        val floor = adaptiveFloor[ch]
        val sens = removal.sensitivity.coerceIn(0.4f, 3.5f)
        val keepFaint = removal.keepFaintSounds

        // 1. Estimate per-bin speech/signal presence mask (cleanMask in 0..1)
        for (k in 0 until half) {
            val mag = sqrt(real[k] * real[k] + imag[k] * imag[k]).toFloat()
            val noiseRef = if (profile != null) {
                val mean = profile.meanMag[k]
                val std = profile.stdMag.getOrElse(k) { mean * 0.25f }
                (mean + std * (sens - 0.8f).coerceAtLeast(0f)) * sens
            } else {
                if (!adaptiveInitialized[ch]) {
                    floor[k] = mag * 0.65f
                } else if (mag < floor[k]) {
                    floor[k] = floor[k] * 0.90f + mag * 0.10f
                } else {
                    floor[k] = floor[k] * 0.996f + mag * 0.004f
                }
                floor[k] * sens * 1.4f
            }

            val denom = max(mag, 1e-7f)
            val snr = mag / max(noiseRef, 1e-7f)

            // Soft spectral separation ratio: cleanMask = fraction of bin belonging to desired signal
            val cleanMask = if (keepFaint) {
                // Soft-knee Wiener-like mask preserving weak faint sounds near the noise floor
                val snrPow = snr * snr
                val knee = (snrPow / (snrPow + 0.85f)).coerceIn(0f, 1f)
                if (snr > 0.75f && snr < 2.2f) max(knee, 0.28f * snr / 2.2f) else knee
            } else {
                // Classic oversubtraction spectral gate
                val excess = (mag - noiseRef) / denom
                excess.coerceIn(0f, 1f)
            }
            rawGainMask[k] = cleanMask
        }
        adaptiveInitialized[ch] = true

        // 2. Frequency smoothing across adjacent bins
        val smoothRadius = removal.freqSmoothingBins.coerceIn(0, 8)
        if (smoothRadius > 0) {
            for (k in 0 until half) {
                val lo = max(0, k - smoothRadius)
                val hi = min(half - 1, k + smoothRadius)
                var sum = 0f
                for (b in lo..hi) sum += rawGainMask[b]
                smoothGainMask[k] = sum / (hi - lo + 1)
            }
        } else {
            System.arraycopy(rawGainMask, 0, smoothGainMask, 0, half)
        }

        // 3. Combine clean + noise * gain (with spectral removal floor + 3-band Noise Level Control)
        val removalFloorLin = if (removalActive) {
            AudioMath.dbToLinear(-removal.reductionDb.coerceIn(0f, 40f))
        } else 1f
        val globalNoiseLin = if (levelActive) {
            AudioMath.dbToLinear(level.existingNoiseDb.coerceIn(-40f, 12f))
        } else 1f
        val lowNoiseLin = if (levelActive) AudioMath.dbToLinear(level.lowBandNoiseDb.coerceIn(-40f, 12f)) else 1f
        val midNoiseLin = if (levelActive) AudioMath.dbToLinear(level.midBandNoiseDb.coerceIn(-40f, 12f)) else 1f
        val highNoiseLin = if (levelActive) AudioMath.dbToLinear(level.highBandNoiseDb.coerceIn(-40f, 12f)) else 1f

        for (k in 0 until half) {
            val freq = k * binHz
            val bandGain = when {
                freq < 300f -> lowNoiseLin
                freq <= 4000f -> midNoiseLin
                else -> highNoiseLin
            }
            val cleanFrac = smoothGainMask[k].coerceIn(0f, 1f)
            val noiseFrac = 1f - cleanFrac
            val effectiveNoiseGain = (removalFloorLin * globalNoiseLin * bandGain).coerceIn(0.005f, 6.0f)

            val totalBinGain = if (noiseOnlyMode) {
                // Listen exclusively to the separated noise component
                noiseFrac * effectiveNoiseGain
            } else {
                // output = clean + noise * gain
                cleanFrac + noiseFrac * effectiveNoiseGain
            }

            cleanReal[k] = real[k] * totalBinGain
            cleanImag[k] = imag[k] * totalBinGain
            if (k in 1 until (fftSize / 2)) {
                val mirror = fftSize - k
                cleanReal[mirror] = real[mirror] * totalBinGain
                cleanImag[mirror] = imag[mirror] * totalBinGain
            }
        }

        FftProcessor.fftInPlace(cleanReal, cleanImag, inverse = true)

        // 4. Overlap-add synthesis (Hann window with 50% overlap sums to 1.0 seamlessly)
        val queue = outQueue[ch]
        val tail = overlapTail[ch]
        for (i in 0 until hop) {
            val outSample = (cleanReal[i].toFloat() + tail[i]).coerceIn(-4f, 4f)
            queue[outWritePos[ch] % queue.size] = outSample
            outWritePos[ch]++
            tail[i] = cleanReal[i + hop].toFloat()
        }
    }
}

/**
 * Stateful one-tap noise cleanup tools:
 *  - Hiss remover (high-frequency >4 kHz spectral damping + soft expander)
 *  - Hum remover (50 Hz or 60 Hz notch + 2nd..5th harmonics)
 *  - Click/pop remover (local impulse spike detection & linear interpolation)
 *  - Wind-noise cut (adaptive high-pass filter tracking <180 Hz rumble energy)
 */
class OneTapNoiseProcessor(
    private val sampleRate: Int,
    private val channels: Int
) {
    private val notchBanks = Array(channels) { Array(5) { BiquadFilter() } }
    private val hissShelf = Array(channels) { BiquadFilter() }
    private val hissBand = Array(channels) { BiquadFilter() }
    private val windHpf1 = Array(channels) { BiquadFilter() }
    private val windHpf2 = Array(channels) { BiquadFilter() }
    private val rumbleLpf = Array(channels) { BiquadFilter() }

    private var configuredTool: OneTapNoiseTool = OneTapNoiseTool.NONE
    private var adaptiveWindCutoffHz = 90.0

    fun configure(tool: OneTapNoiseTool) {
        if (tool == configuredTool) return
        configuredTool = tool
        val sr = sampleRate.toDouble()
        for (c in 0 until channels) {
            notchBanks[c].forEach { it.reset(); it.setBypass() }
            hissShelf[c].reset(); hissShelf[c].setBypass()
            hissBand[c].reset(); hissBand[c].setBypass()
            windHpf1[c].reset(); windHpf1[c].setBypass()
            windHpf2[c].reset(); windHpf2[c].setBypass()
            rumbleLpf[c].reset(); rumbleLpf[c].setLowPass(sr, 180.0)

            when (tool) {
                OneTapNoiseTool.HUM_REMOVER_50HZ -> {
                    for (h in 1..5) {
                        notchBanks[c][h - 1].setNotch(sr, 50.0 * h, q = 22.0)
                    }
                }
                OneTapNoiseTool.HUM_REMOVER_60HZ -> {
                    for (h in 1..5) {
                        notchBanks[c][h - 1].setNotch(sr, 60.0 * h, q = 22.0)
                    }
                }
                OneTapNoiseTool.HISS_REMOVER -> {
                    hissShelf[c].setHighShelf(sr, 4500.0, gainDb = -10.5, slope = 0.85)
                    hissBand[c].setHighPass(sr, 4500.0)
                }
                OneTapNoiseTool.WIND_NOISE_CUT -> {
                    adaptiveWindCutoffHz = 95.0
                    windHpf1[c].setHighPass(sr, adaptiveWindCutoffHz)
                    windHpf2[c].setHighPass(sr, adaptiveWindCutoffHz)
                }
                else -> Unit
            }
        }
    }

    fun processInterleaved(buffer: FloatArray, frames: Int, tool: OneTapNoiseTool) {
        if (tool == OneTapNoiseTool.NONE) return
        configure(tool)

        when (tool) {
            OneTapNoiseTool.HUM_REMOVER_50HZ,
            OneTapNoiseTool.HUM_REMOVER_60HZ -> {
                for (f in 0 until frames) {
                    for (c in 0 until channels) {
                        val idx = f * channels + c
                        var s = buffer[idx]
                        val bank = notchBanks[c]
                        for (h in 0 until 5) s = bank[h].process(s)
                        buffer[idx] = s
                    }
                }
            }
            OneTapNoiseTool.HISS_REMOVER -> {
                // Dynamic de-hiss: attenuates quiet high-frequency hiss (>4.5 kHz) more aggressively
                for (c in 0 until channels) {
                    var hfEnv = 0f
                    for (f in 0 until frames) {
                        val idx = f * channels + c
                        val x = buffer[idx]
                        val hf = hissBand[c].process(x)
                        hfEnv = hfEnv * 0.96f + abs(hf) * 0.04f
                        val damped = hissShelf[c].process(x)
                        // Stronger damping when high-frequency energy is low-level steady hiss
                        val blend = if (hfEnv < 0.04f) 1.0f else (0.04f / hfEnv).coerceIn(0.25f, 1.0f)
                        buffer[idx] = x * (1f - blend) + damped * blend
                    }
                }
            }
            OneTapNoiseTool.CLICK_POP_REMOVER -> {
                removeClicksPerChannel(buffer, frames)
            }
            OneTapNoiseTool.WIND_NOISE_CUT -> {
                // Measure <180 Hz rumble ratio in this chunk and adapt HPF cutoff (80 Hz .. 280 Hz)
                var rumbleSq = 0.0
                var fullSq = 0.0
                for (f in 0 until frames) {
                    val s = buffer[f * channels]
                    val r = rumbleLpf[0].process(s)
                    rumbleSq += (r * r).toDouble()
                    fullSq += (s * s).toDouble()
                }
                val rumbleRatio = (rumbleSq / max(fullSq, 1e-9)).coerceIn(0.0, 1.0)
                val targetCutoff = 80.0 + rumbleRatio * 200.0
                adaptiveWindCutoffHz = adaptiveWindCutoffHz * 0.75 + targetCutoff * 0.25
                val sr = sampleRate.toDouble()
                for (c in 0 until channels) {
                    windHpf1[c].setHighPass(sr, adaptiveWindCutoffHz)
                    windHpf2[c].setHighPass(sr, adaptiveWindCutoffHz)
                }
                for (f in 0 until frames) {
                    for (c in 0 until channels) {
                        val idx = f * channels + c
                        var s = windHpf1[c].process(buffer[idx])
                        s = windHpf2[c].process(s)
                        buffer[idx] = s
                    }
                }
            }
            OneTapNoiseTool.NONE -> Unit
        }
    }

    private fun removeClicksPerChannel(buffer: FloatArray, frames: Int) {
        if (frames < 16) return
        for (c in 0 until channels) {
            // Compute local second-derivative envelope to pinpoint sharp impulsive clicks/pops
            var absDiffAvg = 0.0005f
            var f = 2
            while (f < frames - 3) {
                val prev = buffer[(f - 1) * channels + c]
                val cur = buffer[f * channels + c]
                val next = buffer[(f + 1) * channels + c]
                val diff = abs(cur - 0.5f * (prev + next))
                absDiffAvg = absDiffAvg * 0.97f + diff * 0.03f

                if (diff > max(absDiffAvg * 7.5f, 0.045f)) {
                    // Interpolate over short click burst (up to 10 frames)
                    val burstStart = (f - 1).coerceAtLeast(0)
                    var burstEnd = (f + 1).coerceAtMost(frames - 1)
                    var look = f + 1
                    while (look < min(frames - 2, f + 10)) {
                        val d2 = abs(buffer[look * channels + c] - buffer[(look + 1) * channels + c])
                        if (d2 > absDiffAvg * 4.5f) {
                            burstEnd = look + 1
                            look++
                        } else break
                    }
                    val startVal = buffer[burstStart * channels + c]
                    val endVal = buffer[burstEnd * channels + c]
                    val span = (burstEnd - burstStart).coerceAtLeast(1)
                    for (k in (burstStart + 1) until burstEnd) {
                        val frac = (k - burstStart).toFloat() / span.toFloat()
                        buffer[k * channels + c] = startVal + (endVal - startVal) * frac
                    }
                    f = burstEnd + 1
                } else {
                    f++
                }
            }
        }
    }
}

/**
 * Stateful noise generator for Task 1 "Noise Add":
 * Supports White, Pink, Brown, Rain, Wind, Fan/Room Tone, Static, and Learned Room Tone (IFFT random phase),
 * with optional HPF/LPF, selection scope, fade in/out, and Fixed SNR mode.
 */
class NoiseAddSynthesizer(
    private val sampleRate: Int,
    private val channels: Int,
    seed: Int = 1337
) {
    private val rng = Random(seed)

    // Paul Kellet pink noise state per channel
    private val b0 = DoubleArray(channels)
    private val b1 = DoubleArray(channels)
    private val b2 = DoubleArray(channels)
    private val b3 = DoubleArray(channels)
    private val b4 = DoubleArray(channels)
    private val b5 = DoubleArray(channels)
    private val b6 = DoubleArray(channels)

    // Brown noise integrator per channel
    private val brown = DoubleArray(channels)

    // Wind / Fan LFO phase
    private var lfoPhase = 0.0
    private var fanPhase = 0.0

    // Shaping filters
    private val windBandpass = Array(channels) { BiquadFilter() }
    private val rainLpf = Array(channels) { BiquadFilter() }
    private val userHpf = Array(channels) { BiquadFilter() }
    private val userLpf = Array(channels) { BiquadFilter() }

    // Learned room tone overlap-add IFFT buffer
    private var roomFftSize = 1024
    private var roomHop = 512
    private var roomTail = FloatArray(512)
    private var roomRing = FloatArray(512)
    private var roomRingPos = 512

    private var lastHpfHz = -1f
    private var lastLpfHz = -1f

    init {
        val sr = sampleRate.toDouble()
        for (c in 0 until channels) {
            windBandpass[c].setBandPass(sr, 420.0, q = 2.2)
            rainLpf[c].setLowPass(sr, 5200.0)
        }
    }

    fun mixIntoChunk(
        buffer: FloatArray,
        frames: Int,
        chunkStartFrame: Long,
        totalTimelineFrames: Long,
        params: NoiseAddParams,
        selectionStartFrame: Long,
        selectionEndFrame: Long,
        learnedProfile: NoiseProfilePayload?,
        overrideVolumeDb: Float? = null,
        noiseOnlyMode: Boolean = false
    ) {
        if (!params.enabled) return

        val scopeStart = if (params.applyToSelectionOnly) selectionStartFrame else 0L
        val scopeEnd = if (params.applyToSelectionOnly) selectionEndFrame else totalTimelineFrames
        if (scopeEnd <= scopeStart) return
        val chunkEnd = chunkStartFrame + frames
        if (chunkEnd <= scopeStart || chunkStartFrame >= scopeEnd) return

        // Update optional user HPF / LPF filters on the added noise
        if (abs(params.hpfHz - lastHpfHz) > 1f || abs(params.lpfHz - lastLpfHz) > 1f) {
            lastHpfHz = params.hpfHz
            lastLpfHz = params.lpfHz
            val sr = sampleRate.toDouble()
            for (c in 0 until channels) {
                if (params.hpfHz > 20f) userHpf[c].setHighPass(sr, params.hpfHz.toDouble())
                else userHpf[c].setBypass()
                if (params.lpfHz in 200f..(sampleRate * 0.46f)) userLpf[c].setLowPass(sr, params.lpfHz.toDouble())
                else userLpf[c].setBypass()
            }
        }

        val fadeInFrames = ((params.fadeInMs.coerceAtLeast(0L) * sampleRate) / 1000L).toFloat()
        val fadeOutFrames = ((params.fadeOutMs.coerceAtLeast(0L) * sampleRate) / 1000L).toFloat()

        // Compute target gain (Fixed SNR mode adapts to chunk signal RMS, else explicit volume dBFS)
        val effectiveDb = if (params.fixedSnrMode) {
            val sigRmsDb = AudioMath.computeRmsDb(buffer, frames * channels).coerceAtLeast(-70f)
            (sigRmsDb - params.targetSnrDb).coerceIn(-65f, -1f)
        } else {
            (overrideVolumeDb ?: params.volumeDb).coerceIn(-60f, 0f)
        }
        val gainLin = AudioMath.dbToLinear(effectiveDb)
        val sr = sampleRate.toDouble()

        // Slowly modulate wind bandpass center frequency once per chunk
        if (params.noiseType == NoiseType.WIND) {
            lfoPhase += (frames / sr) * 0.28 * 2.0 * PI
            val gustHz = 340.0 + 240.0 * (0.5 + 0.5 * sin(lfoPhase))
            for (c in 0 until channels) {
                windBandpass[c].setBandPass(sr, gustHz, q = 2.4)
            }
        }

        for (f in 0 until frames) {
            val gf = chunkStartFrame + f
            if (gf !in scopeStart until scopeEnd) continue

            // Fade in / out envelope
            var env = 1f
            if (fadeInFrames > 1f && gf - scopeStart < fadeInFrames) {
                env = min(env, ((gf - scopeStart) / fadeInFrames).coerceIn(0f, 1f))
            }
            if (fadeOutFrames > 1f && scopeEnd - gf < fadeOutFrames) {
                env = min(env, ((scopeEnd - gf) / fadeOutFrames).coerceIn(0f, 1f))
            }

            // Room tone generated mono then gently decorrelated across stereo if needed
            val roomSample = if (params.noiseType == NoiseType.LEARNED_ROOM_TONE) {
                nextLearnedRoomToneSample(learnedProfile)
            } else 0f

            if (params.noiseType == NoiseType.FAN_ROOM_TONE) {
                fanPhase += 2.0 * PI * 110.0 / sr
            }

            for (c in 0 until channels) {
                var n = when (params.noiseType) {
                    NoiseType.WHITE -> nextWhite() * 0.55f
                    NoiseType.PINK -> nextPink(c) * 0.5f
                    NoiseType.BROWN -> nextBrown(c) * 0.85f
                    NoiseType.RAIN -> {
                        val base = rainLpf[c].process(nextPink(c)) * 0.45f
                        val drop = if (rng.nextFloat() < 0.0018f) (rng.nextFloat() * 2f - 1f) * 0.7f else 0f
                        base + drop
                    }
                    NoiseType.WIND -> {
                        val gustMod = (0.55 + 0.45 * sin(lfoPhase * 0.73)).toFloat()
                        windBandpass[c].process(nextPink(c)) * 1.4f * gustMod
                    }
                    NoiseType.FAN_ROOM_TONE -> {
                        val motor = (sin(fanPhase) * 0.08 + sin(fanPhase * 2.0) * 0.04).toFloat()
                        val air = nextBrown(c) * 0.55f + nextPink(c) * 0.25f
                        (air + motor) * (0.9f + 0.1f * sin(fanPhase * 0.12).toFloat())
                    }
                    NoiseType.STATIC -> {
                        val hiss = nextWhite() * 0.38f
                        val crackle = if (rng.nextFloat() < 0.006f) (rng.nextFloat() * 2f - 1f) * 1.15f else 0f
                        hiss + crackle
                    }
                    NoiseType.LEARNED_ROOM_TONE -> {
                        if (c == 0) roomSample else (roomSample * 0.85f + nextPink(c) * 0.06f)
                    }
                }

                n = userHpf[c].process(n)
                n = userLpf[c].process(n)
                val scaledNoise = n * gainLin * env
                val idx = f * channels + c
                buffer[idx] = if (noiseOnlyMode) {
                    AudioMath.softLimit(scaledNoise)
                } else {
                    AudioMath.softLimit(buffer[idx] + scaledNoise)
                }
            }
        }
    }

    private fun nextWhite(): Float = rng.nextFloat() * 2f - 1f

    private fun nextPink(c: Int): Float {
        val white = (rng.nextDouble() * 2.0 - 1.0)
        b0[c] = 0.99886 * b0[c] + white * 0.0555179
        b1[c] = 0.99332 * b1[c] + white * 0.0750759
        b2[c] = 0.96900 * b2[c] + white * 0.1538520
        b3[c] = 0.86650 * b3[c] + white * 0.3104856
        b4[c] = 0.55000 * b4[c] + white * 0.5329522
        b5[c] = -0.7616 * b5[c] - white * 0.0168980
        val pink = b0[c] + b1[c] + b2[c] + b3[c] + b4[c] + b5[c] + b6[c] + white * 0.5362
        b6[c] = white * 0.115926
        return (pink * 0.14).toFloat()
    }

    private fun nextBrown(c: Int): Float {
        val white = rng.nextDouble() * 2.0 - 1.0
        brown[c] = (brown[c] + 0.025 * white) / 1.022
        return (brown[c] * 3.2).toFloat().coerceIn(-1f, 1f)
    }

    /**
     * Synthesizes natural room tone matching the exact spectral envelope of the learned noise profile
     * via overlap-add inverse FFT with randomized phase per bin.
     */
    private fun nextLearnedRoomToneSample(profile: NoiseProfilePayload?): Float {
        if (profile == null || profile.meanMag.isEmpty()) {
            return nextPink(0) * 0.35f
        }
        if (roomRingPos >= roomHop) {
            val n = profile.fftSize.coerceIn(256, 2048)
            val hop = n / 2
            if (n != roomFftSize) {
                roomFftSize = n
                roomHop = hop
                roomTail = FloatArray(hop)
                roomRing = FloatArray(hop)
            }
            val half = n / 2 + 1
            val real = DoubleArray(n)
            val imag = DoubleArray(n)
            val win = FftProcessor.buildWindow(n, FftWindowType.HANN)

            for (k in 1 until min(half, profile.meanMag.size)) {
                val mag = profile.meanMag[k].toDouble()
                val phase = rng.nextDouble() * 2.0 * PI
                val r = mag * cos(phase)
                val im = mag * sin(phase)
                real[k] = r
                imag[k] = im
                if (k < n / 2) {
                    real[n - k] = r
                    imag[n - k] = -im
                }
            }
            FftProcessor.fftInPlace(real, imag, inverse = true)
            for (i in 0 until hop) {
                val s = (real[i] * win[i]).toFloat() + roomTail[i]
                roomRing[i] = s.coerceIn(-1f, 1f)
                roomTail[i] = (real[i + hop] * win[i + hop]).toFloat()
            }
            roomRingPos = 0
        }
        return roomRing[roomRingPos++]
    }
}
