package com.ians.korno.feature.tracking.presentation

internal sealed interface TrackingRecordingAction {
    data object PauseClicked : TrackingRecordingAction

    data object ResumeClicked : TrackingRecordingAction

    /** Тап по ручке шторки или по кнопке переключения: следующий режим по кругу. */
    data object CycleMapSheetMode : TrackingRecordingAction

    /** Шторку дотащили до ближайшей позиции. */
    data class MapSheetModeChanged(val mode: MapSheetMode) : TrackingRecordingAction

    data object RequestFinish : TrackingRecordingAction

    data object CancelFinish : TrackingRecordingAction

    data object ConfirmFinish : TrackingRecordingAction
}
