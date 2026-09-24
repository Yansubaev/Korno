package com.ians.korno.core.data.local

import app.cash.turbine.test
import assertk.assertThat
import assertk.assertions.isEqualTo
import com.ians.korno.core.database.dao.TrackDao
import com.ians.korno.core.database.entity.RecognitionSampleEntity
import com.ians.korno.core.database.entity.StepSampleEntity
import com.ians.korno.core.database.entity.ActivityEntity
import com.ians.korno.core.database.entity.ActivitySegmentEntity
import com.ians.korno.core.database.entity.GeoPointEntity
import com.ians.korno.core.database.entity.RoutePointProjection
import com.ians.korno.core.domain.error.DataError
import com.ians.korno.core.domain.error.Result
import com.ians.korno.core.domain.model.Activity
import com.ians.korno.core.domain.model.GeoPoint
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class RoomTrackLocalDataSourceTest {
    private val dao = FakeTrackDao()
    private val dataSource = RoomTrackLocalDataSource(dao)

    @Test
    fun `observed entities are mapped to domain models`() = runTest {
        dao.activities.value = listOf(
            ActivityEntity(
                id = 8L,
                startTimeEpochMillis = 1_000L,
            ),
        )

        dataSource.observeActivities().test {
            assertThat(awaitItem()).isEqualTo(
                Result.Success(
                    listOf(
                        Activity(
                            id = 8L,
                            startTimeEpochMillis = 1_000L,
                        ),
                    ),
                ),
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `missing activity produces typed not found error`() = runTest {
        dataSource.observeActivity(activityId = 404L).test {
            assertThat(awaitItem()).isEqualTo(Result.Error(DataError.Local.NOT_FOUND))
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `update of missing activity produces typed not found error`() = runTest {
        dao.updatedRows = 0

        val result = dataSource.updateActivity(
            Activity(
                id = 404L,
                startTimeEpochMillis = 1_000L,
            ),
        )

        assertThat(result).isEqualTo(Result.Error(DataError.Local.NOT_FOUND))
    }

    @Test
    fun `replace track updates activity and replaces its points`() = runTest {
        val activity = Activity(
            id = 8L,
            startTimeEpochMillis = 1_000L,
        )
        val point = GeoPoint(
            activityId = 8L,
            latitude = 43.0,
            longitude = 76.0,
            timestampEpochMillis = 1_000L,
        )

        val result = dataSource.replaceTrack(activity, listOf(point), segments = null)

        assertThat(result).isEqualTo(Result.Success(Unit))
        assertThat(dao.deletedGeoPointsForActivity).isEqualTo(8L)
        assertThat(dao.insertedGeoPoints.size).isEqualTo(1)
    }

    private class FakeTrackDao : TrackDao {
        val activities = MutableStateFlow<List<ActivityEntity>>(emptyList())
        val pendingActivities = MutableStateFlow<List<ActivityEntity>>(emptyList())
        private val activity = MutableStateFlow<ActivityEntity?>(null)
        private val points = MutableStateFlow<List<GeoPointEntity>>(emptyList())
        var updatedRows: Int = 1
        var deletedGeoPointsForActivity: Long? = null
        val insertedGeoPoints = mutableListOf<GeoPointEntity>()

        override fun observeActivities(): Flow<List<ActivityEntity>> = activities

        override fun observeActivity(activityId: Long): Flow<ActivityEntity?> = activity

        override fun observeGeoPoints(activityId: Long): Flow<List<GeoPointEntity>> = points

        override suspend fun findActiveActivity(): ActivityEntity? = null

        override fun observePendingSummaryActivities(): Flow<List<ActivityEntity>> = pendingActivities

        override suspend fun routePoints(activityId: Long): List<RoutePointProjection> =
            points.value.filter { it.activityId == activityId && !it.isOutlier }
                .sortedWith(compareBy({ it.timestamp }, { it.id }))
                .map { RoutePointProjection(latitude = it.latitude, longitude = it.longitude) }

        override suspend fun insertActivity(activity: ActivityEntity): Long = 1L

        override suspend fun updateActivity(activity: ActivityEntity): Int = updatedRows

        override suspend fun insertGeoPoints(points: List<GeoPointEntity>): List<Long> {
            insertedGeoPoints += points
            return points.indices.map { it.toLong() + 1L }
        }

        override suspend fun deleteGeoPoints(activityId: Long): Int {
            deletedGeoPointsForActivity = activityId
            return 1
        }

        override suspend fun insertSegments(segments: List<ActivitySegmentEntity>): List<Long> =
            segments.indices.map { it.toLong() + 1L }

        override suspend fun deleteSegments(activityId: Long): Int = 1

        override suspend fun segments(activityId: Long): List<ActivitySegmentEntity> = emptyList()

        override fun observeAllSegments(): Flow<List<ActivitySegmentEntity>> = MutableStateFlow(emptyList())

        override suspend fun updateOpenSegment(
            activityId: Long,
            kind: String,
            startTs: Long,
            endTs: Long?,
            steps: Int?,
            distanceMeters: Double,
            movingTimeMillis: Long?,
            isManuallyEdited: Boolean,
        ): Int = 1

        override suspend fun insertRecognitionSample(sample: RecognitionSampleEntity): Long = 1L

        override suspend fun recognitionSamples(activityId: Long): List<RecognitionSampleEntity> = emptyList()

        override suspend fun insertStepSample(sample: StepSampleEntity): Long = 1L

        override suspend fun stepSamples(activityId: Long): List<StepSampleEntity> = emptyList()

        override suspend fun deleteActivity(activityId: Long): Int = updatedRows
    }
}
