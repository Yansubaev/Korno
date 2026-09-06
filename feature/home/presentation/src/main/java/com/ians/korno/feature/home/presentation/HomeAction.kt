package com.ians.korno.feature.home.presentation

import com.ians.korno.core.domain.model.ActivityType

internal sealed interface HomeAction {
    data class SelectActivityType(val type: ActivityType) : HomeAction

    data object StartRecordingClicked : HomeAction
}
