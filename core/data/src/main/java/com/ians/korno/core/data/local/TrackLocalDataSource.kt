package com.ians.korno.core.data.local

import com.ians.korno.core.domain.error.DataError
import com.ians.korno.core.domain.error.EmptyResult
import com.ians.korno.core.domain.error.Result
import com.ians.korno.core.domain.model.Activity
import com.ians.korno.core.domain.model.GeoPoint
import com.ians.korno.core.domain.model.RoutePoint
import kotlinx.coroutines.flow.Flow

internal interface TrackLocalDataSource {
    fun observeActivities(): Flow<Result<List<Activity>, DataError.Local>>

    fun observeActivity(activityId: Long): Flow<Result<Activity, DataError.Local>>

    fun observeGeoPoints(activityId: Long): Flow<Result<List<GeoPoint>, DataError.Local>>

    suspend fun findActiveActivity(): Result<Activity?, DataError.Local>

    suspend fun getRoutePreview(
        activityId: Long,
        maxPoints: Int,
    ): Result<List<RoutePoint>, DataError.Local>

    fun observePendingSummaryActivities(): Flow<Result<List<Activity>, DataError.Local>>

    suspend fun createActivity(activity: Activity): Result<Long, DataError.Local>

    suspend fun updateActivity(activity: Activity): EmptyResult<DataError.Local>

    suspend fun addGeoPoints(points: List<GeoPoint>): EmptyResult<DataError.Local>

    suspend fun insertTrack(activity: Activity, points: List<GeoPoint>): Result<Long, DataError.Local>

    suspend fun replaceTrack(activity: Activity, points: List<GeoPoint>): EmptyResult<DataError.Local>

    suspend fun deleteActivity(activityId: Long): EmptyResult<DataError.Local>
}
