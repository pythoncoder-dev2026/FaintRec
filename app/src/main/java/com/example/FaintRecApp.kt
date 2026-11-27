package com.example

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.example.logging.AppLogger
import com.example.storage.StorageLocationManager
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class FaintRecApp : Application() {

    @Inject lateinit var appLogger: AppLogger
    @Inject lateinit var storageLocationManager: StorageLocationManager

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    companion object {
        const val CHANNEL_RECORDING = "faintrec_recording_channel"
        const val CHANNEL_CONVERSION = "faintrec_conversion_channel"
        const val CHANNEL_SPIRIT_BOX = "faintrec_spirit_box_channel"
        const val CHANNEL_STORAGE_ALERTS = "faintrec_storage_alerts_channel"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        appLogger.logDeviceCapabilitiesOnce()
        appScope.launch {
            storageLocationManager.recoverInterruptedRecordings()
        }
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            val recChannel = NotificationChannel(
                CHANNEL_RECORDING,
                getString(R.string.notif_channel_recording),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Live recording status, pause/resume, marker, and stop actions"
                setShowBadge(false)
            }
            val convChannel = NotificationChannel(
                CHANNEL_CONVERSION,
                getString(R.string.notif_channel_conversion),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Background audio format conversion progress"
                setShowBadge(false)
            }
            val spiritChannel = NotificationChannel(
                CHANNEL_SPIRIT_BOX,
                getString(R.string.notif_channel_spirit_box),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Active Spirit Box sweep session, markers, and quick stop"
                setShowBadge(false)
            }
            val storageAlertChannel = NotificationChannel(
                CHANNEL_STORAGE_ALERTS,
                getString(R.string.notif_channel_storage_alerts),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Low storage warnings and safe auto-stop notifications"
                setShowBadge(true)
            }
            nm.createNotificationChannel(recChannel)
            nm.createNotificationChannel(convChannel)
            nm.createNotificationChannel(spiritChannel)
            nm.createNotificationChannel(storageAlertChannel)
        }
    }
}
