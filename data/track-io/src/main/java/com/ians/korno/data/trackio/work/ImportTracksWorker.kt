package com.ians.korno.data.trackio.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.ians.korno.core.domain.error.Result as DomainResult
import com.ians.korno.core.domain.error.TrackFileError
import com.ians.korno.core.domain.model.Activity
import com.ians.korno.core.domain.model.GeoPoint
import com.ians.korno.core.domain.repository.TrackRepository
import com.ians.korno.core.logging.AppLogger
import com.ians.korno.core.logging.LogCategory
import com.ians.korno.core.logging.warn
import com.ians.korno.core.trackprocessing.TrackProcessor
import com.ians.korno.core.trackprocessing.TrackStatistics
import com.ians.korno.data.gpxexport.TrackFileCodec
import com.ians.korno.data.gpxexport.mapper.toGeoPoints
import com.ians.korno.data.gpxexport.model.TrackDocument
import com.ians.korno.data.trackio.io.DocumentStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * Импорт выбранных файлов. Каждый файл обрабатывается независимо: битый файл в пачке
 * не отменяет остальные, а попадает в счётчик неудач.
 */
internal class ImportTracksWorker(
    context: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(context, parameters), KoinComponent {

    private val trackRepository: TrackRepository by inject()
    private val documentStore: DocumentStore by inject()
    private val codec: TrackFileCodec by inject()
    private val trackProcessor: TrackProcessor by inject()
    private val logger: AppLogger by inject()

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val sourceUris = inputData.getStringArray(TrackFileWork.KEY_SOURCE_URIS).orEmpty()
        if (sourceUris.isEmpty()) {
            return@withContext Result.failure(
                TrackFileWork.outcome(0, failureCount = 1, error = TrackFileError.UNKNOWN),
            )
        }

        var successCount = 0
        var failureCount = 0
        var lastError: TrackFileError? = null

        sourceUris.forEach { uri ->
            when (val result = importDocument(uri)) {
                is DomainResult.Success -> successCount++
                is DomainResult.Error -> {
                    failureCount++
                    lastError = result.error
                    logger.warn(
                        category = LogCategory.EXPORT,
                        tag = TAG,
                        fields = mapOf("error" to result.error.name),
                    ) { "Track import failed" }
                }
            }
        }

        if (successCount == 0) {
            Result.failure(
                TrackFileWork.outcome(0, failureCount, lastError ?: TrackFileError.UNKNOWN),
            )
        } else {
            Result.success(TrackFileWork.outcome(successCount, failureCount))
        }
    }

    private suspend fun importDocument(uri: String): DomainResult<Long, TrackFileError> {
        val content = when (val result = documentStore.readText(uri)) {
            is DomainResult.Success -> result.data
            is DomainResult.Error -> return DomainResult.Error(result.error)
        }

        val fileName = documentStore.displayName(uri)
        val document = when (
            val result = codec.parse(content, codec.detectFormat(content, fileName))
        ) {
            is DomainResult.Success -> result.data
            is DomainResult.Error -> return DomainResult.Error(result.error)
        }

        val points = document.toGeoPoints(
            activityId = 0L,
            fallbackStartEpochMillis = System.currentTimeMillis(),
        )
        // Файл может прийти откуда угодно, поэтому статистика считается своим pipeline,
        // а не берётся из документа: только так цифры сходятся с собственными записями.
        val processed = trackProcessor.process(points, document.activityType)
        if (processed.points.isEmpty()) return DomainResult.Error(TrackFileError.NO_TRACK_POINTS)

        val activity = document.toActivity(processed.points, processed.statistics, fileName)
        return when (val result = trackRepository.insertTrack(activity, processed.points)) {
            is DomainResult.Success -> DomainResult.Success(result.data)
            is DomainResult.Error -> DomainResult.Error(TrackFileError.STORAGE_FAILURE)
        }
    }

    private fun TrackDocument.toActivity(
        points: List<GeoPoint>,
        statistics: TrackStatistics,
        fileName: String?,
    ): Activity {
        return Activity(
            startTimeEpochMillis = points.first().timestampEpochMillis,
            // Время окончания обязательно: активность без него репозиторий считает
            // незавершённой записью и после перезапуска попытается её продолжить.
            endTimeEpochMillis = points.last().timestampEpochMillis,
            activityType = activityType,
            title = name ?: fileName?.substringBeforeLast('.'),
            note = note,
            distanceMeters = statistics.distanceMeters,
            movingTimeMillis = statistics.movingTimeMillis,
            elapsedTimeMillis = statistics.elapsedTimeMillis,
            averageSpeedMetersPerSecond = statistics.averageSpeedMetersPerSecond,
            maxSpeedMetersPerSecond = statistics.maxSpeedMetersPerSecond,
            elevationGainMeters = statistics.elevationGainMeters,
        )
    }

    private companion object {
        const val TAG = "ImportTracksWorker"
    }
}
