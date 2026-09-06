package com.ians.korno.feature.tracking.data.session

import assertk.assertThat
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThan
import assertk.assertions.isLessThan
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import com.ians.korno.core.domain.error.DataError
import com.ians.korno.core.domain.error.EmptyResult
import com.ians.korno.core.domain.error.Result
import com.ians.korno.core.domain.model.Activity
import com.ians.korno.core.domain.model.ActivityType
import com.ians.korno.core.domain.model.GeoPoint
import com.ians.korno.core.domain.model.RoutePoint
import com.ians.korno.core.domain.model.RecordingStatus
import com.ians.korno.core.domain.model.AutoPauseSensitivity
import com.ians.korno.core.domain.model.GpsAccuracy
import com.ians.korno.core.domain.model.MeasurementSystem
import com.ians.korno.core.domain.model.PrivacyZone
import com.ians.korno.core.domain.model.UserPreferences
import com.ians.korno.core.domain.repository.TrackRepository
import com.ians.korno.core.domain.repository.UserPreferencesRepository
import com.ians.korno.core.trackprocessing.TrackProcessor
import com.ians.korno.data.locationtracking.location.LocationSample
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class TrackingSessionCoordinatorTest {
    private val repository = FakeTrackRepository()
    private val preferencesRepository = FakeUserPreferencesRepository()
    private var clockMillis = 1_000_000L
    private val coordinator = TrackingSessionCoordinator(
        repository,
        TrackProcessor(),
        preferencesRepository,
    ) { clockMillis }

    @Test
    fun `sample before session started is ignored`() = runTest {
        coordinator.applySample(sample(latitude = 0.0, longitude = 0.0, timestamp = 0L))

        assertThat(coordinator.session.value.status).isEqualTo(RecordingStatus.IDLE)
        assertThat(coordinator.session.value.distanceMeters).isEqualTo(0.0)
    }

    @Test
    fun `begin session creates activity and waits for a gps fix`() = runTest {
        val activityId = coordinator.beginSession(ActivityType.RUNNING)

        assertThat(activityId).isEqualTo(1L)
        assertThat(coordinator.session.value.status).isEqualTo(RecordingStatus.WAITING_FOR_GPS)
        assertThat(coordinator.session.value.activityId).isEqualTo(1L)
        assertThat(repository.createdActivities.first().activityType).isEqualTo(ActivityType.RUNNING)
    }

    @Test
    fun `a rough sample does not count as a fix but a precise one starts the recording`() = runTest {
        coordinator.beginSession(ActivityType.RUNNING)

        // Первые сэмплы приходят от сети с погрешностью в сотни метров.
        coordinator.applySample(sample(latitude = 0.0, longitude = 0.0, timestamp = 0L, accuracyMeters = 400f))

        assertThat(coordinator.session.value.status).isEqualTo(RecordingStatus.WAITING_FOR_GPS)
        assertThat(repository.insertedPoints).hasSize(0)

        clockMillis += 8_000L
        coordinator.applySample(sample(latitude = 0.0, longitude = 0.0, timestamp = 8_000L, accuracyMeters = 8f))

        assertThat(coordinator.session.value.status).isEqualTo(RecordingStatus.RECORDING)
    }

    @Test
    fun `the activity clock starts from the fix, not from the button`() = runTest {
        val pressedAt = clockMillis
        coordinator.beginSession(ActivityType.RUNNING)

        clockMillis += 12_000L
        coordinator.applySample(sample(latitude = 0.0, longitude = 0.0, timestamp = 0L, accuracyMeters = 5f))

        assertThat(coordinator.session.value.startTimeEpochMillis).isEqualTo(pressedAt + 12_000L)
        assertThat(repository.activityUpdates.single().startTimeEpochMillis).isEqualTo(pressedAt + 12_000L)
    }

    @Test
    fun `fix caught while warming up starts the recording right away`() = runTest {
        coordinator.applySample(sample(latitude = 0.0, longitude = 0.0, timestamp = 0L, accuracyMeters = 5f))

        coordinator.beginSession(ActivityType.RUNNING)

        assertThat(coordinator.session.value.status).isEqualTo(RecordingStatus.RECORDING)
        assertThat(repository.activityUpdates).hasSize(0)
    }

    @Test
    fun `a stale warm-up fix is not trusted`() = runTest {
        coordinator.applySample(sample(latitude = 0.0, longitude = 0.0, timestamp = 0L, accuracyMeters = 5f))
        clockMillis += 60_000L

        coordinator.beginSession(ActivityType.RUNNING)

        assertThat(coordinator.session.value.status).isEqualTo(RecordingStatus.WAITING_FOR_GPS)
    }

    @Test
    fun `after a long wait even a rough point starts the recording`() = runTest {
        coordinator.beginSession(ActivityType.RUNNING)
        clockMillis += 31_000L

        coordinator.applySample(sample(latitude = 0.0, longitude = 0.0, timestamp = 0L, accuracyMeters = 400f))

        assertThat(coordinator.session.value.status).isEqualTo(RecordingStatus.RECORDING)
    }

    @Test
    fun `stopping before the fix arrives leaves an activity of zero length`() = runTest {
        coordinator.beginSession(ActivityType.RUNNING)
        clockMillis += 20_000L

        coordinator.endSession()

        assertThat(repository.updatedActivities.single().elapsedTimeMillis).isEqualTo(0L)
    }

    @Test
    fun `consecutive samples accumulate distance and moving time`() = runTest {
        coordinator.beginSession(ActivityType.RUNNING)

        coordinator.applySample(sample(latitude = 0.0, longitude = 0.0, timestamp = 0L))
        coordinator.applySample(sample(latitude = 0.0005, longitude = 0.0, timestamp = 10_000L))

        val session = coordinator.session.value
        // ~0.0005 degree of latitude is ~56 meters.
        assertThat(session.distanceMeters).isGreaterThan(50.0)
        assertThat(session.distanceMeters).isLessThan(60.0)
        assertThat(session.movingTimeMillis).isEqualTo(10_000L)
    }

    @Test
    fun `samples while paused do not change distance or moving time`() = runTest {
        coordinator.beginSession(ActivityType.RUNNING)
        coordinator.applySample(sample(latitude = 0.0, longitude = 0.0, timestamp = 0L))
        coordinator.pause()

        coordinator.applySample(sample(latitude = 1.0, longitude = 1.0, timestamp = 60_000L))

        val session = coordinator.session.value
        assertThat(session.status).isEqualTo(RecordingStatus.MANUAL_PAUSE)
        assertThat(session.distanceMeters).isEqualTo(0.0)
        assertThat(session.movingTimeMillis).isEqualTo(0L)
    }

    @Test
    fun `samples while manually paused are stored with pause status`() = runTest {
        coordinator.beginSession(ActivityType.RUNNING)
        coordinator.applySample(sample(latitude = 0.0, longitude = 0.0, timestamp = 0L))
        coordinator.pause()
        coordinator.applySample(sample(latitude = 1.0, longitude = 1.0, timestamp = 60_000L))

        coordinator.endSession()

        val pausedPoint = repository.replacedPoints.single {
            it.recordingStatus == RecordingStatus.MANUAL_PAUSE
        }
        assertThat(pausedPoint.latitude).isEqualTo(1.0)
        assertThat(pausedPoint.longitude).isEqualTo(1.0)
        assertThat(repository.updatedActivities.single().distanceMeters).isEqualTo(0.0)
        assertThat(repository.updatedActivities.single().movingTimeMillis).isEqualTo(0L)
    }

    @Test
    fun `ending while paused excludes the ongoing pause from elapsed time`() = runTest {
        beginWarmedSession()
        clockMillis += 10_000L
        coordinator.pause()
        clockMillis += 30_000L

        coordinator.endSession()

        assertThat(repository.updatedActivities.single().elapsedTimeMillis).isEqualTo(10_000L)
    }

    @Test
    fun `stationary samples switch session to auto pause and movement resumes it`() = runTest {
        coordinator.beginSession(ActivityType.RUNNING)
        coordinator.applySample(sample(latitude = 0.0, longitude = 0.0, timestamp = 0L))
        coordinator.applySample(sample(latitude = 0.0, longitude = 0.0, timestamp = 5_000L))
        coordinator.applySample(sample(latitude = 0.0, longitude = 0.0, timestamp = 10_000L))

        assertThat(coordinator.session.value.status).isEqualTo(RecordingStatus.AUTO_PAUSE)
        assertThat(coordinator.session.value.movingTimeMillis).isEqualTo(0L)

        coordinator.applySample(
            sample(
                latitude = 0.0001,
                longitude = 0.0,
                timestamp = 15_000L,
                speedMetersPerSecond = 1.4f,
            ),
        )

        assertThat(coordinator.session.value.status).isEqualTo(RecordingStatus.RECORDING)
        assertThat(coordinator.session.value.movingTimeMillis).isEqualTo(5_000L)
    }

    @Test
    fun `stationary samples remain recording when auto pause is disabled`() = runTest {
        preferencesRepository.preferences.value = preferencesRepository.preferences.value.copy(
            autoPauseEnabled = false,
        )
        coordinator.beginSession(ActivityType.RUNNING)
        coordinator.applySample(sample(latitude = 0.0, longitude = 0.0, timestamp = 0L))
        coordinator.applySample(sample(latitude = 0.0, longitude = 0.0, timestamp = 5_000L))
        coordinator.applySample(sample(latitude = 0.0, longitude = 0.0, timestamp = 10_000L))

        assertThat(coordinator.session.value.status).isEqualTo(RecordingStatus.RECORDING)
    }

    @Test
    fun `manual pause can override auto pause`() = runTest {
        coordinator.beginSession(ActivityType.RUNNING)
        coordinator.applySample(sample(latitude = 0.0, longitude = 0.0, timestamp = 0L))
        coordinator.applySample(sample(latitude = 0.0, longitude = 0.0, timestamp = 5_000L))
        coordinator.applySample(sample(latitude = 0.0, longitude = 0.0, timestamp = 10_000L))

        coordinator.pause()

        assertThat(coordinator.session.value.status).isEqualTo(RecordingStatus.MANUAL_PAUSE)
    }

    @Test
    fun `resume clears pause state and recording continues accumulating`() = runTest {
        beginWarmedSession()
        coordinator.pause()
        coordinator.resume()

        assertThat(coordinator.session.value.status).isEqualTo(RecordingStatus.RECORDING)
        assertThat(coordinator.session.value.currentPauseStartMillis).isNull()
    }

    @Test
    fun `buffer is flushed automatically once threshold is reached`() = runTest {
        coordinator.beginSession(ActivityType.RUNNING)

        repeat(10) { index ->
            coordinator.applySample(sample(latitude = index * 0.0001, longitude = 0.0, timestamp = index * 1_000L))
        }

        assertThat(repository.insertedPoints).hasSize(10)
    }

    @Test
    fun `buffer is flushed by time even below the threshold`() = runTest {
        coordinator.beginSession(ActivityType.RUNNING)

        coordinator.applySample(sample(latitude = 0.0, longitude = 0.0, timestamp = 0L))
        assertThat(repository.insertedPoints).hasSize(0)

        clockMillis += 5_000L
        coordinator.applySample(sample(latitude = 0.0001, longitude = 0.0, timestamp = 5_000L))

        assertThat(repository.insertedPoints).hasSize(2)
    }

    @Test
    fun `end session finalizes activity and resets state`() = runTest {
        coordinator.beginSession(ActivityType.RUNNING)
        coordinator.applySample(sample(latitude = 0.0, longitude = 0.0, timestamp = 0L))
        coordinator.applySample(sample(latitude = 0.0005, longitude = 0.0, timestamp = 10_000L))

        val finishedActivityId = coordinator.endSession()

        assertThat(finishedActivityId).isEqualTo(1L)
        assertThat(repository.updatedActivities.first().endTimeEpochMillis).isNotNull()
        assertThat(repository.updatedActivities.first().distanceMeters).isGreaterThan(0.0)
        assertThat(coordinator.session.value.status).isEqualTo(RecordingStatus.STOPPED)
        assertThat(coordinator.session.value.activityId).isNull()
    }

    @Test
    fun `end session from app confirms activity right away`() = runTest {
        coordinator.beginSession(ActivityType.RUNNING)

        coordinator.endSession()

        assertThat(repository.updatedActivities.first().isPendingSummary).isEqualTo(false)
    }

    @Test
    fun `end session from notification leaves activity waiting for summary screen`() = runTest {
        coordinator.beginSession(ActivityType.RUNNING)

        coordinator.endSession(pendingSummary = true)

        assertThat(repository.updatedActivities.first().isPendingSummary).isEqualTo(true)
    }

    /**
     * Прогрев перед стартом: пойманный сигнал позволяет начать запись сразу,
     * без ожидания на экране записи.
     */
    private suspend fun beginWarmedSession(activityType: ActivityType = ActivityType.RUNNING): Long? {
        coordinator.applySample(sample(latitude = 0.0, longitude = 0.0, timestamp = 0L, accuracyMeters = 5f))
        return coordinator.beginSession(activityType)
    }

    private fun sample(
        latitude: Double,
        longitude: Double,
        timestamp: Long,
        speedMetersPerSecond: Float? = null,
        accuracyMeters: Float? = null,
    ) = LocationSample(
        latitude = latitude,
        longitude = longitude,
        altitudeMeters = null,
        timestampEpochMillis = timestamp,
        accuracyMeters = accuracyMeters,
        speedMetersPerSecond = speedMetersPerSecond,
    )

    private class FakeTrackRepository : TrackRepository {
        var nextActivityId = 1L
        val createdActivities = mutableListOf<Activity>()

        /** Правки уже созданной активности по ходу записи (сдвиг времени старта). */
        val activityUpdates = mutableListOf<Activity>()

        /** Финализированные записи: то, что легло в БД через replaceTrack. */
        val updatedActivities = mutableListOf<Activity>()
        val insertedPoints = mutableListOf<GeoPoint>()
        val replacedPoints = mutableListOf<GeoPoint>()

        override fun observeActivities(): Flow<Result<List<Activity>, DataError.Local>> =
            flowOf(Result.Success(emptyList()))

        override fun observeActivity(activityId: Long): Flow<Result<Activity, DataError.Local>> =
            flowOf(Result.Error(DataError.Local.NOT_FOUND))

        override fun observeGeoPoints(activityId: Long): Flow<Result<List<GeoPoint>, DataError.Local>> =
            flowOf(Result.Success(emptyList()))

        override suspend fun findActiveActivity(): Result<Activity?, DataError.Local> = Result.Success(null)

        override fun observePendingSummaryActivities(): Flow<Result<List<Activity>, DataError.Local>> =
            flowOf(Result.Success(emptyList()))

        override suspend fun createActivity(activity: Activity): Result<Long, DataError.Local> {
            createdActivities += activity
            return Result.Success(nextActivityId)
        }

        override suspend fun updateActivity(activity: Activity): EmptyResult<DataError.Local> {
            activityUpdates += activity
            return Result.Success(Unit)
        }

        override suspend fun addGeoPoints(points: List<GeoPoint>): EmptyResult<DataError.Local> {
            insertedPoints += points
            return Result.Success(Unit)
        }

        override suspend fun getRoutePreview(
            activityId: Long,
            maxPoints: Int,
        ): Result<List<RoutePoint>, DataError.Local> = Result.Success(emptyList())

        override suspend fun insertTrack(
            activity: Activity,
            points: List<GeoPoint>,
        ): Result<Long, DataError.Local> = Result.Success(activity.id)

        override suspend fun replaceTrack(
            activity: Activity,
            points: List<GeoPoint>,
        ): EmptyResult<DataError.Local> {
            updatedActivities += activity
            replacedPoints.clear()
            replacedPoints += points
            return Result.Success(Unit)
        }

        override suspend fun deleteActivity(activityId: Long): EmptyResult<DataError.Local> =
            Result.Success(Unit)
    }

    private class FakeUserPreferencesRepository : UserPreferencesRepository {
        val preferences = MutableStateFlow(
            UserPreferences(
                measurementSystem = MeasurementSystem.METRIC,
                autoPauseEnabled = true,
                gpsAccuracy = GpsAccuracy.BALANCED,
                autoPauseSensitivity = AutoPauseSensitivity.MEDIUM,
                dynamicColorEnabled = true,
                privacyZone = PrivacyZone.OFF,
                onboardingCompleted = true,
                lastActivityType = ActivityType.RUNNING,
            ),
        )

        override fun observePreferences(): Flow<UserPreferences> = preferences
        override suspend fun initialize(): EmptyResult<DataError.Local> = success()
        override suspend fun setMeasurementSystem(value: MeasurementSystem): EmptyResult<DataError.Local> = success()
        override suspend fun setAutoPauseEnabled(value: Boolean): EmptyResult<DataError.Local> = success()
        override suspend fun setGpsAccuracy(value: GpsAccuracy): EmptyResult<DataError.Local> = success()
        override suspend fun setAutoPauseSensitivity(value: AutoPauseSensitivity): EmptyResult<DataError.Local> = success()
        override suspend fun setPrivacyZone(value: PrivacyZone): EmptyResult<DataError.Local> = success()
        override suspend fun setDynamicColorEnabled(value: Boolean): EmptyResult<DataError.Local> = success()
        override suspend fun setOnboardingCompleted(value: Boolean): EmptyResult<DataError.Local> = success()
        override suspend fun setLastActivityType(value: ActivityType): EmptyResult<DataError.Local> = success()

        private fun success(): EmptyResult<DataError.Local> = Result.Success(Unit)
    }
}
