package com.ians.korno.core.data.local

import com.ians.korno.core.data.mapper.toDomain
import com.ians.korno.core.data.mapper.toEntity
import com.ians.korno.core.database.dao.TrackDao
import com.ians.korno.core.domain.error.DataError
import com.ians.korno.core.domain.error.EmptyResult
import com.ians.korno.core.domain.error.Result
import com.ians.korno.core.domain.model.Activity
import com.ians.korno.core.domain.model.ActivitySegment
import com.ians.korno.core.domain.model.GeoPoint
import com.ians.korno.core.domain.model.RecognitionSample
import com.ians.korno.core.domain.model.RoutePoint
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

internal class RoomTrackLocalDataSource(
    private val trackDao: TrackDao,
) : TrackLocalDataSource {
    override fun observeActivities(): Flow<Result<List<Activity>, DataError.Local>> =
        trackDao.observeActivities()
            .map { entities -> entities.map { it.toDomain() } }
            .asLocalResult()

    override fun observeActivity(activityId: Long): Flow<Result<Activity, DataError.Local>> =
        trackDao.observeActivity(activityId)
            .map { entity ->
                entity?.let { Result.Success(it.toDomain()) }
                    ?: Result.Error(DataError.Local.NOT_FOUND)
            }
            .catch { error ->
                if (error is kotlinx.coroutines.CancellationException) throw error
                emit(Result.Error(error.toLocalError()))
            }

    override fun observeGeoPoints(
        activityId: Long,
    ): Flow<Result<List<GeoPoint>, DataError.Local>> =
        trackDao.observeGeoPoints(activityId)
            .map { entities -> entities.map { it.toDomain() } }
            .asLocalResult()

    override suspend fun findActiveActivity(): Result<Activity?, DataError.Local> =
        safeLocalCall { trackDao.findActiveActivity()?.toDomain() }

    override suspend fun getRoutePreview(
        activityId: Long,
        maxPoints: Int,
    ): Result<List<RoutePoint>, DataError.Local> = safeLocalCall {
        trackDao.routePoints(activityId)
            .map { RoutePoint(latitude = it.latitude, longitude = it.longitude) }
            .downsampledTo(maxPoints)
    }

    override fun observePendingSummaryActivities(): Flow<Result<List<Activity>, DataError.Local>> =
        trackDao.observePendingSummaryActivities()
            .map { entities -> entities.map { it.toDomain() } }
            .asLocalResult()

    override suspend fun createActivity(
        activity: Activity,
        segments: List<ActivitySegment>,
    ): Result<Long, DataError.Local> = safeLocalCall {
        trackDao.insertActivityWithSegments(activity.toEntity(), segments.map { it.toEntity() })
    }

    override suspend fun updateActivity(activity: Activity): EmptyResult<DataError.Local> =
        when (val result = safeLocalCall { trackDao.updateActivity(activity.toEntity()) }) {
            is Result.Success -> {
                if (result.data > 0) Result.Success(Unit)
                else Result.Error(DataError.Local.NOT_FOUND)
            }
            is Result.Error -> result
        }

    override suspend fun getSegments(activityId: Long): Result<List<ActivitySegment>, DataError.Local> =
        safeLocalCall { trackDao.segments(activityId).map { it.toDomain() } }

    override fun observeAllSegments(): Flow<Result<List<ActivitySegment>, DataError.Local>> =
        trackDao.observeAllSegments()
            .map { entities -> entities.map { it.toDomain() } }
            .asLocalResult()

    override suspend fun saveOpenSegment(
        current: ActivitySegment,
        next: ActivitySegment?,
    ): EmptyResult<DataError.Local> = when (
        val result = safeLocalCall { trackDao.saveOpenSegment(current.toEntity(), next?.toEntity()) }
    ) {
        is Result.Success -> Result.Success(Unit)
        is Result.Error -> result
    }

    override suspend fun addGeoPoints(points: List<GeoPoint>): EmptyResult<DataError.Local> {
        if (points.isEmpty()) return Result.Success(Unit)

        return when (val result = safeLocalCall { trackDao.insertGeoPoints(points.map { it.toEntity() }) }) {
            is Result.Success -> Result.Success(Unit)
            is Result.Error -> result
        }
    }

    override suspend fun addRecognitionSample(sample: RecognitionSample): EmptyResult<DataError.Local> =
        when (val result = safeLocalCall { trackDao.insertRecognitionSample(sample.toEntity()) }) {
            is Result.Success -> Result.Success(Unit)
            is Result.Error -> result
        }

    override suspend fun getRecognitionSamples(activityId: Long): Result<List<RecognitionSample>, DataError.Local> =
        safeLocalCall { trackDao.recognitionSamples(activityId).map { it.toDomain() } }

    override suspend fun insertTrack(
        activity: Activity,
        points: List<GeoPoint>,
        segments: List<ActivitySegment>,
    ): Result<Long, DataError.Local> = safeLocalCall {
        trackDao.insertTrack(activity.toEntity(), points.map { it.toEntity() }, segments.map { it.toEntity() })
    }

    override suspend fun replaceTrack(
        activity: Activity,
        points: List<GeoPoint>,
        segments: List<ActivitySegment>?,
    ): EmptyResult<DataError.Local> = when (
        val result = safeLocalCall {
            trackDao.replaceTrack(
                activity.toEntity(),
                points.map { it.toEntity() },
                segments?.map { it.toEntity() },
            )
        }
    ) {
        is Result.Success -> {
            if (result.data) Result.Success(Unit) else Result.Error(DataError.Local.NOT_FOUND)
        }
        is Result.Error -> result
    }

    override suspend fun deleteActivity(activityId: Long): EmptyResult<DataError.Local> =
        when (val result = safeLocalCall { trackDao.deleteActivity(activityId) }) {
            is Result.Success -> {
                if (result.data > 0) Result.Success(Unit)
                else Result.Error(DataError.Local.NOT_FOUND)
            }
            is Result.Error -> result
        }
}
