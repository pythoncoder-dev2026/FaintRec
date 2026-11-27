package com.example

import com.example.analysis.FftProcessor
import com.example.analysis.FftWindowType
import com.example.audio.PreRollBuffer
import com.example.audio.WavCuePoint
import com.example.audio.WavFileHelper
import com.example.data.AppFeature
import com.example.data.NoiseAddParams
import com.example.data.NoiseLevelParams
import com.example.data.NoiseProfilePayload
import com.example.data.NoiseRemovalParams
import com.example.data.NoiseType
import com.example.editor.BiquadFilter
import com.example.editor.NoiseAddSynthesizer
import com.example.editor.SpectralNoiseProcessor
import com.example.spiritbox.SpiritBandMode
import com.example.spiritbox.SpiritBoxDialMath
import com.example.spiritbox.SweepDirection
import com.example.util.AudioMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random

class ExampleUnitTest {

    @Test
    fun addition_isCorrect() {
        assertEquals(4, 2 + 2)
    }

    @Test
    fun fftDetects1000HzSineAccurately() {
        val sampleRate = 48000
        val n = 2048
        val samples = FloatArray(n) { i ->
            sin(2.0 * PI * 1000.0 * i / sampleRate).toFloat()
        }
        val fft = FftProcessor(n, FftWindowType.HANN)
        val result = fft.analyze(samples, sampleRate)
        assertTrue(
            "Expected peak near 1000 Hz, got ${result.peakFrequencyHz}",
            abs(result.peakFrequencyHz - 1000.0) < 25.0
        )
    }

    @Test
    fun softLimiterPreventsClippingAboveCeiling() {
        val pos = AudioMath.softLimit(12.0f, ceiling = 0.98f)
        val neg = AudioMath.softLimit(-12.0f, ceiling = 0.98f)
        assertTrue(pos <= 0.98f && pos > 0.9f)
        assertTrue(neg >= -0.98f && neg < -0.9f)
    }

    @Test
    fun wavRoundtripFloat32AndCueChunks() {
        val tmp = File.createTempFile("faintrec_test_", ".wav")
        try {
            val frames = 1024
            val input = FloatArray(frames) { i -> sin(2.0 * PI * 440.0 * i / 48000.0).toFloat() * 0.5f }
            RandomAccessFile(tmp, "rw").use { raf ->
                WavFileHelper.writePlaceholderHeader(raf, 48000, 1, 32, isFloat = true)
                val bytes = WavFileHelper.writeFloatSamples(raf, input, frames, writeAsFloat32 = true)
                WavFileHelper.patchWavHeaderSizes(raf, bytes)
            }

            val appended = WavFileHelper.appendCueChunks(
                tmp,
                listOf(WavCuePoint(id = 1, sampleOffset = 512L, label = "Test Marker"))
            )
            assertTrue(appended)

            val info = WavFileHelper.parseWavInfo(tmp)
            assertNotNull(info)
            assertEquals(48000, info!!.sampleRate)
            assertEquals(1, info.channels)
            assertTrue(info.isFloat)
            assertEquals(frames.toLong(), info.totalFrames)

            val readBack = FloatArray(frames)
            RandomAccessFile(tmp, "r").use { raf ->
                val count = WavFileHelper.readFramesAsFloat(raf, info, 0L, frames, readBack)
                assertEquals(frames, count)
            }
            for (i in 0 until frames) {
                assertEquals(input[i], readBack[i], 1e-4f)
            }
        } finally {
            tmp.delete()
        }
    }

    @Test
    fun preRollBufferDrainsChronologicallyAfterWrap() {
        val preRoll = PreRollBuffer(4)
        preRoll.push(floatArrayOf(1f, 2f, 3f), 3)
        preRoll.push(floatArrayOf(4f, 5f, 6f), 3)
        val drained = preRoll.drainChronological()
        assertEquals(listOf(3f, 4f, 5f, 6f), drained.toList())
    }

    @Test
    fun biquadLowPassRemainsFiniteAndStable() {
        val filter = BiquadFilter()
        filter.setLowPass(48000.0, 2000.0)
        var maxOut = 0f
        for (i in 0 until 2048) {
            val out = filter.process(sin(2.0 * PI * 500.0 * i / 48000.0).toFloat())
            assertTrue(out.isFinite())
            if (abs(out) > maxOut) maxOut = abs(out)
        }
        assertTrue(maxOut in 0.5f..1.2f)
    }

    @Test
    fun biquadNotchAttenuates60HzHumAndPreserves1000Hz() {
        val sr = 48000.0
        val notch = BiquadFilter().apply { setNotch(sr, 60.0, q = 10.0) }
        var humEnergy = 0.0
        for (i in 0 until 24000) {
            val out = notch.process(sin(2.0 * PI * 60.0 * i / sr).toFloat())
            if (i >= 12000) humEnergy += out * out
        }
        val humRms = kotlin.math.sqrt(humEnergy / 12000.0)
        assertTrue("Expected 60 Hz hum heavily attenuated, got RMS=$humRms", humRms < 0.05)

        val notch2 = BiquadFilter().apply { setNotch(sr, 60.0, q = 10.0) }
        var toneEnergy = 0.0
        for (i in 0 until 4096) {
            val out = notch2.process(sin(2.0 * PI * 1000.0 * i / sr).toFloat())
            if (i >= 1024) toneEnergy += out * out
        }
        val toneRms = kotlin.math.sqrt(toneEnergy / 3072.0)
        assertTrue("Expected 1 kHz tone preserved, got RMS=$toneRms", toneRms > 0.65)
    }

    @Test
    fun spectralNoiseProcessorReducesNoiseAndSupportsNoiseOnly() {
        val sr = 48000
        val frames = 4096
        val rng = Random(42)
        val noiseOnly = FloatArray(frames) { (rng.nextFloat() * 2f - 1f) * 0.05f }
        val profile = NoiseProfilePayload(
            fftSize = 1024,
            sampleRate = sr,
            meanMag = List(513) { 2.5f },
            stdMag = List(513) { 0.3f },
            learnedStartMs = 0L,
            learnedEndMs = 1000L
        )
        val beforeRms = AudioMath.computeRmsDb(noiseOnly, frames)
        val reduced = noiseOnly.copyOf()
        val proc = SpectralNoiseProcessor(sr, 1, 1024)
        proc.processInterleaved(
            buffer = reduced,
            frames = frames,
            profile = profile,
            removal = NoiseRemovalParams(enabled = true, reductionDb = 24f, sensitivity = 1.2f),
            level = NoiseLevelParams(enabled = true, existingNoiseDb = -18f),
            noiseOnlyMode = false
        )
        assertTrue(reduced.all { it.isFinite() })
        val afterRms = AudioMath.computeRmsDb(reduced, frames)
        assertTrue("Expected noise reduction (before=$beforeRms, after=$afterRms)", afterRms < beforeRms)

        val residual = noiseOnly.copyOf()
        SpectralNoiseProcessor(sr, 1, 1024).processInterleaved(
            buffer = residual,
            frames = frames,
            profile = profile,
            removal = NoiseRemovalParams(enabled = true, reductionDb = 24f),
            level = NoiseLevelParams(),
            noiseOnlyMode = true
        )
        assertTrue(residual.all { it.isFinite() })
    }

    @Test
    fun noiseAddSynthesizerRespectsBoundsAndSelectionScope() {
        val sr = 48000
        val frames = 2048
        val buf = FloatArray(frames)
        val synth = NoiseAddSynthesizer(sr, 1)
        synth.mixIntoChunk(
            buffer = buf,
            frames = frames,
            chunkStartFrame = 0L,
            totalTimelineFrames = frames.toLong(),
            params = NoiseAddParams(
                enabled = true,
                noiseType = NoiseType.PINK,
                volumeDb = -12f,
                applyToSelectionOnly = true,
                fadeInMs = 10,
                fadeOutMs = 10
            ),
            selectionStartFrame = 512L,
            selectionEndFrame = 1536L,
            learnedProfile = null
        )
        assertTrue(buf.all { it.isFinite() && it in -1f..1f })
        assertEquals(0f, buf[10], 1e-6f)
        assertTrue(AudioMath.computePeakDb(buf, frames) > -40f)
    }

    @Test
    fun spiritBoxDialMathStepsAndWrapsCorrectly() {
        assertEquals("88.0 MHz", SpiritBoxDialMath.formatFrequency(SpiritBandMode.FM, 0f))
        assertEquals("108.0 MHz", SpiritBoxDialMath.formatFrequency(SpiritBandMode.FM, 1f))
        assertEquals("530 kHz", SpiritBoxDialMath.formatFrequency(SpiritBandMode.AM, 0f))

        val fwd = SpiritBoxDialMath.nextDialFraction(0.99f, SweepDirection.FORWARD, SpiritBandMode.FM, 0.2f, 0.8f)
        assertEquals(0.2f, fwd, 1e-4f)

        val bwd = SpiritBoxDialMath.nextDialFraction(0.2f, SweepDirection.BACKWARD, SpiritBandMode.FM, 0.2f, 0.8f)
        assertEquals(0.8f, bwd, 1e-4f)

        val hopMs = SpiritBoxDialMath.computeNextHopDurationMs(200, jitterEnabled = true, rng = Random(7))
        assertTrue(hopMs in 50L..2400L)
    }

    @Test
    fun appFeaturesListContainsAllRequiredHealthEntries() {
        assertTrue(AppFeature.entries.size >= 26)
        assertEquals(AppFeature.entries.size, AppFeature.entries.map { it.id }.distinct().size)
        assertEquals(AppFeature.NOISE_TOOLS, AppFeature.fromId("noise_tools"))
        assertEquals(AppFeature.SPIRIT_BOX_RADIO, AppFeature.fromId("spirit_box_radio"))
    }

    @Test
    fun storageEstimationMatchesBitrateAndSafetyFloor() {
        val oneGb = 1024L * 1024L * 1024L
        // 48000 Hz, 32-bit float, mono = 192,000 B/s
        val secMono32 = com.example.storage.StorageLocationManager.estimateRecordingSecondsLeft(
            freeBytes = oneGb,
            sampleRate = 48000,
            bitsPerSample = 32,
            channels = 1
        )
        val expected = (oneGb - com.example.storage.StorageLocationManager.CRITICAL_STOP_BYTES) / 192000L
        assertEquals(expected, secMono32)
        // Below critical floor should report 0 seconds
        assertEquals(
            0L,
            com.example.storage.StorageLocationManager.estimateRecordingSecondsLeft(
                freeBytes = 10L * 1024L * 1024L,
                sampleRate = 48000,
                bitsPerSample = 32,
                channels = 1
            )
        )
        assertEquals("1h 1m", com.example.storage.StorageLocationManager.formatEstimatedDuration(3665L))
        assertEquals("0m", com.example.storage.StorageLocationManager.formatEstimatedDuration(0L))
    }

    @Test
    fun wavHeaderRepairFixesUnfinalizedRecording() {
        val tmp = File.createTempFile("faintrec_repair_", ".wav")
        try {
            val frames = 2400
            val input = FloatArray(frames) { i -> sin(2.0 * PI * 440.0 * i / 48000.0).toFloat() * 0.4f }
            // Simulate a crash mid-recording: placeholder header (dataSize=0) + raw PCM written, NEVER finalized
            RandomAccessFile(tmp, "rw").use { raf ->
                WavFileHelper.writePlaceholderHeader(raf.channel, 48000, 1, 16, isFloat = false)
                WavFileHelper.writeFloatSamples(raf.channel, input, frames, writeAsFloat32 = false)
            }
            // Repair on next launch
            val repairedBytes = RandomAccessFile(tmp, "rw").use { raf ->
                WavFileHelper.repairWavHeaderIfNeeded(raf.channel)
            }
            assertNotNull(repairedBytes)
            assertEquals(frames * 2L, repairedBytes!!)

            // Subsequent call should detect header is already valid (returns null)
            val secondPass = RandomAccessFile(tmp, "rw").use { raf ->
                WavFileHelper.repairWavHeaderIfNeeded(raf.channel)
            }
            assertEquals(null, secondPass)

            val info = WavFileHelper.parseWavInfo(tmp)
            assertNotNull(info)
            assertEquals(frames.toLong(), info!!.totalFrames)
            assertEquals(48000, info.sampleRate)
        } finally {
            tmp.delete()
        }
    }
}
