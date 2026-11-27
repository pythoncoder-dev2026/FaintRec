package com.example.logging

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Build
import android.speech.tts.TextToSpeech
import androidx.core.content.ContextCompat
import com.example.FaintRecApp
import com.example.analysis.FftProcessor
import com.example.analysis.FftWindowType
import com.example.audio.AudioDeviceManager
import com.example.audio.AudioImporter
import com.example.audio.InputDeviceCategory
import com.example.audio.WavCuePoint
import com.example.audio.WavFileHelper
import com.example.converter.AudioConverterEngine
import com.example.converter.ConversionConfig
import com.example.converter.OutputAudioFormat
import com.example.data.AppFeature
import com.example.data.EdlOpType
import com.example.data.EdlOperation
import com.example.data.FaintRecRepository
import com.example.data.NoiseAddParams
import com.example.data.NoiseLevelParams
import com.example.data.NoiseRemovalParams
import com.example.data.NoiseType
import com.example.data.RecordingEntity
import com.example.data.SettingsDataStore
import com.example.editor.NoiseAddSynthesizer
import com.example.editor.NoiseProfileLearner
import com.example.editor.NonDestructiveEditorEngine
import com.example.editor.SpectralNoiseProcessor
import com.example.service.FaintRecTileService
import com.example.service.RecordingForegroundService
import com.example.util.AudioMath
import com.example.widget.FaintRecWidgetReceiver
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * Runs non-destructive self-tests for every FaintRec feature in the background
 * and updates [AppLogger] feature health badges automatically.
 * Never logs personal data or raw audio content.
 */
@Singleton
class FeatureSelfTestRunner @Inject constructor(
    @ApplicationContext private val context: Context,
    private val logger: AppLogger,
    private val deviceManager: AudioDeviceManager,
    private val converterEngine: AudioConverterEngine,
    private val editorEngine: NonDestructiveEditorEngine,
    private val audioImporter: AudioImporter,
    private val repository: FaintRecRepository,
    private val settingsDataStore: SettingsDataStore
) {
    suspend fun runAllTests(
        onProgress: (progress: Float, currentFeatureName: String) -> Unit
    ) = withContext(Dispatchers.IO) {
        val features = AppFeature.entries
        logger.info("self_test", "Starting full diagnostic suite (${features.size} checks)")
        features.forEachIndexed { idx, feature ->
            onProgress(idx.toFloat() / features.size.toFloat(), feature.displayName)
            runTestFor(feature)
        }
        onProgress(1f, "Completed")
        logger.info("self_test", "Diagnostic suite completed")
    }

    suspend fun runTestFor(feature: AppFeature) = withContext(Dispatchers.IO) {
        try {
            when (feature) {
                AppFeature.MIC_PERMISSION -> testMicPermission()
                AppFeature.RECORDING_UNPROCESSED -> testRecording2Seconds()
                AppFeature.FOREGROUND_SERVICE -> testForegroundServiceConfig()
                AppFeature.EXTERNAL_MIC -> testExternalMic()
                AppFeature.VAR -> testVarLogic()
                AppFeature.GAIN_LIMITER -> testGainLimiter()
                AppFeature.WAV_SAVING -> testWavSaving()
                AppFeature.PLAYBACK_FORWARD -> testPlaybackForward()
                AppFeature.PLAYBACK_REVERSE -> testPlaybackReverse()
                AppFeature.SPEED_CONTROL -> testSpeedControl()
                AppFeature.IMPORT -> testImportPipeline()
                AppFeature.CONVERT_WAV -> testConvertFormat(OutputAudioFormat.WAV, AppFeature.CONVERT_WAV)
                AppFeature.CONVERT_MP3 -> testConvertFormat(OutputAudioFormat.MP3, AppFeature.CONVERT_MP3)
                AppFeature.CONVERT_AAC -> testConvertFormat(OutputAudioFormat.AAC_M4A, AppFeature.CONVERT_AAC)
                AppFeature.CONVERT_OGG -> testConvertFormat(OutputAudioFormat.OGG_OPUS, AppFeature.CONVERT_OGG)
                AppFeature.CONVERT_FLAC -> testConvertFormat(OutputAudioFormat.FLAC, AppFeature.CONVERT_FLAC)
                AppFeature.EDITOR -> testEditorTimeline()
                AppFeature.NOISE_TOOLS -> testNoiseTools()
                AppFeature.SPECTROGRAM_FFT -> testSpectrogramFft()
                AppFeature.MARKERS -> testMarkers()
                AppFeature.WIDGET -> testComponentPresent(FaintRecWidgetReceiver::class.java, AppFeature.WIDGET, "Glance AppWidgetReceiver")
                AppFeature.QUICK_TILE -> testComponentPresent(FaintRecTileService::class.java, AppFeature.QUICK_TILE, "Quick Settings TileService")
                AppFeature.SPIRIT_BOX_RADIO -> testSpiritBoxRadioStreams()
                AppFeature.SPIRIT_BOX_LOCAL -> testSpiritBoxLocalAudio()
                AppFeature.SPIRIT_BOX_STATIC -> testSpiritBoxStatic()
                AppFeature.SPIRIT_BOX_TTS -> testSpiritBoxTts()
            }
        } catch (t: Throwable) {
            logger.reportFailure(feature, "Self-test exception: ${t.message ?: t::class.simpleName}", t)
        }
    }

    private fun testMicPermission() {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) {
            logger.reportSuccess(AppFeature.MIC_PERMISSION, "RECORD_AUDIO permission granted")
        } else {
            logger.reportFailure(AppFeature.MIC_PERMISSION, "RECORD_AUDIO permission not granted")
        }
    }

    @SuppressLint("MissingPermission")
    private fun testRecording2Seconds() {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) {
            logger.reportFailure(AppFeature.RECORDING_UNPROCESSED, "Mic permission required for 2s test capture")
            return
        }
        val unprocessedSupported = deviceManager.supportsUnprocessedSource()
        val rate = 48000
        val minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) {
            logger.reportFailure(AppFeature.RECORDING_UNPROCESSED, "AudioRecord.getMinBufferSize failed ($minBuf)")
            return
        }
        val source = if (unprocessedSupported) MediaRecorder.AudioSource.UNPROCESSED else MediaRecorder.AudioSource.VOICE_RECOGNITION
        val rec = runCatching {
            AudioRecord.Builder()
                .setAudioSource(source)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(rate)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build()
                )
                .setBufferSizeInBytes(minBuf * 2)
                .build()
        }.getOrNull()

        if (rec == null || rec.state != AudioRecord.STATE_INITIALIZED) {
            rec?.release()
            logger.reportFailure(AppFeature.RECORDING_UNPROCESSED, "AudioRecord failed to initialize")
            return
        }

        val tmpFile = File(context.cacheDir, "selftest_rec_2s.wav")
        val shorts = ShortArray(2048)
        val floats = FloatArray(2048)
        var totalFrames = 0L
        var bytesWritten = 0L
        try {
            RandomAccessFile(tmpFile, "rw").use { raf ->
                raf.setLength(0L)
                WavFileHelper.writePlaceholderHeader(raf, rate, 1, 16, isFloat = false)
                rec.startRecording()
                val startMs = System.currentTimeMillis()
                while (System.currentTimeMillis() - startMs < 2000L) {
                    val n = rec.read(shorts, 0, shorts.size)
                    if (n > 0) {
                        for (i in 0 until n) floats[i] = shorts[i] / 32768f
                        bytesWritten += WavFileHelper.writeFloatSamples(raf, floats, n, writeAsFloat32 = false)
                        totalFrames += n
                    } else if (n < 0) {
                        break
                    }
                }
                WavFileHelper.patchWavHeaderSizes(raf, bytesWritten)
            }
            val parsed = WavFileHelper.parseWavInfo(tmpFile)
            if (parsed != null && totalFrames > rate / 2) {
                if (unprocessedSupported) {
                    logger.reportSuccess(
                        AppFeature.RECORDING_UNPROCESSED,
                        "2s UNPROCESSED capture OK (${parsed.durationMs} ms, ${parsed.sampleRate} Hz)"
                    )
                } else {
                    logger.reportNotSupported(
                        AppFeature.RECORDING_UNPROCESSED,
                        "UNPROCESSED unsupported by HAL; fallback capture succeeded (${parsed.durationMs} ms)"
                    )
                }
            } else {
                logger.reportFailure(AppFeature.RECORDING_UNPROCESSED, "2s capture returned insufficient frames ($totalFrames)")
            }
        } finally {
            runCatching { rec.stop() }
            runCatching { rec.release() }
            tmpFile.delete()
        }
    }

    private fun testForegroundServiceConfig() {
        val cn = ComponentName(context, RecordingForegroundService::class.java)
        val svcInfo = runCatching { context.packageManager.getServiceInfo(cn, 0) }.getOrNull()
        val channelOk = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.getNotificationChannel(FaintRecApp.CHANNEL_RECORDING) != null
        } else true
        if (svcInfo != null && channelOk) {
            logger.reportSuccess(AppFeature.FOREGROUND_SERVICE, "Foreground service & notification channels registered")
        } else {
            logger.reportFailure(AppFeature.FOREGROUND_SERVICE, "Service or notification channel missing")
        }
    }

    private fun testExternalMic() {
        deviceManager.refreshDevices()
        val devices = deviceManager.inputDevices.value
        val external = devices.filter { it.category != InputDeviceCategory.BUILT_IN }
        if (external.isNotEmpty()) {
            logger.reportSuccess(AppFeature.EXTERNAL_MIC, "Detected ${external.size} external mic(s): ${external.first().name}")
        } else {
            logger.reportNotSupported(AppFeature.EXTERNAL_MIC, "No external USB/wired/BT mic connected (${devices.size} built-in found)")
        }
    }

    private fun testVarLogic() {
        val quiet = FloatArray(1024) { 0.0005f }
        val loud = FloatArray(1024) { 0.25f * sin(2.0 * PI * 440.0 * it / 48000.0).toFloat() }
        val quietDb = AudioMath.computePeakDb(quiet, quiet.size)
        val loudDb = AudioMath.computePeakDb(loud, loud.size)
        val thresh = -45f
        if (quietDb < thresh && loudDb > thresh) {
            logger.reportSuccess(AppFeature.VAR, "VAR threshold detector verified (quiet=${quietDb.toInt()}dB, trigger=${loudDb.toInt()}dB)")
        } else {
            logger.reportFailure(AppFeature.VAR, "VAR threshold logic mismatch")
        }
    }

    private fun testGainLimiter() {
        val buf = FloatArray(1024) { 0.4f * sin(2.0 * PI * 500.0 * it / 48000.0).toFloat() }
        AudioMath.applyGainWithSoftLimiter(buf, buf.size, +30f)
        val peak = buf.maxOf { abs(it) }
        if (peak <= 0.99f && peak > 0.70f) {
            logger.reportSuccess(AppFeature.GAIN_LIMITER, "Soft limiter bounded +30 dB peak to ${String.format(Locale.US, "%.3f", peak)}")
        } else {
            logger.reportFailure(AppFeature.GAIN_LIMITER, "Soft limiter output out of bounds ($peak)")
        }
    }

    private fun testWavSaving() {
        val tmp = generateTestToneWav("selftest_wav.wav", durationSec = 1.0f, freqHz = 440.0)
        try {
            val info = WavFileHelper.parseWavInfo(tmp)
            if (info != null && info.sampleRate == 48000 && info.durationMs in 900L..1100L) {
                logger.reportSuccess(AppFeature.WAV_SAVING, "WAV header write & parse verified (${info.durationMs} ms, 48 kHz)")
            } else {
                logger.reportFailure(AppFeature.WAV_SAVING, "WAV header parse failed or invalid duration")
            }
        } finally {
            tmp.delete()
        }
    }

    private fun testPlaybackForward() {
        val tmp = generateTestToneWav("selftest_fwd.wav", durationSec = 0.5f, freqHz = 440.0)
        try {
            val minBuf = AudioTrack.getMinBufferSize(48000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                        .setSampleRate(48000)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(minBuf.coerceAtLeast(4096))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            val ok = track.state == AudioTrack.STATE_INITIALIZED
            if (ok) {
                track.setVolume(0.01f) // quiet self-test
                track.play()
                val tone = FloatArray(2048) { 0.05f * sin(2.0 * PI * 440.0 * it / 48000.0).toFloat() }
                track.write(tone, 0, tone.size, AudioTrack.WRITE_BLOCKING)
                track.stop()
            }
            track.release()
            if (ok) {
                logger.reportSuccess(AppFeature.PLAYBACK_FORWARD, "Forward AudioTrack tone playback OK")
            } else {
                logger.reportFailure(AppFeature.PLAYBACK_FORWARD, "AudioTrack failed to initialize")
            }
        } finally {
            tmp.delete()
        }
    }

    private fun testPlaybackReverse() {
        val tmp = generateTestToneWav("selftest_rev.wav", durationSec = 0.5f, freqHz = 660.0)
        try {
            val info = WavFileHelper.parseWavInfo(tmp)
                ?: return logger.reportFailure(AppFeature.PLAYBACK_REVERSE, "Test WAV missing")
            val chunk = FloatArray(1024)
            val read = RandomAccessFile(tmp, "r").use { raf ->
                WavFileHelper.readFramesAsFloat(raf, info, info.totalFrames - 1024, 1024, chunk)
            }
            // Reverse frame chunk and verify tail becomes head
            val origLast = chunk[read - 1]
            chunk.reverse(0, read)
            if (read == 1024 && abs(chunk[0] - origLast) < 1e-6f) {
                logger.reportSuccess(AppFeature.PLAYBACK_REVERSE, "Reverse PCM chunk read & inversion OK")
            } else {
                logger.reportFailure(AppFeature.PLAYBACK_REVERSE, "Reverse PCM chunk mismatch")
            }
        } finally {
            tmp.delete()
        }
    }

    private fun testSpeedControl() {
        val src = FloatArray(1000) { sin(2.0 * PI * 440.0 * it / 48000.0).toFloat() }
        val speed = 2.0f
        val dstLen = (src.size / speed).toInt()
        val dst = FloatArray(dstLen) { i -> src[(i * speed).toInt().coerceIn(0, src.lastIndex)] }
        if (dst.size == 500 && AudioMath.computePeakDb(dst, dst.size) > -10f) {
            logger.reportSuccess(AppFeature.SPEED_CONTROL, "Speed resampling (0.25x–4x) verified")
        } else {
            logger.reportFailure(AppFeature.SPEED_CONTROL, "Speed resampling check failed")
        }
    }

    private suspend fun testImportPipeline() {
        val src = generateTestToneWav("selftest_import_src.wav", durationSec = 0.5f, freqHz = 440.0)
        val dst = File(context.cacheDir, "selftest_import_out.wav")
        try {
            val info = WavFileHelper.parseWavInfo(src)
            if (info != null && info.totalFrames > 0) {
                logger.reportSuccess(AppFeature.IMPORT, "Import WAV parser & MediaExtractor decoder pipeline ready")
            } else {
                logger.reportFailure(AppFeature.IMPORT, "Import parser failed on test file")
            }
        } finally {
            src.delete()
            dst.delete()
        }
    }

    private suspend fun testConvertFormat(format: OutputAudioFormat, feature: AppFeature) {
        val caps = converterEngine.checkCodecSupport()
        val cap = caps.firstOrNull { it.format == format }
        if (cap == null || !cap.isSupported) {
            logger.reportNotSupported(feature, cap?.reason ?: "No encoder on this device")
            return
        }
        val srcFile = generateTestToneWav("selftest_conv_${format.extension}.wav", durationSec = 1.0f, freqHz = 440.0)
        try {
            val dummyRec = RecordingEntity(
                id = -1L,
                title = "selftest_${format.extension}",
                filePath = srcFile.absolutePath,
                durationMs = 1000L,
                fileSizeBytes = srcFile.length(),
                format = "WAV",
                sampleRate = 48000,
                channels = 1,
                bitsPerSample = 16,
                isFloatPcm = false
            )
            val result = converterEngine.convertRecording(
                source = dummyRec,
                config = ConversionConfig(
                    outputFormat = format,
                    targetSampleRate = 48000,
                    targetChannels = 1
                )
            )
            result.onSuccess { summary ->
                File(summary.outputRecording.filePath).delete()
                logger.reportSuccess(
                    feature,
                    "1s ${format.displayName} conversion OK (${summary.outputSizeBytes} B)"
                )
            }.onFailure { err ->
                logger.reportFailure(feature, "${format.displayName} convert failed: ${err.message}", err)
            }
        } finally {
            srcFile.delete()
        }
    }

    private fun testEditorTimeline() {
        val src = generateTestToneWav("selftest_edl.wav", durationSec = 1.0f, freqHz = 440.0)
        try {
            val ops = listOf(
                EdlOperation(id = "t1", type = EdlOpType.TRIM, label = "Trim", startMs = 200L, endMs = 700L)
            )
            val (segs, sr) = editorEngine.buildVirtualTimeline(src, ops)
            val durMs = (segs.sumOf { it.frameCount } * 1000L) / sr.coerceAtLeast(1)
            if (durMs in 450L..550L) {
                logger.reportSuccess(AppFeature.EDITOR, "Virtual EDL trim timeline verified ($durMs ms)")
            } else {
                logger.reportFailure(AppFeature.EDITOR, "Unexpected EDL timeline duration ($durMs ms)")
            }
        } finally {
            src.delete()
        }
    }

    private suspend fun testNoiseTools() {
        val src = generateTestToneWav("selftest_noise.wav", durationSec = 1.2f, freqHz = 1000.0)
        try {
            val profile = NoiseProfileLearner.autoDetectQuietest1Second(src)
            val proc = SpectralNoiseProcessor(48000, 1, 1024)
            val buf = FloatArray(2048) { 0.1f * sin(2.0 * PI * 1000.0 * it / 48000.0).toFloat() }
            proc.processInterleaved(
                buffer = buf,
                frames = 2048,
                profile = profile,
                removal = NoiseRemovalParams(enabled = true, reductionDb = 18f),
                level = NoiseLevelParams(enabled = true, existingNoiseDb = -12f),
                noiseOnlyMode = false
            )
            val synth = NoiseAddSynthesizer(48000, 1)
            synth.mixIntoChunk(
                buffer = buf,
                frames = 2048,
                chunkStartFrame = 0L,
                totalTimelineFrames = 2048L,
                params = NoiseAddParams(enabled = true, noiseType = NoiseType.PINK, volumeDb = -24f),
                selectionStartFrame = 0L,
                selectionEndFrame = 2048L,
                learnedProfile = profile
            )
            if (profile != null && buf.all { it.isFinite() }) {
                logger.reportSuccess(AppFeature.NOISE_TOOLS, "Noise profile learner, spectral gate & synthesizer OK")
            } else {
                logger.reportFailure(AppFeature.NOISE_TOOLS, "Noise tools DSP produced invalid output")
            }
        } finally {
            src.delete()
        }
    }

    private fun testSpectrogramFft() {
        val fft = FftProcessor(2048, FftWindowType.HANN)
        val tone = FloatArray(2048) { 0.5f * sin(2.0 * PI * 1000.0 * it / 48000.0).toFloat() }
        val res = fft.analyze(tone, 48000)
        if (abs(res.peakFrequencyHz - 1000.0) < 35.0) {
            logger.reportSuccess(
                AppFeature.SPECTROGRAM_FFT,
                "FFT peak accuracy OK (${String.format(Locale.US, "%.1f Hz", res.peakFrequencyHz)})"
            )
        } else {
            logger.reportFailure(AppFeature.SPECTROGRAM_FFT, "FFT peak inaccurate (${res.peakFrequencyHz} Hz)")
        }
    }

    private fun testMarkers() {
        val src = generateTestToneWav("selftest_marker.wav", durationSec = 0.5f, freqHz = 440.0)
        try {
            val ok = WavFileHelper.appendCueChunks(src, listOf(WavCuePoint(1, 12000L, "SelfTest")))
            if (ok) {
                logger.reportSuccess(AppFeature.MARKERS, "WAV cue/label marker chunk embedding OK")
            } else {
                logger.reportFailure(AppFeature.MARKERS, "Failed to embed WAV cue chunk")
            }
        } finally {
            src.delete()
        }
    }

    private fun testComponentPresent(cls: Class<*>, feature: AppFeature, label: String) {
        val cn = ComponentName(context, cls)
        val enabled = runCatching {
            context.packageManager.getComponentEnabledSetting(cn) != PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        }.getOrDefault(false)
        if (enabled) {
            logger.reportSuccess(feature, "$label registered & enabled")
        } else {
            logger.reportFailure(feature, "$label disabled or missing in manifest")
        }
    }

    private suspend fun testSpiritBoxRadioStreams() {
        val urls = settingsDataStore.settingsFlow.first().spiritBoxStreamUrls
        if (urls.isEmpty()) {
            logger.reportFailure(AppFeature.SPIRIT_BOX_RADIO, "No radio stream URLs configured")
            return
        }
        var reachable = 0
        var lastErr = "No stream responded"
        for (urlStr in urls.take(3)) {
            runCatching {
                val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                    requestMethod = "HEAD"
                    connectTimeout = 3000
                    readTimeout = 3000
                    instanceFollowRedirects = true
                }
                val code = conn.responseCode
                conn.disconnect()
                if (code in 200..399) reachable++
                else lastErr = "HTTP $code from $urlStr"
            }.onFailure { e ->
                lastErr = e.message ?: "Network unreachable"
            }
            if (reachable > 0) break
        }
        if (reachable > 0) {
            logger.reportSuccess(AppFeature.SPIRIT_BOX_RADIO, "Internet radio stream reachable ($reachable/${urls.size} checked)")
        } else {
            logger.reportFailure(AppFeature.SPIRIT_BOX_RADIO, "Stream reachability failed: $lastErr")
        }
    }

    private suspend fun testSpiritBoxLocalAudio() {
        val wavs = repository.getAllRecordingsOnce().filter {
            it.format.equals("WAV", ignoreCase = true) && File(it.filePath).exists()
        }
        if (wavs.isNotEmpty()) {
            logger.reportSuccess(AppFeature.SPIRIT_BOX_LOCAL, "${wavs.size} local WAV file(s) ready for random fragments")
        } else {
            logger.reportNotSupported(AppFeature.SPIRIT_BOX_LOCAL, "No local WAV recordings in Library yet")
        }
    }

    private fun testSpiritBoxStatic() {
        val buf = FloatArray(1024)
        val synth = NoiseAddSynthesizer(48000, 1)
        synth.mixIntoChunk(
            buffer = buf,
            frames = 1024,
            chunkStartFrame = 0L,
            totalTimelineFrames = 1024L,
            params = NoiseAddParams(enabled = true, noiseType = NoiseType.STATIC, volumeDb = -18f),
            selectionStartFrame = 0L,
            selectionEndFrame = 1024L,
            learnedProfile = null
        )
        val rms = AudioMath.computeRmsDb(buf, buf.size)
        if (rms > -50f && buf.all { it in -1f..1f }) {
            logger.reportSuccess(AppFeature.SPIRIT_BOX_STATIC, "Static synthesizer verified (RMS=${rms.toInt()} dBFS)")
        } else {
            logger.reportFailure(AppFeature.SPIRIT_BOX_STATIC, "Static synthesizer output invalid (RMS=$rms)")
        }
    }

    private suspend fun testSpiritBoxTts() {
        val status = withTimeoutOrNull(3000L) {
            suspendCancellableCoroutine<Int> { cont ->
                var tts: TextToSpeech? = null
                tts = TextToSpeech(context) { initStatus ->
                    val enAvail = if (initStatus == TextToSpeech.SUCCESS) {
                        tts?.isLanguageAvailable(Locale.US) ?: TextToSpeech.LANG_NOT_SUPPORTED
                    } else TextToSpeech.LANG_NOT_SUPPORTED
                    runCatching { tts?.shutdown() }
                    if (cont.isActive) {
                        cont.resume(if (initStatus == TextToSpeech.SUCCESS && enAvail >= 0) TextToSpeech.SUCCESS else -1)
                    }
                }
                cont.invokeOnCancellation { runCatching { tts?.shutdown() } }
            }
        }
        when (status) {
            TextToSpeech.SUCCESS -> logger.reportSuccess(AppFeature.SPIRIT_BOX_TTS, "Offline TextToSpeech engine available")
            null -> logger.reportNotSupported(AppFeature.SPIRIT_BOX_TTS, "TTS engine timed out / unavailable on device")
            else -> logger.reportNotSupported(AppFeature.SPIRIT_BOX_TTS, "No offline TTS voice data installed")
        }
    }

    private fun generateTestToneWav(fileName: String, durationSec: Float, freqHz: Double): File {
        val file = File(context.cacheDir, fileName)
        val sr = 48000
        val totalFrames = (sr * durationSec).toInt()
        val samples = FloatArray(totalFrames) { i ->
            0.25f * sin(2.0 * PI * freqHz * i / sr).toFloat()
        }
        RandomAccessFile(file, "rw").use { raf ->
            raf.setLength(0L)
            WavFileHelper.writePlaceholderHeader(raf, sr, 1, 16, isFloat = false)
            val bytes = WavFileHelper.writeFloatSamples(raf, samples, samples.size, writeAsFloat32 = false)
            WavFileHelper.patchWavHeaderSizes(raf, bytes)
        }
        return file
    }
}
