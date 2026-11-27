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
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.example.FaintRecApp
import com.example.MainActivity
import com.example.R
import com.example.data.AppFeature
import com.example.logging.AppLogger
import com.example.spiritbox.SpiritBoxEngine
import com.example.util.AudioMath
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class SpiritBoxForegroundService : Service() {

    companion object {
        const val NOTIF_ID = 4109
        const val ACTION_START = "com.example.service.SPIRIT_BOX_START"
        const val ACTION_STOP = "com.example.service.SPIRIT_BOX_STOP"
        const val ACTION_TOGGLE_HOLD = "com.example.service.SPIRIT_BOX_HOLD"
        const val ACTION_MARK = "com.example.service.SPIRIT_BOX_MARK"
        const val EXTRA_WITH_MIC = "extra_with_mic"

        fun startIntent(context: Context, withMic: Boolean): Intent =
            Intent(context, SpiritBoxForegroundService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_WITH_MIC, withMic)
            }

        fun stopIntent(context: Context): Intent =
            Intent(context, SpiritBoxForegroundService::class.java).apply { action = ACTION_STOP }

        fun toggleHoldIntent(context: Context): Intent =
            Intent(context, SpiritBoxForegroundService::class.java).apply { action = ACTION_TOGGLE_HOLD }

        fun markIntent(context: Context): Intent =
            Intent(context, SpiritBoxForegroundService::class.java).apply { action = ACTION_MARK }
    }

    @Inject lateinit var engine: SpiritBoxEngine
    @Inject lateinit var logger: AppLogger

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var stateJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val withMic = intent.getBooleanExtra(EXTRA_WITH_MIC, false)
                startSpiritBoxForeground(withMic)
            }
            ACTION_TOGGLE_HOLD -> engine.toggleHold()
            ACTION_MARK -> engine.addMarker("Notification Marker", "Added from notification")
            ACTION_STOP -> stopSpiritBoxForeground()
        }
        return START_NOT_STICKY
    }

    private fun startSpiritBoxForeground(withMicRequested: Boolean) {
        val hasMicPerm = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        val useMicFgsType = withMicRequested && hasMicPerm

        val notif = buildNotification(isHeld = false, elapsedMs = 0L, hops = 0, freqLabel = "Sweeping")
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val fgsType = if (useMicFgsType && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK or
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                } else {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                }
                ServiceCompat.startForeground(this, NOTIF_ID, notif, fgsType)
            } else {
                startForeground(NOTIF_ID, notif)
            }
            logger.reportSuccess(AppFeature.FOREGROUND_SERVICE, "SpiritBoxForegroundService started")
        } catch (e: Exception) {
            logger.reportFailure(AppFeature.FOREGROUND_SERVICE, "SpiritBoxForegroundService start failed: ${e.message}", e)
            stopSelf()
            return
        }

        val started = engine.startSession(engine.config.copy(recordMicTrack = useMicFgsType))
        if (!started) {
            stopSpiritBoxForeground()
            return
        }

        stateJob?.cancel()
        var lastUpdateMs = 0L
        stateJob = serviceScope.launch {
            engine.liveState.collect { st ->
                if (!st.isRunning) {
                    stopSpiritBoxForeground()
                    return@collect
                }
                val now = System.currentTimeMillis()
                if (now - lastUpdateMs >= 950L) {
                    lastUpdateMs = now
                    try {
                        NotificationManagerCompat.from(this@SpiritBoxForegroundService).notify(
                            NOTIF_ID,
                            buildNotification(st.isHeld, st.elapsedMs, st.hopCount, st.currentFrequencyLabel)
                        )
                    } catch (_: SecurityException) {
                    }
                }
            }
        }
    }

    private fun stopSpiritBoxForeground() {
        stateJob?.cancel()
        stateJob = null
        engine.stopSession()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun buildNotification(
        isHeld: Boolean,
        elapsedMs: Long,
        hops: Int,
        freqLabel: String
    ): Notification {
        val openPi = PendingIntent.getActivity(
            this, 401,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val holdPi = PendingIntent.getService(
            this, 402, toggleHoldIntent(this),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val markPi = PendingIntent.getService(
            this, 403, markIntent(this),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopPi = PendingIntent.getService(
            this, 404, stopIntent(this),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val content = "${AudioMath.formatDurationShort(elapsedMs)} • $freqLabel • $hops hops"
        return NotificationCompat.Builder(this, FaintRecApp.CHANNEL_SPIRIT_BOX)
            .setSmallIcon(R.drawable.ic_stat_faintrec)
            .setContentTitle(getString(R.string.notif_spirit_box_title))
            .setContentText(content)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(openPi)
            .addAction(0, if (isHeld) getString(R.string.spirit_box_resume) else getString(R.string.spirit_box_hold), holdPi)
            .addAction(0, getString(R.string.spirit_box_mark), markPi)
            .addAction(0, getString(R.string.spirit_box_stop), stopPi)
            .build()
    }

    override fun onDestroy() {
        stateJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }
}
