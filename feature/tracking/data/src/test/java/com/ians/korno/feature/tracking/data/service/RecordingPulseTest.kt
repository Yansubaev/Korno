package com.ians.korno.feature.tracking.data.service

import assertk.assertThat
import assertk.assertions.isEqualTo
import org.junit.jupiter.api.Test

class RecordingPulseTest {

    @Test
    fun `snapshot reports growth since the previous one and running totals`() {
        val pulse = RecordingPulse()
        repeat(3) { pulse.locationSamples.incrementAndGet() }
        pulse.snapshot()
        pulse.locationSamples.incrementAndGet()

        val fields = pulse.snapshot()

        assertThat(fields["locationSamples"]).isEqualTo("1")
        assertThat(fields["locationSamplesTotal"]).isEqualTo("4")
        assertThat(fields["recognitionBroadcasts"]).isEqualTo("0")
        assertThat(fields["recognitionBroadcastsTotal"]).isEqualTo("0")
    }
}
