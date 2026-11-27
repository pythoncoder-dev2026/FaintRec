package com.example.data

import android.content.Context
import com.example.audio.WavCuePoint
import com.example.audio.WavFileHelper
import com.example.storage.StorageLocationManager
import com.example.util.AudioMath
import com.example.util.FaintRecFolder
import com.example.util.StorageHelper
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FaintRecRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val recordingDao: RecordingDao,
    private val markerDao: MarkerDao,
    private val editListDao: EditListDao,
    private val spiritBoxDao: SpiritBoxDao,
    private val storageLocationManager: StorageLocationManager
) {
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    val recordings: Flow<List<RecordingEntity>> = recordingDao.observeAllRecordings()

    val spiritBoxSessions: Flow<List<SpiritBoxSessionEntity>> = spiritBoxDao.observeSessions()

    suspend fun getAllRecordingsOnce(): List<RecordingEntity> = recordingDao.getAllRecordingsOnce()

    fun observeRecording(id: Long): Flow<RecordingEntity?> = recordingDao.observeById(id)

    suspend fun getRecording(id: Long): RecordingEntity? = recordingDao.getById(id)

    suspend fun insertRecording(recording: RecordingEntity): Long = recordingDao.insert(recording)

    suspend fun renameRecording(id: Long, newTitle: String) = recordingDao.rename(id, newTitle)

    suspend fun deleteRecording(recording: RecordingEntity) = withContext(Dispatchers.IO) {
        storageLocationManager.deleteRecordingBackingData(recording)
        recordingDao.deleteById(recording.id)
    }

    fun observeMarkers(recordingId: Long): Flow<List<MarkerEntity>> =
        markerDao.observeMarkersForRecording(recordingId)

    suspend fun getMarkers(recordingId: Long): List<MarkerEntity> =
        markerDao.getMarkersForRecording(recordingId)

    suspend fun addMarker(
        recordingId: Long,
        timestampMs: Long,
        label: String,
        note: String = ""
    ): Long = markerDao.insert(
        MarkerEntity(
            recordingId = recordingId,
            timestampMs = timestampMs,
            label = label,
            note = note
        )
    )

    suspend fun insertMarkers(markers: List<MarkerEntity>) = markerDao.insertAll(markers)

    suspend fun updateMarker(marker: MarkerEntity) = markerDao.update(marker)

    suspend fun deleteMarker(markerId: Long) = markerDao.deleteById(markerId)

    /**
     * Exports recording markers as a sidecar JSON file in Documents/FaintRec
     * AND embeds standard RIFF WAV `cue ` / `labl` chunks if the recording is a WAV file.
     */
    suspend fun exportMarkersSidecarAndCue(recording: RecordingEntity): Pair<File?, Boolean> =
        withContext(Dispatchers.IO) {
            val markers = markerDao.getMarkersForRecording(recording.id)
            val exportObj = MarkerSidecarExport(
                recordingTitle = recording.title,
                sampleRate = recording.sampleRate,
                durationMs = recording.durationMs,
                markers = markers.map { MarkerExportItem(it.timestampMs, it.label, it.note) }
            )
            val jsonText = json.encodeToString(exportObj)
            val baseName = if (recording.filePath.isNotBlank()) {
                File(recording.filePath).nameWithoutExtension
            } else {
                StorageHelper.sanitizeFileName(recording.title).substringBeforeLast('.')
            }
            val jsonFile = StorageHelper.createWorkingFile(
                context,
                FaintRecFolder.ROOT,
                "${baseName}_markers.json"
            )
            jsonFile.writeText(jsonText)
            StorageHelper.publishToMediaStore(
                context,
                jsonFile,
                FaintRecFolder.ROOT,
                "application/json",
                jsonFile.name
            )

            var cueWritten = false
            val audioFile = if (recording.filePath.isNotBlank()) File(recording.filePath) else null
            if (recording.format.equals("WAV", ignoreCase = true) && audioFile != null && audioFile.exists() && markers.isNotEmpty()) {
                val cuePoints = markers.mapIndexed { idx, m ->
                    val sampleOffset = (m.timestampMs * recording.sampleRate) / 1000L
                    WavCuePoint(
                        id = idx + 1,
                        sampleOffset = sampleOffset,
                        label = if (m.note.isNotBlank()) "${m.label}: ${m.note}" else m.label
                    )
                }
                cueWritten = WavFileHelper.appendCueChunks(audioFile, cuePoints)
            }
            jsonFile to cueWritten
        }

    fun observeEditList(recordingId: Long): Flow<EditListEntity?> =
        editListDao.observeEditList(recordingId)

    suspend fun loadEdl(recordingId: Long): Pair<List<EdlOperation>, List<EdlOperation>> {
        val entity = editListDao.getEditList(recordingId) ?: return emptyList<EdlOperation>() to emptyList()
        val ops = runCatching { json.decodeFromString<List<EdlOperation>>(entity.operationsJson) }
            .getOrDefault(emptyList())
        val redo = runCatching { json.decodeFromString<List<EdlOperation>>(entity.redoStackJson) }
            .getOrDefault(emptyList())
        return ops to redo
    }

    suspend fun saveEdl(
        recordingId: Long,
        operations: List<EdlOperation>,
        redoStack: List<EdlOperation>
    ) {
        val existing = editListDao.getEditList(recordingId)
        val entity = EditListEntity(
            id = existing?.id ?: 0L,
            recordingId = recordingId,
            operationsJson = json.encodeToString(operations),
            redoStackJson = json.encodeToString(redoStack),
            updatedAtMillis = System.currentTimeMillis()
        )
        editListDao.upsert(entity)
    }

    suspend fun resetEdl(recordingId: Long) = editListDao.clearForRecording(recordingId)

    // --- Spirit Box Session Logs ---
    suspend fun insertSpiritBoxSession(session: SpiritBoxSessionEntity): Long =
        spiritBoxDao.insert(session)

    suspend fun deleteSpiritBoxSession(id: Long) = spiritBoxDao.deleteById(id)

    fun parseSessionQuestions(session: SpiritBoxSessionEntity): List<SpiritBoxQuestionLogItem> =
        runCatching { json.decodeFromString<List<SpiritBoxQuestionLogItem>>(session.questionsJson) }
            .getOrDefault(emptyList())

    fun parseSessionMarkers(session: SpiritBoxSessionEntity): List<MarkerExportItem> =
        runCatching { json.decodeFromString<List<MarkerExportItem>>(session.markersJson) }
            .getOrDefault(emptyList())

    fun parseSessionHops(session: SpiritBoxSessionEntity): List<SpiritBoxHopLogItem> =
        runCatching { json.decodeFromString<List<SpiritBoxHopLogItem>>(session.hopLogJson) }
            .getOrDefault(emptyList())

    suspend fun exportSpiritBoxSession(
        session: SpiritBoxSessionEntity,
        asJson: Boolean
    ): File = withContext(Dispatchers.IO) {
        val questions = parseSessionQuestions(session)
        val markers = parseSessionMarkers(session)
        val hops = parseSessionHops(session)
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(session.startedAtMillis))

        val ext = if (asJson) "json" else "txt"
        val mime = if (asJson) "application/json" else "text/plain"
        val outFile = StorageHelper.createWorkingFile(
            context,
            FaintRecFolder.SPIRIT_BOX,
            "SpiritBox_Session_${session.id}_$ts.$ext"
        )

        val content = if (asJson) {
            val exportObj = SpiritBoxSessionExport(
                sessionId = session.id,
                startedAtMillis = session.startedAtMillis,
                durationMs = session.durationMs,
                bandMode = session.bandMode,
                scanSpeedMs = session.scanSpeedMs,
                jitterEnabled = session.jitterEnabled,
                direction = session.direction,
                sourcesUsed = session.sourcesUsed,
                blindTestEnabled = session.blindTestEnabled,
                hopCount = session.hopCount,
                questions = questions,
                markers = markers,
                hopLog = hops
            )
            json.encodeToString(exportObj)
        } else {
            val dateStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(session.startedAtMillis))
            buildString {
                appendLine("=== FaintRec Spirit Box Session Log ===")
                appendLine("Disclaimer: For entertainment. Results are random radio/audio fragments.")
                appendLine("Date: $dateStr")
                appendLine("Duration: ${AudioMath.formatDuration(session.durationMs)}")
                appendLine("Band: ${session.bandMode} | Direction: ${session.direction} | Speed: ${session.scanSpeedMs} ms (Jitter: ${session.jitterEnabled})")
                appendLine("Sources Used: ${session.sourcesUsed}")
                appendLine("Blind Test Mode: ${session.blindTestEnabled} | Total Hops: ${session.hopCount}")
                appendLine()
                appendLine("--- Questions (${questions.size}) ---")
                questions.forEach { q ->
                    appendLine("[${AudioMath.formatDuration(q.timestampMs)}] (${q.inputMode}) ${q.question}")
                }
                appendLine()
                appendLine("--- Markers (${markers.size}) ---")
                markers.forEach { m ->
                    appendLine("[${AudioMath.formatDuration(m.timestampMs)}] ${m.label}${if (m.note.isNotBlank()) " — ${m.note}" else ""}")
                }
                appendLine()
                appendLine("--- Hop Source Log (${hops.size} entries) ---")
                hops.forEach { h ->
                    appendLine("#${h.hopIndex} [${AudioMath.formatDuration(h.timestampMs)}] ${h.freqLabel} -> ${h.actualSource}${if (h.detail.isNotBlank()) " (${h.detail})" else ""}")
                }
            }
        }

        outFile.writeText(content)
        StorageHelper.publishToMediaStore(
            context = context,
            sourceFile = outFile,
            folder = FaintRecFolder.SPIRIT_BOX,
            mimeType = mime,
            displayName = outFile.name
        )
        outFile
    }
}
