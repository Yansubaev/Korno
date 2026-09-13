package com.ians.korno.feature.activitydetail.presentation

import com.ians.korno.core.domain.model.SegmentKind
import com.ians.korno.core.domain.model.TrackFileFormat

/** Что пользователь собирается сделать с файлом трека после выбора формата. */
internal enum class TrackFileIntent { EXPORT, SHARE }

internal sealed interface ActivityDetailAction {
    data object ShareClicked : ActivityDetailAction

    data object ExportClicked : ActivityDetailAction

    data class SelectFileFormat(val format: TrackFileFormat) : ActivityDetailAction

    data object DismissFormatChoice : ActivityDetailAction

    /** Пользователь выбрал документ в системном диалоге сохранения. */
    data class ExportDestinationChosen(val uri: String) : ActivityDetailAction

    /** Системный диалог закрыт без выбора файла. */
    data object ExportDestinationCancelled : ActivityDetailAction

    data object RequestDelete : ActivityDetailAction

    data object CancelDelete : ActivityDetailAction

    data object ConfirmDelete : ActivityDetailAction

    /** Переключить режим показа маршрута на карте. */
    data class SelectLens(val lens: MapLens) : ActivityDetailAction

    /**
     * Касание графика. Позиция — доля дистанции, 0..1: ось X у всех графиков экрана
     * общая, поэтому карте достаточно одного числа, чтобы найти то же самое место.
     */
    data class SelectTrackPosition(val fraction: Float) : ActivityDetailAction

    data object ClearSelection : ActivityDetailAction

    /** Нажатие на строку сплита: повторное нажатие снимает выделение. */
    data class ToggleSplit(val number: Int) : ActivityDetailAction

    /** Отдать ZIP с логом только этой активности (debug-сборка). */
    data object ShareActivityLogsClicked : ActivityDetailAction

    /** Нажатие на участок: открыть выбор вида. */
    data class SelectSegment(val index: Int) : ActivityDetailAction

    data object DismissSegmentKindChoice : ActivityDetailAction

    /** Новый вид выбранного участка — с пересчётом трека и статистики. */
    data class ChangeSegmentKind(val kind: SegmentKind) : ActivityDetailAction

    data object DismissSnackbar : ActivityDetailAction
}

internal sealed interface ActivityDetailEvent {
    data class PickExportDestination(
        val fileName: String,
        val format: TrackFileFormat,
    ) : ActivityDetailEvent

    data class ShareTrackFile(
        val uri: String,
        val mimeType: String,
    ) : ActivityDetailEvent

    data class ShareLogArchive(
        val uri: String,
        val mimeType: String,
    ) : ActivityDetailEvent
}
