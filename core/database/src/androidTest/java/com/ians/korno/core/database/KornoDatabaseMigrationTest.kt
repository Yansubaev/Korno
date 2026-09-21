package com.ians.korno.core.database

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isTrue
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private const val TEST_DATABASE_NAME = "korno-migration-test.db"

/**
 * Миграции проверяются на настоящем SQLite: `fallbackToDestructiveMigration` в проекте
 * нет, и ошибка в схеме означает не потерянную колонку, а потерянные тренировки
 * пользователя при обновлении приложения.
 *
 * Все переходы автоматические, поэтому тесты смотрят на то, чего автогенерация не
 * гарантирует сама по себе: что старые строки переживают миграцию и что новые колонки
 * получают ровно те значения по умолчанию, на которые рассчитывает остальной код.
 */
@RunWith(AndroidJUnit4::class)
class KornoDatabaseMigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        KornoDatabase::class.java,
    )

    @Test
    fun migration1To2KeepsActivitiesAndDefaultsPendingSummary() {
        helper.createDatabase(TEST_DATABASE_NAME, 1).use { database ->
            database.insertActivity(id = 1L, title = "Morning run")
        }

        val migrated = helper.runMigrationsAndValidate(TEST_DATABASE_NAME, 2, true)

        migrated.query("SELECT title, is_pending_summary FROM activities WHERE id = 1").use { row ->
            assertThat(row.moveToFirst()).isTrue()
            assertThat(row.getString(0)).isEqualTo("Morning run")
            // Записи, сделанные до появления экрана подтверждения, подтверждения не ждут:
            // иначе после обновления приложение показало бы его для всей старой истории.
            assertThat(row.getInt(1)).isEqualTo(0)
        }
    }

    @Test
    fun migration2To3KeepsGeoPointsAndDefaultsRecordingStatus() {
        helper.createDatabase(TEST_DATABASE_NAME, 2).use { database ->
            database.insertActivity(id = 1L, title = "Evening ride")
            database.insertGeoPoint(id = 1L, activityId = 1L, timestamp = 1_757_052_000_000L)
        }

        val migrated = helper.runMigrationsAndValidate(TEST_DATABASE_NAME, 3, true)

        migrated.query("SELECT timestamp, recording_status FROM geo_points WHERE id = 1").use { row ->
            assertThat(row.moveToFirst()).isTrue()
            assertThat(row.getLong(0)).isEqualTo(1_757_052_000_000L)
            // Треки, записанные до появления паузы в модели, целиком считаются записью.
            assertThat(row.getString(1)).isEqualTo("RECORDING")
        }
    }

    @Test
    fun migration3To4KeepsGeoPointsAndLeavesVelocityColumnsEmpty() {
        helper.createDatabase(TEST_DATABASE_NAME, 3).use { database ->
            database.insertActivity(id = 1L, title = "City walk")
            database.insertGeoPoint(id = 1L, activityId = 1L, timestamp = 1_757_052_000_000L)
        }

        val migrated = helper.runMigrationsAndValidate(TEST_DATABASE_NAME, 4, true)

        migrated.query(
            "SELECT timestamp, speed_accuracy, bearing, bearing_accuracy FROM geo_points WHERE id = 1",
        ).use { row ->
            assertThat(row.moveToFirst()).isTrue()
            assertThat(row.getLong(0)).isEqualTo(1_757_052_000_000L)
            // Вектор скорости у старых треков взять неоткуда, и подставлять вместо него
            // ноль нельзя: ноль — это утверждение «стоял на месте», а не «неизвестно».
            assertThat(row.isNull(1)).isTrue()
            assertThat(row.isNull(2)).isTrue()
            assertThat(row.isNull(3)).isTrue()
        }
    }

    @Test
    fun migration4To5KeepsActivitiesAndLeavesStepCountEmpty() {
        helper.createDatabase(TEST_DATABASE_NAME, 4).use { database ->
            database.insertActivity(id = 1L, title = "Evening walk")
        }

        val migrated = helper.runMigrationsAndValidate(TEST_DATABASE_NAME, 5, true)

        migrated.query("SELECT title, step_count FROM activities WHERE id = 1").use { row ->
            assertThat(row.moveToFirst()).isTrue()
            assertThat(row.getString(0)).isEqualTo("Evening walk")
            // Старые активности шагов не считали: NULL здесь значит «не измерялось»,
            // а не ноль шагов.
            assertThat(row.isNull(1)).isTrue()
        }
    }

    @Test
    fun migration5To6GivesEveryActivityOneSegmentOverItsWholeRecording() {
        helper.createDatabase(TEST_DATABASE_NAME, 5).use { database ->
            database.insertActivity(id = 1L, title = "Evening walk")
            database.execSQL("UPDATE activities SET end_time = 1757055600000, step_count = 4200 WHERE id = 1")
            database.insertActivity(id = 2L, title = "Unfinished", activityType = "DRIVING")
            database.insertActivity(id = 3L, title = "Imported", activityType = "SOMETHING_ELSE")
        }

        val migrated = helper.runMigrationsAndValidate(TEST_DATABASE_NAME, 6, true)

        migrated.query(
            "SELECT activity_id, kind, start_ts, end_ts, steps, distance_meters, is_manually_edited " +
                "FROM activity_segments ORDER BY activity_id",
        ).use { row ->
            assertThat(row.count).isEqualTo(3)

            row.moveToNext()
            assertThat(row.getLong(0)).isEqualTo(1L)
            assertThat(row.getString(1)).isEqualTo("ON_FOOT")
            assertThat(row.getLong(2)).isEqualTo(1_757_052_000_000L)
            assertThat(row.getLong(3)).isEqualTo(1_757_055_600_000L)
            assertThat(row.getInt(4)).isEqualTo(4200)
            assertThat(row.getDouble(5)).isEqualTo(1200.0)
            assertThat(row.getInt(6)).isEqualTo(0)

            // Незавершённая запись продолжится после обновления — её сегмент открыт,
            // а шагов у неё не считалось.
            row.moveToNext()
            assertThat(row.getString(1)).isEqualTo("VEHICLE")
            assertThat(row.isNull(3)).isTrue()
            assertThat(row.isNull(4)).isTrue()

            row.moveToNext()
            assertThat(row.getString(1)).isEqualTo("UNKNOWN")
        }
    }

    @Test
    fun migration6To7MarksExistingActivitiesAsFixedMode() {
        helper.createDatabase(TEST_DATABASE_NAME, 6).use { database ->
            database.insertActivity(id = 1L, title = "Evening walk")
        }

        val migrated = helper.runMigrationsAndValidate(TEST_DATABASE_NAME, 7, true)

        migrated.query("SELECT title, is_auto_mode FROM activities WHERE id = 1").use { row ->
            assertThat(row.moveToFirst()).isTrue()
            assertThat(row.getString(0)).isEqualTo("Evening walk")
            assertThat(row.getInt(1)).isEqualTo(0)
        }
    }

    @Test
    fun migration7To8KeepsActivitiesAndStartsWithoutRecognitionSamples() {
        helper.createDatabase(TEST_DATABASE_NAME, 7).use { database ->
            database.insertActivity(id = 1L, title = "Commute")
        }

        val migrated = helper.runMigrationsAndValidate(TEST_DATABASE_NAME, 8, true)

        migrated.query("SELECT title FROM activities WHERE id = 1").use { row ->
            assertThat(row.moveToFirst()).isTrue()
            assertThat(row.getString(0)).isEqualTo("Commute")
        }
        migrated.query("SELECT COUNT(*) FROM recognition_samples").use { row ->
            assertThat(row.moveToFirst()).isTrue()
            assertThat(row.getInt(0)).isEqualTo(0)
        }
    }

    @Test
    fun migration8To9RecordsMediumSensitivityAndWhoChoseTheType() {
        helper.createDatabase(TEST_DATABASE_NAME, 8).use { database ->
            database.insertActivity(id = 1L, title = "Fixed walk")
            database.insertActivity(id = 2L, title = "Auto ride", activityType = "CYCLING")
            database.insertActivity(id = 3L, title = "Auto hike", activityType = "HIKING")
            database.execSQL("UPDATE activities SET is_auto_mode = 1 WHERE id IN (2, 3)")
            database.insertSegment(activityId = 2L, kind = "ON_FOOT", distanceMeters = 300.0)
            database.insertSegment(activityId = 2L, kind = "CYCLING", distanceMeters = 5_000.0)
            database.insertSegment(activityId = 3L, kind = "ON_FOOT", distanceMeters = 8_000.0)
        }

        val migrated = helper.runMigrationsAndValidate(TEST_DATABASE_NAME, 9, true)

        migrated.query(
            "SELECT auto_pause_sensitivity, is_type_chosen_by_user FROM activities ORDER BY id",
        ).use { row ->
            assertThat(row.moveToFirst()).isTrue()
            assertThat(row.getString(0)).isEqualTo("MEDIUM")
            assertThat(row.getInt(1)).isEqualTo(1)
            row.moveToNext()
            assertThat(row.getInt(1)).isEqualTo(0)
            row.moveToNext()
            assertThat(row.getInt(1)).isEqualTo(1)
        }
    }

    @Test
    fun migration10To11DerivesTheTypeOfAutoActivitiesNobodyChose() {
        helper.createDatabase(TEST_DATABASE_NAME, 10).use { database ->
            // Так выглядит история авторежима после миграции 8 → 9: тип остался «другим»,
            // и расхождение с доминирующим видом она приняла за ручной выбор.
            database.insertActivity(id = 1L, title = "Auto ride", activityType = "OTHER")
            database.insertActivity(id = 2L, title = "Imported", activityType = "OTHER")
            database.insertActivity(id = 3L, title = "Chosen hike", activityType = "HIKING")
            database.execSQL("UPDATE activities SET is_auto_mode = 1, is_type_chosen_by_user = 1 WHERE id = 1")
            database.execSQL("UPDATE activities SET is_type_chosen_by_user = 1 WHERE id IN (2, 3)")
            database.insertSegment(activityId = 1L, kind = "ON_FOOT", distanceMeters = 300.0)
            database.insertSegment(activityId = 1L, kind = "CYCLING", distanceMeters = 5_000.0)
            database.insertSegment(activityId = 1L, kind = "PAUSE", distanceMeters = 0.0)
            database.insertSegment(activityId = 2L, kind = "UNKNOWN", distanceMeters = 1_200.0)
            database.insertSegment(activityId = 3L, kind = "ON_FOOT", distanceMeters = 8_000.0)
        }

        val migrated = helper.runMigrationsAndValidate(
            TEST_DATABASE_NAME,
            11,
            true,
            KornoDatabase.MIGRATION_10_11,
        )

        migrated.query("SELECT activity_type, is_type_chosen_by_user FROM activities ORDER BY id").use { row ->
            assertThat(row.moveToFirst()).isTrue()
            // Тип выводится из доминирующего вида, как это делает пересчёт трека.
            assertThat(row.getString(0)).isEqualTo("CYCLING")
            assertThat(row.getInt(1)).isEqualTo(0)
            row.moveToNext()
            // Выводить не из чего — «другое» остаётся, но выбором пользователя больше не считается.
            assertThat(row.getString(0)).isEqualTo("OTHER")
            assertThat(row.getInt(1)).isEqualTo(0)
            row.moveToNext()
            // Настоящий ручной выбор не трогается.
            assertThat(row.getString(0)).isEqualTo("HIKING")
            assertThat(row.getInt(1)).isEqualTo(1)
        }
    }

    @Test
    fun allMigrationsFromFirstVersionLeaveTheTrackReadableByRoom() = runTest {
        helper.createDatabase(TEST_DATABASE_NAME, 1).use { database ->
            database.insertActivity(id = 1L, title = "Hike")
            database.insertGeoPoint(id = 1L, activityId = 1L, timestamp = 1_757_052_000_000L)
            database.insertGeoPoint(id = 2L, activityId = 1L, timestamp = 1_757_052_005_000L)
        }
        helper.runMigrationsAndValidate(TEST_DATABASE_NAME, 11, true, KornoDatabase.MIGRATION_10_11).close()

        // Валидация схемы ещё не значит, что по ней читается трек: DAO спрашивает и те
        // колонки, которых в первой версии не было.
        val database = Room.databaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            KornoDatabase::class.java,
            TEST_DATABASE_NAME,
        ).addMigrations(KornoDatabase.MIGRATION_10_11).build()

        try {
            val points = database.trackDao().routePoints(activityId = 1L)
            assertThat(points.size).isEqualTo(2)
            assertThat(points.first().latitude).isEqualTo(55.75)

            val activity = database.trackDao().findActiveActivity()
            assertThat(activity?.title).isEqualTo("Hike")
            assertThat(activity?.isPendingSummary).isEqualTo(false)
        } finally {
            database.close()
        }
    }
}

private fun SupportSQLiteDatabase.insertSegment(activityId: Long, kind: String, distanceMeters: Double) {
    execSQL(
        """
        INSERT INTO activity_segments (activity_id, kind, start_ts, end_ts, steps, distance_meters, is_manually_edited)
        VALUES ($activityId, '$kind', 1757052000000, 1757052600000, NULL, $distanceMeters, 0)
        """.trimIndent(),
    )
}

/** Колонки первой версии — те, что есть во всех версиях и вставляются одинаково. */
private fun SupportSQLiteDatabase.insertActivity(id: Long, title: String, activityType: String = "WALKING") {
    execSQL(
        """
        INSERT INTO activities (
            id, start_time, end_time, activity_type, title, note, distance,
            moving_time, elapsed_time, avg_speed, max_speed, elevation_gain,
            is_manually_edited
        ) VALUES ($id, 1757052000000, NULL, '$activityType', '$title', NULL, 1200.0,
            600000, 700000, 2.0, 3.5, 12.0, 0)
        """.trimIndent(),
    )
}

private fun SupportSQLiteDatabase.insertGeoPoint(id: Long, activityId: Long, timestamp: Long) {
    execSQL(
        """
        INSERT INTO geo_points (
            id, activity_id, lat, lon, altitude, timestamp, accuracy, speed,
            is_stop, is_outlier, is_manually_edited
        ) VALUES ($id, $activityId, 55.75, 37.61, 142.0, $timestamp, 8.0, 1.4, 0, 0, 0)
        """.trimIndent(),
    )
}
