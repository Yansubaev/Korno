package com.ians.korno.data.trackio.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.ians.korno.core.domain.error.Result as DomainResult
import com.ians.korno.core.domain.error.TrackFileError
import com.ians.korno.core.domain.model.Activity
import com.ians.korno.core.domain.model.TrackFileFormat
import com.ians.korno.core.domain.repository.TrackRepository
import com.ians.korno.core.logging.AppLogger
import com.ians.korno.core.logging.LogCategory
import com.ians.korno.core.logging.warn
import com.ians.korno.data.gpxexport.TrackFileNaming
import com.ians.korno.data.trackio.io.DocumentStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/** Экспорт всех завершённых активностей в выбранную папку — по файлу на активность. */
internal class ExportAllActivitiesWorker(
    context: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(context, parameters), KoinComponent {

    private val trackRepository: TrackRepository by inject()
    private val documentStore: DocumentStore by inject()
    private val trackFileWriter: TrackFileWriter by inject()
    private val logger: AppLogger by inject()

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val treeUri = inputData.getString(TrackFileWork.KEY_TREE_URI)
            ?: return@withContext failed(TrackFileError.UNKNOWN)
        val format = trackFileFormatOf(inputData.getString(TrackFileWork.KEY_FORMAT))

        val allActivities = when (val result = trackRepository.observeActivities().first()) {
            is DomainResult.Success -> result.data
            is DomainResult.Error -> return@withContext failed(TrackFileError.STORAGE_FAILURE)
        }
        // Незавершённая запись — это активность, которая пишется прямо сейчас;
        // выгружать её половину смысла нет.
        val activities = allActivities.filter { it.endTimeEpochMillis != null }

        if (activities.isEmpty()) return@withContext failed(TrackFileError.NO_TRACK_POINTS)

        var successCount = 0
        var failureCount = 0
        var lastError: TrackFileError? = null

        activities.forEach { activity ->
            when (val result = export(activity, treeUri, format)) {
                is DomainResult.Success -> successCount++
                is DomainResult.Error -> {
                    failureCount++
                    lastError = result.error
                    logger.warn(
                        category = LogCategory.EXPORT,
                        tag = TAG,
                        fields = mapOf(
                            "activityId" to activity.id.toString(),
                            "error" to result.error.name,
                        ),
                    ) { "Activity export failed" }
                }
            }
        }

        // Полностью провалившаяся операция — это failure: пользователю нужно показать
        // ошибку, а не «готово, 0 файлов».
        if (successCount == 0) {
            Result.failure(
                TrackFileWork.outcome(0, failureCount, lastError ?: TrackFileError.UNKNOWN),
            )
        } else {
            Result.success(TrackFileWork.outcome(successCount, failureCount))
        }
    }

    private suspend fun export(
        activity: Activity,
        treeUri: String,
        format: TrackFileFormat,
    ): DomainResult<Unit, TrackFileError> {
        val track = when (val result = trackRepository.loadTrack(activity.id)) {
            is DomainResult.Success -> result.data
            is DomainResult.Error -> return DomainResult.Error(result.error)
        }
        // Содержимое готовится до создания документа: пустой файл в папке хуже,
        // чем активность, которую пропустили с понятной ошибкой.
        val content = when (val result = trackFileWriter.write(track, format)) {
            is DomainResult.Success -> result.data
            is DomainResult.Error -> return DomainResult.Error(result.error)
        }
        val fileName = TrackFileNaming.uniqueFileName(activity, format)
        val documentUri = when (
            val created = documentStore.createInTree(treeUri, fileName, format.mimeType)
        ) {
            is DomainResult.Success -> created.data
            is DomainResult.Error -> return DomainResult.Error(created.error)
        }
        return documentStore.writeText(documentUri, content)
    }

    private fun failed(error: TrackFileError): Result =
        Result.failure(TrackFileWork.outcome(successCount = 0, failureCount = 1, error = error))

    private companion object {
        const val TAG = "ExportAllActivitiesWorker"
    }
}
