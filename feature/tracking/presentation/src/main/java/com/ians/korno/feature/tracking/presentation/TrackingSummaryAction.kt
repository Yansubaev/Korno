package com.ians.korno.feature.tracking.presentation

import com.ians.korno.core.domain.model.SegmentKind

internal sealed interface TrackingSummaryAction {
    data class SelectSegmentKind(val kind: SegmentKind) : TrackingSummaryAction

    data class SetTitle(val title: String) : TrackingSummaryAction

    data class SetNote(val note: String) : TrackingSummaryAction

    data object SaveClicked : TrackingSummaryAction

    data object DiscardClicked : TrackingSummaryAction
}
