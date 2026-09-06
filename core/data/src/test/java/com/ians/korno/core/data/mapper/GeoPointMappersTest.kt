package com.ians.korno.core.data.mapper

import assertk.assertThat
import assertk.assertions.isEqualTo
import com.ians.korno.core.domain.model.GeoPoint
import com.ians.korno.core.domain.model.RecordingStatus
import org.junit.jupiter.api.Test

class GeoPointMappersTest {
    @Test
    fun `geo point survives entity round trip`() {
        val point = GeoPoint(
            id = 7L,
            activityId = 42L,
            latitude = 43.238949,
            longitude = 76.889709,
            altitudeMeters = 830.5,
            timestampEpochMillis = 1_750_000_000_000L,
            accuracyMeters = 4.2f,
            speedMetersPerSecond = 3.1f,
            recordingStatus = RecordingStatus.MANUAL_PAUSE,
            isStop = true,
            isOutlier = false,
            isManuallyEdited = true,
        )

        assertThat(point.toEntity().toDomain()).isEqualTo(point)
    }
}
