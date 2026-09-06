package com.ians.korno.feature.activitylist.domain.usecase

import com.ians.korno.core.domain.model.TrackFileOperation
import com.ians.korno.core.domain.repository.TrackFileRepository
import kotlinx.coroutines.flow.Flow

class ImportTracksUseCase(
    private val trackFileRepository: TrackFileRepository,
) {
    operator fun invoke(sourceUris: List<String>) {
        trackFileRepository.importTracks(sourceUris)
    }
}

class ObserveTrackImportUseCase(
    private val trackFileRepository: TrackFileRepository,
) {
    operator fun invoke(): Flow<TrackFileOperation> = trackFileRepository.observeImport()
}
