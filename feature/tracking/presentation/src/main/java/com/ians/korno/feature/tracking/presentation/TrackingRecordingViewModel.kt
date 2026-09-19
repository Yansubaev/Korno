package com.ians.korno.feature.tracking.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ians.korno.core.domain.error.Result
import com.ians.korno.core.domain.model.RecordingStatus
import com.ians.korno.core.domain.model.MeasurementSystem
import com.ians.korno.core.domain.model.SegmentKind
import com.ians.korno.core.domain.model.isTrackGap
import com.ians.korno.core.domain.repository.UserPreferencesRepository
import com.ians.korno.core.map.MapPoint
import com.ians.korno.feature.tracking.domain.model.RecordingSession
import com.ians.korno.feature.tracking.domain.usecase.ChangeSegmentKindUseCase
import com.ians.korno.feature.tracking.domain.usecase.ObserveSessionTrackUseCase
import com.ians.korno.feature.tracking.domain.usecase.ObserveTrackingSessionUseCase
import com.ians.korno.feature.tracking.domain.usecase.PauseTrackingUseCase
import com.ians.korno.feature.tracking.domain.usecase.ResumeTrackingUseCase
import com.ians.korno.feature.tracking.domain.usecase.StopTrackingUseCase
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

private const val TICK_INTERVAL_MILLIS = 1_000L

private data class LocalUi(
    val mapSheetMode: MapSheetMode = MapSheetMode.SPLIT,
    val finishDialogOpen: Boolean = false,
    val finishedActivityId: Long? = null,
    val segmentMenuOpen: Boolean = false,
)

internal class TrackingRecordingViewModel(
    observeTrackingSession: ObserveTrackingSessionUseCase,
    observeSessionTrack: ObserveSessionTrackUseCase,
    private val pauseTracking: PauseTrackingUseCase,
    private val resumeTracking: ResumeTrackingUseCase,
    private val stopTracking: StopTrackingUseCase,
    preferencesRepository: UserPreferencesRepository,
    private val changeSegmentKind: ChangeSegmentKindUseCase,
) : ViewModel() {

    private val local = MutableStateFlow(LocalUi())

    private val ticker = flow {
        while (true) {
            emit(Unit)
            delay(TICK_INTERVAL_MILLIS.milliseconds)
        }
    }

    // Маппинг живёт здесь, а не в toUiState: иначе список пересобирался бы
    // на каждый тик таймера, а не только при появлении новых точек.
    private val routePoints = observeSessionTrack().map { points ->
        // Выбросы остаются в линии, но помеченными: их координаты фильтр не правил, и
        // сплошной линией они и есть тот самый зигзаг, а выкинутые совсем — прямая через
        // весь участок. Карта рисует их бледным слоем.
        points.mapIndexed { index, point ->
            MapPoint(
                latitude = point.latitude,
                longitude = point.longitude,
                isPaused = point.isStop || point.recordingStatus.isPause,
                isGap = index > 0 && isTrackGap(points[index - 1], point),
                isRejected = point.isOutlier,
            )
        }
    }

    val state: StateFlow<TrackingRecordingState> = combine(
        observeTrackingSession(),
        ticker,
        routePoints,
        local,
        preferencesRepository.observePreferences(),
    ) { session, _, route, localUi, preferences ->
        session.toUiState(route, localUi, preferences.measurementSystem)
    }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000L),
            initialValue = TrackingRecordingState(),
        )

    fun onAction(action: TrackingRecordingAction) {
        when (action) {
            TrackingRecordingAction.PauseClicked -> pauseTracking()
            TrackingRecordingAction.ResumeClicked -> resumeTracking()
            TrackingRecordingAction.CycleMapSheetMode -> local.update { it.copy(mapSheetMode = it.mapSheetMode.next()) }
            is TrackingRecordingAction.MapSheetModeChanged -> local.update { it.copy(mapSheetMode = action.mode) }
            TrackingRecordingAction.ToggleSegmentMenu -> local.update { it.copy(segmentMenuOpen = !it.segmentMenuOpen) }
            is TrackingRecordingAction.ChangeSegmentKind -> {
                local.update { it.copy(segmentMenuOpen = false) }
                viewModelScope.launch { changeSegmentKind(action.kind) }
            }
            TrackingRecordingAction.RequestFinish -> local.update { it.copy(finishDialogOpen = true) }
            TrackingRecordingAction.CancelFinish -> local.update { it.copy(finishDialogOpen = false) }
            TrackingRecordingAction.ConfirmFinish -> viewModelScope.launch {
                local.update { it.copy(finishDialogOpen = false) }
                when (val result = stopTracking()) {
                    is Result.Success -> local.update { it.copy(finishedActivityId = result.data) }
                    is Result.Error -> Unit
                }
            }
        }
    }

    private fun RecordingSession.toUiState(
        route: List<MapPoint>,
        localUi: LocalUi,
        measurementSystem: MeasurementSystem,
    ): TrackingRecordingState {
        val now = System.currentTimeMillis()
        val isMetric = measurementSystem == MeasurementSystem.METRIC
        return TrackingRecordingState(
            recordingStatus = status,
            activityType = activityType,
            segmentKind = segmentKind,
            segmentDistances = segmentDistancesMeters
                .filterKeys { it != SegmentKind.UNKNOWN }
                .toSortedMap()
                .map { (kind, meters) -> kind to meters / if (isMetric) 1_000.0 else 1_609.344 },
            segmentMenuOpen = localUi.segmentMenuOpen,
            elapsedTimeMillis = elapsedMillisAt(now),
            movingTimeMillis = movingTimeMillis,
            distanceValue = distanceMeters / if (isMetric) 1_000.0 else 1_609.344,
            currentSpeedValue = currentSpeedMetersPerSecond * if (isMetric) 3.6 else 2.2369362921,
            averageSpeedValue = averageSpeedMetersPerSecond() * if (isMetric) 3.6 else 2.2369362921,
            elevationGainValue = elevationGainMeters * if (isMetric) 1.0 else 3.280839895,
            stepCount = stepCount,
            measurementSystem = measurementSystem,
            routePoints = route,
            currentPoint = lastPoint?.let {
                MapPoint(
                    latitude = it.latitude,
                    longitude = it.longitude,
                    isPaused = it.isStop || it.recordingStatus.isPause,
                )
            } ?: route.lastOrNull(),
            mapSheetMode = localUi.mapSheetMode,
            finishDialogOpen = localUi.finishDialogOpen,
            finishedActivityId = localUi.finishedActivityId,
        )
    }

    private fun RecordingSession.averageSpeedMetersPerSecond(): Double =
        if (movingTimeMillis > 0) distanceMeters / (movingTimeMillis / 1000.0) else 0.0

    private fun RecordingSession.elapsedMillisAt(now: Long): Long {
        // Ожидание сигнала не идёт в зачёт: секундомер тронется с нуля вместе с записью.
        if (
            status == RecordingStatus.IDLE ||
            status == RecordingStatus.STOPPED ||
            status == RecordingStatus.WAITING_FOR_GPS
        ) {
            return 0L
        }
        val ongoingPauseMillis = currentPauseStartMillis?.let { now - it } ?: 0L
        return (now - startTimeEpochMillis - pausedAccumulatedMillis - ongoingPauseMillis).coerceAtLeast(0L)
    }

}

private val RecordingStatus.isPause: Boolean
    get() = this == RecordingStatus.MANUAL_PAUSE || this == RecordingStatus.AUTO_PAUSE
