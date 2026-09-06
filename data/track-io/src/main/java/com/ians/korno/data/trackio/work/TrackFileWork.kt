package com.ians.korno.data.trackio.work

import androidx.work.Data
import com.ians.korno.core.domain.error.Result as DomainResult
import com.ians.korno.core.domain.error.TrackFileError
import com.ians.korno.core.domain.model.Activity
import com.ians.korno.core.domain.model.GeoPoint
import com.ians.korno.core.domain.model.TrackFileFormat
import com.ians.korno.core.domain.repository.TrackRepository
import kotlinx.coroutines.flow.first

internal object TrackFileWork {
    const val KEY_ACTIVITY_ID = "activity_id"
    const val KEY_DESTINATION_URI = "destination_uri"
    const val KEY_TREE_URI = "tree_uri"
    const val KEY_SOURCE_URIS = "source_uris"
    const val KEY_FORMAT = "format"

    const val KEY_SUCCESS_COUNT = "success_count"
    const val KEY_FAILURE_COUNT = "failure_count"
    const val KEY_ERROR = "error"

    const val TAG_EXPORT_ACTIVITY = "korno-track-export-activity"
    const val TAG_EXPORT_ALL = "korno-track-export-all"
    const val TAG_IMPORT = "korno-track-import"

    /** Data ограничена 10 КБ, а ссылки на документы длинные — импорт режется на пачки. */
    const val MAX_URIS_PER_REQUEST = 20

    fun outcome(successCount: Int, failureCount: Int, error: TrackFileError? = null): Data =
        Data.Builder()
            .putInt(KEY_SUCCESS_COUNT, successCount)
            .putInt(KEY_FAILURE_COUNT, failureCount)
            .apply { error?.let { putString(KEY_ERROR, it.name) } }
            .build()
}

internal fun trackFileFormatOf(name: String?): TrackFileFormat =
    TrackFileFormat.entries.firstOrNull { it.name == name } ?: TrackFileFormat.GPX

internal data class LoadedTrack(
    val activity: Activity,
    val points: List<GeoPoint>,
)

/**
 * Разовое чтение активности вместе с треком. Репозиторий реактивен, а работнику нужен
 * снимок: [first] берёт текущее содержимое таблиц и отпускает подписку.
 */
internal suspend fun TrackRepository.loadTrack(
    activityId: Long,
): DomainResult<LoadedTrack, TrackFileError> {
    val activity = when (val result = observeActivity(activityId).first()) {
        is DomainResult.Success -> result.data
        is DomainResult.Error -> return DomainResult.Error(TrackFileError.NOT_FOUND)
    }
    val points = when (val result = observeGeoPoints(activityId).first()) {
        is DomainResult.Success -> result.data
        is DomainResult.Error -> return DomainResult.Error(TrackFileError.STORAGE_FAILURE)
    }
    if (points.isEmpty()) return DomainResult.Error(TrackFileError.NO_TRACK_POINTS)
    return DomainResult.Success(LoadedTrack(activity, points))
}
