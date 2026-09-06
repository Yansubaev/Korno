package com.ians.korno.core.trackprocessing

import assertk.assertThat
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import com.ians.korno.core.domain.model.GeoPoint
import org.junit.jupiter.api.Test

class PrivacyZoneUseCaseTest {

    private val privacyZone = PrivacyZoneUseCase()

    @Test
    fun `a disabled zone leaves the track untouched`() {
        val points = straightTrack(count = 10, stepMeters = 50.0)

        assertThat(privacyZone(points, radiusMeters = 0)).isEqualTo(points)
    }

    @Test
    fun `points within the radius are cut from both ends`() {
        // Шаг 50 м, десять точек: радиус 120 м убирает по три точки с каждой стороны.
        val points = straightTrack(count = 10, stepMeters = 50.0)

        val trimmed = privacyZone(points, radiusMeters = 120)

        assertThat(trimmed).hasSize(4)
        assertThat(trimmed.first().id).isEqualTo(3L)
        assertThat(trimmed.last().id).isEqualTo(6L)
    }

    @Test
    fun `a track shorter than the zone yields nothing`() {
        val points = straightTrack(count = 4, stepMeters = 20.0)

        assertThat(privacyZone(points, radiusMeters = 500)).isEmpty()
    }

    @Test
    fun `a loop track is cut around its shared start and finish`() {
        // Кольцо: финиш возвращается к старту, поэтому обе зоны накрывают один и тот же угол.
        val out = straightTrack(count = 6, stepMeters = 100.0)
        val back = out.reversed().drop(1).mapIndexed { index, point ->
            point.copy(id = 6L + index, timestampEpochMillis = point.timestampEpochMillis + 600_000L)
        }
        val points = out + back

        val trimmed = privacyZone(points, radiusMeters = 150)

        assertThat(trimmed.first().id).isEqualTo(2L)
        assertThat(trimmed.last().id).isEqualTo(8L)
    }

    @Test
    fun `a track of a single point is returned as is`() {
        val points = straightTrack(count = 1, stepMeters = 0.0)

        assertThat(privacyZone(points, radiusMeters = 500)).isEqualTo(points)
    }

    /** Точки по меридиану: один градус широты — примерно 111 320 м. */
    private fun straightTrack(count: Int, stepMeters: Double): List<GeoPoint> {
        val degreesPerMeter = 1.0 / 111_320.0
        return List(count) { index ->
            GeoPoint(
                id = index.toLong(),
                activityId = 1L,
                latitude = 55.75 + index * stepMeters * degreesPerMeter,
                longitude = 37.61,
                timestampEpochMillis = 1_757_052_000_000L + index * 10_000L,
            )
        }
    }
}
