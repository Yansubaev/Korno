package com.ians.korno.core.domain.repository

import com.ians.korno.core.domain.error.DataError
import com.ians.korno.core.domain.error.EmptyResult
import com.ians.korno.core.domain.error.Result
import com.ians.korno.core.domain.model.Activity
import com.ians.korno.core.domain.model.GeoPoint
import com.ians.korno.core.domain.model.RoutePoint
import kotlinx.coroutines.flow.Flow

interface TrackRepository {
    fun observeActivities(): Flow<Result<List<Activity>, DataError.Local>>

    fun observeActivity(activityId: Long): Flow<Result<Activity, DataError.Local>>

    fun observeGeoPoints(activityId: Long): Flow<Result<List<GeoPoint>, DataError.Local>>

    suspend fun findActiveActivity(): Result<Activity?, DataError.Local>

    /**
     * Упрощённый маршрут для превью: не более [maxPoints] точек, равномерно
     * прореженных по треку. Разовое чтение, без подписки — миниатюра в списке
     * не обязана перерисовываться на каждое изменение трека.
     */
    suspend fun getRoutePreview(
        activityId: Long,
        maxPoints: Int,
    ): Result<List<RoutePoint>, DataError.Local>

    /** Завершённые записи, которые пользователь ещё не подтвердил на экране сохранения. */
    fun observePendingSummaryActivities(): Flow<Result<List<Activity>, DataError.Local>>

    suspend fun createActivity(activity: Activity): Result<Long, DataError.Local>

    suspend fun updateActivity(activity: Activity): EmptyResult<DataError.Local>

    suspend fun addGeoPoints(points: List<GeoPoint>): EmptyResult<DataError.Local>

    /**
     * Atomically stores a complete track that arrived from outside the app.
     * Points are re-bound to the generated activity id, so a failed insert cannot
     * leave an activity without its track.
     */
    suspend fun insertTrack(
        activity: Activity,
        points: List<GeoPoint>,
    ): Result<Long, DataError.Local>

    /** Atomically replaces an activity's calculated fields and its complete point set. */
    suspend fun replaceTrack(
        activity: Activity,
        points: List<GeoPoint>,
    ): EmptyResult<DataError.Local>

    suspend fun deleteActivity(activityId: Long): EmptyResult<DataError.Local>
}
