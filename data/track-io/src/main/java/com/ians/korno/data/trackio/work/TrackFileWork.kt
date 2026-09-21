package com.ians.korno.data.trackio.work

import androidx.work.CoroutineWorker
import androidx.work.Data
import com.ians.korno.core.domain.error.Result as DomainResult
import com.ians.korno.core.domain.error.TrackFileError
import com.ians.korno.core.domain.model.Activity
import com.ians.korno.core.domain.model.GeoPoint
import com.ians.korno.core.domain.model.SegmentKind
import com.ians.korno.core.domain.model.dominantKind
import com.ians.korno.core.domain.model.TrackFileFormat
import com.ians.korno.core.domain.repository.TrackRepository
import com.ians.korno.core.logging.AppLogger
import com.ians.korno.core.logging.LogCategory
import com.ians.korno.core.logging.warn
import kotlin.coroutines.cancellation.CancellationException
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

    /** Сколько файлов уже обработали предыдущие пачки этого же импорта. */
    const val KEY_PROGRESS_OFFSET = "progress_offset"

    /** Сколько файлов в импорте целиком — чтобы уведомление считало не по пачке. */
    const val KEY_PROGRESS_TOTAL = "progress_total"

    /**
     * Начиная с этого количества элементов операция считается долгой и получает
     * уведомление. Один файл уходит быстрее, чем уведомление успевает прочитаться.
     */
    const val PROGRESS_NOTIFICATION_THRESHOLD = 2

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
    /** Лицо записи — её доминирующий вид. Формат описывает один вид спорта на файл. */
    val kind: SegmentKind,
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
    val segments = (getSegments(activityId) as? DomainResult.Success)?.data.orEmpty()
    return DomainResult.Success(LoadedTrack(activity, points, segments.dominantKind() ?: SegmentKind.UNKNOWN))
}

/**
 * Показывает прогресс уведомлением, если операция достаточно длинная.
 *
 * Перевод в foreground — вежливость, а не часть работы: Android 12+ вправе его
 * запретить (например, когда приложение уже ушло в фон), и отказ не должен ронять
 * саму выгрузку. Экранный индикатор в этом случае остаётся единственным.
 */
internal suspend fun CoroutineWorker.reportProgress(
    notifier: TrackFileNotifier,
    operation: TrackFileNotification,
    completed: Int,
    total: Int,
    logger: AppLogger,
) {
    if (total < TrackFileWork.PROGRESS_NOTIFICATION_THRESHOLD) return
    try {
        setForeground(notifier.progress(operation, completed, total))
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Exception) {
        logger.warn(
            category = LogCategory.EXPORT,
            tag = "TrackFileWork",
            throwable = error,
        ) { "Foreground progress notification rejected" }
    }
}
