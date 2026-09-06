package com.ians.korno.core.data.mapper

import assertk.assertThat
import assertk.assertions.isEqualTo
import com.ians.korno.core.database.entity.ActivityEntity
import com.ians.korno.core.domain.model.Activity
import com.ians.korno.core.domain.model.ActivityType
import org.junit.jupiter.api.Test

class ActivityMappersTest {
    @Test
    fun `activity survives entity round trip`() {
        val activity = Activity(
            id = 42L,
            startTimeEpochMillis = 1_000L,
            endTimeEpochMillis = 9_000L,
            activityType = ActivityType.CYCLING,
            title = "Morning ride",
            note = "Easy pace",
            distanceMeters = 12_345.0,
            movingTimeMillis = 7_500L,
            elapsedTimeMillis = 8_000L,
            averageSpeedMetersPerSecond = 5.4,
            maxSpeedMetersPerSecond = 11.2,
            elevationGainMeters = 180.0,
            isManuallyEdited = true,
        )

        assertThat(activity.toEntity().toDomain()).isEqualTo(activity)
    }

    @Test
    fun `unknown persisted activity type maps to other`() {
        val entity = ActivityEntity(
            startTimeEpochMillis = 1_000L,
            activityType = "SKIING",
        )

        assertThat(entity.toDomain().activityType).isEqualTo(ActivityType.OTHER)
    }
}
