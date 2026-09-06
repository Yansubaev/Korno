package com.ians.korno.feature.activitydetail.presentation

internal sealed interface TrackCorrectionAction {
    data class SelectPoint(val id: Long) : TrackCorrectionAction

    data object DeleteSelected : TrackCorrectionAction

    data object Undo : TrackCorrectionAction

    data object Reset : TrackCorrectionAction

    data object Save : TrackCorrectionAction
}
