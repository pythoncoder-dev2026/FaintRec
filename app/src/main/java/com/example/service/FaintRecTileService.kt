package com.example.service

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import com.example.MainActivity
import com.example.R
import com.example.util.AudioMath

/**
 * Quick Settings Tile for starting/stopping FaintRec recording.
 * Reflects active/inactive recording state + elapsed time subtitle, and handles
 * Android 14+ background microphone FGS restrictions by opening MainActivity via PendingIntent.
 */
class FaintRecTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        refreshTile()
    }

    override fun onClick() {
        super.onClick()
        val snap = RecordingStateBus.snapshot.value
        if (snap.isRecording) {
            try {
                startService(RecordingForegroundService.stopIntent(this))
            } catch (_: Exception) {
                stopService(Intent(this, RecordingForegroundService::class.java))
            }
            refreshTile()
            return
        }

        // Android 14+ prohibits starting FOREGROUND_SERVICE_TYPE_MICROPHONE directly from background
        // unless initiated from a visible Activity. Launch MainActivity with auto-start intent!
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            action = MainActivity.ACTION_START_FROM_TILE
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            Toast.makeText(this, getString(R.string.tile_android14_notice), Toast.LENGTH_SHORT).show()
            val pi = PendingIntent.getActivity(
                this,
                3051,
                launchIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            startActivityAndCollapse(pi)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(launchIntent)
        }
    }

    private fun refreshTile() {
        val tile = qsTile ?: return
        val snap = RecordingStateBus.snapshot.value
        tile.label = getString(R.string.tile_label)
        if (snap.isRecording) {
            tile.state = Tile.STATE_ACTIVE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                tile.subtitle = if (snap.isPaused) {
                    getString(R.string.record_pause)
                } else {
                    AudioMath.formatDurationShort(snap.elapsedMs)
                }
            }
        } else {
            tile.state = Tile.STATE_INACTIVE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                tile.subtitle = getString(R.string.tile_idle)
            }
        }
        tile.updateTile()
    }
}
