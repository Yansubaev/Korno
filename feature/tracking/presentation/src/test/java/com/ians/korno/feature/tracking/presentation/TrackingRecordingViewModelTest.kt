package com.ians.korno.feature.tracking.presentation

import assertk.assertThat
import assertk.assertions.isEqualTo
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
import com.ians.korno.core.map.MapPoint
import com.ians.korno.feature.tracking.domain.model.RecordingSession
import com.ians.korno.feature.tracking.domain.repository.TrackingController
import com.ians.korno.feature.tracking.domain.usecase.ObserveSessionTrackUseCase
import com.ians.korno.feature.tracking.domain.usecase.ObserveTrackPointsUseCase
import com.ians.korno.feature.tracking.domain.usecase.ObserveTrackingSessionUseCase
import com.ians.korno.feature.tracking.domain.usecase.PauseTrackingUseCase
import com.ians.korno.feature.tracking.domain.usecase.ResumeTrackingUseCase
import com.ians.korno.feature.tracking.domain.usecase.StopTrackingUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TrackingRecordingViewModelTest {
    private val testDispatcher = StandardTestDispatcher()
    private val controller = FakeTrackingController()
    private val trackRepository = FakeTrackRepository()
    private val preferencesRepository = FakeUserPreferencesRepository()
    private lateinit var viewModel: TrackingRecordingViewModel

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        viewModel = TrackingRecordingViewModel(
            observeTrackingSession = ObserveTrackingSessionUseCase(controller),
            observeSessionTrack = ObserveSessionTrackUseCase(
                trackingController = controller,
                observeTrackPoints = ObserveTrackPointsUseCase(trackRepository),
            ),
            pauseTracking = PauseTrackingUseCase(controller),
            resumeTracking = ResumeTrackingUseCase(controller),
            stopTracking = StopTrackingUseCase(controller),
            preferencesRepository = preferencesRepository,
        )
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initial state reflects the already-recording session`() = runTest(testDispatcher) {
        backgroundScope.launch { viewModel.state.collect {} }
        runCurrent()

        assertThat(viewModel.state.value.recordingStatus).isEqualTo(RecordingStatus.RECORDING)
        assertThat(viewModel.state.value.finishedActivityId).isNull()
    }

    @Test
    fun `the timer stays at zero while the session waits for a gps fix`() = runTest(testDispatcher) {
        backgroundScope.launch { viewModel.state.collect {} }
        // Старт нажат минуту назад, а сигнала всё нет: ожидание в тренировку не идёт.
        controller.emitWaitingForGps(startedMillisAgo = 60_000L)
        runCurrent()

        assertThat(viewModel.state.value.recordingStatus).isEqualTo(RecordingStatus.WAITING_FOR_GPS)
        assertThat(viewModel.state.value.elapsedTimeMillis).isEqualTo(0L)
    }

    @Test
    fun `pause and resume actions round-trip the recording status`() = runTest(testDispatcher) {
        backgroundScope.launch { viewModel.state.collect {} }
        runCurrent()

        viewModel.onAction(TrackingRecordingAction.PauseClicked)
        runCurrent()
        assertThat(viewModel.state.value.recordingStatus).isEqualTo(RecordingStatus.MANUAL_PAUSE)

        viewModel.onAction(TrackingRecordingAction.ResumeClicked)
        runCurrent()
        assertThat(viewModel.state.value.recordingStatus).isEqualTo(RecordingStatus.RECORDING)
    }

    @Test
    fun `confirming finish stops the session and exposes the saved activity id`() = runTest(testDispatcher) {
        backgroundScope.launch { viewModel.state.collect {} }
        runCurrent()

        viewModel.onAction(TrackingRecordingAction.RequestFinish)
        runCurrent()
        assertThat(viewModel.state.value.finishDialogOpen).isEqualTo(true)

        viewModel.onAction(TrackingRecordingAction.ConfirmFinish)
        runCurrent()

        assertThat(viewModel.state.value.recordingStatus).isEqualTo(RecordingStatus.STOPPED)
        assertThat(viewModel.state.value.finishedActivityId).isEqualTo(1L)
    }

    @Test
    fun `cycling the map sheet mode walks through all three states`() = runTest(testDispatcher) {
        backgroundScope.launch { viewModel.state.collect {} }
        runCurrent()

        assertThat(viewModel.state.value.mapSheetMode).isEqualTo(MapSheetMode.SPLIT)

        viewModel.onAction(TrackingRecordingAction.CycleMapSheetMode)
        runCurrent()
        assertThat(viewModel.state.value.mapSheetMode).isEqualTo(MapSheetMode.TEXT)

        viewModel.onAction(TrackingRecordingAction.CycleMapSheetMode)
        runCurrent()
        assertThat(viewModel.state.value.mapSheetMode).isEqualTo(MapSheetMode.MAP)

        viewModel.onAction(TrackingRecordingAction.CycleMapSheetMode)
        runCurrent()
        assertThat(viewModel.state.value.mapSheetMode).isEqualTo(MapSheetMode.SPLIT)
    }

    @Test
    fun `elevation gain from the session reaches the state`() = runTest(testDispatcher) {
        backgroundScope.launch { viewModel.state.collect {} }
        runCurrent()

        controller.emitElevationGain(148.0)
        runCurrent()

        assertThat(viewModel.state.value.elevationGainValue).isEqualTo(148.0)
    }

    @Test
    fun `imperial preference converts live recording values and units`() = runTest(testDispatcher) {
        backgroundScope.launch { viewModel.state.collect {} }
        controller.emitMeasurements(distanceMeters = 1_609.344, speedMetersPerSecond = 1f)
        preferencesRepository.setMeasurementSystem(MeasurementSystem.IMPERIAL)
        runCurrent()

        assertThat(viewModel.state.value.distanceValue).isEqualTo(1.0)
        assertThat(viewModel.state.value.measurementSystem).isEqualTo(MeasurementSystem.IMPERIAL)
    }

    @Test
    fun `dragging the sheet to an anchor applies that mode`() = runTest(testDispatcher) {
        backgroundScope.launch { viewModel.state.collect {} }
        runCurrent()

        viewModel.onAction(TrackingRecordingAction.MapSheetModeChanged(MapSheetMode.MAP))
        runCurrent()

        assertThat(viewModel.state.value.mapSheetMode).isEqualTo(MapSheetMode.MAP)
    }

    @Test
    fun `recorded points reach the state as map points`() = runTest(testDispatcher) {
        backgroundScope.launch { viewModel.state.collect {} }
        runCurrent()

        controller.attachActivity(ACTIVITY_ID)
        trackRepository.emitPoints(
            listOf(
                geoPoint(latitude = 55.75, longitude = 37.61),
                geoPoint(
                    latitude = 55.76,
                    longitude = 37.62,
                    recordingStatus = RecordingStatus.MANUAL_PAUSE,
                ),
            ),
        )
        runCurrent()

        assertThat(viewModel.state.value.routePoints)
            .isEqualTo(
                listOf(
                    MapPoint(55.75, 37.61),
                    MapPoint(55.76, 37.62, isPaused = true),
                ),
            )
        assertThat(viewModel.state.value.currentPoint)
            .isEqualTo(MapPoint(55.76, 37.62, isPaused = true))
    }

    private fun geoPoint(
        latitude: Double,
        longitude: Double,
        recordingStatus: RecordingStatus = RecordingStatus.RECORDING,
    ) = GeoPoint(
        activityId = ACTIVITY_ID,
        latitude = latitude,
        longitude = longitude,
        timestampEpochMillis = 0L,
        recordingStatus = recordingStatus,
    )

    private class FakeTrackingController : TrackingController {
        private val sessionFlow = MutableStateFlow(
            RecordingSession(
                status = RecordingStatus.RECORDING,
                activityType = ActivityType.RUNNING,
                startTimeEpochMillis = System.currentTimeMillis(),
            ),
        )

        override fun observeSession(): Flow<RecordingSession> = sessionFlow

        override fun prepare() = Unit

        override fun cancelPreparation() = Unit

        override suspend fun start(activityType: ActivityType) {
            sessionFlow.value = sessionFlow.value.copy(status = RecordingStatus.RECORDING, activityType = activityType)
        }

        override fun pause() {
            sessionFlow.value = sessionFlow.value.copy(status = RecordingStatus.MANUAL_PAUSE)
        }

        override fun resume() {
            sessionFlow.value = sessionFlow.value.copy(status = RecordingStatus.RECORDING)
        }

        override suspend fun stop(): Result<Long, DataError.Local> {
            sessionFlow.value = sessionFlow.value.copy(status = RecordingStatus.STOPPED)
            return Result.Success(1L)
        }

        override suspend fun recoverActiveSession() = Unit

        fun attachActivity(activityId: Long) {
            sessionFlow.value = sessionFlow.value.copy(activityId = activityId)
        }

        fun emitWaitingForGps(startedMillisAgo: Long) {
            sessionFlow.value = sessionFlow.value.copy(
                status = RecordingStatus.WAITING_FOR_GPS,
                startTimeEpochMillis = System.currentTimeMillis() - startedMillisAgo,
            )
        }

        fun emitElevationGain(meters: Double) {
            sessionFlow.value = sessionFlow.value.copy(elevationGainMeters = meters)
        }

        fun emitMeasurements(distanceMeters: Double, speedMetersPerSecond: Float) {
            sessionFlow.value = sessionFlow.value.copy(
                distanceMeters = distanceMeters,
                currentSpeedMetersPerSecond = speedMetersPerSecond,
                movingTimeMillis = 1_000L,
            )
        }
    }

    private class FakeUserPreferencesRepository : UserPreferencesRepository {
        private val preferences = MutableStateFlow(
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
        override suspend fun setMeasurementSystem(value: MeasurementSystem): EmptyResult<DataError.Local> {
            preferences.value = preferences.value.copy(measurementSystem = value)
            return success()
        }
        override suspend fun setAutoPauseEnabled(value: Boolean): EmptyResult<DataError.Local> = success()
        override suspend fun setGpsAccuracy(value: GpsAccuracy): EmptyResult<DataError.Local> = success()
        override suspend fun setAutoPauseSensitivity(value: AutoPauseSensitivity): EmptyResult<DataError.Local> = success()
        override suspend fun setPrivacyZone(value: PrivacyZone): EmptyResult<DataError.Local> = success()
        override suspend fun setDynamicColorEnabled(value: Boolean): EmptyResult<DataError.Local> = success()
        override suspend fun setOnboardingCompleted(value: Boolean): EmptyResult<DataError.Local> = success()
        override suspend fun setLastActivityType(value: ActivityType): EmptyResult<DataError.Local> = success()

        private fun success(): EmptyResult<DataError.Local> = Result.Success(Unit)
    }

    /** Точки трека приходят из БД, поэтому репозиторий подменяется целиком. */
    private class FakeTrackRepository : TrackRepository {
        private val points = MutableStateFlow<List<GeoPoint>>(emptyList())

        fun emitPoints(newPoints: List<GeoPoint>) {
            points.value = newPoints
        }

        override fun observeGeoPoints(activityId: Long): Flow<Result<List<GeoPoint>, DataError.Local>> =
            points.map { Result.Success(it) }

        override fun observeActivities(): Flow<Result<List<Activity>, DataError.Local>> =
            throw UnsupportedOperationException()

        override fun observeActivity(activityId: Long): Flow<Result<Activity, DataError.Local>> =
            throw UnsupportedOperationException()

        override suspend fun findActiveActivity(): Result<Activity?, DataError.Local> =
            Result.Success(null)

        override fun observePendingSummaryActivities(): Flow<Result<List<Activity>, DataError.Local>> =
            throw UnsupportedOperationException()

        override suspend fun createActivity(activity: Activity): Result<Long, DataError.Local> =
            Result.Success(ACTIVITY_ID)

        override suspend fun updateActivity(activity: Activity): EmptyResult<DataError.Local> =
            Result.Success(Unit)

        override suspend fun addGeoPoints(points: List<GeoPoint>): EmptyResult<DataError.Local> =
            Result.Success(Unit)

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
        ): EmptyResult<DataError.Local> = Result.Success(Unit)

        override suspend fun deleteActivity(activityId: Long): EmptyResult<DataError.Local> =
            Result.Success(Unit)
    }

    private companion object {
        const val ACTIVITY_ID = 42L
    }
}
