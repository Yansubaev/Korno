package com.ians.korno.feature.settings.domain.usecase

import com.ians.korno.core.domain.model.TrackFileFormat
import com.ians.korno.core.domain.model.TrackFileOperation
import com.ians.korno.core.domain.repository.TrackFileRepository
import kotlinx.coroutines.flow.Flow

class ExportAllActivitiesUseCase(
    private val trackFileRepository: TrackFileRepository,
) {
    operator fun invoke(destinationTreeUri: String, format: TrackFileFormat) {
        trackFileRepository.exportAllActivities(destinationTreeUri, format)
    }
}

class ObserveTrackExportUseCase(
    private val trackFileRepository: TrackFileRepository,
) {
    operator fun invoke(): Flow<TrackFileOperation> = trackFileRepository.observeBulkExport()
}
