package com.example.data

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

class FaintRecTypeConverters {
    @TypeConverter
    fun fromSourceTag(tag: RecordingSourceTag): String = tag.name

    @TypeConverter
    fun toSourceTag(value: String): RecordingSourceTag =
        RecordingSourceTag.entries.firstOrNull { it.name == value } ?: RecordingSourceTag.RECORDED

    @TypeConverter
    fun fromStorageType(type: RecordingStorageType): String = type.name

    @TypeConverter
    fun toStorageType(value: String): RecordingStorageType =
        RecordingStorageType.entries.firstOrNull { it.name == value } ?: RecordingStorageType.APP_PRIVATE

    @TypeConverter
    fun fromLogLevel(level: LogLevel): String = level.name

    @TypeConverter
    fun toLogLevel(value: String): LogLevel =
        LogLevel.entries.firstOrNull { it.name == value } ?: LogLevel.INFO

    @TypeConverter
    fun fromHealthStatus(status: FeatureHealthStatus): String = status.name

    @TypeConverter
    fun toHealthStatus(value: String): FeatureHealthStatus =
        FeatureHealthStatus.entries.firstOrNull { it.name == value } ?: FeatureHealthStatus.NOT_TESTED
}

@Database(
    entities = [
        RecordingEntity::class,
        MarkerEntity::class,
        EditListEntity::class,
        SpiritBoxSessionEntity::class,
        AppLogEntity::class,
        FeatureHealthEntity::class
    ],
    version = 4,
    exportSchema = false
)
@TypeConverters(FaintRecTypeConverters::class)
abstract class FaintRecDatabase : RoomDatabase() {
    abstract fun recordingDao(): RecordingDao
    abstract fun markerDao(): MarkerDao
    abstract fun editListDao(): EditListDao
    abstract fun spiritBoxDao(): SpiritBoxDao
    abstract fun appLogDao(): AppLogDao
    abstract fun featureHealthDao(): FeatureHealthDao

    companion object {
        /**
         * Preserves all existing recordings and metadata while adding storageType and storageUri.
         * Legacy recordings stay in place and are tagged based on whether they had a MediaStore URI.
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE recordings ADD COLUMN storageType TEXT NOT NULL DEFAULT 'APP_PRIVATE'"
                )
                db.execSQL(
                    "ALTER TABLE recordings ADD COLUMN storageUri TEXT"
                )
                db.execSQL(
                    """
                    UPDATE recordings
                    SET storageType = CASE
                        WHEN mediaStoreUri IS NOT NULL AND mediaStoreUri != '' THEN 'PUBLIC_DOCUMENTS'
                        ELSE 'APP_PRIVATE'
                    END,
                    storageUri = mediaStoreUri
                    """.trimIndent()
                )
            }
        }
    }
}
