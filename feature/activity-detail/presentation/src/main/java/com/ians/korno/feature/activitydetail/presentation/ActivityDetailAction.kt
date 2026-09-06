package com.ians.korno.feature.activitydetail.presentation

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

    /** Отдать ZIP с логом только этой активности (debug-сборка). */
    data object ShareActivityLogsClicked : ActivityDetailAction

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
