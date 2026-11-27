package com.example.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.ui.theme.ThemePreference
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.settingsDataStore by preferencesDataStore(name = "faintrec_settings")

val DEFAULT_SPIRIT_BOX_STREAMS = listOf(
    "https://ice1.somafm.com/dronezone-128-mp3",
    "https://ice1.somafm.com/spacestation-128-mp3",
    "https://ice1.somafm.com/deepspaceone-128-mp3",
    "https://stream.live.vc.bbcmedia.co.uk/bbc_world_service"
)

data class FaintRecSettings(
    val themePreference: ThemePreference = ThemePreference.DARK,
    val languageTag: String = "en",
    val channels: Int = 1,
    val preferFloatPcm: Boolean = true,
    val inputGainDb: Float = 6f,
    val saveRawNoGain: Boolean = true,
    val varEnabled: Boolean = false,
    val varThresholdDb: Float = -45f,
    val varHangTimeSec: Float = 3.0f,
    val preferredDeviceId: Int = -1,
    val preferredDeviceName: String = "",
    val fftSize: Int = 2048,
    val fftWindow: String = "HANN",
    val fftLogScale: Boolean = true,
    val spiritBoxStreamUrls: List<String> = DEFAULT_SPIRIT_BOX_STREAMS,
    val spiritBoxRecorderEnabled: Boolean = true,
    val spiritBoxRecordSweepOutput: Boolean = true,
    val spiritBoxRecordMicTrack: Boolean = false,
    val mp3UnavailableNoticeDismissed: Boolean = false,
    val storageType: RecordingStorageType = RecordingStorageType.APP_PRIVATE,
    val safTreeUri: String? = null,
    val safDisplayPath: String? = null
)

class SettingsDataStore(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true }

    private object Keys {
        val THEME = stringPreferencesKey("theme_preference")
        val LANGUAGE = stringPreferencesKey("language_tag")
        val CHANNELS = intPreferencesKey("channels")
        val PREFER_FLOAT = booleanPreferencesKey("prefer_float_pcm")
        val INPUT_GAIN = floatPreferencesKey("input_gain_db")
        val SAVE_RAW = booleanPreferencesKey("save_raw_no_gain")
        val VAR_ENABLED = booleanPreferencesKey("var_enabled")
        val VAR_THRESHOLD = floatPreferencesKey("var_threshold_db")
        val VAR_HANG = floatPreferencesKey("var_hang_sec")
        val PREFERRED_DEVICE_ID = intPreferencesKey("preferred_device_id")
        val PREFERRED_DEVICE_NAME = stringPreferencesKey("preferred_device_name")
        val FFT_SIZE = intPreferencesKey("fft_size")
        val FFT_WINDOW = stringPreferencesKey("fft_window")
        val FFT_LOG = booleanPreferencesKey("fft_log_scale")
        val SPIRIT_BOX_STREAMS = stringPreferencesKey("spirit_box_stream_urls")
        val SPIRIT_BOX_RECORDER_ENABLED = booleanPreferencesKey("spirit_box_recorder_enabled")
        val SPIRIT_BOX_RECORDER_USER_SET = booleanPreferencesKey("spirit_box_recorder_user_set")
        val SPIRIT_BOX_RECORD_SWEEP = booleanPreferencesKey("spirit_box_record_sweep_output")
        val SPIRIT_BOX_RECORD_MIC = booleanPreferencesKey("spirit_box_record_mic_track")
        val MP3_NOTICE_DISMISSED = booleanPreferencesKey("mp3_unavailable_notice_dismissed")
        val STORAGE_TYPE = stringPreferencesKey("recording_storage_type")
        val SAF_TREE_URI = stringPreferencesKey("saf_tree_uri")
        val SAF_DISPLAY_PATH = stringPreferencesKey("saf_display_path")
    }

    val settingsFlow: Flow<FaintRecSettings> = context.settingsDataStore.data.map { prefs ->
        val savedStreams = prefs[Keys.SPIRIT_BOX_STREAMS]?.let { raw ->
            runCatching { json.decodeFromString<List<String>>(raw) }.getOrNull()
        } ?: DEFAULT_SPIRIT_BOX_STREAMS
        val recorderUserSet = prefs[Keys.SPIRIT_BOX_RECORDER_USER_SET] ?: false
        val recorderEnabled = if (recorderUserSet) {
            prefs[Keys.SPIRIT_BOX_RECORDER_ENABLED] ?: true
        } else {
            true
        }

        FaintRecSettings(
            themePreference = ThemePreference.entries.firstOrNull {
                it.name == prefs[Keys.THEME]
            } ?: ThemePreference.DARK,
            languageTag = prefs[Keys.LANGUAGE] ?: "en",
            channels = prefs[Keys.CHANNELS] ?: 1,
            preferFloatPcm = prefs[Keys.PREFER_FLOAT] ?: true,
            inputGainDb = prefs[Keys.INPUT_GAIN] ?: 6f,
            saveRawNoGain = prefs[Keys.SAVE_RAW] ?: true,
            varEnabled = prefs[Keys.VAR_ENABLED] ?: false,
            varThresholdDb = prefs[Keys.VAR_THRESHOLD] ?: -45f,
            varHangTimeSec = prefs[Keys.VAR_HANG] ?: 3.0f,
            preferredDeviceId = prefs[Keys.PREFERRED_DEVICE_ID] ?: -1,
            preferredDeviceName = prefs[Keys.PREFERRED_DEVICE_NAME] ?: "",
            fftSize = prefs[Keys.FFT_SIZE] ?: 2048,
            fftWindow = prefs[Keys.FFT_WINDOW] ?: "HANN",
            fftLogScale = prefs[Keys.FFT_LOG] ?: true,
            spiritBoxStreamUrls = savedStreams,
            spiritBoxRecorderEnabled = recorderEnabled,
            spiritBoxRecordSweepOutput = prefs[Keys.SPIRIT_BOX_RECORD_SWEEP] ?: true,
            spiritBoxRecordMicTrack = prefs[Keys.SPIRIT_BOX_RECORD_MIC] ?: false,
            mp3UnavailableNoticeDismissed = prefs[Keys.MP3_NOTICE_DISMISSED] ?: false,
            storageType = RecordingStorageType.entries.firstOrNull {
                it.name == prefs[Keys.STORAGE_TYPE]
            } ?: RecordingStorageType.APP_PRIVATE,
            safTreeUri = prefs[Keys.SAF_TREE_URI],
            safDisplayPath = prefs[Keys.SAF_DISPLAY_PATH]
        )
    }

    suspend fun setTheme(theme: ThemePreference) {
        context.settingsDataStore.edit { it[Keys.THEME] = theme.name }
    }

    suspend fun setLanguage(tag: String) {
        context.settingsDataStore.edit { it[Keys.LANGUAGE] = tag }
    }

    suspend fun setChannels(channels: Int) {
        context.settingsDataStore.edit { it[Keys.CHANNELS] = channels.coerceIn(1, 2) }
    }

    suspend fun setPreferFloatPcm(preferFloat: Boolean) {
        context.settingsDataStore.edit { it[Keys.PREFER_FLOAT] = preferFloat }
    }

    suspend fun setInputGainDb(gainDb: Float) {
        context.settingsDataStore.edit { it[Keys.INPUT_GAIN] = gainDb.coerceIn(0f, 30f) }
    }

    suspend fun setSaveRawNoGain(saveRaw: Boolean) {
        context.settingsDataStore.edit { it[Keys.SAVE_RAW] = saveRaw }
    }

    suspend fun setVarConfig(enabled: Boolean, thresholdDb: Float, hangSec: Float) {
        context.settingsDataStore.edit {
            it[Keys.VAR_ENABLED] = enabled
            it[Keys.VAR_THRESHOLD] = thresholdDb.coerceIn(-75f, -5f)
            it[Keys.VAR_HANG] = hangSec.coerceIn(0.5f, 15f)
        }
    }

    suspend fun setPreferredDevice(deviceId: Int, deviceName: String) {
        context.settingsDataStore.edit {
            it[Keys.PREFERRED_DEVICE_ID] = deviceId
            it[Keys.PREFERRED_DEVICE_NAME] = deviceName
        }
    }

    suspend fun setFftConfig(size: Int, window: String, logScale: Boolean) {
        context.settingsDataStore.edit {
            it[Keys.FFT_SIZE] = size
            it[Keys.FFT_WINDOW] = window
            it[Keys.FFT_LOG] = logScale
        }
    }

    suspend fun setSpiritBoxStreamUrls(urls: List<String>) {
        context.settingsDataStore.edit {
            it[Keys.SPIRIT_BOX_STREAMS] = json.encodeToString(urls)
        }
    }

    suspend fun setSpiritBoxRecorderEnabled(enabled: Boolean) {
        context.settingsDataStore.edit {
            it[Keys.SPIRIT_BOX_RECORDER_ENABLED] = enabled
            it[Keys.SPIRIT_BOX_RECORDER_USER_SET] = true
        }
    }

    suspend fun setSpiritBoxRecordSweepOutput(enabled: Boolean) {
        context.settingsDataStore.edit {
            it[Keys.SPIRIT_BOX_RECORD_SWEEP] = enabled
        }
    }

    suspend fun setSpiritBoxRecordMicTrack(enabled: Boolean) {
        context.settingsDataStore.edit {
            it[Keys.SPIRIT_BOX_RECORD_MIC] = enabled
        }
    }

    suspend fun setMp3UnavailableNoticeDismissed(dismissed: Boolean) {
        context.settingsDataStore.edit {
            it[Keys.MP3_NOTICE_DISMISSED] = dismissed
        }
    }

    suspend fun setStorageType(type: RecordingStorageType) {
        context.settingsDataStore.edit {
            it[Keys.STORAGE_TYPE] = type.name
        }
    }

    suspend fun setSafTreeLocation(treeUri: String, displayPath: String) {
        context.settingsDataStore.edit {
            it[Keys.SAF_TREE_URI] = treeUri
            it[Keys.SAF_DISPLAY_PATH] = displayPath
            it[Keys.STORAGE_TYPE] = RecordingStorageType.EXTERNAL_SD_SAF.name
        }
    }

    suspend fun clearSafTreeLocation() {
        context.settingsDataStore.edit {
            it.remove(Keys.SAF_TREE_URI)
            it.remove(Keys.SAF_DISPLAY_PATH)
            if (it[Keys.STORAGE_TYPE] == RecordingStorageType.EXTERNAL_SD_SAF.name) {
                it[Keys.STORAGE_TYPE] = RecordingStorageType.APP_PRIVATE.name
            }
        }
    }
}
