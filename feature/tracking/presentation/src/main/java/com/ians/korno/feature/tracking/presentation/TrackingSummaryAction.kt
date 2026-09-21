package com.ians.korno.feature.tracking.presentation

internal sealed interface TrackingSummaryAction {
    data class SetTitle(val title: String) : TrackingSummaryAction

    data class SetNote(val note: String) : TrackingSummaryAction

    data object SaveClicked : TrackingSummaryAction

    data object DiscardClicked : TrackingSummaryAction
}
