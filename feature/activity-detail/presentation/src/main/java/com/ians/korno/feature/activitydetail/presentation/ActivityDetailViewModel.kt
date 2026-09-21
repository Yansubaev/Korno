package com.ians.korno.feature.activitydetail.presentation

import android.annotation.SuppressLint
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ians.korno.core.domain.error.Result
import com.ians.korno.core.domain.error.TrackFileError
import com.ians.korno.core.domain.model.Activity
import com.ians.korno.core.domain.model.ActivitySegment
import com.ians.korno.core.domain.model.GeoPoint
import com.ians.korno.core.domain.model.MeasurementSystem
import com.ians.korno.core.domain.model.RecordingStatus
import com.ians.korno.core.domain.model.RecordingMode
import com.ians.korno.core.domain.model.SegmentKind
import com.ians.korno.core.domain.model.dominantKind
import com.ians.korno.core.domain.model.TrackFileFormat
import com.ians.korno.core.domain.model.TrackFileOperation
import com.ians.korno.core.domain.model.isTrackGap
import com.ians.korno.core.domain.model.slicePoints
import com.ians.korno.core.domain.repository.UserPreferencesRepository
import com.ians.korno.core.logging.ActivityLogInfo
import com.ians.korno.core.logging.LogArchiveResult
import com.ians.korno.core.logging.LogManager
import com.ians.korno.core.map.MapPoint
import com.ians.korno.core.presentation.formatDistance
import com.ians.korno.core.presentation.formatElevation
import com.ians.korno.core.presentation.formatPace
import com.ians.korno.core.presentation.formatSpeed
import com.ians.korno.core.presentation.formatTimerDuration
import com.ians.korno.core.presentation.labelRes
import com.ians.korno.core.presentation.R as CoreR
import com.ians.korno.core.trackanalytics.TrackAnalysis
import com.ians.korno.feature.activitydetail.domain.usecase.AnalyzeActivityUseCase
import com.ians.korno.feature.activitydetail.domain.usecase.ChangeActivitySegmentKindUseCase
import com.ians.korno.feature.activitydetail.domain.usecase.RedecodeActivitySegmentsUseCase
import com.ians.korno.feature.activitydetail.domain.usecase.ObserveActivitySegmentsUseCase
import com.ians.korno.feature.activitydetail.domain.usecase.SegmentKindSummary
import com.ians.korno.feature.activitydetail.domain.usecase.summarizeSegmentKinds
import com.ians.korno.feature.activitydetail.domain.usecase.DeleteActivityUseCase
import com.ians.korno.feature.activitydetail.domain.usecase.ExportActivityUseCase
import com.ians.korno.feature.activitydetail.domain.usecase.ObserveActivityUseCase
import com.ians.korno.feature.activitydetail.domain.usecase.ObserveGeoPointsUseCase
import com.ians.korno.feature.activitydetail.domain.usecase.ObserveTrackExportUseCase
import com.ians.korno.feature.activitydetail.domain.usecase.PrepareActivityExportUseCase
import com.ians.korno.feature.activitydetail.domain.usecase.PrepareActivityShareUseCase
import java.text.DateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class ActivityDetailViewModel(
    context: Context,
    private val activityId: Long,
    private val observeActivity: ObserveActivityUseCase,
    private val observeGeoPoints: ObserveGeoPointsUseCase,
    private val analyzeActivity: AnalyzeActivityUseCase,
    private val deleteActivity: DeleteActivityUseCase,
    private val exportActivity: ExportActivityUseCase,
    private val prepareActivityExport: PrepareActivityExportUseCase,
    private val prepareActivityShare: PrepareActivityShareUseCase,
    private val logManager: LogManager,
    observeTrackExport: ObserveTrackExportUseCase,
    preferencesRepository: UserPreferencesRepository,
    private val observeSegments: ObserveActivitySegmentsUseCase,
    private val changeSegmentKind: ChangeActivitySegmentKindUseCase,
    private val redecodeSegments: RedecodeActivitySegmentsUseCase,
) : ViewModel() {

    // Лит-чек не отслеживает, что это applicationContext, а не тот, что пришёл в конструктор —
    // предупреждение о поле Context в ViewModel срабатывает по типу, без анализа значения.
    @SuppressLint("StaticFieldLeak")
    private val context: Context = context.applicationContext

    private data class LocalUi(
        /** `null` — линза по умолчанию: «Виды» у смешанной записи, иначе маршрут. */
        val lens: MapLens? = null,
        val segmentKindChoice: Int? = null,
        val isRecalculating: Boolean = false,
        /** Доля дистанции, выбранная касанием графика; `null` — ничего не выбрано. */
        val selectedFraction: Float? = null,
        val selectedSplitNumber: Int? = null,
        val snackbarMessageRes: Int? = null,
        val deleteDialogOpen: Boolean = false,
        val deleted: Boolean = false,
        val formatChoice: TrackFileIntent? = null,
        val isExporting: Boolean = false,
        val activityLog: ActivityLogInfo? = null,
        val isPreparingLogArchive: Boolean = false,
    )

    /** Точки трека вместе с их разбором: считаются вместе и устаревают вместе. */
    private data class TrackData(
        val points: List<GeoPoint>,
        val segments: List<ActivitySegment>,
        val analysis: TrackAnalysis,
        val kindSummaries: List<SegmentKindSummary>,
        /** Вид каждой точки из [points]; пусто, если вид в записи один. */
        val pointKinds: List<SegmentKind>,
    )

    private val local = MutableStateFlow(LocalUi())
    private val eventChannel = Channel<ActivityDetailEvent>()
    val events = eventChannel.receiveAsFlow()

    /** Формат, выбранный в диалоге, нужен ещё раз — когда вернётся системный выбор файла. */
    private var pendingExportFormat: TrackFileFormat? = null

    /**
     * Разбор трека живёт отдельным потоком и на фоновом диспетчере: он проходит по тысячам
     * точек, и делать это в общем `combine` значило бы пересчитывать всё при каждом
     * открытии диалога или показе снекбара.
     */
    private val trackData: Flow<TrackData> = combine(
        observeGeoPoints(activityId),
        observeSegments(activityId),
        preferencesRepository.observePreferences(),
    ) { pointsResult, segments, preferences ->
        val points = (pointsResult as? Result.Success)?.data.orEmpty()
        val summaries = summarizeSegmentKinds(points, segments)
        val isMixed = summaries.size > 1
        TrackData(
            points = points,
            segments = segments,
            analysis = analyzeActivity(points, segments, preferences.measurementSystem),
            kindSummaries = summaries,
            // Точки приходят из БД по времени — в том же порядке, в каком их режут сегменты.
            pointKinds = if (isMixed) {
                segments.slicePoints(points).flatMap { (segment, slice) -> List(slice.size) { segment.kind } }
            } else {
                emptyList()
            },
        )
    }.flowOn(Dispatchers.Default)

    val state: StateFlow<ActivityDetailState> = combine(
        observeActivity(activityId),
        trackData,
        local,
        preferencesRepository.observePreferences(),
    ) { activityResult, track, localUi, preferences ->
        val activity = (activityResult as? Result.Success)?.data
        if (activity == null) {
            // Флаг удаления переживает исчезновение активности из БД: Room успевает
            // сообщить о пропаже раньше, чем UI прочитает состояние, и без переноса
            // флага экран остался бы висеть на пустых данных вместо возврата назад.
            ActivityDetailState(isLoading = true, deleted = localUi.deleted)
        } else {
            activity.toUiState(track, localUi, preferences.measurementSystem)
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
            is ActivityDetailAction.SelectLens -> local.update { it.copy(lens = action.lens) }
            is ActivityDetailAction.SelectTrackPosition -> local.update {
                // Точка и сплит — два способа сказать «вот это место», и одновременно
                // подсвечивать оба значило бы показывать два ответа на один вопрос.
                it.copy(selectedFraction = action.fraction.coerceIn(0f, 1f), selectedSplitNumber = null)
            }
            ActivityDetailAction.ClearSelection ->
                local.update { it.copy(selectedFraction = null, selectedSplitNumber = null) }
            is ActivityDetailAction.ToggleSplit -> local.update {
                it.copy(
                    selectedSplitNumber = action.number.takeIf { number -> number != it.selectedSplitNumber },
                    selectedFraction = null,
                )
            }
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
            ActivityDetailAction.RedecodeSegmentsClicked -> redecodeStoredSegments()
            is ActivityDetailAction.SelectSegment -> local.update { it.copy(segmentKindChoice = action.index) }
            ActivityDetailAction.DismissSegmentKindChoice -> local.update { it.copy(segmentKindChoice = null) }
            is ActivityDetailAction.ChangeSegmentKind -> changeKindOfChosenSegment(action.kind)
            ActivityDetailAction.DismissSnackbar -> local.update { it.copy(snackbarMessageRes = null) }
        }
    }

    private fun changeKindOfChosenSegment(kind: SegmentKind) {
        val index = local.value.segmentKindChoice ?: return
        local.update { it.copy(segmentKindChoice = null, isRecalculating = true) }
        viewModelScope.launch {
            // Всё читается заново, а не берётся из экрана: пересчёт должен идти по тому, что
            // лежит в БД, иначе быстрая вторая смена затёрла бы первую.
            val activity = (observeActivity(activityId).first() as? Result.Success)?.data
            val points = (observeGeoPoints(activityId).first() as? Result.Success)?.data
            val segments = observeSegments(activityId).first()
            if (activity != null && points != null && index in segments.indices) {
                withContext(Dispatchers.Default) { changeSegmentKind(activity, points, segments, index, kind) }
            }
            local.update { it.copy(isRecalculating = false) }
        }
    }

    /**
     * Пересчёт разметки сохранённой записи текущим декодером — debug-действие. Вид движения
     * определяется один раз при остановке записи, и правка трека его не трогает, поэтому
     * после изменения декодера иначе пришлось бы ехать заново.
     */
    private fun redecodeStoredSegments() {
        local.update { it.copy(isRecalculating = true, snackbarMessageRes = null) }
        viewModelScope.launch {
            val activity = (observeActivity(activityId).first() as? Result.Success)?.data
            val points = (observeGeoPoints(activityId).first() as? Result.Success)?.data
            val segments = observeSegments(activityId).first()
            val outcome = if (activity == null || points == null) {
                null
            } else {
                withContext(Dispatchers.Default) { redecodeSegments(activity, points, segments) }
            }
            local.update {
                it.copy(
                    isRecalculating = false,
                    snackbarMessageRes = when ((outcome as? Result.Error)?.error) {
                        RedecodeActivitySegmentsUseCase.Skipped.NOT_AUTO_MODE ->
                            R.string.activity_detail_redecode_not_auto
                        RedecodeActivitySegmentsUseCase.Skipped.NO_RECOGNITION ->
                            R.string.activity_detail_redecode_no_recognition
                        null -> if (outcome == null) R.string.activity_detail_redecode_failed else null
                    },
                )
            }
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
        track: TrackData,
        localUi: LocalUi,
        measurementSystem: MeasurementSystem,
    ): ActivityDetailState {
        val isMixed = track.kindSummaries.size > 1
        val kind = track.segments.dominantKind() ?: SegmentKind.UNKNOWN
        val insights = context.toInsightsUi(track.analysis, kind, measurementSystem)?.let {
            if (!isMixed) {
                it
            } else {
                it.copy(
                    availableLenses = listOf(MapLens.KINDS) + it.availableLenses,
                    // Отметки считают дистанцию только доминирующего вида, а маршрут на карте —
                    // вся запись: километр «3» посреди автобуса ничего бы не значил.
                    distanceMarkers = emptyList(),
                )
            }
        }
        val defaultLens = if (isMixed) MapLens.KINDS else MapLens.ROUTE
        val display = kind.speedDisplay
        return ActivityDetailState(
            isLoading = false,
            title = title ?: context.getString(kind.labelRes()),
            dateLabel = dateLabel(startTimeEpochMillis),
            kind = kind,
            distanceText = context.formatDistance(distanceMeters, measurementSystem).text,
            durationText = formatTimerDuration(elapsedTimeMillis),
            movingText = formatTimerDuration(movingTimeMillis),
            avgSpeedText = context.movementText(averageSpeedMetersPerSecond, display, measurementSystem),
            // Максимум тоже из разбора: сохранённый считается по одной паре точек и
            // почти всегда оказывается GPS-скачком — на бегу он выдаёт темп, которого
            // не бывает.
            maxSpeedText = context.movementText(
                track.analysis.peakSpeedMetersPerSecond ?: maxSpeedMetersPerSecond,
                display,
                measurementSystem,
            ),
            // Набор берётся из разбора трека, а не из сохранённого значения: в записи он
            // считается порогом на каждый сегмент и теряет пологий подъём целиком, а
            // разбор ведёт его по сглаженному профилю с гистерезисом. Показывать рядом
            // два разных числа про одну и ту же высоту нельзя.
            elevationText = context.formatElevation(
                track.analysis.elevation?.gainMeters ?: elevationGainMeters,
                measurementSystem,
            ).text,
            stepsText = stepCount?.let {
                context.resources.getQuantityString(R.plurals.activity_detail_steps_count, it, it)
            },
            routeKinds = track.pointKinds,
            kindBreakdown = if (isMixed) {
                track.kindSummaries.map { summary -> context.toKindSummaryUi(summary, measurementSystem) }
            } else {
                emptyList()
            },
            segments = if (recordingMode == RecordingMode.AUTO) {
                track.segments.mapIndexed { index, segment ->
                    SegmentUi(
                        index = index,
                        kind = segment.kind,
                        timeRangeText = timeRange(segment),
                        distanceText = context.formatDistance(segment.distanceMeters, measurementSystem).text,
                        isManuallyEdited = segment.isManuallyEdited,
                    )
                }
            } else {
                emptyList()
            },
            segmentKindChoice = localUi.segmentKindChoice,
            isRecalculating = localUi.isRecalculating,
            // Выбросы остаются в линии, но помеченными: карта рисует их бледным слоем.
            // Выкинутые совсем, они оставляли прямую через весь выброшенный участок.
            // Индекс точки линии обязан совпадать с индексом в [TrackData.pointKinds]:
            // оба списка — все точки по времени.
            routePoints = track.points.mapIndexed { index, point ->
                MapPoint(
                    latitude = point.latitude,
                    longitude = point.longitude,
                    isPaused = point.isStop || point.recordingStatus.isPause,
                    isGap = index > 0 && isTrackGap(track.points[index - 1], point),
                    isRejected = point.isOutlier,
                )
            },
            insights = insights,
            // Линза может исчезнуть вместе с данными, на которых держалась: после
            // ручной правки трека выбранной высоты или остановок может уже не быть.
            lens = localUi.lens?.takeIf { insights == null || it in insights.availableLenses }
                ?: defaultLens,
            selection = localUi.selectedFraction?.let {
                context.toSelectionUi(track.analysis, it, kind, measurementSystem)
            },
            selectedSplitNumber = localUi.selectedSplitNumber,
            snackbarMessageRes = localUi.snackbarMessageRes,
            deleteDialogOpen = localUi.deleteDialogOpen,
            deleted = localUi.deleted,
            formatChoice = localUi.formatChoice,
            isExporting = localUi.isExporting,
            activityLog = localUi.activityLog,
            isPreparingLogArchive = localUi.isPreparingLogArchive,
        )
    }

    private fun timeRange(segment: ActivitySegment): String {
        val format = DateFormat.getTimeInstance(DateFormat.SHORT)
        val end = segment.endTimeEpochMillis ?: return format.format(Date(segment.startTimeEpochMillis))
        return "${format.format(Date(segment.startTimeEpochMillis))}–${format.format(Date(end))}"
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
}

private fun Context.toKindSummaryUi(summary: SegmentKindSummary, system: MeasurementSystem) = KindSummaryUi(
    kind = summary.kind,
    distanceText = formatDistance(summary.distanceMeters, system).text,
    movingText = formatTimerDuration(summary.movingTimeMillis),
    movementText = movementText(summary.averageSpeedMetersPerSecond, summary.kind.speedDisplay, system),
    stepsText = summary.stepCount
        ?.takeIf { summary.kind.speedDisplay == SpeedDisplay.PACE }
        ?.let { resources.getQuantityString(R.plurals.activity_detail_steps_count, it, it) },
)

/** Темп или скорость — смотря чем меряют эту активность. */
private fun Context.movementText(
    metersPerSecond: Double,
    display: SpeedDisplay,
    system: MeasurementSystem,
): String = when (display) {
    SpeedDisplay.PACE -> formatPace(metersPerSecond, system)?.text
        ?: getString(R.string.activity_detail_value_unavailable)
    SpeedDisplay.SPEED -> formatSpeed(metersPerSecond, system).text
}

private val RecordingStatus.isPause: Boolean
    get() = this == RecordingStatus.MANUAL_PAUSE || this == RecordingStatus.AUTO_PAUSE
