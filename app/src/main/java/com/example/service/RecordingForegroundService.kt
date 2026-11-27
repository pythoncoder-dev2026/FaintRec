package com.example.service

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.example.FaintRecApp
import com.example.MainActivity
import com.example.R
import com.example.audio.AudioDeviceManager
import com.example.audio.RecordingConfig
import com.example.audio.RecordingEngine
import com.example.data.AppFeature
import com.example.data.SettingsDataStore
import com.example.logging.AppLogger
import com.example.util.AudioMath
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject

@AndroidEntryPoint
class RecordingForegroundService : Service() {

    companion object {
        const val NOTIF_ID = 4101
        const val ACTION_START = "com.example.service.ACTION_START_RECORDING"
        const val ACTION_STOP = "com.example.service.ACTION_STOP_RECORDING"
        const val ACTION_TOGGLE_PAUSE = "com.example.service.ACTION_TOGGLE_PAUSE"
        const val ACTION_ADD_MARKER = "com.example.service.ACTION_ADD_MARKER"

        fun startIntent(context: Context): Intent {
            return Intent(context, RecordingForegroundService::class.java).apply {
                action = ACTION_START
            }
        }

        fun stopIntent(context: Context): Intent =
            Intent(context, RecordingForegroundService::class.java).apply { action = ACTION_STOP }

        fun togglePauseIntent(context: Context): Intent =
            Intent(context, RecordingForegroundService::class.java).apply { action = ACTION_TOGGLE_PAUSE }

        fun addMarkerIntent(context: Context): Intent =
            Intent(context, RecordingForegroundService::class.java).apply { action = ACTION_ADD_MARKER }
    }

    @Inject lateinit var recordingEngine: RecordingEngine
    @Inject lateinit var deviceManager: AudioDeviceManager
    @Inject lateinit var settingsDataStore: SettingsDataStore
    @Inject lateinit var appLogger: AppLogger

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var telemetryJob: Job? = null
    private var disconnectJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var markerCounter = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()

        // Listen for external mic unplug during recording: safely switch to built-in mic + insert "Mic changed" marker!
        disconnectJob = serviceScope.launch {
            deviceManager.deviceDisconnectedEvents.collect { removedName ->
                if (recordingEngine.telemetry.value.isRecording) {
                    val fallback = deviceManager.getSelectedDeviceInfo()
                    val fallbackName = deviceManager.selectedDevice.value?.name ?: "Built-in Mic"
                    recordingEngine.switchInputDeviceLive(fallback, fallbackName)
                    recordingEngine.addLiveMarker(
                        label = getString(R.string.mic_changed_marker),
                        note = "$removedName disconnected -> switched to $fallbackName"
                    )
                    markerCounter++
                    notifyMicSwitched(removedName, fallbackName)
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                startCaptureSession()
            }
            ACTION_TOGGLE_PAUSE -> {
                recordingEngine.togglePause()
                pushNotificationAndBus(force = true)
            }
            ACTION_ADD_MARKER -> {
                markerCounter++
                recordingEngine.addLiveMarker("Marker #$markerCounter", "Added from quick control")
                pushNotificationAndBus(force = true)
            }
            ACTION_STOP -> {
                stopCaptureSession()
            }
        }
        return START_NOT_STICKY
    }

    private fun startCaptureSession() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            appLogger.reportFailure(AppFeature.MIC_PERMISSION, "RECORD_AUDIO denied when starting ForegroundService")
            stopSelf()
            return
        }

        markerCounter = 0
        acquireWakeLock()

        val initialNotif = buildNotification(isPaused = false, elapsedMs = 0L, db = AudioMath.MIN_DB)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                ServiceCompat.startForeground(
                    this,
                    NOTIF_ID,
                    initialNotif,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                )
            } else {
                startForeground(NOTIF_ID, initialNotif)
            }
            appLogger.reportSuccess(AppFeature.FOREGROUND_SERVICE, "Microphone ForegroundService active")
        } catch (e: Exception) {
            appLogger.reportFailure(AppFeature.FOREGROUND_SERVICE, "startForeground failed: ${e.message}", e)
            releaseWakeLock()
            stopSelf()
            return
        }

        serviceScope.launch {
            val prefs = settingsDataStore.settingsFlow.first()
            val selectedDev = deviceManager.selectedDevice.value

            val config = RecordingConfig(
                sampleRate = 48000,
                channels = prefs.channels,
                preferFloat = prefs.preferFloatPcm,
                inputGainDb = prefs.inputGainDb,
                saveRawNoGain = prefs.saveRawNoGain,
                varEnabled = prefs.varEnabled,
                varThresholdDb = prefs.varThresholdDb,
                varHangTimeSec = prefs.varHangTimeSec,
                preferredDevice = selectedDev?.deviceInfo,
                deviceName = selectedDev?.name ?: "Built-in Mic",
                storageType = prefs.storageType,
                safTreeUri = prefs.safTreeUri
            )

            val started = recordingEngine.startRecording(config)
            if (!started) {
                stopCaptureSession()
                return@launch
            }

            telemetryJob?.cancel()
            var lastNotifMs = 0L
            var lowSpaceNotified = false
            var fullStopNotified = false
            telemetryJob = serviceScope.launch {
                recordingEngine.telemetry.collect { t ->
                    if (t.lowSpaceWarning && !lowSpaceNotified) {
                        lowSpaceNotified = true
                        notifyStorageAlert(
                            notifId = NOTIF_ID + 2,
                            title = getString(R.string.storage_low_notif_title),
                            body = getString(R.string.storage_low_notif_body)
                        )
                    }
                    if (t.stoppedDueToFullStorage && !fullStopNotified) {
                        fullStopNotified = true
                        notifyStorageAlert(
                            notifId = NOTIF_ID + 3,
                            title = getString(R.string.storage_full_stop_notif_title),
                            body = getString(R.string.storage_full_stop_notif_body)
                        )
                    }
                    val now = System.currentTimeMillis()
                    if (now - lastNotifMs >= 900L || !t.isRecording) {
                        lastNotifMs = now
                        pushNotificationAndBus(force = !t.isRecording)
                    }
                    if (!t.isRecording) {
                        stopCaptureSession()
                    }
                }
            }
        }
    }

    private fun stopCaptureSession() {
        telemetryJob?.cancel()
        telemetryJob = null
        recordingEngine.stopRecording()
        RecordingStateBus.update(
            this,
            ServiceRecordingSnapshot(isRecording = false, isPaused = false, elapsedMs = 0L),
            forceUiSync = true
        )
        releaseWakeLock()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun pushNotificationAndBus(force: Boolean) {
        val t = recordingEngine.telemetry.value
        RecordingStateBus.update(
            this,
            ServiceRecordingSnapshot(
                isRecording = t.isRecording,
                isPaused = t.isPaused,
                elapsedMs = t.elapsedMs,
                currentDb = t.currentDb,
                markerCount = markerCounter
            ),
            forceUiSync = force
        )
        if (t.isRecording) {
            try {
                NotificationManagerCompat.from(this).notify(
                    NOTIF_ID,
                    buildNotification(t.isPaused, t.elapsedMs, t.currentDb)
                )
            } catch (_: SecurityException) {
            }
        }
    }

    private fun buildNotification(isPaused: Boolean, elapsedMs: Long, db: Float): Notification {
        val openAppPi = PendingIntent.getActivity(
            this,
            100,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopPi = PendingIntent.getService(
            this, 101, stopIntent(this),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val pausePi = PendingIntent.getService(
            this, 102, togglePauseIntent(this),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val markerPi = PendingIntent.getService(
            this, 103, addMarkerIntent(this),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val title = if (isPaused) getString(R.string.notif_recording_paused)
        else getString(R.string.notif_recording_title)
        val content = String.format(
            Locale.US,
            "%s  •  %.1f dBFS  •  %d markers",
            AudioMath.formatDurationShort(elapsedMs),
            db,
            markerCounter
        )

        return NotificationCompat.Builder(this, FaintRecApp.CHANNEL_RECORDING)
            .setSmallIcon(R.drawable.ic_stat_faintrec)
            .setContentTitle(title)
            .setContentText(content)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(openAppPi)
            .addAction(0, if (isPaused) getString(R.string.record_resume) else getString(R.string.record_pause), pausePi)
            .addAction(0, getString(R.string.add_marker), markerPi)
            .addAction(0, getString(R.string.record_stop), stopPi)
            .build()
    }

    private fun notifyMicSwitched(oldMic: String, newMic: String) {
        try {
            val notif = NotificationCompat.Builder(this, FaintRecApp.CHANNEL_RECORDING)
                .setSmallIcon(R.drawable.ic_stat_faintrec)
                .setContentTitle(getString(R.string.mic_changed_marker))
                .setContentText("$oldMic disconnected. Recording continues safely on $newMic.")
                .setAutoCancel(true)
                .build()
            NotificationManagerCompat.from(this).notify(NOTIF_ID + 1, notif)
        } catch (_: SecurityException) {
        }
    }

    private fun notifyStorageAlert(notifId: Int, title: String, body: String) {
        try {
            val openAppPi = PendingIntent.getActivity(
                this,
                notifId,
                Intent(this, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                },
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val notif = NotificationCompat.Builder(this, FaintRecApp.CHANNEL_STORAGE_ALERTS)
                .setSmallIcon(R.drawable.ic_stat_faintrec)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setContentIntent(openAppPi)
                .setAutoCancel(true)
                .build()
            NotificationManagerCompat.from(this).notify(notifId, notif)
        } catch (_: SecurityException) {
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "FaintRec:RecordingWakeLock").apply {
            acquire(6 * 60 * 60 * 1000L) // Up to 6h continuous capture
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (_: Exception) {
        }
        wakeLock = null
    }

    override fun onDestroy() {
        releaseWakeLock()
        telemetryJob?.cancel()
        disconnectJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }
}
