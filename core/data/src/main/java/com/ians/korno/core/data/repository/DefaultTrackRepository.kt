package com.ians.korno.core.data.repository

import com.ians.korno.core.data.local.TrackLocalDataSource
import com.ians.korno.core.domain.error.DataError
import com.ians.korno.core.domain.error.EmptyResult
import com.ians.korno.core.domain.error.Result
import com.ians.korno.core.domain.model.Activity
import com.ians.korno.core.domain.model.GeoPoint
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

    override suspend fun createActivity(activity: Activity): Result<Long, DataError.Local> =
        localDataSource.createActivity(activity)

    override suspend fun updateActivity(activity: Activity): EmptyResult<DataError.Local> =
        localDataSource.updateActivity(activity)

    override suspend fun addGeoPoints(points: List<GeoPoint>): EmptyResult<DataError.Local> =
        localDataSource.addGeoPoints(points)

    override suspend fun insertTrack(
        activity: Activity,
        points: List<GeoPoint>,
    ): Result<Long, DataError.Local> = localDataSource.insertTrack(activity, points)

    override suspend fun replaceTrack(
        activity: Activity,
        points: List<GeoPoint>,
    ): EmptyResult<DataError.Local> = localDataSource.replaceTrack(activity, points)

    override suspend fun deleteActivity(activityId: Long): EmptyResult<DataError.Local> =
        localDataSource.deleteActivity(activityId)
}
