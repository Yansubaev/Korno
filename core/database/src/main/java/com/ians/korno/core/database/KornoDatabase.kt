package com.ians.korno.core.database

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.AutoMigrationSpec
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.ians.korno.core.database.dao.TrackDao
import com.ians.korno.core.database.entity.ActivityEntity
import com.ians.korno.core.database.entity.ActivitySegmentEntity
import com.ians.korno.core.database.entity.GeoPointEntity
import com.ians.korno.core.database.entity.RecognitionSampleEntity
import com.ians.korno.core.database.entity.StepSampleEntity

@Database(
    entities = [
        ActivityEntity::class,
        GeoPointEntity::class,
        ActivitySegmentEntity::class,
        RecognitionSampleEntity::class,
        StepSampleEntity::class,
    ],
    version = 13,
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
        // 5 -> 6: таблица activity_segments; каждой активности — один сегмент на всю запись.
        AutoMigration(from = 5, to = 6, spec = KornoDatabase.Migration5To6::class),
        // 6 -> 7: activities.is_auto_mode (NOT NULL DEFAULT 0). Все прежние записи — в
        // фиксированном режиме.
        AutoMigration(from = 6, to = 7),
        // 7 -> 8: таблица recognition_samples. У прежних записей ответов распознавания нет.
        AutoMigration(from = 7, to = 8),
        // 8 -> 9: activities.auto_pause_sensitivity (NOT NULL DEFAULT 'MEDIUM') и
        // activities.is_type_chosen_by_user (NOT NULL DEFAULT 0). С какой чувствительностью
        // писались прежние записи, неизвестно; MEDIUM — значение по умолчанию.
        AutoMigration(from = 8, to = 9),
        // 9 -> 10: activity_segments.moving_time_millis (nullable). У прежних участков
        // времени в движении нет: до ближайшего пересчёта трека оно читается как вся
        // длительность участка (см. `ActivitySegment.movingTime`).
        AutoMigration(from = 9, to = 10),
        // 10 -> 11: схема не менялась, переход остаётся пустым.
        AutoMigration(from = 10, to = 11),
        // 11 -> 12: activity_type и is_type_chosen_by_user сносятся, is_auto_mode
        // становится режимом записи — см. `MIGRATION_11_12`.
        // 12 -> 13: таблица step_samples. У прежних записей шагов во времени нет: в БД
        // лежит только сумма за сегмент.
        AutoMigration(from = 12, to = 13),
    ],
)
abstract class KornoDatabase : RoomDatabase() {
    abstract fun trackDao(): TrackDao

    /**
     * Код чтения работает только с сегментами, без ветки «сегментов нет», поэтому старым
     * активностям сегмент нужен сразу. Вид — из тогдашнего типа активности, которого
     * с версии 12 уже нет; у незавершённой записи сегмент остаётся открытым.
     */
    class Migration5To6 : AutoMigrationSpec {
        override fun onPostMigrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                INSERT INTO activity_segments (
                    activity_id, kind, start_ts, end_ts, steps, distance_meters, is_manually_edited
                )
                SELECT id,
                    CASE activity_type
                        WHEN 'WALKING' THEN 'ON_FOOT'
                        WHEN 'HIKING' THEN 'ON_FOOT'
                        WHEN 'RUNNING' THEN 'RUNNING'
                        WHEN 'CYCLING' THEN 'CYCLING'
                        WHEN 'DRIVING' THEN 'VEHICLE'
                        ELSE 'UNKNOWN'
                    END,
                    start_time, end_time, step_count, distance, 0
                FROM activities
                """.trimIndent(),
            )
        }
    }

    companion object {
        const val DATABASE_NAME = "korno.db"

        /**
         * Перечень видов сегментов заменил тип активности (`docs/adr/0004`): колонки
         * `activity_type` и `is_type_chosen_by_user` сносятся, а булево `is_auto_mode`
         * становится режимом записи.
         *
         * Данные типа не переносятся: релиза не было (`versionCode = 1`, тегов нет), а
         * вид у каждой активности и так лежит в её сегментах. Хайкинг в истории при этом
         * теряется — он был только в типе — и при надобности проставляется руками.
         *
         * Таблица пересоздаётся целиком: `ALTER TABLE ... DROP COLUMN` есть только в
         * SQLite 3.35, то есть с Android 14, а minSdk у нас 24.
         */
        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE activities_new (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `start_time` INTEGER NOT NULL,
                        `end_time` INTEGER,
                        `title` TEXT,
                        `note` TEXT,
                        `distance` REAL NOT NULL,
                        `moving_time` INTEGER NOT NULL,
                        `elapsed_time` INTEGER NOT NULL,
                        `avg_speed` REAL NOT NULL,
                        `max_speed` REAL NOT NULL,
                        `elevation_gain` REAL NOT NULL,
                        `is_manually_edited` INTEGER NOT NULL,
                        `is_pending_summary` INTEGER NOT NULL DEFAULT 0,
                        `step_count` INTEGER,
                        `recording_mode` TEXT NOT NULL DEFAULT 'FIXED',
                        `auto_pause_sensitivity` TEXT NOT NULL DEFAULT 'MEDIUM'
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    INSERT INTO activities_new (
                        id, start_time, end_time, title, note, distance, moving_time,
                        elapsed_time, avg_speed, max_speed, elevation_gain,
                        is_manually_edited, is_pending_summary, step_count,
                        recording_mode, auto_pause_sensitivity
                    )
                    SELECT id, start_time, end_time, title, note, distance, moving_time,
                        elapsed_time, avg_speed, max_speed, elevation_gain,
                        is_manually_edited, is_pending_summary, step_count,
                        CASE WHEN is_auto_mode = 1 THEN 'AUTO' ELSE 'FIXED' END,
                        auto_pause_sensitivity
                    FROM activities
                    """.trimIndent(),
                )
                db.execSQL("DROP TABLE activities")
                db.execSQL("ALTER TABLE activities_new RENAME TO activities")
            }
        }
    }
}
