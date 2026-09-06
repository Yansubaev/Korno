package com.ians.korno.feature.activitydetail.domain.usecase

import assertk.assertThat
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThan
import assertk.assertions.isTrue
import com.ians.korno.core.domain.error.DataError
import com.ians.korno.core.domain.error.EmptyResult
import com.ians.korno.core.domain.error.Result
import com.ians.korno.core.domain.model.Activity
import com.ians.korno.core.domain.model.ActivityType
import com.ians.korno.core.domain.model.GeoPoint
import com.ians.korno.core.domain.model.RoutePoint
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

        override fun observeActivities(): Flow<Result<List<Activity>, DataError.Local>> =
            flowOf(Result.Success(emptyList()))

        override fun observeActivity(activityId: Long): Flow<Result<Activity, DataError.Local>> =
            flowOf(Result.Error(DataError.Local.NOT_FOUND))

        override fun observeGeoPoints(activityId: Long): Flow<Result<List<GeoPoint>, DataError.Local>> =
            flowOf(Result.Success(emptyList()))

        override suspend fun findActiveActivity(): Result<Activity?, DataError.Local> = Result.Success(null)

        override fun observePendingSummaryActivities(): Flow<Result<List<Activity>, DataError.Local>> =
            flowOf(Result.Success(emptyList()))

        override suspend fun createActivity(activity: Activity): Result<Long, DataError.Local> = Result.Success(1L)

        override suspend fun updateActivity(activity: Activity): EmptyResult<DataError.Local> = Result.Success(Unit)

        override suspend fun addGeoPoints(points: List<GeoPoint>): EmptyResult<DataError.Local> = Result.Success(Unit)

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
            replacedActivity = activity
            replacedPoints = points
            return Result.Success(Unit)
        }

        override suspend fun deleteActivity(activityId: Long): EmptyResult<DataError.Local> = Result.Success(Unit)
    }
}
