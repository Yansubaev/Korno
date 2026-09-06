package com.ians.korno.data.trackio.work

import com.ians.korno.core.domain.error.Result as DomainResult
import com.ians.korno.core.domain.error.TrackFileError
import com.ians.korno.core.domain.model.Activity
import com.ians.korno.core.domain.model.GeoPoint
import com.ians.korno.core.domain.model.TrackFileFormat
import com.ians.korno.core.domain.repository.UserPreferencesRepository
import com.ians.korno.core.trackprocessing.PrivacyZoneUseCase
import com.ians.korno.core.trackprocessing.StatisticsCalculatorUseCase
import com.ians.korno.data.gpxexport.TrackFileCodec
import com.ians.korno.data.gpxexport.mapper.trackDocumentOf
import kotlinx.coroutines.flow.first

/**
 * Превращает сохранённый трек в содержимое файла — единственное место, где
 * применяется приватная зона.
 *
 * Обрезка живёт здесь, а не в кодеке и не в репозитории, потому что она касается
 * ровно того, что покидает приложение: экспорта, массовой выгрузки и отправки.
 * На экранах и в базе трек остаётся целым.
 */
internal class TrackFileWriter(
    private val preferencesRepository: UserPreferencesRepository,
    private val privacyZone: PrivacyZoneUseCase,
    private val statisticsCalculator: StatisticsCalculatorUseCase,
    private val codec: TrackFileCodec,
) {

    /** Точки, которые попадут в файл: трек за вычетом приватной зоны. */
    suspend fun exportablePoints(points: List<GeoPoint>): List<GeoPoint> {
        val radiusMeters = preferencesRepository.observePreferences().first().privacyZone.radiusMeters
        return privacyZone(points, radiusMeters)
    }

    suspend fun write(
        track: LoadedTrack,
        format: TrackFileFormat,
    ): DomainResult<String, TrackFileError> {
        val points = exportablePoints(track.points)
        // Весь трек уложился в приватную зону: писать нечего, и сказать об этом
        // честнее, чем отдать файл с парой точек рядом с домом.
        if (points.isEmpty()) return DomainResult.Error(TrackFileError.NO_TRACK_POINTS)

        val activity = track.activity.withStatisticsFor(points, trimmed = points.size != track.points.size)
        return DomainResult.Success(codec.write(trackDocumentOf(activity, points), format))
    }

    /**
     * После обрезки исходная статистика описывает уже другой трек, поэтому она
     * пересчитывается: иначе файл сообщал бы дистанцию, которой в нём нет.
     */
    private fun Activity.withStatisticsFor(points: List<GeoPoint>, trimmed: Boolean): Activity {
        if (!trimmed) return this
        val statistics = statisticsCalculator(points)
        return copy(
            startTimeEpochMillis = points.first().timestampEpochMillis,
            endTimeEpochMillis = points.last().timestampEpochMillis,
            distanceMeters = statistics.distanceMeters,
            movingTimeMillis = statistics.movingTimeMillis,
            elapsedTimeMillis = statistics.elapsedTimeMillis,
            averageSpeedMetersPerSecond = statistics.averageSpeedMetersPerSecond,
            maxSpeedMetersPerSecond = statistics.maxSpeedMetersPerSecond,
            elevationGainMeters = statistics.elevationGainMeters,
        )
    }
}
