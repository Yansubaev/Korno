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

@Database(
    entities = [
        ActivityEntity::class,
        GeoPointEntity::class,
        ActivitySegmentEntity::class,
        RecognitionSampleEntity::class,
    ],
    version = 11,
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
        AutoMigration(from = 8, to = 9, spec = KornoDatabase.Migration8To9::class),
        // 9 -> 10: activity_segments.moving_time_millis (nullable). У прежних участков
        // времени в движении нет: до ближайшего пересчёта трека оно читается как вся
        // длительность участка (см. `ActivitySegment.movingTime`).
        AutoMigration(from = 9, to = 10),
        // 10 -> 11: схема не меняется, чинятся данные — см. `MIGRATION_10_11`.
    ],
)
abstract class KornoDatabase : RoomDatabase() {
    abstract fun trackDao(): TrackDao

    /**
     * Код чтения работает только с сегментами, без ветки «сегментов нет», поэтому старым
     * активностям сегмент нужен сразу. Вид — по тому же правилу, что и
     * `ActivityType.segmentKind`; у незавершённой записи сегмент остаётся открытым.
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

    /**
     * Выбран ли тип пользователем, восстанавливается по тому, что уже лежит в БД: в
     * фиксированном режиме тип выбирают до старта, а в авторежиме выбор виден по
     * расхождению с видом сегмента с наибольшей дистанцией.
     *
     * «Другое» из этого правила исключено: выбрать его нельзя (см. `SETUP_ACTIVITY_TYPES`),
     * и расхождение там означает не ручной выбор, а то, что тип авторежима так и остался
     * невыведенным. Уже мигрированные базы чинит [MIGRATION_10_11].
     */
    class Migration8To9 : AutoMigrationSpec {
        override fun onPostMigrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                UPDATE activities SET is_type_chosen_by_user = 1
                WHERE activity_type != 'OTHER' AND (is_auto_mode = 0
                    OR activity_type != (
                        SELECT CASE kind
                            WHEN 'ON_FOOT' THEN 'WALKING'
                            WHEN 'RUNNING' THEN 'RUNNING'
                            WHEN 'CYCLING' THEN 'CYCLING'
                            WHEN 'VEHICLE' THEN 'DRIVING'
                        END
                        FROM activity_segments
                        WHERE activity_id = activities.id AND kind != 'UNKNOWN'
                        GROUP BY kind
                        ORDER BY SUM(distance_meters) DESC
                        LIMIT 1
                    ))
                """.trimIndent(),
            )
        }
    }

    companion object {
        const val DATABASE_NAME = "korno.db"

        /**
         * Чинит записи, которым миграция 8 → 9 приписала ручной выбор типа «другое».
         * Выбрать «другое» нельзя, так что такой флаг — всегда ошибка, и цена у неё не
         * косметическая: он замораживает тип (пересчёт трека больше не выводит его из
         * доминирующего вида) и заставляет список фильтровать запись по типу вместо её
         * видов, из-за чего вся история авторежима оседала в чипе «Другое».
         *
         * Тип выводится здесь же, тем же правилом, что и при пересчёте трека: иначе он
         * остался бы «не определено» до ближайшей правки записи.
         */
        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    UPDATE activities
                    SET activity_type = COALESCE((
                            SELECT CASE kind
                                WHEN 'ON_FOOT' THEN 'WALKING'
                                WHEN 'RUNNING' THEN 'RUNNING'
                                WHEN 'CYCLING' THEN 'CYCLING'
                                WHEN 'VEHICLE' THEN 'DRIVING'
                            END
                            FROM activity_segments
                            WHERE activity_id = activities.id AND kind NOT IN ('UNKNOWN', 'PAUSE')
                            GROUP BY kind
                            ORDER BY SUM(distance_meters) DESC
                            LIMIT 1
                        ), 'OTHER'),
                        is_type_chosen_by_user = 0
                    WHERE activity_type = 'OTHER'
                    """.trimIndent(),
                )
            }
        }
    }
}
