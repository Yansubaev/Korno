package com.ians.korno.data.trackio.repository

import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.ians.korno.core.domain.error.Result
import com.ians.korno.core.domain.error.TrackFileError
import com.ians.korno.core.domain.model.SharedTrackFile
import com.ians.korno.core.domain.model.TrackFileFormat
import com.ians.korno.core.domain.model.TrackFileOperation
import com.ians.korno.core.domain.repository.TrackFileRepository
import com.ians.korno.core.domain.repository.TrackRepository
import com.ians.korno.data.gpxexport.TrackFileNaming
import com.ians.korno.data.trackio.io.DocumentStore
import com.ians.korno.data.trackio.work.ExportActivityWorker
import com.ians.korno.data.trackio.work.ExportAllActivitiesWorker
import com.ians.korno.data.trackio.work.ImportTracksWorker
import com.ians.korno.data.trackio.work.TrackFileWork
import com.ians.korno.data.trackio.work.TrackFileWriter
import com.ians.korno.data.trackio.work.loadTrack
import java.util.Collections
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

internal class DefaultTrackFileRepository(
    private val workManager: WorkManager,
    private val trackRepository: TrackRepository,
    private val documentStore: DocumentStore,
    private val trackFileWriter: TrackFileWriter,
) : TrackFileRepository {

    /**
     * Работы, поставленные в очередь этим запуском приложения. WorkManager хранит
     * результаты и после перезапуска, а показывать снекбар о позавчерашнем экспорте
     * при следующем открытии экрана незачем.
     */
    private val ownedRequests = Collections.synchronizedSet(mutableSetOf<UUID>())
    private val reportedRequests = Collections.synchronizedSet(mutableSetOf<UUID>())

    override fun exportActivity(activityId: Long, destinationUri: String, format: TrackFileFormat) {
        documentStore.persistPermission(destinationUri, writable = true)
        enqueue<ExportActivityWorker>(
            tag = TrackFileWork.TAG_EXPORT_ACTIVITY,
            uniqueName = "$UNIQUE_EXPORT_ACTIVITY-$activityId",
            data = Data.Builder()
                .putLong(TrackFileWork.KEY_ACTIVITY_ID, activityId)
                .putString(TrackFileWork.KEY_DESTINATION_URI, destinationUri)
                .putString(TrackFileWork.KEY_FORMAT, format.name)
                .build(),
        )
    }

    override fun exportAllActivities(destinationTreeUri: String, format: TrackFileFormat) {
        documentStore.persistPermission(destinationTreeUri, writable = true)
        enqueue<ExportAllActivitiesWorker>(
            tag = TrackFileWork.TAG_EXPORT_ALL,
            uniqueName = UNIQUE_EXPORT_ALL,
            data = Data.Builder()
                .putString(TrackFileWork.KEY_TREE_URI, destinationTreeUri)
                .putString(TrackFileWork.KEY_FORMAT, format.name)
                .build(),
        )
    }

    override fun importTracks(sourceUris: List<String>) {
        if (sourceUris.isEmpty()) return
        sourceUris.forEach { documentStore.persistPermission(it, writable = false) }
        // Ссылки на документы длинные, а входные Data ограничены 10 КБ, поэтому
        // большой выбор разбивается на несколько работ с общим тегом.
        sourceUris.chunked(TrackFileWork.MAX_URIS_PER_REQUEST).forEachIndexed { index, chunk ->
            enqueue<ImportTracksWorker>(
                tag = TrackFileWork.TAG_IMPORT,
                uniqueName = "$UNIQUE_IMPORT-${System.currentTimeMillis()}-$index",
                data = Data.Builder()
                    .putStringArray(TrackFileWork.KEY_SOURCE_URIS, chunk.toTypedArray())
                    .build(),
            )
        }
    }

    override fun observeActivityExport(): Flow<TrackFileOperation> =
        observe(TrackFileWork.TAG_EXPORT_ACTIVITY)

    override fun observeBulkExport(): Flow<TrackFileOperation> =
        observe(TrackFileWork.TAG_EXPORT_ALL)

    override fun observeImport(): Flow<TrackFileOperation> = observe(TrackFileWork.TAG_IMPORT)

    override suspend fun prepareExport(
        activityId: Long,
        format: TrackFileFormat,
    ): Result<String, TrackFileError> {
        val track = when (val result = trackRepository.loadTrack(activityId)) {
            is Result.Success -> result.data
            is Result.Error -> return result
        }
        // Приватная зона может съесть трек целиком — узнать об этом нужно раньше,
        // чем системный диалог создаст файл.
        if (trackFileWriter.exportablePoints(track.points).isEmpty()) {
            return Result.Error(TrackFileError.NO_TRACK_POINTS)
        }
        return Result.Success(TrackFileNaming.fileName(track.activity, format))
    }

    override suspend fun prepareShare(
        activityId: Long,
        format: TrackFileFormat,
    ): Result<SharedTrackFile, TrackFileError> {
        val track = when (val result = trackRepository.loadTrack(activityId)) {
            is Result.Success -> result.data
            is Result.Error -> return result
        }
        val content = when (val result = trackFileWriter.write(track, format)) {
            is Result.Success -> result.data
            is Result.Error -> return result
        }
        return documentStore.writeToShareCache(
            fileName = TrackFileNaming.fileName(track.activity, format),
            content = content,
            format = format,
        )
    }

    private inline fun <reified W : androidx.work.ListenableWorker> enqueue(
        tag: String,
        uniqueName: String,
        data: Data,
    ) {
        val request = OneTimeWorkRequestBuilder<W>()
            .setInputData(data)
            .addTag(tag)
            .build()
        ownedRequests += request.id
        // Уникальность по имени защищает от дубля при повторном тапе, но не мешает
        // экспортировать две разные активности одновременно.
        workManager.enqueueUniqueWork(uniqueName, ExistingWorkPolicy.REPLACE, request)
    }

    private fun observe(tag: String): Flow<TrackFileOperation> =
        workManager.getWorkInfosByTagFlow(tag)
            .map(::toOperation)
            .distinctUntilChanged()

    private fun toOperation(infos: List<WorkInfo>): TrackFileOperation {
        val owned = infos.filter { it.id in ownedRequests }
        if (owned.any { !it.state.isFinished }) return TrackFileOperation.Running

        val finished = owned.filter {
            it.state != WorkInfo.State.CANCELLED && it.id !in reportedRequests
        }
        if (finished.isEmpty()) return TrackFileOperation.Idle
        finished.forEach { reportedRequests += it.id }

        val successCount = finished.sumOf { it.outputData.getInt(TrackFileWork.KEY_SUCCESS_COUNT, 0) }
        val failureCount = finished.sumOf {
            // Работа могла упасть с исключением, не успев записать свой результат:
            // такой файл всё равно не обработан, и молчать о нём нельзя.
            val reported = it.outputData.getInt(TrackFileWork.KEY_FAILURE_COUNT, 0)
            if (reported == 0 && it.state == WorkInfo.State.FAILED) 1 else reported
        }
        val error = finished.firstNotNullOfOrNull { info ->
            info.outputData.getString(TrackFileWork.KEY_ERROR)?.toTrackFileError()
        }

        return if (successCount == 0) {
            TrackFileOperation.Failed(error ?: TrackFileError.UNKNOWN)
        } else {
            TrackFileOperation.Completed(successCount, failureCount)
        }
    }

    private fun String.toTrackFileError(): TrackFileError =
        TrackFileError.entries.firstOrNull { it.name == this } ?: TrackFileError.UNKNOWN

    private companion object {
        const val UNIQUE_EXPORT_ACTIVITY = "korno-export-activity"
        const val UNIQUE_EXPORT_ALL = "korno-export-all"
        const val UNIQUE_IMPORT = "korno-import-tracks"
    }
}
