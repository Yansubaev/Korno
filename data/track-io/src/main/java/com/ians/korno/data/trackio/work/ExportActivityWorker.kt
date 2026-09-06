package com.ians.korno.data.trackio.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.ians.korno.core.domain.error.Result as DomainResult
import com.ians.korno.core.domain.error.TrackFileError
import com.ians.korno.core.domain.repository.TrackRepository
import com.ians.korno.data.trackio.io.DocumentStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/** Экспорт одной активности в документ, который пользователь уже выбрал в системном диалоге. */
internal class ExportActivityWorker(
    context: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(context, parameters), KoinComponent {

    private val trackRepository: TrackRepository by inject()
    private val documentStore: DocumentStore by inject()
    private val trackFileWriter: TrackFileWriter by inject()

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val activityId = inputData.getLong(TrackFileWork.KEY_ACTIVITY_ID, -1L)
        val destinationUri = inputData.getString(TrackFileWork.KEY_DESTINATION_URI)
        val format = trackFileFormatOf(inputData.getString(TrackFileWork.KEY_FORMAT))

        if (activityId <= 0L || destinationUri == null) {
            return@withContext failure(TrackFileError.UNKNOWN)
        }

        val track = when (val result = trackRepository.loadTrack(activityId)) {
            is DomainResult.Success -> result.data
            is DomainResult.Error -> return@withContext failure(result.error)
        }

        val content = when (val result = trackFileWriter.write(track, format)) {
            is DomainResult.Success -> result.data
            is DomainResult.Error -> return@withContext failure(result.error)
        }

        when (val result = documentStore.writeText(destinationUri, content)) {
            is DomainResult.Success -> Result.success(
                TrackFileWork.outcome(successCount = 1, failureCount = 0),
            )
            is DomainResult.Error -> failure(result.error)
        }
    }

    private fun failure(error: TrackFileError): Result =
        Result.failure(TrackFileWork.outcome(successCount = 0, failureCount = 1, error = error))
}
