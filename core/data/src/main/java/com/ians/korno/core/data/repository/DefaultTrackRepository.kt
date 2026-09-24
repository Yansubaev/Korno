package com.ians.korno.core.data.repository

import com.ians.korno.core.data.local.TrackLocalDataSource
import com.ians.korno.core.domain.error.DataError
import com.ians.korno.core.domain.error.EmptyResult
import com.ians.korno.core.domain.error.Result
import com.ians.korno.core.domain.model.Activity
import com.ians.korno.core.domain.model.ActivitySegment
import com.ians.korno.core.domain.model.GeoPoint
import com.ians.korno.core.domain.model.RecognitionSample
import com.ians.korno.core.domain.model.StepSample
import com.ians.korno.core.domain.model.RoutePoint
import com.ians.korno.core.domain.repository.TrackRepository
import kotlinx.coroutines.flow.Flow

internal class DefaultTrackRepository(
    private val localDataSource: TrackLocalDataSource,
) : TrackRepository {
    override fun observeActivities(): Flow<Result<List<Activity>, DataError.Local>> =
        localDataSource.observeActivities()

    override fun observeActivity(activityId: Long): Flow<Result<Activity, DataError.Local>> =
        localDataSource.observeActivity(activityId)

    override fun observeGeoPoints(activityId: Long): Flow<Result<List<GeoPoint>, DataError.Local>> =
        localDataSource.observeGeoPoints(activityId)

    override suspend fun findActiveActivity(): Result<Activity?, DataError.Local> =
        localDataSource.findActiveActivity()

    override suspend fun getRoutePreview(
        activityId: Long,
        maxPoints: Int,
    ): Result<List<RoutePoint>, DataError.Local> =
        localDataSource.getRoutePreview(activityId, maxPoints)

    override fun observePendingSummaryActivities(): Flow<Result<List<Activity>, DataError.Local>> =
        localDataSource.observePendingSummaryActivities()

    override suspend fun createActivity(
        activity: Activity,
        segments: List<ActivitySegment>,
    ): Result<Long, DataError.Local> = localDataSource.createActivity(activity, segments)

    override suspend fun updateActivity(activity: Activity): EmptyResult<DataError.Local> =
        localDataSource.updateActivity(activity)

    override suspend fun getSegments(activityId: Long): Result<List<ActivitySegment>, DataError.Local> =
        localDataSource.getSegments(activityId)

    override fun observeAllSegments(): Flow<Result<List<ActivitySegment>, DataError.Local>> =
        localDataSource.observeAllSegments()

    override suspend fun saveOpenSegment(
        current: ActivitySegment,
        next: ActivitySegment?,
    ): EmptyResult<DataError.Local> = localDataSource.saveOpenSegment(current, next)

    override suspend fun addGeoPoints(points: List<GeoPoint>): EmptyResult<DataError.Local> =
        localDataSource.addGeoPoints(points)

    override suspend fun addRecognitionSample(sample: RecognitionSample): EmptyResult<DataError.Local> =
        localDataSource.addRecognitionSample(sample)

    override suspend fun getRecognitionSamples(activityId: Long): Result<List<RecognitionSample>, DataError.Local> =
        localDataSource.getRecognitionSamples(activityId)

    override suspend fun addStepSample(sample: StepSample): EmptyResult<DataError.Local> =
        localDataSource.addStepSample(sample)

    override suspend fun getStepSamples(activityId: Long): Result<List<StepSample>, DataError.Local> =
        localDataSource.getStepSamples(activityId)

    override suspend fun insertTrack(
        activity: Activity,
        points: List<GeoPoint>,
        segments: List<ActivitySegment>,
    ): Result<Long, DataError.Local> = localDataSource.insertTrack(activity, points, segments)

    override suspend fun replaceTrack(
        activity: Activity,
        points: List<GeoPoint>,
        segments: List<ActivitySegment>?,
    ): EmptyResult<DataError.Local> = localDataSource.replaceTrack(activity, points, segments)

    override suspend fun deleteActivity(activityId: Long): EmptyResult<DataError.Local> =
        localDataSource.deleteActivity(activityId)
}
