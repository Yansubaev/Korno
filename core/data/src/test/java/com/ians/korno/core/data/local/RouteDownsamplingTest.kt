package com.ians.korno.core.data.local

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class RouteDownsamplingTest {

    @Test
    fun `a track shorter than the limit is returned unchanged`() {
        val points = (1..10).toList()

        assertThat(points.downsampledTo(maxPoints = 64)).isEqualTo(points)
    }

    @Test
    fun `the first and last points always survive`() {
        val points = (1..1_000).toList()

        val sampled = points.downsampledTo(maxPoints = 64)

        assertThat(sampled).hasSize(64)
        assertThat(sampled.first()).isEqualTo(1)
        assertThat(sampled.last()).isEqualTo(1_000)
    }

    @Test
    fun `sampling keeps the original order and steps evenly`() {
        val points = (0..8).toList()

        assertThat(points.downsampledTo(maxPoints = 5)).containsExactly(0, 2, 4, 6, 8)
    }

    @ParameterizedTest
    @ValueSource(ints = [2, 3, 17, 64, 999])
    fun `the result never exceeds the limit`(limit: Int) {
        val points = (1..5_000).toList()

        assertThat(points.downsampledTo(limit)).hasSize(limit)
    }
}
