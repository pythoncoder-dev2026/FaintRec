package com.example.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import androidx.glance.Button
import androidx.glance.ButtonDefaults
import com.example.MainActivity
import com.example.service.RecordingForegroundService
import com.example.service.RecordingStateBus
import com.example.util.AudioMath
import java.util.Locale

class FaintRecWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = FaintRecWidget()
}

class FaintRecWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val snap = RecordingStateBus.snapshot.value
        provideContent {
            WidgetContent(
                isRecording = snap.isRecording,
                isPaused = snap.isPaused,
                elapsedMs = snap.elapsedMs,
                currentDb = snap.currentDb,
                markerCount = snap.markerCount
            )
        }
    }

    @Composable
    private fun WidgetContent(
        isRecording: Boolean,
        isPaused: Boolean,
        elapsedMs: Long,
        currentDb: Float,
        markerCount: Int
    ) {
        val bg = ColorProvider(Color(0xFF0E141D))
        val cyan = ColorProvider(Color(0xFF00E5FF))
        val amber = ColorProvider(Color(0xFFFFB300))
        val red = ColorProvider(Color(0xFFFF3B5C))
        val textWhite = ColorProvider(Color(0xFFEAF1F8))
        val textSub = ColorProvider(Color(0xFF94A7BE))

        Column(
            modifier = GlanceModifier
                .fillMaxSize()
                .background(bg)
                .cornerRadius(18.dp)
                .padding(14.dp),
            verticalAlignment = Alignment.Vertical.CenterVertically
        ) {
            Row(
                modifier = GlanceModifier.fillMaxWidth(),
                verticalAlignment = Alignment.Vertical.CenterVertically
            ) {
                Text(
                    text = "FAINTREC",
                    style = TextStyle(color = cyan, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                )
                Spacer(modifier = GlanceModifier.defaultWeight())
                Text(
                    text = when {
                        isRecording && isPaused -> "PAUSED"
                        isRecording -> "LIVE REC"
                        else -> "READY"
                    },
                    style = TextStyle(
                        color = if (isRecording) red else textSub,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                )
            }

            Spacer(modifier = GlanceModifier.height(6.dp))

            Row(
                modifier = GlanceModifier.fillMaxWidth(),
                verticalAlignment = Alignment.Vertical.CenterVertically
            ) {
                Text(
                    text = AudioMath.formatDuration(elapsedMs),
                    style = TextStyle(color = textWhite, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                )
                Spacer(modifier = GlanceModifier.defaultWeight())
                Text(
                    text = if (isRecording) String.format(Locale.US, "%.1f dB", currentDb) else "--.- dB",
                    style = TextStyle(color = amber, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                )
            }

            Spacer(modifier = GlanceModifier.height(10.dp))

            Row(modifier = GlanceModifier.fillMaxWidth()) {
                Button(
                    text = if (isRecording) "Stop" else "Record",
                    onClick = actionRunCallback<ToggleRecordWidgetAction>(),
                    colors = ButtonDefaults.buttonColors(
                        backgroundColor = if (isRecording) red else cyan,
                        contentColor = ColorProvider(Color(0xFF090C10))
                    ),
                    modifier = GlanceModifier.defaultWeight()
                )
                Spacer(modifier = GlanceModifier.width(8.dp))
                Button(
                    text = "+ Marker ($markerCount)",
                    onClick = actionRunCallback<AddMarkerWidgetAction>(),
                    colors = ButtonDefaults.buttonColors(
                        backgroundColor = ColorProvider(Color(0xFF1F2937)),
                        contentColor = amber
                    ),
                    modifier = GlanceModifier.defaultWeight()
                )
            }
        }
    }
}

class ToggleRecordWidgetAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val snap = RecordingStateBus.snapshot.value
        if (snap.isRecording) {
            try {
                context.startService(RecordingForegroundService.stopIntent(context))
            } catch (_: Exception) {
                context.stopService(Intent(context, RecordingForegroundService::class.java))
            }
        } else {
            // Launch MainActivity to satisfy Android 14+ foreground microphone start requirements safely
            val intent = Intent(context, MainActivity::class.java).apply {
                action = MainActivity.ACTION_START_FROM_WIDGET
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            context.startActivity(intent)
        }
    }
}

class AddMarkerWidgetAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        if (RecordingStateBus.snapshot.value.isRecording) {
            try {
                context.startService(RecordingForegroundService.addMarkerIntent(context))
            } catch (_: Exception) {
            }
        }
    }
}
