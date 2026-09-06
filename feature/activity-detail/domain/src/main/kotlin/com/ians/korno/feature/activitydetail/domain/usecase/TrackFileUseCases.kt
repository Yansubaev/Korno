package com.ians.korno.feature.activitydetail.domain.usecase

import com.ians.korno.core.domain.error.Result
import com.ians.korno.core.domain.error.TrackFileError
import com.ians.korno.core.domain.model.SharedTrackFile
import com.ians.korno.core.domain.model.TrackFileFormat
import com.ians.korno.core.domain.model.TrackFileOperation
import com.ians.korno.core.domain.repository.TrackFileRepository
import kotlinx.coroutines.flow.Flow

class ExportActivityUseCase(
    private val trackFileRepository: TrackFileRepository,
) {
    operator fun invoke(activityId: Long, destinationUri: String, format: TrackFileFormat) {
        trackFileRepository.exportActivity(activityId, destinationUri, format)
    }
}

/** Проверяет, что экспорт возможен, и отдаёт имя файла для системного диалога. */
class PrepareActivityExportUseCase(
    private val trackFileRepository: TrackFileRepository,
) {
    suspend operator fun invoke(
        activityId: Long,
        format: TrackFileFormat,
    ): Result<String, TrackFileError> = trackFileRepository.prepareExport(activityId, format)
}

class PrepareActivityShareUseCase(
    private val trackFileRepository: TrackFileRepository,
) {
    suspend operator fun invoke(
        activityId: Long,
        format: TrackFileFormat,
    ): Result<SharedTrackFile, TrackFileError> = trackFileRepository.prepareShare(activityId, format)
}

class ObserveTrackExportUseCase(
    private val trackFileRepository: TrackFileRepository,
) {
    operator fun invoke(): Flow<TrackFileOperation> = trackFileRepository.observeActivityExport()
}
