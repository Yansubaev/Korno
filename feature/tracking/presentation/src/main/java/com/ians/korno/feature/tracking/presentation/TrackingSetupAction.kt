package com.ians.korno.feature.tracking.presentation

import com.ians.korno.core.domain.model.ActivityType
import com.ians.korno.core.domain.model.AutoPauseSensitivity
import com.ians.korno.core.domain.model.GpsAccuracy

internal sealed interface TrackingSetupAction {
    data class SelectActivityType(val type: ActivityType) : TrackingSetupAction

    data class SetAutoPause(val enabled: Boolean) : TrackingSetupAction

    data class SelectGpsAccuracy(val accuracy: GpsAccuracy) : TrackingSetupAction

    data class SelectAutoPauseSensitivity(val sensitivity: AutoPauseSensitivity) : TrackingSetupAction

    data object StartClicked : TrackingSetupAction
}
