package com.example.service

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import com.example.FaintRecApp
import com.example.R

/**
 * Foreground notification service for long-running / batch format conversion jobs.
 */
class ConversionForegroundService : Service() {

    companion object {
        const val NOTIF_ID = 4201
        const val ACTION_UPDATE = "com.example.service.CONV_UPDATE"
        const val ACTION_STOP = "com.example.service.CONV_STOP"
        const val ACTION_CANCEL_BROADCAST = "com.example.service.CONV_CANCEL"
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_PROGRESS = "extra_progress"

        fun updateProgress(context: Context, title: String, progressPercent: Int) {
            val intent = Intent(context, ConversionForegroundService::class.java).apply {
                action = ACTION_UPDATE
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_PROGRESS, progressPercent.coerceIn(0, 100))
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (_: Exception) {
            }
        }

        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, ConversionForegroundService::class.java))
            } catch (_: Exception) {
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            ACTION_UPDATE -> {
                val title = intent.getStringExtra(EXTRA_TITLE) ?: getString(R.string.converter_title)
                val progress = intent.getIntExtra(EXTRA_PROGRESS, 0)
                val cancelPi = PendingIntent.getBroadcast(
                    this, 4202,
                    Intent(ACTION_CANCEL_BROADCAST).setPackage(packageName),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                val notif = NotificationCompat.Builder(this, FaintRecApp.CHANNEL_CONVERSION)
                    .setSmallIcon(R.drawable.ic_stat_faintrec)
                    .setContentTitle(title)
                    .setContentText("$progress%")
                    .setProgress(100, progress, false)
                    .setOngoing(true)
                    .setSilent(true)
                    .addAction(0, getString(R.string.converter_cancel), cancelPi)
                    .build()

                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        ServiceCompat.startForeground(
                            this,
                            NOTIF_ID,
                            notif,
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                        )
                    } else {
                        startForeground(NOTIF_ID, notif)
                    }
                    NotificationManagerCompat.from(this).notify(NOTIF_ID, notif)
                } catch (_: Exception) {
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
}
