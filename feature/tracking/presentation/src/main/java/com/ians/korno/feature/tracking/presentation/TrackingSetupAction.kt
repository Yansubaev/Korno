package com.ians.korno.feature.tracking.presentation

import com.ians.korno.core.domain.model.ActivityType
import com.ians.korno.core.domain.model.AutoPauseSensitivity
import com.ians.korno.core.domain.model.GpsAccuracy

internal sealed interface TrackingSetupAction {
    data class SelectActivityType(val type: ActivityType) : TrackingSetupAction

    /** «Авто» выбрано, и распознавание доступно — экран уже проверил. */
    data object SelectAutoMode : TrackingSetupAction

    /** Вернулся ответ на запрос разрешения: доступность проверяется заново. */
    data object AutoModePermissionResult : TrackingSetupAction

    data class SetAutoPause(val enabled: Boolean) : TrackingSetupAction

    data class SelectGpsAccuracy(val accuracy: GpsAccuracy) : TrackingSetupAction

    data class SelectAutoPauseSensitivity(val sensitivity: AutoPauseSensitivity) : TrackingSetupAction

    data object StartClicked : TrackingSetupAction
}
