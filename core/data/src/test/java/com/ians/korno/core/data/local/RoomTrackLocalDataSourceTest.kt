package com.ians.korno.core.data.local

import app.cash.turbine.test
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isSameInstanceAs
import com.ians.korno.core.database.dao.TrackDao
import com.ians.korno.core.database.entity.RecognitionSampleEntity
import com.ians.korno.core.database.entity.StepSampleEntity
import com.ians.korno.core.database.entity.ActivityEntity
import com.ians.korno.core.database.entity.ActivitySegmentEntity
import com.ians.korno.core.database.entity.GeoPointEntity
import com.ians.korno.core.database.entity.RoutePointProjection
import com.ians.korno.core.database.entity.RoutePreviewEntity
import com.ians.korno.core.domain.error.DataError
import com.ians.korno.core.domain.error.Result
import com.ians.korno.core.domain.model.Activity
import com.ians.korno.core.domain.model.GeoPoint
import com.ians.korno.core.domain.model.RoutePoint
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
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

    @Test
    fun `replace track stores a preview without outliers in time order`() = runTest {
        val points = listOf(
            GeoPoint(activityId = 8L, latitude = 3.0, longitude = 3.0, timestampEpochMillis = 3_000L),
            GeoPoint(activityId = 8L, latitude = 9.0, longitude = 9.0, timestampEpochMillis = 2_000L, isOutlier = true),
            GeoPoint(activityId = 8L, latitude = 1.0, longitude = 1.0, timestampEpochMillis = 1_000L),
        )

        dataSource.replaceTrack(Activity(id = 8L, startTimeEpochMillis = 1_000L), points, segments = null)

        assertThat(dao.previews.getValue(8L).decodeRoutePreview()).containsExactly(
            RoutePoint(1.0, 1.0),
            RoutePoint(3.0, 3.0),
        )
    }

    @Test
    fun `a long track is stored as a fixed number of preview points`() = runTest {
        val points = (0 until 1_000).map {
            GeoPoint(activityId = 8L, latitude = it.toDouble(), longitude = 0.0, timestampEpochMillis = it.toLong())
        }

        dataSource.insertTrack(Activity(startTimeEpochMillis = 0L), points, segments = emptyList())

        assertThat(dao.previews.getValue(1L).decodeRoutePreview()).hasSize(ROUTE_PREVIEW_POINTS)
    }

    @Test
    fun `backfill fills only missing previews and keeps existing ones`() = runTest {
        dao.idsWithoutPreview = listOf(5L, 6L)
        dao.setPoints(
            GeoPointEntity(activityId = 5L, latitude = 1.0, longitude = 2.0, timestamp = 1L),
            GeoPointEntity(activityId = 5L, latitude = 3.0, longitude = 4.0, timestamp = 2L),
        )
        // Трек 6 успели пересохранить, пока шло дозаполнение: его свежее превью не трогаем.
        val fresh = listOf(RoutePoint(7.0, 7.0)).encodeRoutePreview()
        dao.previews[6L] = fresh

        dataSource.backfillRoutePreviews()

        assertThat(dao.previews.getValue(5L).decodeRoutePreview()).containsExactly(RoutePoint(1.0, 2.0), RoutePoint(3.0, 4.0))
        assertThat(dao.previews.getValue(6L)).isSameInstanceAs(fresh)
    }

    @Test
    fun `a track without points gets an empty preview so backfill does not retry it`() = runTest {
        dao.idsWithoutPreview = listOf(5L)

        dataSource.backfillRoutePreviews()

        assertThat(dao.previews.getValue(5L).decodeRoutePreview()).isEmpty()
    }

    @Test
    fun `observed previews are decoded per activity`() = runTest {
        dao.previews[5L] = listOf(RoutePoint(55.75, 37.61)).encodeRoutePreview()

        dataSource.observeRoutePreviews().test {
            assertThat(awaitItem()).isEqualTo(Result.Success(mapOf(5L to listOf(RoutePoint(55.75, 37.61)))))
            cancelAndIgnoreRemainingEvents()
        }
    }

    private class FakeTrackDao : TrackDao {
        val activities = MutableStateFlow<List<ActivityEntity>>(emptyList())
        val pendingActivities = MutableStateFlow<List<ActivityEntity>>(emptyList())
        private val activity = MutableStateFlow<ActivityEntity?>(null)
        private val points = MutableStateFlow<List<GeoPointEntity>>(emptyList())
        var updatedRows: Int = 1
        var deletedGeoPointsForActivity: Long? = null
        val insertedGeoPoints = mutableListOf<GeoPointEntity>()
        val previews = mutableMapOf<Long, ByteArray>()
        var idsWithoutPreview: List<Long> = emptyList()

        fun setPoints(vararg entities: GeoPointEntity) {
            points.value = entities.toList()
        }

        override fun observeRoutePreviews(): Flow<List<RoutePreviewEntity>> =
            flowOf(previews.map { (id, bytes) -> RoutePreviewEntity(id, bytes) })

        override suspend fun activityIdsWithoutRoutePreview(): List<Long> = idsWithoutPreview

        override suspend fun upsertRoutePreview(preview: RoutePreviewEntity) {
            previews[preview.activityId] = preview.points
        }

        override suspend fun insertRoutePreviewIfAbsent(preview: RoutePreviewEntity) {
            previews.putIfAbsent(preview.activityId, preview.points)
        }

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
