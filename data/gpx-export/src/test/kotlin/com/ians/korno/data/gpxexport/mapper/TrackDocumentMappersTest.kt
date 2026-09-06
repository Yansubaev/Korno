package com.ians.korno.data.gpxexport.mapper

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.hasSize
import assertk.assertions.isCloseTo
import assertk.assertions.isEqualTo
import assertk.assertions.isTrue
import com.ians.korno.core.domain.error.Result
import com.ians.korno.core.domain.model.Activity
import com.ians.korno.core.domain.model.ActivityType
import com.ians.korno.core.domain.model.GeoPoint
import com.ians.korno.core.domain.model.TrackFileFormat
import com.ians.korno.data.gpxexport.TrackFileCodec
import com.ians.korno.data.gpxexport.model.TrackDocument
import com.ians.korno.data.gpxexport.model.TrackDocumentPoint
import org.junit.jupiter.api.Test

class TrackDocumentMappersTest {

    @Test
    fun `export drops outliers and keeps the track in chronological order`() {
        val activity = activity()
        val points = listOf(
            geoPoint(id = 3, timestamp = START + 2_000L),
            geoPoint(id = 1, timestamp = START, isOutlier = true),
            geoPoint(id = 2, timestamp = START + 1_000L),
        )

        val document = trackDocumentOf(activity, points)

        assertThat(document.points.map { it.timestampEpochMillis })
            .containsExactly(START + 1_000L, START + 2_000L)
        assertThat(document.name).isEqualTo("Morning run")
        assertThat(document.summary?.distanceMeters).isEqualTo(5_180.0)
    }

    @Test
    fun `import binds points to the activity and keeps their timestamps`() {
        val document = document(
            TrackDocumentPoint(55.75, 37.61, timestampEpochMillis = START),
            TrackDocumentPoint(55.76, 37.62, timestampEpochMillis = START + 5_000L),
        )

        val points = document.toGeoPoints(activityId = 42L, fallbackStartEpochMillis = 0L)

        assertThat(points.map { it.activityId }).containsExactly(42L, 42L)
        assertThat(points.map { it.timestampEpochMillis })
            .containsExactly(START, START + 5_000L)
    }

    @Test
    fun `points without timestamps are spaced one second apart from the fallback start`() {
        val document = document(
            TrackDocumentPoint(55.75, 37.61, timestampEpochMillis = null),
            TrackDocumentPoint(55.76, 37.62, timestampEpochMillis = null),
            TrackDocumentPoint(55.77, 37.63, timestampEpochMillis = null),
        )

        val points = document.toGeoPoints(activityId = 1L, fallbackStartEpochMillis = START)

        assertThat(points.map { it.timestampEpochMillis })
            .containsExactly(START, START + 1_000L, START + 2_000L)
    }

    @Test
    fun `gaps between known timestamps are filled by interpolation`() {
        val document = document(
            TrackDocumentPoint(55.75, 37.61, timestampEpochMillis = START),
            TrackDocumentPoint(55.76, 37.62, timestampEpochMillis = null),
            TrackDocumentPoint(55.77, 37.63, timestampEpochMillis = null),
            TrackDocumentPoint(55.78, 37.64, timestampEpochMillis = START + 30_000L),
        )

        val points = document.toGeoPoints(activityId = 1L, fallbackStartEpochMillis = 0L)

        assertThat(points.map { it.timestampEpochMillis }).containsExactly(
            START,
            START + 10_000L,
            START + 20_000L,
            START + 30_000L,
        )
    }

    @Test
    fun `timestamps missing at the edges are extrapolated around the known ones`() {
        val document = document(
            TrackDocumentPoint(55.75, 37.61, timestampEpochMillis = null),
            TrackDocumentPoint(55.76, 37.62, timestampEpochMillis = START),
            TrackDocumentPoint(55.77, 37.63, timestampEpochMillis = null),
        )

        val points = document.toGeoPoints(activityId = 1L, fallbackStartEpochMillis = 0L)

        assertThat(points.map { it.timestampEpochMillis })
            .containsExactly(START - 1_000L, START, START + 1_000L)
    }

    @Test
    fun `stop flags survive the trip through a file`() {
        val document = document(
            TrackDocumentPoint(55.75, 37.61, timestampEpochMillis = START, isStop = true),
        )

        val points = document.toGeoPoints(activityId = 1L, fallbackStartEpochMillis = 0L)

        assertThat(points).hasSize(1)
        assertThat(points.single().isStop).isTrue()
    }

    @Test
    fun `a stored track survives a full write and read cycle`() {
        val codec = TrackFileCodec()
        val stored = listOf(
            geoPoint(id = 1, timestamp = START),
            geoPoint(id = 2, timestamp = START + 1_000L),
            geoPoint(id = 3, timestamp = START + 2_000L),
        )

        TrackFileFormat.entries.forEach { format ->
            val written = codec.write(trackDocumentOf(activity(), stored), format)
            val parsed = when (val result = codec.parse(written)) {
                is Result.Success -> result.data
                is Result.Error -> error("Parsing failed: ${result.error}")
            }

            val restored = parsed.toGeoPoints(activityId = 99L, fallbackStartEpochMillis = 0L)

            assertThat(restored.map { it.activityId }).containsExactly(99L, 99L, 99L)
            assertThat(restored.map { it.timestampEpochMillis })
                .containsExactly(START, START + 1_000L, START + 2_000L)
            restored.forEachIndexed { index, point ->
                assertThat(point.latitude).isCloseTo(stored[index].latitude, 1e-7)
                assertThat(point.longitude).isCloseTo(stored[index].longitude, 1e-7)
            }
            assertThat(parsed.activityType).isEqualTo(ActivityType.RUNNING)
            assertThat(parsed.name).isEqualTo("Morning run")
        }
    }

    private fun document(vararg points: TrackDocumentPoint) = TrackDocument(
        name = "Imported",
        activityType = ActivityType.RUNNING,
        points = points.toList(),
    )

    private fun activity() = Activity(
        id = 7L,
        startTimeEpochMillis = START,
        endTimeEpochMillis = START + 1_780_000L,
        activityType = ActivityType.RUNNING,
        title = "Morning run",
        distanceMeters = 5_180.0,
    )

    private fun geoPoint(
        id: Long,
        timestamp: Long,
        isOutlier: Boolean = false,
    ) = GeoPoint(
        id = id,
        activityId = 7L,
        latitude = 55.75,
        longitude = 37.61,
        timestampEpochMillis = timestamp,
        isOutlier = isOutlier,
    )

    private companion object {
        const val START = 1_757_052_000_000L
    }
}
