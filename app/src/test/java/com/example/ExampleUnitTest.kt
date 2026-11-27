package com.example

import android.media.AudioDeviceInfo
import com.example.analysis.FftProcessor
import com.example.analysis.FftWindowType
import com.example.audio.AudioDeviceManager
import com.example.audio.PreRollBuffer
import com.example.audio.WavCuePoint
import com.example.audio.WavFileHelper
import com.example.data.AppFeature
import com.example.data.FaintRecSettings
import com.example.data.NoiseAddParams
import com.example.data.NoiseLevelParams
import com.example.data.NoiseProfilePayload
import com.example.data.NoiseRemovalParams
import com.example.data.NoiseType
import com.example.editor.BiquadFilter
import com.example.editor.NoiseAddSynthesizer
import com.example.editor.SpectralNoiseProcessor
import com.example.logging.DevModeSessionGuard
import com.example.logging.DevPinVerifier
import com.example.spiritbox.SpiritBandMode
import com.example.spiritbox.SpiritBoxConfig
import com.example.spiritbox.SpiritBoxDialMath
import com.example.spiritbox.SpiritBoxLocalAudioSource
import com.example.spiritbox.SweepDirection
import com.example.util.AudioMath
import com.example.util.MarkerDebouncer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
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

    @Test
    fun markerDebouncerEnforces500msWindow() {
        val debouncer = MarkerDebouncer(500L)
        assertTrue(debouncer.tryAcquire(1000L))
        assertFalse(debouncer.tryAcquire(1000L))
        assertFalse(debouncer.tryAcquire(1250L))
        assertFalse(debouncer.tryAcquire(1499L))
        assertTrue(debouncer.tryAcquire(1500L))
        assertFalse(debouncer.tryAcquire(1999L))
        assertTrue(debouncer.tryAcquire(2000L))
        debouncer.reset()
        assertTrue(debouncer.tryAcquire(2050L))
    }

    @Test
    fun externalMicAllowlistFiltersNonExternalTypes() {
        assertTrue(AudioDeviceManager.isAllowedExternalMicType(AudioDeviceInfo.TYPE_WIRED_HEADSET, 34))
        assertTrue(AudioDeviceManager.isAllowedExternalMicType(AudioDeviceInfo.TYPE_WIRED_HEADPHONES, 34))
        assertTrue(AudioDeviceManager.isAllowedExternalMicType(AudioDeviceInfo.TYPE_USB_DEVICE, 34))
        assertTrue(AudioDeviceManager.isAllowedExternalMicType(AudioDeviceInfo.TYPE_USB_HEADSET, 34))
        assertTrue(AudioDeviceManager.isAllowedExternalMicType(AudioDeviceInfo.TYPE_USB_ACCESSORY, 34))
        assertTrue(AudioDeviceManager.isAllowedExternalMicType(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, 34))
        assertTrue(AudioDeviceManager.isAllowedExternalMicType(AudioDeviceInfo.TYPE_BLE_HEADSET, 31))
        assertFalse(AudioDeviceManager.isAllowedExternalMicType(AudioDeviceInfo.TYPE_BLE_HEADSET, 30))

        // Must exclude built-in, telephony, FM tuner, remote submix, bus, etc.
        assertFalse(AudioDeviceManager.isAllowedExternalMicType(AudioDeviceInfo.TYPE_BUILTIN_MIC, 34))
        assertFalse(AudioDeviceManager.isAllowedExternalMicType(AudioDeviceInfo.TYPE_TELEPHONY, 34))
        assertFalse(AudioDeviceManager.isAllowedExternalMicType(AudioDeviceInfo.TYPE_FM_TUNER, 34))
        assertFalse(AudioDeviceManager.isAllowedExternalMicType(AudioDeviceInfo.TYPE_REMOTE_SUBMIX, 34))
        assertFalse(AudioDeviceManager.isAllowedExternalMicType(AudioDeviceInfo.TYPE_BUS, 34))
    }

    @Test
    fun spiritBoxStaticLinearRmsIsValidAndFinite() {
        val frames = 4096
        val buf = FloatArray(frames)
        val synth = NoiseAddSynthesizer(48000, 1)
        synth.mixIntoChunk(
            buffer = buf,
            frames = frames,
            chunkStartFrame = 0L,
            totalTimelineFrames = frames.toLong(),
            params = NoiseAddParams(
                enabled = true,
                noiseType = NoiseType.STATIC,
                volumeDb = -24f,
                fadeInMs = 0L,
                fadeOutMs = 0L
            ),
            selectionStartFrame = 0L,
            selectionEndFrame = frames.toLong(),
            learnedProfile = null
        )
        assertTrue(buf.all { it.isFinite() })
        var sumSq = 0.0
        for (s in buf) sumSq += s.toDouble() * s.toDouble()
        val linearRms = kotlin.math.sqrt(sumSq / frames)
        assertTrue("Expected linear RMS in 0.0005..0.99, got $linearRms", linearRms in 0.0005..0.99)
    }

    @Test
    fun spiritBoxRecorderFeatureIsRegisteredAndDefaultsOn() {
        assertEquals(AppFeature.SPIRIT_BOX_RECORDER, AppFeature.fromId("spirit_box_recorder"))
        assertTrue("SpiritBoxConfig.recorderEnabled must default ON", SpiritBoxConfig().recorderEnabled)
        assertTrue("FaintRecSettings.spiritBoxRecorderEnabled must default ON", FaintRecSettings().spiritBoxRecorderEnabled)
    }

    @Test
    fun wavFileChannelAndInputStreamReadingAndCorruptRejection() {
        val tmp = File.createTempFile("faintrec_channel_", ".wav")
        try {
            val frames = 1600
            val input = FloatArray(frames) { i -> sin(2.0 * PI * 440.0 * i / 48000.0).toFloat() * 0.4f }
            RandomAccessFile(tmp, "rw").use { raf ->
                WavFileHelper.writePlaceholderHeader(raf.channel, 48000, 1, 16, isFloat = false)
                val written = WavFileHelper.writeFloatSamples(raf.channel, input, frames, writeAsFloat32 = false)
                WavFileHelper.patchWavHeaderSizes(raf.channel, written)
            }

            // Read back via FileChannel (same path used for ContentResolver PFD)
            FileInputStream(tmp).use { fis ->
                val info = WavFileHelper.parseWavInfo(fis.channel)
                assertNotNull(info)
                assertEquals(frames.toLong(), info!!.totalFrames)
                val out = FloatArray(400)
                val read = WavFileHelper.readFramesAsFloat(fis.channel, info, 200L, 400, out)
                assertEquals(400, read)
                assertEquals(input[200], out[0], 1e-3f)
                assertEquals(input[599], out[399], 1e-3f)
            }

            // Read back via InputStream (same path used for non-seekable SAF providers)
            val bytes = tmp.readBytes()
            val streamInfo = WavFileHelper.parseWavInfo(ByteArrayInputStream(bytes), totalBytesHint = bytes.size.toLong())
            assertNotNull(streamInfo)
            val outStream = FloatArray(256)
            val streamRead = WavFileHelper.readFramesAsFloat(ByteArrayInputStream(bytes), streamInfo!!, 100L, 256, outStream)
            assertEquals(256, streamRead)
            assertEquals(input[100], outStream[0], 1e-3f)

            // Corrupt/non-WAV bytes must be rejected
            val badInfo = WavFileHelper.parseWavInfo(ByteArrayInputStream("NOT_A_WAV_FILE_HEADER_123456789012345678901234".toByteArray()))
            assertNull(badInfo)
        } finally {
            tmp.delete()
        }
    }

    @Test
    fun spiritBoxLocalFragmentWindowAndResamplingRespectBounds() {
        val sr = 48000
        val totalFrames = sr * 5L // 5 second WAV
        val rng = Random(12345)
        repeat(25) {
            val (startFrame, count) = SpiritBoxLocalAudioSource.pickFragmentWindow(totalFrames, sr, 0.3f, 2.0f, rng)
            assertTrue("Fragment count should be between 0.3s and 2.0s, got $count", count in (0.3f * sr).toInt()..(2.0f * sr).toInt())
            assertTrue("Start + count must stay within file bounds", startFrame >= 0L && startFrame + count <= totalFrames)
        }
        // Short 0.2s file must clamp cleanly without overflow
        val shortFrames = (sr * 0.2f).toLong()
        val (shortStart, shortCount) = SpiritBoxLocalAudioSource.pickFragmentWindow(shortFrames, sr, 0.3f, 2.0f, rng)
        assertEquals(0L, shortStart)
        assertEquals(shortFrames.toInt(), shortCount)

        // Stereo 44.1 kHz downmix + resample to 48 kHz mono
        val srcFrames = 4410
        val stereo = FloatArray(srcFrames * 2) { idx -> if (idx % 2 == 0) 0.5f else 0.3f }
        val resampled = SpiritBoxLocalAudioSource.downmixAndResampleToMono(stereo, srcFrames, 2, 44100, 48000)
        assertEquals(4800, resampled.size)
        assertEquals(0.4f, resampled[100], 1e-3f)
    }

    @Test
    fun devPinVerifierUsesSaltedSha256AndDisabledWhenUnconfigured() {
        assertFalse(DevPinVerifier.isConfigured("", ""))
        assertFalse(DevPinVerifier.isConfigured("salt", "short"))
        assertFalse(DevPinVerifier.verifyPinConstantTime("1234", "", ""))

        val salt = "0123456789abcdef0123456789abcdef"
        val hash = DevPinVerifier.computeSaltedSha256Hex(salt, "4829")
        assertEquals(64, hash.length)
        assertTrue(DevPinVerifier.isConfigured(salt, hash))
        assertTrue(DevPinVerifier.verifyPinConstantTime("4829", salt, hash))
        assertFalse(DevPinVerifier.verifyPinConstantTime("4828", salt, hash))
        assertFalse(DevPinVerifier.verifyPinConstantTime("", salt, hash))
    }

    @Test
    fun devModeSessionGuardEnforcesTapsLockoutAndBackgroundExpiry() {
        // Unconfigured guard ignores 7 rapid taps completely
        val disabledGuard = DevModeSessionGuard("", "")
        repeat(10) { i -> disabledGuard.onVersionTap(1_000L + i * 200L) }
        assertFalse(disabledGuard.state.value.isConfigured)
        assertFalse(disabledGuard.state.value.showPinDialog)
        assertFalse(disabledGuard.state.value.isUnlocked)

        val salt = "fedcba9876543210fedcba9876543210"
        val hash = DevPinVerifier.computeSaltedSha256Hex(salt, "9042")
        val guard = DevModeSessionGuard(salt, hash)

        // 6 rapid taps do NOT open the dialog
        repeat(6) { i -> guard.onVersionTap(1_000L + i * 300L) }
        assertFalse(guard.state.value.showPinDialog)

        // Gap > 2s resets tap streak
        guard.onVersionTap(1_000L + 5 * 300L + 2_500L)
        assertFalse(guard.state.value.showPinDialog)

        // 6 more rapid taps after reset reach 7 -> opens PIN dialog
        val base = 5_000L
        repeat(6) { i -> guard.onVersionTap(base + (i + 1) * 300L) }
        assertTrue(guard.state.value.showPinDialog)

        // 3 wrong attempts trigger a 30-second lockout
        val t0 = 10_000L
        assertFalse(guard.submitPin("0000", t0))
        assertTrue(guard.state.value.showIncorrectPinError)
        assertEquals(0, guard.state.value.lockoutRemainingSeconds(t0))

        assertFalse(guard.submitPin("1111", t0 + 500L))
        assertEquals(0, guard.state.value.lockoutRemainingSeconds(t0 + 500L))

        assertFalse(guard.submitPin("2222", t0 + 1_000L))
        assertTrue(guard.state.value.lockoutRemainingSeconds(t0 + 1_000L) in 29..30)

        // Even the correct PIN is rejected while locked out
        assertFalse(guard.submitPin("9042", t0 + 10_000L))
        assertFalse(guard.state.value.isUnlocked)

        // After 30 seconds pass, correct PIN unlocks and opens Log
        val afterLockout = t0 + 1_000L + DevModeSessionGuard.LOCKOUT_DURATION_MS
        assertTrue(guard.submitPin("9042", afterLockout))
        assertTrue(guard.state.value.isUnlocked)
        assertTrue(guard.state.value.isLogOpen)
        assertFalse(guard.state.value.showPinDialog)

        // Background <= 5 minutes stays unlocked
        guard.onAppBackgrounded(afterLockout + 1_000L)
        guard.onAppForegrounded(afterLockout + 1_000L + 4L * 60L * 1000L)
        assertTrue(guard.state.value.isUnlocked)

        // Background > 5 minutes locks automatically
        guard.onAppBackgrounded(afterLockout + 500_000L)
        guard.onAppForegrounded(afterLockout + 500_000L + DevModeSessionGuard.BACKGROUND_LOCK_TIMEOUT_MS + 1L)
        assertFalse(guard.state.value.isUnlocked)
        assertFalse(guard.state.value.isLogOpen)
    }
}
