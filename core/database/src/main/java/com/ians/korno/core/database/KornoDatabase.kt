package com.ians.korno.core.database

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.RoomDatabase
import com.ians.korno.core.database.dao.TrackDao
import com.ians.korno.core.database.entity.ActivityEntity
import com.ians.korno.core.database.entity.GeoPointEntity

@Database(
    entities = [
        ActivityEntity::class,
        GeoPointEntity::class,
    ],
    version = 3,
    exportSchema = true,
    autoMigrations = [
        // 1 -> 2: activities.is_pending_summary (NOT NULL DEFAULT 0).
        AutoMigration(from = 1, to = 2),
        // 2 -> 3: geo_points.recording_status (NOT NULL DEFAULT 'RECORDING').
        AutoMigration(from = 2, to = 3),
    ],
)
abstract class KornoDatabase : RoomDatabase() {
    abstract fun trackDao(): TrackDao

    companion object {
        const val DATABASE_NAME = "korno.db"
    }
}
