package com.ians.korno.feature.tracking.presentation

import com.ians.korno.core.domain.model.SegmentKind

internal sealed interface TrackingRecordingAction {
    data object PauseClicked : TrackingRecordingAction

    data object ResumeClicked : TrackingRecordingAction

    /** Тап по ручке шторки или по кнопке переключения: следующий режим по кругу. */
    data object CycleMapSheetMode : TrackingRecordingAction

    /** Шторку дотащили до ближайшей позиции. */
    data class MapSheetModeChanged(val mode: MapSheetMode) : TrackingRecordingAction

    data object ToggleSegmentMenu : TrackingRecordingAction

    /** Ручная поправка вида текущего сегмента. */
    data class ChangeSegmentKind(val kind: SegmentKind) : TrackingRecordingAction

    data object RequestFinish : TrackingRecordingAction

    data object CancelFinish : TrackingRecordingAction

    data object ConfirmFinish : TrackingRecordingAction
}
