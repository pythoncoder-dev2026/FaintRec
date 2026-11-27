package com.example.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface RecordingDao {
    @Query("SELECT * FROM recordings ORDER BY createdAtMillis DESC")
    fun observeAllRecordings(): Flow<List<RecordingEntity>>

    @Query("SELECT * FROM recordings ORDER BY createdAtMillis DESC")
    suspend fun getAllRecordingsOnce(): List<RecordingEntity>

    @Query("SELECT * FROM recordings WHERE id = :id")
    suspend fun getById(id: Long): RecordingEntity?

    @Query("SELECT * FROM recordings WHERE id = :id")
    fun observeById(id: Long): Flow<RecordingEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(recording: RecordingEntity): Long

    @Update
    suspend fun update(recording: RecordingEntity)

    @Query("UPDATE recordings SET title = :newTitle WHERE id = :id")
    suspend fun rename(id: Long, newTitle: String)

    @Query("DELETE FROM recordings WHERE id = :id")
    suspend fun deleteById(id: Long)
}

@Dao
interface MarkerDao {
    @Query("SELECT * FROM markers WHERE recordingId = :recordingId ORDER BY timestampMs ASC")
    fun observeMarkersForRecording(recordingId: Long): Flow<List<MarkerEntity>>

    @Query("SELECT * FROM markers WHERE recordingId = :recordingId ORDER BY timestampMs ASC")
    suspend fun getMarkersForRecording(recordingId: Long): List<MarkerEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(marker: MarkerEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(markers: List<MarkerEntity>)

    @Update
    suspend fun update(marker: MarkerEntity)

    @Query("DELETE FROM markers WHERE id = :id")
    suspend fun deleteById(id: Long)
}

@Dao
interface EditListDao {
    @Query("SELECT * FROM edit_lists WHERE recordingId = :recordingId LIMIT 1")
    fun observeEditList(recordingId: Long): Flow<EditListEntity?>

    @Query("SELECT * FROM edit_lists WHERE recordingId = :recordingId LIMIT 1")
    suspend fun getEditList(recordingId: Long): EditListEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(editList: EditListEntity)

    @Query("DELETE FROM edit_lists WHERE recordingId = :recordingId")
    suspend fun clearForRecording(recordingId: Long)
}

@Dao
interface SpiritBoxDao {
    @Query("SELECT * FROM spirit_box_sessions ORDER BY startedAtMillis DESC")
    fun observeSessions(): Flow<List<SpiritBoxSessionEntity>>

    @Query("SELECT * FROM spirit_box_sessions WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): SpiritBoxSessionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(session: SpiritBoxSessionEntity): Long

    @Query("DELETE FROM spirit_box_sessions WHERE id = :id")
    suspend fun deleteById(id: Long)
}

@Dao
interface AppLogDao {
    @Query("SELECT * FROM app_logs ORDER BY timestampMillis DESC, id DESC LIMIT 2000")
    fun observeLogs(): Flow<List<AppLogEntity>>

    @Query("SELECT * FROM app_logs ORDER BY timestampMillis DESC, id DESC LIMIT 2000")
    suspend fun getAllLogsOnce(): List<AppLogEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entries: List<AppLogEntity>)

    @Query(
        "DELETE FROM app_logs WHERE id NOT IN (SELECT id FROM app_logs ORDER BY timestampMillis DESC, id DESC LIMIT :maxEntries)"
    )
    suspend fun trimToLatest(maxEntries: Int = 2000)

    @Query("DELETE FROM app_logs")
    suspend fun clearAll()
}

@Dao
interface FeatureHealthDao {
    @Query("SELECT * FROM feature_health")
    fun observeAll(): Flow<List<FeatureHealthEntity>>

    @Query("SELECT * FROM feature_health")
    suspend fun getAllOnce(): List<FeatureHealthEntity>

    @Query("SELECT * FROM feature_health WHERE featureId = :featureId LIMIT 1")
    suspend fun getById(featureId: String): FeatureHealthEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: FeatureHealthEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<FeatureHealthEntity>)

    @Query("DELETE FROM feature_health")
    suspend fun clearAll()
}

