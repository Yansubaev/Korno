package com.ians.korno.core.map

import assertk.assertThat
import androidx.compose.ui.graphics.Color
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

    @Test
    fun `edges touching a rejected fix form a pale section that outranks pause and gap`() {
        // Отбракованная точка в линии вместо прямой через её место. Её соседи — стоянка и
        // разрыв — про само ребро ничего не говорят: координатам не верят.
        val points = listOf(
            point(0.0),
            point(1.0, isPaused = true),
            point(2.0, isGap = true, isRejected = true),
            point(3.0, isRejected = true),
            point(4.0),
            point(5.0),
        )

        assertThat(points.toRouteSections().map { it.kind to it.points.size }).isEqualTo(
            listOf(EdgeKind.PAUSED to 2, EdgeKind.REJECTED to 4, EdgeKind.NORMAL to 2),
        )
    }

    @Test
    fun `lens shades land on accepted points, skipping rejected and unanalysed ones`() {
        val line = listOf(
            point(0.0),
            point(1.0, isRejected = true),
            point(2.0),
            point(3.0), // другой вид смешанной записи: в разборе его нет
        )
        val analysed = listOf(point(0.0).copy(shade = Color.Red), point(2.0).copy(shade = Color.Green))

        assertThat(line.withShadesFrom(analysed, unmatched = Color.Gray).map(MapPoint::shade))
            .isEqualTo(listOf(Color.Red, null, Color.Green, Color.Gray))
    }

    private fun point(
        latitude: Double,
        isPaused: Boolean = false,
        isGap: Boolean = false,
        isRejected: Boolean = false,
    ) = MapPoint(latitude = latitude, longitude = 0.0, isPaused = isPaused, isGap = isGap, isRejected = isRejected)
}
