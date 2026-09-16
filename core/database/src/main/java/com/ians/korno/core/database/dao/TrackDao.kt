package com.ians.korno.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.ians.korno.core.database.entity.ActivityEntity
import com.ians.korno.core.database.entity.ActivitySegmentEntity
import com.ians.korno.core.database.entity.GeoPointEntity
import com.ians.korno.core.database.entity.RecognitionSampleEntity
import com.ians.korno.core.database.entity.RoutePointProjection
import kotlinx.coroutines.flow.Flow

@Dao
interface TrackDao {
    @Query("SELECT * FROM activities ORDER BY start_time DESC")
    fun observeActivities(): Flow<List<ActivityEntity>>

    @Query("SELECT * FROM activities WHERE id = :activityId LIMIT 1")
    fun observeActivity(activityId: Long): Flow<ActivityEntity?>

    @Query("SELECT * FROM activities WHERE end_time IS NULL LIMIT 1")
    suspend fun findActiveActivity(): ActivityEntity?

    @Query(
        "SELECT * FROM activities " +
            "WHERE end_time IS NOT NULL AND is_pending_summary = 1 " +
            "ORDER BY start_time ASC",
    )
    fun observePendingSummaryActivities(): Flow<List<ActivityEntity>>

    @Query(
        "SELECT * FROM geo_points " +
            "WHERE activity_id = :activityId " +
            "ORDER BY timestamp ASC, id ASC",
    )
    fun observeGeoPoints(activityId: Long): Flow<List<GeoPointEntity>>

    /**
     * Только координаты и только валидные точки: миниатюре в списке не нужны ни
     * скорость, ни высота, а строк в треке тысячи.
     */
    @Query(
        "SELECT lat, lon FROM geo_points " +
            "WHERE activity_id = :activityId AND is_outlier = 0 " +
            "ORDER BY timestamp ASC, id ASC",
    )
    suspend fun routePoints(activityId: Long): List<RoutePointProjection>

    @Insert
    suspend fun insertActivity(activity: ActivityEntity): Long

    @Update
    suspend fun updateActivity(activity: ActivityEntity): Int

    @Insert
    suspend fun insertGeoPoints(points: List<GeoPointEntity>): List<Long>

    @Query("DELETE FROM geo_points WHERE activity_id = :activityId")
    suspend fun deleteGeoPoints(activityId: Long): Int

    @Query(
        "SELECT * FROM activity_segments " +
            "WHERE activity_id = :activityId " +
            "ORDER BY start_ts ASC, id ASC",
    )
    suspend fun segments(activityId: Long): List<ActivitySegmentEntity>

    /** Открытый сегмент у записи один; адресуется по активности, а не по id. */
    @Query(
        "UPDATE activity_segments SET kind = :kind, start_ts = :startTs, end_ts = :endTs, steps = :steps, " +
            "distance_meters = :distanceMeters, is_manually_edited = :isManuallyEdited " +
            "WHERE activity_id = :activityId AND end_ts IS NULL",
    )
    suspend fun updateOpenSegment(
        activityId: Long,
        kind: String,
        startTs: Long,
        endTs: Long?,
        steps: Int?,
        distanceMeters: Double,
        isManuallyEdited: Boolean,
    ): Int

    /** Граница сегмента во время записи: закрыть старый и открыть новый — одной транзакцией. */
    @Transaction
    suspend fun saveOpenSegment(current: ActivitySegmentEntity, next: ActivitySegmentEntity?) {
        updateOpenSegment(
            activityId = current.activityId,
            kind = current.kind,
            startTs = current.startTimeEpochMillis,
            endTs = current.endTimeEpochMillis,
            steps = current.steps,
            distanceMeters = current.distanceMeters,
            isManuallyEdited = current.isManuallyEdited,
        )
        if (next != null) insertSegments(listOf(next.copy(id = 0L)))
    }

    @Insert
    suspend fun insertSegments(segments: List<ActivitySegmentEntity>): List<Long>

    @Query("DELETE FROM activity_segments WHERE activity_id = :activityId")
    suspend fun deleteSegments(activityId: Long): Int

    /** Активность без сегмента не бывает: оба пишутся одной транзакцией. */
    @Transaction
    suspend fun insertActivityWithSegments(activity: ActivityEntity, segments: List<ActivitySegmentEntity>): Long {
        val activityId = insertActivity(activity)
        insertSegments(segments.map { it.copy(id = 0L, activityId = activityId) })
        return activityId
    }

    @Transaction
    suspend fun insertTrack(
        activity: ActivityEntity,
        points: List<GeoPointEntity>,
        segments: List<ActivitySegmentEntity>,
    ): Long {
        val activityId = insertActivityWithSegments(activity, segments)
        insertGeoPoints(points.map { it.copy(id = 0L, activityId = activityId) })
        return activityId
    }

    /** @param segments `null` — сегменты не трогать. */
    @Transaction
    suspend fun replaceTrack(
        activity: ActivityEntity,
        points: List<GeoPointEntity>,
        segments: List<ActivitySegmentEntity>?,
    ): Boolean {
        if (updateActivity(activity) == 0) return false
        deleteGeoPoints(activity.id)
        insertGeoPoints(points)
        if (segments != null) {
            deleteSegments(activity.id)
            insertSegments(segments.map { it.copy(id = 0L, activityId = activity.id) })
        }
        return true
    }

    @Insert
    suspend fun insertRecognitionSample(sample: RecognitionSampleEntity): Long

    @Query(
        "SELECT * FROM recognition_samples " +
            "WHERE activity_id = :activityId " +
            "ORDER BY timestamp ASC, id ASC",
    )
    suspend fun recognitionSamples(activityId: Long): List<RecognitionSampleEntity>

    @Query("DELETE FROM activities WHERE id = :activityId")
    suspend fun deleteActivity(activityId: Long): Int
}
