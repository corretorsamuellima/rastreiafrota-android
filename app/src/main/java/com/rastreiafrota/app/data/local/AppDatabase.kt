package com.rastreiafrota.app.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [PendingLocationEntity::class, PendingAudioEntity::class], version = 4, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun pendingLocationDao(): PendingLocationDao
    abstract fun pendingAudioDao(): PendingAudioDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS pending_audios (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        uuid TEXT NOT NULL,
                        sessionUuid TEXT NOT NULL,
                        recordingType TEXT NOT NULL,
                        filePath TEXT NOT NULL,
                        mimeType TEXT NOT NULL,
                        fileSize INTEGER NOT NULL,
                        sha256 TEXT NOT NULL,
                        durationSeconds INTEGER NOT NULL,
                        startedAt TEXT NOT NULL,
                        endedAt TEXT NOT NULL,
                        latitude REAL,
                        longitude REAL,
                        syncState INTEGER NOT NULL,
                        attempts INTEGER NOT NULL,
                        lastError TEXT,
                        createdAt INTEGER NOT NULL
                    )"""
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_pending_audios_uuid ON pending_audios(uuid)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_pending_audios_syncState_startedAt ON pending_audios(syncState,startedAt)")
            }
        }


        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE pending_audios ADD COLUMN audioCommandId INTEGER")
                db.execSQL("ALTER TABLE pending_audios ADD COLUMN commandOccurrenceUuid TEXT")
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE pending_locations ADD COLUMN routeSessionUuid TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE pending_locations ADD COLUMN sequenceNo INTEGER NOT NULL DEFAULT 0")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_pending_locations_routeSessionUuid_sequenceNo ON pending_locations(routeSessionUuid, sequenceNo)")
            }
        }

        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "rastreiafrota.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                .build()
                .also { instance = it }
        }
    }
}
