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
                RouteSection(points = listOf(point(0.0), point(1.0)), kind = EdgeKind.NORMAL),
                RouteSection(
                    points = listOf(
                        point(1.0),
                        point(2.0, isPaused = true),
                        point(3.0, isPaused = true),
                        point(4.0),
                    ),
                    kind = EdgeKind.PAUSED,
                ),
                RouteSection(points = listOf(point(4.0), point(5.0)), kind = EdgeKind.NORMAL),
            ),
        )
    }

    @Test
    fun `route without pauses remains one normal section`() {
        val points = listOf(point(0.0), point(1.0), point(2.0))

        assertThat(points.toRouteSections()).isEqualTo(
            listOf(RouteSection(points = points, kind = EdgeKind.NORMAL)),
        )
    }

    @Test
    fun `an unobserved edge is its own section, apart from pauses`() {
        // Пауза и разрыв рисуются одним пунктиром, но разными цветами, и попасть в один
        // и тот же источник не должны: иначе разрыв красится как пауза.
        val points = listOf(point(0.0), point(1.0), point(2.0, isGap = true), point(3.0))

        assertThat(points.toRouteSections()).isEqualTo(
            listOf(
                RouteSection(points = listOf(point(0.0), point(1.0)), kind = EdgeKind.NORMAL),
                RouteSection(
                    points = listOf(point(1.0), point(2.0, isGap = true)),
                    kind = EdgeKind.GAP,
                ),
                RouteSection(points = listOf(point(2.0, isGap = true), point(3.0)), kind = EdgeKind.NORMAL),
            ),
        )
    }

    @Test
    fun `a pause that also lost the signal stays a pause`() {
        // Пауза объясняет, почему точек нет; разрыв её не отменяет.
        val points = listOf(point(0.0, isPaused = true), point(1.0, isPaused = true, isGap = true))

        assertThat(points.toRouteSections()).isEqualTo(
            listOf(RouteSection(points = points, kind = EdgeKind.PAUSED)),
        )
    }

    private fun point(latitude: Double, isPaused: Boolean = false, isGap: Boolean = false) =
        MapPoint(latitude = latitude, longitude = 0.0, isPaused = isPaused, isGap = isGap)
}
