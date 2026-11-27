package com.example.data

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): FaintRecDatabase {
        return Room.databaseBuilder(
            context,
            FaintRecDatabase::class.java,
            "faintrec_database.db"
        )
            .addMigrations(FaintRecDatabase.MIGRATION_3_4)
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()
    }

    @Provides
    fun provideRecordingDao(db: FaintRecDatabase): RecordingDao = db.recordingDao()

    @Provides
    fun provideMarkerDao(db: FaintRecDatabase): MarkerDao = db.markerDao()

    @Provides
    fun provideEditListDao(db: FaintRecDatabase): EditListDao = db.editListDao()

    @Provides
    fun provideSpiritBoxDao(db: FaintRecDatabase): SpiritBoxDao = db.spiritBoxDao()

    @Provides
    fun provideAppLogDao(db: FaintRecDatabase): AppLogDao = db.appLogDao()

    @Provides
    fun provideFeatureHealthDao(db: FaintRecDatabase): FeatureHealthDao = db.featureHealthDao()

    @Provides
    @Singleton
    fun provideSettingsDataStore(@ApplicationContext context: Context): SettingsDataStore =
        SettingsDataStore(context)
}
