package com.ians.korno.feature.activitydetail.domain.usecase

import com.ians.korno.core.domain.error.DataError
import com.ians.korno.core.domain.error.EmptyResult
import com.ians.korno.core.domain.error.Result
import com.ians.korno.core.domain.model.Activity
import com.ians.korno.core.domain.model.ActivitySegment
import com.ians.korno.core.domain.model.GeoPoint
import com.ians.korno.core.domain.model.RecognitionSample
import com.ians.korno.core.domain.model.RoutePoint
import com.ians.korno.core.domain.repository.TrackRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/** Репозиторий-заглушка для use case'ов экрана активности: запоминает, что ему отдали. */
internal class FakeTrackRepository : TrackRepository {
    var replacedActivity: Activity? = null
    var replacedPoints: List<GeoPoint> = emptyList()
    var replacedSegments: List<ActivitySegment>? = null

    /** Ответы распознавания записи: их читает пересчёт разметки. */
    var recognitionSamples: List<RecognitionSample> = emptyList()

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

    override fun observeAllSegments(): Flow<Result<List<ActivitySegment>, DataError.Local>> =
        flowOf(Result.Success(emptyList()))

    override suspend fun saveOpenSegment(
        current: ActivitySegment,
        next: ActivitySegment?,
    ): EmptyResult<DataError.Local> = Result.Success(Unit)

    override suspend fun addRecognitionSample(sample: RecognitionSample): EmptyResult<DataError.Local> =
        Result.Success(Unit)

    override suspend fun getRecognitionSamples(activityId: Long): Result<List<RecognitionSample>, DataError.Local> =
        Result.Success(recognitionSamples)

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
