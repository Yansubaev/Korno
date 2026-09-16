package com.ians.korno.feature.home.presentation

internal sealed interface HomeAction {
    data object StartRecordingClicked : HomeAction
}
