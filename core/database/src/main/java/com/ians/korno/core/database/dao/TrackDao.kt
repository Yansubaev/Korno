package com.ians.korno.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.ians.korno.core.database.entity.ActivityEntity
import com.ians.korno.core.database.entity.GeoPointEntity
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

    @Transaction
    suspend fun insertTrack(activity: ActivityEntity, points: List<GeoPointEntity>): Long {
        val activityId = insertActivity(activity)
        insertGeoPoints(points.map { it.copy(id = 0L, activityId = activityId) })
        return activityId
    }

    @Transaction
    suspend fun replaceTrack(activity: ActivityEntity, points: List<GeoPointEntity>): Boolean {
        if (updateActivity(activity) == 0) return false
        deleteGeoPoints(activity.id)
        insertGeoPoints(points)
        return true
    }

    @Query("DELETE FROM activities WHERE id = :activityId")
    suspend fun deleteActivity(activityId: Long): Int
}
