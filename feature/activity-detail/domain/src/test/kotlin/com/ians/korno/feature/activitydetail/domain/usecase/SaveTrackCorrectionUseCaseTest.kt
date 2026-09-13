package com.ians.korno.feature.activitydetail.domain.usecase

import assertk.assertThat
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThan
import assertk.assertions.isLessThan
import assertk.assertions.isTrue
import com.ians.korno.core.domain.error.DataError
import com.ians.korno.core.domain.error.EmptyResult
import com.ians.korno.core.domain.error.Result
import com.ians.korno.core.domain.model.RecognitionSample
import com.ians.korno.core.domain.model.Activity
import com.ians.korno.core.domain.model.ActivitySegment
import com.ians.korno.core.domain.model.ActivityType
import com.ians.korno.core.domain.model.GeoPoint
import com.ians.korno.core.domain.model.RoutePoint
import com.ians.korno.core.domain.model.SegmentKind
import com.ians.korno.core.domain.repository.TrackRepository
import com.ians.korno.core.trackprocessing.ManualCorrectionUseCase
import com.ians.korno.core.trackprocessing.TrackProcessor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class SaveTrackCorrectionUseCaseTest {
    @Test
    fun `save removes selected point and persists recalculated activity atomically`() = runTest {
        val repository = FakeTrackRepository()
        val useCase = SaveTrackCorrectionUseCase(
            trackRepository = repository,
            manualCorrection = ManualCorrectionUseCase(TrackProcessor()),
        )
        val activity = Activity(
            id = 7L,
            startTimeEpochMillis = 0L,
            endTimeEpochMillis = 10_000L,
            activityType = ActivityType.WALKING,
            distanceMeters = 50_000.0,
        )
        val points = listOf(
            point(id = 1L, latitude = 0.0),
            point(id = 2L, latitude = 1.0),
            point(id = 3L, latitude = 0.0001),
        )

        val result = useCase(activity, points, removedPointIds = setOf(2L))

        assertThat(result).isEqualTo(Result.Success(Unit))
        assertThat(repository.replacedPoints).hasSize(2)
        assertThat(repository.replacedPoints.map(GeoPoint::id)).isEqualTo(listOf(1L, 3L))
        assertThat(repository.replacedActivity!!.isManuallyEdited).isTrue()
        assertThat(repository.replacedActivity!!.distanceMeters).isGreaterThan(10.0)
    }

    @Test
    fun `kind breakdown sums the segments of one kind, and splits use the dominant kind only`() {
        val summaries = summarizeSegmentKinds(mixedPoints, mixedSegments)

        assertThat(summaries.map { it.kind }).isEqualTo(listOf(SegmentKind.ON_FOOT, SegmentKind.VEHICLE))
        val onFoot = summaries.first()
        assertThat(onFoot.distanceMeters).isEqualTo(280.0)
        assertThat(onFoot.stepCount).isEqualTo(220)
        assertThat(onFoot.averageSpeedMetersPerSecond).isLessThan(3.0)

        // Автобус проехал больше, чем прошли пешком, — сплиты считаются по нему.
        val dominant = dominantKindPoints(mixedPoints, mixedSegments)
        assertThat(dominant.all { it.timestampEpochMillis in 100_000L until 200_000L }).isTrue()
    }

    @Test
    fun `changing a kind reprocesses the track and the derived type follows the new dominant kind`() = runTest {
        val repository = FakeTrackRepository()
        val useCase = ChangeActivitySegmentKindUseCase(repository, TrackProcessor())
        val activity = Activity(
            id = 7L,
            startTimeEpochMillis = 0L,
            endTimeEpochMillis = 300_000L,
            activityType = ActivityType.DRIVING,
            stepCount = 220,
            isAutoMode = true,
        )

        useCase(activity, mixedPoints, mixedSegments, segmentIndex = 1, kind = SegmentKind.CYCLING)

        val segments = repository.replacedSegments!!
        assertThat(segments.map { it.kind })
            .isEqualTo(listOf(SegmentKind.ON_FOOT, SegmentKind.CYCLING, SegmentKind.ON_FOOT))
        assertThat(segments[1].isManuallyEdited).isTrue()
        assertThat(segments[1].distanceMeters).isGreaterThan(1_000.0)
        assertThat(repository.replacedActivity!!.activityType).isEqualTo(ActivityType.CYCLING)
        assertThat(repository.replacedActivity!!.stepCount).isEqualTo(220)
    }

    @Test
    fun `a type chosen by the user survives a kind change`() = runTest {
        val repository = FakeTrackRepository()
        val activity = Activity(id = 7L, startTimeEpochMillis = 0L, activityType = ActivityType.HIKING, isAutoMode = true)

        ChangeActivitySegmentKindUseCase(repository, TrackProcessor())(
            activity, mixedPoints, mixedSegments, segmentIndex = 1, kind = SegmentKind.CYCLING,
        )

        assertThat(repository.replacedActivity!!.activityType).isEqualTo(ActivityType.HIKING)
    }

    /** 100 с пешком, 100 с автобусом на 15 м/с, 100 с пешком; фикс раз в 5 с. */
    private val mixedPoints: List<GeoPoint> = run {
        var latitude = 0.0
        (0..60).map { i ->
            val timestamp = i * 5_000L
            if (i > 0) latitude += if (timestamp in 105_000L..200_000L) 0.000_674 else 0.000_063
            GeoPoint(id = i.toLong(), activityId = 7L, latitude = latitude, longitude = 0.0, timestampEpochMillis = timestamp)
        }
    }

    private val mixedSegments = listOf(
        ActivitySegment(kind = SegmentKind.ON_FOOT, startTimeEpochMillis = 0L, endTimeEpochMillis = 100_000L, stepCount = 120, distanceMeters = 140.0),
        ActivitySegment(kind = SegmentKind.VEHICLE, startTimeEpochMillis = 100_000L, endTimeEpochMillis = 200_000L, stepCount = 0, distanceMeters = 1_500.0),
        ActivitySegment(kind = SegmentKind.ON_FOOT, startTimeEpochMillis = 200_000L, endTimeEpochMillis = 300_000L, stepCount = 100, distanceMeters = 140.0),
    )

    private fun point(id: Long, latitude: Double) = GeoPoint(
        id = id,
        activityId = 7L,
        latitude = latitude,
        longitude = 0.0,
        timestampEpochMillis = if (id == 1L) 0L else 10_000L,
    )

    private class FakeTrackRepository : TrackRepository {
        var replacedActivity: Activity? = null
        var replacedPoints: List<GeoPoint> = emptyList()
        var replacedSegments: List<ActivitySegment>? = null

        override fun observeActivities(): Flow<Result<List<Activity>, DataError.Local>> =
            flowOf(Result.Success(emptyList()))

        override fun observeActivity(activityId: Long): Flow<Result<Activity, DataError.Local>> =
            flowOf(Result.Error(DataError.Local.NOT_FOUND))

        override fun observeGeoPoints(activityId: Long): Flow<Result<List<GeoPoint>, DataError.Local>> =
            flowOf(Result.Success(emptyList()))

        override suspend fun findActiveActivity(): Result<Activity?, DataError.Local> = Result.Success(null)

        override fun observePendingSummaryActivities(): Flow<Result<List<Activity>, DataError.Local>> =
            flowOf(Result.Success(emptyList()))

        override suspend fun createActivity(
            activity: Activity,
            segments: List<ActivitySegment>,
        ): Result<Long, DataError.Local> = Result.Success(1L)

        override suspend fun updateActivity(activity: Activity): EmptyResult<DataError.Local> = Result.Success(Unit)

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

        override suspend fun addGeoPoints(points: List<GeoPoint>): EmptyResult<DataError.Local> = Result.Success(Unit)

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
        ): EmptyResult<DataError.Local> {
            replacedActivity = activity
            replacedPoints = points
            replacedSegments = segments
            return Result.Success(Unit)
        }

        override suspend fun deleteActivity(activityId: Long): EmptyResult<DataError.Local> = Result.Success(Unit)
    }
}
