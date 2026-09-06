package com.ians.korno.feature.activitydetail.presentation

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ians.korno.core.domain.error.Result
import com.ians.korno.core.domain.error.TrackFileError
import com.ians.korno.core.domain.model.Activity
import com.ians.korno.core.domain.model.GeoPoint
import com.ians.korno.core.domain.model.MeasurementSystem
import com.ians.korno.core.domain.model.RecordingStatus
import com.ians.korno.core.domain.model.TrackFileFormat
import com.ians.korno.core.domain.model.TrackFileOperation
import com.ians.korno.core.domain.repository.UserPreferencesRepository
import com.ians.korno.core.logging.ActivityLogInfo
import com.ians.korno.core.logging.LogArchiveResult
import com.ians.korno.core.logging.LogManager
import com.ians.korno.core.map.MapPoint
import com.ians.korno.core.presentation.formatDistance
import com.ians.korno.core.presentation.formatElevation
import com.ians.korno.core.presentation.formatSpeed
import com.ians.korno.core.presentation.R as CoreR
import com.ians.korno.feature.activitydetail.domain.usecase.DeleteActivityUseCase
import com.ians.korno.feature.activitydetail.domain.usecase.ExportActivityUseCase
import com.ians.korno.feature.activitydetail.domain.usecase.ObserveActivityUseCase
import com.ians.korno.feature.activitydetail.domain.usecase.ObserveGeoPointsUseCase
import com.ians.korno.feature.activitydetail.domain.usecase.ObserveTrackExportUseCase
import com.ians.korno.feature.activitydetail.domain.usecase.PrepareActivityExportUseCase
import com.ians.korno.feature.activitydetail.domain.usecase.PrepareActivityShareUseCase
import java.util.Calendar
import java.util.Locale
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val PACE_BUCKET_COUNT = 10

internal class ActivityDetailViewModel(
    private val context: Context,
    private val activityId: Long,
    observeActivity: ObserveActivityUseCase,
    observeGeoPoints: ObserveGeoPointsUseCase,
    private val deleteActivity: DeleteActivityUseCase,
    private val exportActivity: ExportActivityUseCase,
    private val prepareActivityExport: PrepareActivityExportUseCase,
    private val prepareActivityShare: PrepareActivityShareUseCase,
    private val logManager: LogManager,
    observeTrackExport: ObserveTrackExportUseCase,
    preferencesRepository: UserPreferencesRepository,
) : ViewModel() {

    private data class LocalUi(
        val snackbarMessageRes: Int? = null,
        val deleteDialogOpen: Boolean = false,
        val deleted: Boolean = false,
        val formatChoice: TrackFileIntent? = null,
        val isExporting: Boolean = false,
        val activityLog: ActivityLogInfo? = null,
        val isPreparingLogArchive: Boolean = false,
    )

    private val local = MutableStateFlow(LocalUi())
    private val eventChannel = Channel<ActivityDetailEvent>()
    val events = eventChannel.receiveAsFlow()

    /** Формат, выбранный в диалоге, нужен ещё раз — когда вернётся системный выбор файла. */
    private var pendingExportFormat: TrackFileFormat? = null

    val state: StateFlow<ActivityDetailState> = combine(
        observeActivity(activityId),
        observeGeoPoints(activityId),
        local,
        preferencesRepository.observePreferences(),
    ) { activityResult, pointsResult, localUi, preferences ->
        val activity = (activityResult as? Result.Success)?.data
        val points = (pointsResult as? Result.Success)?.data.orEmpty()
        if (activity == null) {
            // Флаг удаления переживает исчезновение активности из БД: Room успевает
            // сообщить о пропаже раньше, чем UI прочитает состояние, и без переноса
            // флага экран остался бы висеть на пустых данных вместо возврата назад.
            ActivityDetailState(isLoading = true, deleted = localUi.deleted)
        } else {
            activity.toUiState(points, localUi, preferences.measurementSystem)
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = ActivityDetailState(),
    )

    init {
        viewModelScope.launch {
            observeTrackExport().collect { operation -> onExportOperation(operation) }
        }
        viewModelScope.launch {
            // Раздел появляется только там, где логгер вообще есть: в release-сборке
            // observeSettings() отдаёт isAvailable = false и состояние остаётся null.
            logManager.observeSettings().collect { settings ->
                if (settings.isAvailable) refreshActivityLogInfo() else {
                    local.update { it.copy(activityLog = null) }
                }
            }
        }
    }

    fun onAction(action: ActivityDetailAction) {
        when (action) {
            ActivityDetailAction.ShareClicked ->
                local.update { it.copy(formatChoice = TrackFileIntent.SHARE) }
            ActivityDetailAction.ExportClicked ->
                local.update { it.copy(formatChoice = TrackFileIntent.EXPORT) }
            ActivityDetailAction.DismissFormatChoice ->
                local.update { it.copy(formatChoice = null) }
            is ActivityDetailAction.SelectFileFormat -> onFormatSelected(action.format)
            is ActivityDetailAction.ExportDestinationChosen -> {
                val format = pendingExportFormat ?: TrackFileFormat.GPX
                pendingExportFormat = null
                local.update { it.copy(isExporting = true) }
                exportActivity(activityId, action.uri, format)
            }
            ActivityDetailAction.ExportDestinationCancelled -> pendingExportFormat = null
            ActivityDetailAction.RequestDelete -> local.update { it.copy(deleteDialogOpen = true) }
            ActivityDetailAction.CancelDelete -> local.update { it.copy(deleteDialogOpen = false) }
            ActivityDetailAction.ConfirmDelete -> viewModelScope.launch {
                local.update { it.copy(deleteDialogOpen = false) }
                deleteActivity(activityId)
                // Лог не должен пережить свой трек: иначе на устройстве остаётся файл
                // с маршрутом активности, которую пользователь уже удалил.
                logManager.deleteActivityLogs(activityId)
                local.update { it.copy(deleted = true) }
            }
            ActivityDetailAction.ShareActivityLogsClicked -> shareActivityLogs()
            ActivityDetailAction.DismissSnackbar -> local.update { it.copy(snackbarMessageRes = null) }
        }
    }

    private fun refreshActivityLogInfo() {
        viewModelScope.launch {
            val info = logManager.getActivityLogInfo(activityId)
            local.update { it.copy(activityLog = info) }
        }
    }

    private fun shareActivityLogs() {
        viewModelScope.launch {
            local.update { it.copy(isPreparingLogArchive = true, snackbarMessageRes = null) }
            when (val result = logManager.createActivityArchive(activityId)) {
                is LogArchiveResult.Success -> eventChannel.send(
                    ActivityDetailEvent.ShareLogArchive(
                        uri = result.archive.uri,
                        mimeType = result.archive.mimeType,
                    ),
                )
                LogArchiveResult.NoLogs -> local.update {
                    it.copy(snackbarMessageRes = R.string.activity_detail_no_logs)
                }
                LogArchiveResult.Error -> local.update {
                    it.copy(snackbarMessageRes = R.string.activity_detail_log_archive_failed)
                }
            }
            local.update { it.copy(isPreparingLogArchive = false) }
            refreshActivityLogInfo()
        }
    }

    private fun onFormatSelected(format: TrackFileFormat) {
        val intent = local.value.formatChoice
        local.update { it.copy(formatChoice = null) }
        when (intent) {
            TrackFileIntent.EXPORT -> viewModelScope.launch {
                when (val result = prepareActivityExport(activityId, format)) {
                    is Result.Success -> {
                        pendingExportFormat = format
                        eventChannel.send(
                            ActivityDetailEvent.PickExportDestination(result.data, format),
                        )
                    }
                    is Result.Error -> showError(result.error)
                }
            }
            TrackFileIntent.SHARE -> viewModelScope.launch {
                when (val result = prepareActivityShare(activityId, format)) {
                    is Result.Success -> eventChannel.send(
                        ActivityDetailEvent.ShareTrackFile(result.data.uri, result.data.mimeType),
                    )
                    is Result.Error -> showError(result.error)
                }
            }
            null -> Unit
        }
    }

    private fun onExportOperation(operation: TrackFileOperation) {
        when (operation) {
            TrackFileOperation.Idle -> Unit
            TrackFileOperation.Running -> local.update { it.copy(isExporting = true) }
            is TrackFileOperation.Completed -> local.update {
                it.copy(isExporting = false, snackbarMessageRes = R.string.activity_detail_export_done)
            }
            is TrackFileOperation.Failed -> {
                local.update { it.copy(isExporting = false) }
                showError(operation.error)
            }
        }
    }

    private fun showError(error: TrackFileError) {
        local.update { it.copy(snackbarMessageRes = error.messageRes()) }
    }

    private fun TrackFileError.messageRes(): Int = when (this) {
        TrackFileError.NO_TRACK_POINTS -> R.string.track_file_error_empty
        TrackFileError.NOT_FOUND,
        TrackFileError.UNWRITABLE_DESTINATION,
        TrackFileError.UNREADABLE_SOURCE,
        -> R.string.track_file_error_document

        else -> R.string.track_file_error_generic
    }

    private fun Activity.toUiState(
        points: List<GeoPoint>,
        localUi: LocalUi,
        measurementSystem: MeasurementSystem,
    ): ActivityDetailState = ActivityDetailState(
        isLoading = false,
        title = title ?: activityType.name,
        dateLabel = dateLabel(startTimeEpochMillis),
        type = activityType,
        distanceText = context.formatDistance(distanceMeters, measurementSystem).text,
        durationText = formatDuration(elapsedTimeMillis),
        movingText = formatDuration(movingTimeMillis),
        avgSpeedText = context.formatSpeed(averageSpeedMetersPerSecond, measurementSystem).text,
        maxSpeedText = context.formatSpeed(maxSpeedMetersPerSecond, measurementSystem).text,
        elevationText = context.formatElevation(elevationGainMeters, measurementSystem).text,
        paceBars = paceBars(points),
        routePoints = points.map {
            MapPoint(
                latitude = it.latitude,
                longitude = it.longitude,
                isPaused = it.isStop || it.recordingStatus.isPause,
            )
        },
        snackbarMessageRes = localUi.snackbarMessageRes,
        deleteDialogOpen = localUi.deleteDialogOpen,
        deleted = localUi.deleted,
        formatChoice = localUi.formatChoice,
        isExporting = localUi.isExporting,
        activityLog = localUi.activityLog,
        isPreparingLogArchive = localUi.isPreparingLogArchive,
    )

    private fun paceBars(points: List<GeoPoint>): List<Float> {
        if (points.isEmpty()) return List(PACE_BUCKET_COUNT) { 0.5f }
        val bucketSize = (points.size / PACE_BUCKET_COUNT).coerceAtLeast(1)
        val speeds = points.chunked(bucketSize).take(PACE_BUCKET_COUNT).map { bucket ->
            bucket.mapNotNull { it.speedMetersPerSecond }.average().let { if (it.isNaN()) 0.0 else it }
        }
        val max = speeds.maxOrNull()?.takeIf { it > 0 } ?: return List(PACE_BUCKET_COUNT) { 0.5f }
        return speeds.map { (0.2f + 0.8f * (it / max)).toFloat() }
    }

    private fun dateLabel(epochMillis: Long): String {
        val today = Calendar.getInstance()
        val date = Calendar.getInstance().apply { timeInMillis = epochMillis }
        return when {
            isSameDay(today, date) -> context.getString(CoreR.string.date_label_today)
            isSameDay(today.apply { add(Calendar.DAY_OF_YEAR, -1) }, date) ->
                context.getString(CoreR.string.date_label_yesterday)
            else -> String.format(Locale.getDefault(), "%1\$te %1\$tb", date)
        }
    }

    private fun isSameDay(a: Calendar, b: Calendar): Boolean =
        a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)

    private fun formatDuration(millis: Long): String {
        val totalSeconds = millis / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%02d:%02d", minutes, seconds)
        }
    }
}

private val RecordingStatus.isPause: Boolean
    get() = this == RecordingStatus.MANUAL_PAUSE || this == RecordingStatus.AUTO_PAUSE
