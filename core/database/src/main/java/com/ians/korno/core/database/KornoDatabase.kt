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
    version = 5,
    exportSchema = true,
    autoMigrations = [
        // 1 -> 2: activities.is_pending_summary (NOT NULL DEFAULT 0).
        AutoMigration(from = 1, to = 2),
        // 2 -> 3: geo_points.recording_status (NOT NULL DEFAULT 'RECORDING').
        AutoMigration(from = 2, to = 3),
        // 3 -> 4: geo_points.speed_accuracy, bearing, bearing_accuracy (все nullable).
        // Старые треки остаются с NULL: фильтр обходится без вектора скорости, просто
        // опирается на одни координаты, как и раньше.
        AutoMigration(from = 3, to = 4),
        // 4 -> 5: activities.step_count (nullable). Старые активности шагов не считали,
        // и NULL здесь означает именно это, а не ноль шагов.
        AutoMigration(from = 4, to = 5),
    ],
)
abstract class KornoDatabase : RoomDatabase() {
    abstract fun trackDao(): TrackDao

    companion object {
        const val DATABASE_NAME = "korno.db"
    }
}
