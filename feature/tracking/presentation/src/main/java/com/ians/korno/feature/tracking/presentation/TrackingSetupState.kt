package com.ians.korno.feature.tracking.presentation

import com.ians.korno.core.domain.model.ActivityType
import com.ians.korno.core.domain.model.AutoPauseSensitivity
import com.ians.korno.core.domain.model.GpsAccuracy

internal data class TrackingSetupState(
    val activityType: ActivityType = ActivityType.RUNNING,
    val autoPause: Boolean = true,
    val gpsAccuracy: GpsAccuracy = GpsAccuracy.BALANCED,
    val autoPauseSensitivity: AutoPauseSensitivity = AutoPauseSensitivity.MEDIUM,
    val started: Boolean = false,
)

internal val SETUP_ACTIVITY_TYPES = listOf(
    ActivityType.RUNNING,
    ActivityType.CYCLING,
    ActivityType.WALKING,
    ActivityType.DRIVING,
)

/** От самой чуткой к самой терпимой: чаще всего нужна именно HIGH, поэтому она первая. */
internal val SETUP_AUTO_PAUSE_SENSITIVITIES = listOf(
    AutoPauseSensitivity.HIGH,
    AutoPauseSensitivity.MEDIUM,
    AutoPauseSensitivity.LOW,
)
