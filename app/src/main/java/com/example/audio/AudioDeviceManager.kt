package com.example.audio

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

enum class InputDeviceCategory(val label: String) {
    BUILT_IN("Built-in Mic"),
    WIRED_HEADSET("Wired 3.5mm Mic"),
    USB_AUDIO("USB Audio Mic"),
    BLUETOOTH("Bluetooth Mic")
}

data class AudioInputDeviceUi(
    val id: Int,
    val name: String,
    val category: InputDeviceCategory,
    val rawType: Int,
    val sampleRates: List<Int>,
    val channelCounts: List<Int>,
    val encodings: List<String>,
    val isBluetooth: Boolean,
    val isUsb: Boolean,
    val deviceInfo: AudioDeviceInfo? = null
)

@Singleton
class AudioDeviceManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        const val ACTION_USB_PERMISSION = "com.example.action.USB_AUDIO_PERMISSION"

        /**
         * Strict allowlist for external microphone input device types.
         * Excludes TYPE_BUILTIN_MIC, TYPE_TELEPHONY, TYPE_FM_TUNER, TYPE_ECHO_REFERENCE,
         * TYPE_REMOTE_SUBMIX, TYPE_BUS, and all other non-external system input routes.
         */
        fun isAllowedExternalMicType(type: Int, sdkInt: Int = Build.VERSION.SDK_INT): Boolean {
            return when (type) {
                AudioDeviceInfo.TYPE_WIRED_HEADSET,
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                AudioDeviceInfo.TYPE_USB_DEVICE,
                AudioDeviceInfo.TYPE_USB_HEADSET,
                AudioDeviceInfo.TYPE_USB_ACCESSORY,
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> true
                else -> sdkInt >= Build.VERSION_CODES.S && type == AudioDeviceInfo.TYPE_BLE_HEADSET
            }
        }

        /**
         * Ensures NoiseSuppressor, AutomaticGainControl, and AcousticEchoCanceler are never
         * enabled on an AudioRecord session; if the HAL auto-attached any of them, disable and
         * release them immediately.
         */
        fun disableAndReleaseAutoAttachedEffects(audioSessionId: Int) {
            if (audioSessionId <= 0) return
            if (NoiseSuppressor.isAvailable()) {
                runCatching {
                    NoiseSuppressor.create(audioSessionId)?.let { ns ->
                        if (ns.enabled) ns.enabled = false
                        ns.release()
                    }
                }
            }
            if (AutomaticGainControl.isAvailable()) {
                runCatching {
                    AutomaticGainControl.create(audioSessionId)?.let { agc ->
                        if (agc.enabled) agc.enabled = false
                        agc.release()
                    }
                }
            }
            if (AcousticEchoCanceler.isAvailable()) {
                runCatching {
                    AcousticEchoCanceler.create(audioSessionId)?.let { aec ->
                        if (aec.enabled) aec.enabled = false
                        aec.release()
                    }
                }
            }
        }
    }

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val usbManager = context.getSystemService(Context.USB_SERVICE) as? UsbManager

    private val _inputDevices = MutableStateFlow<List<AudioInputDeviceUi>>(emptyList())
    val inputDevices: StateFlow<List<AudioInputDeviceUi>> = _inputDevices.asStateFlow()

    private val _externalInputDevices = MutableStateFlow<List<AudioInputDeviceUi>>(emptyList())
    val externalInputDevices: StateFlow<List<AudioInputDeviceUi>> = _externalInputDevices.asStateFlow()

    private val _selectedDevice = MutableStateFlow<AudioInputDeviceUi?>(null)
    val selectedDevice: StateFlow<AudioInputDeviceUi?> = _selectedDevice.asStateFlow()

    private val _deviceDisconnectedEvents = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val deviceDisconnectedEvents: SharedFlow<String> = _deviceDisconnectedEvents.asSharedFlow()

    private val _usbPermissionStatus = MutableStateFlow<String?>(null)
    val usbPermissionStatus: StateFlow<String?> = _usbPermissionStatus.asStateFlow()

    private var callbackRegistered = false

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
            refreshDevices()
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
            val currentSelected = _selectedDevice.value
            val wasSelectedRemoved = currentSelected != null &&
                removedDevices?.any { it.id == currentSelected.id } == true
            refreshDevices()
            if (wasSelectedRemoved) {
                val builtIn = _inputDevices.value.firstOrNull {
                    it.category == InputDeviceCategory.BUILT_IN
                } ?: _inputDevices.value.firstOrNull()
                _selectedDevice.value = builtIn
                _deviceDisconnectedEvents.tryEmit(
                    currentSelected.name
                )
            }
        }
    }

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            if (intent?.action == ACTION_USB_PERMISSION) {
                val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                _usbPermissionStatus.value = if (granted) {
                    "USB audio permission granted"
                } else {
                    "USB audio permission denied"
                }
                refreshDevices()
            }
        }
    }

    init {
        refreshDevices()
        startMonitoring()
    }

    fun startMonitoring() {
        if (callbackRegistered) return
        try {
            audioManager.registerAudioDeviceCallback(deviceCallback, null)
            val filter = IntentFilter(ACTION_USB_PERMISSION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                context.registerReceiver(usbReceiver, filter)
            }
            callbackRegistered = true
        } catch (_: Exception) {
        }
    }

    fun refreshDevices(preferredId: Int = -1) {
        val rawDevices = try {
            audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
        } catch (_: Exception) {
            emptyArray()
        }

        // Keep ONLY built-in mic and strictly allowlisted external mic types
        val filtered = rawDevices.filter { info ->
            info.type == AudioDeviceInfo.TYPE_BUILTIN_MIC || isAllowedExternalMicType(info.type)
        }

        val mapped = filtered.mapNotNull { info ->
            val category = categorize(info.type) ?: return@mapNotNull null
            val rates = info.sampleRates.toList().ifEmpty { listOf(48000) }.sorted()
            val channels = info.channelCounts.toList().ifEmpty { listOf(1, 2) }.sorted()
            val encNames = info.encodings.map { encodingName(it) }.ifEmpty { listOf("PCM 16-bit") }
            val isUsb = category == InputDeviceCategory.USB_AUDIO

            val friendlyName = info.productName?.toString()?.takeIf { it.isNotBlank() }
                ?: category.label

            AudioInputDeviceUi(
                id = info.id,
                name = "$friendlyName (${category.label})",
                category = category,
                rawType = info.type,
                sampleRates = rates,
                channelCounts = channels,
                encodings = encNames,
                isBluetooth = category == InputDeviceCategory.BLUETOOTH,
                isUsb = isUsb,
                deviceInfo = info
            )
        }.distinctBy { it.id }

        _inputDevices.value = mapped
        _externalInputDevices.value = mapped.filter { isAllowedExternalMicType(it.rawType) }

        val current = _selectedDevice.value
        val match = when {
            preferredId > 0 -> mapped.firstOrNull { it.id == preferredId }
            current != null -> mapped.firstOrNull { it.id == current.id }
            else -> null
        } ?: mapped.firstOrNull { it.category == InputDeviceCategory.BUILT_IN }
            ?: mapped.firstOrNull()

        _selectedDevice.value = match
    }

    fun selectDevice(deviceId: Int) {
        val found = _inputDevices.value.firstOrNull { it.id == deviceId } ?: return
        _selectedDevice.value = found
    }

    fun getSelectedDeviceInfo(): AudioDeviceInfo? = _selectedDevice.value?.deviceInfo

    fun supportsUnprocessedSource(): Boolean {
        val prop = audioManager.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED)
        return prop.equals("true", ignoreCase = true)
    }

    fun getRecommendedAudioSourceLabel(): String {
        return if (supportsUnprocessedSource()) "UNPROCESSED" else "VOICE_RECOGNITION"
    }

    fun findConnectedUsbAudioDevices(): List<UsbDevice> {
        val mgr = usbManager ?: return emptyList()
        return mgr.deviceList.values.filter { device ->
            (0 until device.interfaceCount).any { idx ->
                device.getInterface(idx).interfaceClass == UsbConstants.USB_CLASS_AUDIO
            }
        }
    }

    fun requestUsbAudioPermissionIfNeeded(): Boolean {
        val mgr = usbManager ?: return false
        val audioUsb = findConnectedUsbAudioDevices().firstOrNull() ?: return false
        if (mgr.hasPermission(audioUsb)) {
            _usbPermissionStatus.value = "USB audio permission already granted"
            return true
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_MUTABLE
        } else 0
        val pi = PendingIntent.getBroadcast(
            context,
            2041,
            Intent(ACTION_USB_PERMISSION).setPackage(context.packageName),
            flags
        )
        mgr.requestPermission(audioUsb, pi)
        return false
    }

    private fun categorize(type: Int): InputDeviceCategory? {
        return when (type) {
            AudioDeviceInfo.TYPE_BUILTIN_MIC -> InputDeviceCategory.BUILT_IN
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> InputDeviceCategory.WIRED_HEADSET
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_USB_ACCESSORY -> InputDeviceCategory.USB_AUDIO
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> InputDeviceCategory.BLUETOOTH
            else -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    type == AudioDeviceInfo.TYPE_BLE_HEADSET
                ) {
                    InputDeviceCategory.BLUETOOTH
                } else {
                    null
                }
            }
        }
    }

    private fun encodingName(enc: Int): String = when (enc) {
        AudioFormat.ENCODING_PCM_FLOAT -> "PCM 32-bit Float"
        AudioFormat.ENCODING_PCM_16BIT -> "PCM 16-bit"
        AudioFormat.ENCODING_PCM_8BIT -> "PCM 8-bit"
        AudioFormat.ENCODING_PCM_24BIT_PACKED -> "PCM 24-bit"
        AudioFormat.ENCODING_PCM_32BIT -> "PCM 32-bit"
        else -> "Enc #$enc"
    }
}
