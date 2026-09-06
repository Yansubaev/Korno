package com.ians.korno.core.map

import assertk.assertThat
import assertk.assertions.isEqualTo
import org.junit.jupiter.api.Test

class RouteSectionsTest {

    @Test
    fun `edges touching paused points form a separate paused section`() {
        val points = listOf(
            point(0.0),
            point(1.0),
            point(2.0, isPaused = true),
            point(3.0, isPaused = true),
            point(4.0),
            point(5.0),
        )

        assertThat(points.toRouteSections()).isEqualTo(
            listOf(
                RouteSection(points = listOf(point(0.0), point(1.0)), isPaused = false),
                RouteSection(
                    points = listOf(
                        point(1.0),
                        point(2.0, isPaused = true),
                        point(3.0, isPaused = true),
                        point(4.0),
                    ),
                    isPaused = true,
                ),
                RouteSection(points = listOf(point(4.0), point(5.0)), isPaused = false),
            ),
        )
    }

    @Test
    fun `route without pauses remains one normal section`() {
        val points = listOf(point(0.0), point(1.0), point(2.0))

        assertThat(points.toRouteSections()).isEqualTo(
            listOf(RouteSection(points = points, isPaused = false)),
        )
    }

    private fun point(latitude: Double, isPaused: Boolean = false) =
        MapPoint(latitude = latitude, longitude = 0.0, isPaused = isPaused)
}
