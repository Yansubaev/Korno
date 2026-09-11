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
    fun allMigrationsFromFirstVersionLeaveTheTrackReadableByRoom() = runTest {
        helper.createDatabase(TEST_DATABASE_NAME, 1).use { database ->
            database.insertActivity(id = 1L, title = "Hike")
            database.insertGeoPoint(id = 1L, activityId = 1L, timestamp = 1_757_052_000_000L)
            database.insertGeoPoint(id = 2L, activityId = 1L, timestamp = 1_757_052_005_000L)
        }
        helper.runMigrationsAndValidate(TEST_DATABASE_NAME, 5, true).close()

        // Валидация схемы ещё не значит, что по ней читается трек: DAO спрашивает и те
        // колонки, которых в первой версии не было.
        val database = Room.databaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            KornoDatabase::class.java,
            TEST_DATABASE_NAME,
        ).build()

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

/** Колонки первой версии — те, что есть во всех версиях и вставляются одинаково. */
private fun SupportSQLiteDatabase.insertActivity(id: Long, title: String) {
    execSQL(
        """
        INSERT INTO activities (
            id, start_time, end_time, activity_type, title, note, distance,
            moving_time, elapsed_time, avg_speed, max_speed, elevation_gain,
            is_manually_edited
        ) VALUES ($id, 1757052000000, NULL, 'WALKING', '$title', NULL, 1200.0,
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
