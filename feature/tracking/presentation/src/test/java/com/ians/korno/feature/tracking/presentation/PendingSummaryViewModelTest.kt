package com.ians.korno.feature.tracking.presentation

import app.cash.turbine.test
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import com.ians.korno.core.domain.error.DataError
import com.ians.korno.core.domain.error.EmptyResult
import com.ians.korno.core.domain.error.Result
import com.ians.korno.core.domain.model.RecognitionSample
import com.ians.korno.core.domain.model.Activity
import com.ians.korno.core.domain.model.ActivitySegment
import com.ians.korno.core.domain.model.ActivityType
import com.ians.korno.core.domain.model.SegmentKind
import com.ians.korno.core.domain.model.GeoPoint
import com.ians.korno.core.domain.model.RoutePoint
import com.ians.korno.core.domain.model.RecordingStatus
import com.ians.korno.core.domain.repository.TrackRepository
import com.ians.korno.feature.tracking.domain.model.RecordingSession
import com.ians.korno.feature.tracking.domain.repository.TrackingController
import com.ians.korno.feature.tracking.domain.usecase.ObservePendingSummaryActivitiesUseCase
import com.ians.korno.feature.tracking.domain.usecase.ObserveTrackingSessionUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PendingSummaryViewModelTest {
    private val trackRepository = FakeTrackRepository()
    private val controller = FakeTrackingController()
    private lateinit var viewModel: PendingSummaryViewModel

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        viewModel = PendingSummaryViewModel(
            observePendingSummaryActivities = ObservePendingSummaryActivitiesUseCase(trackRepository),
            observeTrackingSession = ObserveTrackingSessionUseCase(controller),
        )
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `activity stopped from notification is offered for saving`() = runTest {
        viewModel.pendingActivityId.test {
            assertThat(awaitItem()).isNull()

            trackRepository.emitPending(listOf(pendingActivity(id = 7L)))

            assertThat(awaitItem()).isEqualTo(7L)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `summary is not offered again after the user left the screen`() = runTest {
        trackRepository.emitPending(listOf(pendingActivity(id = 7L)))

        viewModel.pendingActivityId.test {
            assertThat(awaitItem()).isEqualTo(7L)

            viewModel.onSummaryOpened(activityId = 7L)

            assertThat(awaitItem()).isNull()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `returning to the app offers the still unsaved activity again`() = runTest {
        trackRepository.emitPending(listOf(pendingActivity(id = 7L)))
        viewModel.onSummaryOpened(activityId = 7L)

        viewModel.pendingActivityId.test {
            assertThat(awaitItem()).isNull()

            viewModel.onAppForegrounded()

            assertThat(awaitItem()).isEqualTo(7L)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `ongoing recording is not interrupted by an older unsaved activity`() = runTest {
        controller.startRecording()
        trackRepository.emitPending(listOf(pendingActivity(id = 7L)))

        viewModel.pendingActivityId.test {
            assertThat(awaitItem()).isNull()
            cancelAndIgnoreRemainingEvents()
        }
    }

    private fun pendingActivity(id: Long) = Activity(
        id = id,
        startTimeEpochMillis = 1_000L,
        endTimeEpochMillis = 2_000L,
        activityType = ActivityType.RUNNING,
        isPendingSummary = true,
    )

    private class FakeTrackingController : TrackingController {
        private val session = MutableStateFlow(RecordingSession())

        fun startRecording() {
            session.value = RecordingSession(status = RecordingStatus.RECORDING, activityId = 9L)
        }

        override fun observeSession(): Flow<RecordingSession> = session

        override fun prepare() = Unit

        override fun cancelPreparation() = Unit

        override suspend fun start(activityType: ActivityType, autoMode: Boolean) = Unit

        override fun isAutoModeAvailable(): Boolean = true

        override suspend fun changeSegmentKind(kind: SegmentKind) = Unit

        override fun pause() = Unit

        override fun resume() = Unit

        override suspend fun stop(): Result<Long, DataError.Local> = Result.Success(0L)

        override suspend fun recoverActiveSession() = Unit
    }

    private class FakeTrackRepository : TrackRepository {
        private val pending = MutableStateFlow<List<Activity>>(emptyList())

        fun emitPending(activities: List<Activity>) {
            pending.value = activities
        }

        override fun observePendingSummaryActivities(): Flow<Result<List<Activity>, DataError.Local>> =
            pending.map { Result.Success(it) }

        override fun observeActivities(): Flow<Result<List<Activity>, DataError.Local>> =
            throw UnsupportedOperationException()

        override fun observeActivity(activityId: Long): Flow<Result<Activity, DataError.Local>> =
            throw UnsupportedOperationException()

        override fun observeGeoPoints(activityId: Long): Flow<Result<List<GeoPoint>, DataError.Local>> =
            throw UnsupportedOperationException()

        override suspend fun findActiveActivity(): Result<Activity?, DataError.Local> =
            Result.Success(null)

        override suspend fun createActivity(
            activity: Activity,
            segments: List<ActivitySegment>,
        ): Result<Long, DataError.Local> =
            Result.Success(0L)

        override suspend fun updateActivity(activity: Activity): EmptyResult<DataError.Local> =
            Result.Success(Unit)

        override suspend fun getSegments(activityId: Long): Result<List<ActivitySegment>, DataError.Local> =
            Result.Success(emptyList())

        override suspend fun saveOpenSegment(
            current: ActivitySegment,
            next: ActivitySegment?,
        ): EmptyResult<DataError.Local> = Result.Success(Unit)

        override suspend fun addRecognitionSample(sample: RecognitionSample): EmptyResult<DataError.Local> =
            Result.Success(Unit)

        override suspend fun getRecognitionSamples(activityId: Long): Result<List<RecognitionSample>, DataError.Local> =
            Result.Success(emptyList())

        override suspend fun addGeoPoints(points: List<GeoPoint>): EmptyResult<DataError.Local> =
            Result.Success(Unit)

        override suspend fun getRoutePreview(
            activityId: Long,
            maxPoints: Int,
        ): Result<List<RoutePoint>, DataError.Local> = Result.Success(emptyList())

        override suspend fun insertTrack(
            activity: Activity,
            points: List<GeoPoint>,
            segments: List<ActivitySegment>,
        ): Result<Long, DataError.Local> = Result.Success(activity.id)

        override suspend fun replaceTrack(
            activity: Activity,
            points: List<GeoPoint>,
            segments: List<ActivitySegment>?,
        ): EmptyResult<DataError.Local> = Result.Success(Unit)

        override suspend fun deleteActivity(activityId: Long): EmptyResult<DataError.Local> =
            Result.Success(Unit)
    }
}
