package com.example.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

enum class RecordingSourceTag {
    RECORDED,
    IMPORTED,
    CONVERTED,
    SPIRIT_BOX,
    PROCESSED
}

enum class RecordingStorageType {
    APP_PRIVATE,
    PUBLIC_DOCUMENTS,
    EXTERNAL_SD_SAF
}

@Entity(tableName = "recordings")
data class RecordingEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val title: String,
    val filePath: String,
    val mediaStoreUri: String? = null,
    val durationMs: Long,
    val fileSizeBytes: Long,
    val createdAtMillis: Long = System.currentTimeMillis(),
    val format: String = "WAV",
    val sampleRate: Int = 48000,
    val channels: Int = 1,
    val bitsPerSample: Int = 32,
    val isFloatPcm: Boolean = true,
    val sourceTag: RecordingSourceTag = RecordingSourceTag.RECORDED,
    val isLossySource: Boolean = false,
    val audioSourceUsed: String = "UNPROCESSED",
    val inputDeviceName: String = "Built-in Mic",
    val gainAppliedDb: Float = 0f,
    val savedRaw: Boolean = true,
    val storageType: RecordingStorageType = RecordingStorageType.APP_PRIVATE,
    val storageUri: String? = null
)

@Entity(
    tableName = "markers",
    foreignKeys = [
        ForeignKey(
            entity = RecordingEntity::class,
            parentColumns = ["id"],
            childColumns = ["recordingId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("recordingId")]
)
data class MarkerEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val recordingId: Long,
    val timestampMs: Long,
    val label: String,
    val note: String = "",
    val createdAtMillis: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "edit_lists",
    foreignKeys = [
        ForeignKey(
            entity = RecordingEntity::class,
            parentColumns = ["id"],
            childColumns = ["recordingId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["recordingId"], unique = true)]
)
data class EditListEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val recordingId: Long,
    val operationsJson: String,
    val redoStackJson: String = "[]",
    val updatedAtMillis: Long = System.currentTimeMillis()
)

@Entity(tableName = "spirit_box_sessions")
data class SpiritBoxSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val startedAtMillis: Long = System.currentTimeMillis(),
    val durationMs: Long = 0L,
    val recordingId: Long? = null,
    val micRecordingId: Long? = null,
    val bandMode: String = "FM",
    val scanSpeedMs: Int = 250,
    val jitterEnabled: Boolean = true,
    val direction: String = "FORWARD",
    val sourcesUsed: String = "",
    val blindTestEnabled: Boolean = false,
    val hopCount: Int = 0,
    val questionsJson: String = "[]",
    val markersJson: String = "[]",
    val hopLogJson: String = "[]"
)

@Serializable
data class MarkerSidecarExport(
    val recordingTitle: String,
    val sampleRate: Int,
    val durationMs: Long,
    val markers: List<MarkerExportItem>
)

@Serializable
data class MarkerExportItem(
    val timestampMs: Long,
    val label: String,
    val note: String
)

@Serializable
data class SpiritBoxQuestionLogItem(
    val timestampMs: Long,
    val question: String,
    val inputMode: String = "TEXT"
)

@Serializable
data class SpiritBoxHopLogItem(
    val hopIndex: Int,
    val timestampMs: Long,
    val freqLabel: String,
    val actualSource: String,
    val detail: String = ""
)

@Serializable
data class SpiritBoxSessionExport(
    val sessionId: Long,
    val startedAtMillis: Long,
    val durationMs: Long,
    val bandMode: String,
    val scanSpeedMs: Int,
    val jitterEnabled: Boolean,
    val direction: String,
    val sourcesUsed: String,
    val blindTestEnabled: Boolean,
    val hopCount: Int,
    val questions: List<SpiritBoxQuestionLogItem>,
    val markers: List<MarkerExportItem>,
    val hopLog: List<SpiritBoxHopLogItem>
)

@Serializable
enum class NoiseType {
    WHITE,
    PINK,
    BROWN,
    RAIN,
    WIND,
    FAN_ROOM_TONE,
    STATIC,
    LEARNED_ROOM_TONE
}

@Serializable
enum class OneTapNoiseTool {
    NONE,
    HISS_REMOVER,
    HUM_REMOVER_50HZ,
    HUM_REMOVER_60HZ,
    CLICK_POP_REMOVER,
    WIND_NOISE_CUT
}

@Serializable
data class NoiseProfilePayload(
    val fftSize: Int = 1024,
    val sampleRate: Int = 48000,
    val meanMag: List<Float> = emptyList(),
    val stdMag: List<Float> = emptyList(),
    val learnedStartMs: Long = 0L,
    val learnedEndMs: Long = 0L
)

@Serializable
data class NoiseRemovalParams(
    val enabled: Boolean = false,
    val reductionDb: Float = 18f,
    val sensitivity: Float = 1.4f,
    val freqSmoothingBins: Int = 2,
    val keepFaintSounds: Boolean = true
)

@Serializable
data class NoiseAddParams(
    val enabled: Boolean = false,
    val noiseType: NoiseType = NoiseType.PINK,
    val volumeDb: Float = -30f,
    val hpfHz: Float = 0f,
    val lpfHz: Float = 20000f,
    val applyToSelectionOnly: Boolean = false,
    val fadeInMs: Long = 50L,
    val fadeOutMs: Long = 50L,
    val fixedSnrMode: Boolean = false,
    val targetSnrDb: Float = 20f
)

@Serializable
data class NoiseLevelParams(
    val enabled: Boolean = false,
    val existingNoiseDb: Float = 0f, // -40 dB to +12 dB
    val addedNoiseDb: Float = -60f,  // -60 dBFS to 0 dBFS
    val lowBandNoiseDb: Float = 0f,  // < 300 Hz (-40..+12 dB)
    val midBandNoiseDb: Float = 0f,  // 300 Hz - 4 kHz (-40..+12 dB)
    val highBandNoiseDb: Float = 0f  // > 4 kHz (-40..+12 dB)
)

@Serializable
enum class EdlOpType {
    TRIM,
    CUT,
    FADE_IN,
    FADE_OUT,
    SILENCE,
    GAIN,
    EQ,
    FILTER,
    LUFS_NORMALIZE,
    MERGE_APPEND,
    NOISE_REMOVAL,
    NOISE_ONE_TAP,
    NOISE_ADD,
    NOISE_LEVEL_CONTROL
}

@Serializable
data class EdlOperation(
    val id: String,
    val type: EdlOpType,
    val label: String,
    val startMs: Long = 0L,
    val endMs: Long = 0L,
    val gainDb: Float = 0f,
    val eqBandsDb: List<Float> = emptyList(),
    val hpfHz: Float = 0f,
    val hpfSlopeDb: Int = 12,
    val lpfHz: Float = 0f,
    val lpfSlopeDb: Int = 12,
    val targetLufs: Float = -16f,
    val truePeakCeilingDb: Float = -1f,
    val mergeSourcePath: String? = null,
    val noiseProfile: NoiseProfilePayload? = null,
    val noiseRemoval: NoiseRemovalParams? = null,
    val oneTapNoiseTool: OneTapNoiseTool? = null,
    val noiseAdd: NoiseAddParams? = null,
    val noiseLevel: NoiseLevelParams? = null
)

// --- Feature Health & Event Log Entities ---

@Serializable
enum class LogLevel {
    INFO,
    WARNING,
    ERROR
}

@Serializable
enum class FeatureHealthStatus {
    WORKING,
    FAILED,
    NOT_SUPPORTED,
    NOT_TESTED
}

enum class AppFeature(val id: String, val displayName: String) {
    RECORDING_UNPROCESSED("recording_unprocessed", "Recording (UNPROCESSED)"),
    MIC_PERMISSION("mic_permission", "Mic Permission"),
    FOREGROUND_SERVICE("foreground_service", "Foreground Service"),
    EXTERNAL_MIC("external_mic", "External Mic"),
    VAR("var_recording", "Voice-Activated Recording"),
    GAIN_LIMITER("gain_limiter", "Gain / Limiter"),
    WAV_SAVING("wav_saving", "WAV Saving"),
    PLAYBACK_FORWARD("playback_forward", "Playback Forward"),
    PLAYBACK_REVERSE("playback_reverse", "Playback Reverse"),
    SPEED_CONTROL("speed_control", "Speed Control"),
    IMPORT("audio_import", "Audio Import"),
    CONVERT_WAV("convert_wav", "Converter: WAV"),
    CONVERT_MP3("convert_mp3", "Converter: MP3"),
    CONVERT_AAC("convert_aac", "Converter: AAC (M4A)"),
    CONVERT_OGG("convert_ogg", "Converter: OGG"),
    CONVERT_FLAC("convert_flac", "Converter: FLAC"),
    EDITOR("editor", "Non-Destructive Editor"),
    NOISE_TOOLS("noise_tools", "Noise Tools"),
    SPECTROGRAM_FFT("spectrogram_fft", "Spectrogram / FFT"),
    MARKERS("markers", "Markers"),
    WIDGET("widget", "Home Screen Widget"),
    QUICK_TILE("quick_tile", "Quick-Settings Tile"),
    SPIRIT_BOX_RADIO("spirit_box_radio", "Spirit Box: Radio Streams"),
    SPIRIT_BOX_LOCAL("spirit_box_local", "Spirit Box: Local Audio"),
    SPIRIT_BOX_STATIC("spirit_box_static", "Spirit Box: Static"),
    SPIRIT_BOX_TTS("spirit_box_tts", "Spirit Box: Offline TTS");

    companion object {
        fun fromId(id: String): AppFeature? = entries.firstOrNull { it.id == id }
    }
}

@Entity(
    tableName = "app_logs",
    indices = [Index("timestampMillis"), Index("level"), Index("featureTag")]
)
data class AppLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val timestampMillis: Long = System.currentTimeMillis(),
    val level: LogLevel = LogLevel.INFO,
    val featureTag: String,
    val message: String,
    val stackTrace: String? = null
)

@Entity(tableName = "feature_health")
data class FeatureHealthEntity(
    @PrimaryKey val featureId: String,
    val status: FeatureHealthStatus = FeatureHealthStatus.NOT_TESTED,
    val lastSuccessMillis: Long? = null,
    val lastErrorMessage: String? = null,
    val lastUpdatedMillis: Long = System.currentTimeMillis()
)

@Serializable
data class AppLogExportEntry(
    val timestampMillis: Long,
    val level: LogLevel,
    val featureTag: String,
    val message: String,
    val stackTrace: String? = null
)

@Serializable
data class FeatureHealthExportItem(
    val featureId: String,
    val displayName: String,
    val status: FeatureHealthStatus,
    val lastSuccessMillis: Long? = null,
    val lastErrorMessage: String? = null
)

@Serializable
data class FullLogReportExport(
    val exportedAtMillis: Long,
    val summary: String,
    val featureHealth: List<FeatureHealthExportItem>,
    val logs: List<AppLogExportEntry>
)

