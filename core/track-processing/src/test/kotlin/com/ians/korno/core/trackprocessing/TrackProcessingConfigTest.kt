package com.ians.korno.core.trackprocessing

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThan
import com.ians.korno.core.domain.model.SegmentKind
import com.ians.korno.core.domain.model.AutoPauseSensitivity
import org.junit.jupiter.api.Test

class TrackProcessingConfigTest {

    @Test
    fun `sensitivity windows for a fifteen second base match the previous fixed values`() {
        // withAutoPauseSensitivity масштабирует окна от базового вместо абсолютных чисел
        // (нужно хайкингу с более широким базовым окном). Для базы 15000/10000, на которой
        // сидят все виды, кроме хайкинга, результат обязан совпасть со старыми
        // константами 25000/20000 и 10000/5000.
        val base = TrackProcessingConfig.forSegmentKind(SegmentKind.ON_FOOT)
        assertThat(base.stopWindowMillis).isEqualTo(15_000L)
        assertThat(base.minimumStopDurationMillis).isEqualTo(10_000L)

        val low = base.withAutoPauseSensitivity(AutoPauseSensitivity.LOW)
        assertThat(low.stopWindowMillis).isEqualTo(25_000L)
        assertThat(low.minimumStopDurationMillis).isEqualTo(20_000L)

        val high = base.withAutoPauseSensitivity(AutoPauseSensitivity.HIGH)
        assertThat(high.stopWindowMillis).isEqualTo(10_000L)
        assertThat(high.minimumStopDurationMillis).isEqualTo(5_000L)
    }

    @Test
    fun `hiking keeps a wider stop window than walking at every sensitivity`() {
        val walking = TrackProcessingConfig.forSegmentKind(SegmentKind.ON_FOOT)
        val hiking = TrackProcessingConfig.forSegmentKind(SegmentKind.HIKING)

        AutoPauseSensitivity.entries.forEach { sensitivity ->
            val walkingWindow = walking.withAutoPauseSensitivity(sensitivity).stopWindowMillis
            val hikingWindow = hiking.withAutoPauseSensitivity(sensitivity).stopWindowMillis
            assertThat(hikingWindow).isGreaterThan(walkingWindow)
        }
    }
}
