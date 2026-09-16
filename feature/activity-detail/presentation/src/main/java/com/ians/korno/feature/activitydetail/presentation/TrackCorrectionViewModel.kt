package com.ians.korno.feature.activitydetail.presentation

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ians.korno.core.domain.error.Result
import com.ians.korno.core.domain.model.Activity
import com.ians.korno.core.domain.model.ActivitySegment
import com.ians.korno.core.domain.model.GeoPoint
import com.ians.korno.core.domain.model.MeasurementSystem
import com.ians.korno.core.domain.model.RecordingStatus
import com.ians.korno.core.domain.repository.UserPreferencesRepository
import com.ians.korno.core.map.MapPoint
import com.ians.korno.core.presentation.formatDistance
import com.ians.korno.feature.activitydetail.domain.usecase.ObserveActivitySegmentsUseCase
import com.ians.korno.feature.activitydetail.domain.usecase.ObserveActivityUseCase
import com.ians.korno.feature.activitydetail.domain.usecase.ObserveGeoPointsUseCase
import com.ians.korno.feature.activitydetail.domain.usecase.PreviewTrackCorrectionUseCase
import com.ians.korno.feature.activitydetail.domain.usecase.SaveTrackCorrectionUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private data class WorkingPoint(
    val point: GeoPoint,
    val deleted: Boolean,
)

internal class TrackCorrectionViewModel(
    private val context: Context,
    private val activityId: Long,
    observeActivity: ObserveActivityUseCase,
    observeGeoPoints: ObserveGeoPointsUseCase,
    observeActivitySegments: ObserveActivitySegmentsUseCase,
    private val previewTrackCorrection: PreviewTrackCorrectionUseCase,
    private val saveTrackCorrection: SaveTrackCorrectionUseCase,
    preferencesRepository: UserPreferencesRepository,
) : ViewModel() {
    private var activity: Activity? = null
    private var originalDistanceMeters = 0.0
    private var originalPoints: List<GeoPoint> = emptyList()
    private var segments: List<ActivitySegment> = emptyList()
    private var basePoints: List<WorkingPoint> = emptyList()
    private var loaded = false

    private val working = MutableStateFlow<List<WorkingPoint>>(emptyList())
    private val history = MutableStateFlow<List<List<WorkingPoint>>>(emptyList())
    private val selectedId = MutableStateFlow<Long?>(null)
    private val mode = MutableStateFlow(TrackCorrectionMode.DELETE_POINTS)
    private val saved = MutableStateFlow(false)
    private val saving = MutableStateFlow(false)

    private val workingAndMeasurementSystem = combine(
        working,
        preferencesRepository.observePreferences(),
    ) { points, preferences -> points to preferences.measurementSystem }

    private val selectionAndMode = combine(selectedId, mode) { selected, currentMode -> selected to currentMode }

    val state: StateFlow<TrackCorrectionState> = combine(
        workingAndMeasurementSystem,
        history,
        selectionAndMode,
        saved,
        saving,
    ) { (points, measurementSystem), hist, (selected, currentMode), isSaved, isSaving ->
        buildState(points, hist, selected, currentMode, isSaved, isSaving, measurementSystem)
    }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000L),
            initialValue = TrackCorrectionState(),
        )

    init {
        viewModelScope.launch {
            combine(
                observeActivity(activityId),
                observeGeoPoints(activityId),
                observeActivitySegments(activityId),
            ) { a, p, s -> Triple(a, p, s) }
                .collect { (activityResult, pointsResult, activitySegments) ->
                    if (!loaded && activityResult is Result.Success && pointsResult is Result.Success) {
                        loaded = true
                        activity = activityResult.data
                        originalDistanceMeters = activityResult.data.distanceMeters
                        originalPoints = pointsResult.data
                        segments = activitySegments
                        basePoints = originalPoints.map { WorkingPoint(point = it, deleted = false) }
                        working.value = basePoints
                    }
                }
        }
    }

    fun onAction(action: TrackCorrectionAction) {
        when (action) {
            is TrackCorrectionAction.SelectPoint ->
                selectedId.update { if (it == action.id) null else action.id }
            TrackCorrectionAction.DeleteSelected -> {
                val id = selectedId.value ?: return
                history.update { it + listOf(working.value) }
                working.update { list ->
                    list.map { if (it.point.id == id) it.copy(deleted = true) else it }
                }
                selectedId.update { null }
            }
            is TrackCorrectionAction.SetMode -> {
                mode.update { action.mode }
                selectedId.update { null }
            }
            is TrackCorrectionAction.TrimRange -> {
                val active = working.value.filter { !it.deleted }
                if (action.startIndex <= 0 && action.endIndex >= active.lastIndex) return
                val idsToTrim = (active.take(action.startIndex) + active.takeLast(active.size - 1 - action.endIndex))
                    .map { it.point.id }.toSet()
                if (idsToTrim.isEmpty()) return
                history.update { it + listOf(working.value) }
                working.update { list -> list.map { if (it.point.id in idsToTrim) it.copy(deleted = true) else it } }
                selectedId.update { null }
            }
            TrackCorrectionAction.Undo -> {
                val previous = history.value.lastOrNull() ?: return
                working.value = previous
                history.update { it.dropLast(1) }
                selectedId.update { null }
            }
            TrackCorrectionAction.Reset -> {
                working.value = basePoints
                history.update { emptyList() }
                selectedId.update { null }
            }
            TrackCorrectionAction.Save -> save()
        }
    }

    private fun save() {
        val currentActivity = activity ?: return
        if (saving.value) return
        val removedPointIds = working.value.filter(WorkingPoint::deleted).map { it.point.id }.toSet()
        viewModelScope.launch {
            saving.value = true
            when (saveTrackCorrection(currentActivity, originalPoints, segments, removedPointIds)) {
                is Result.Success -> saved.value = true
                is Result.Error -> saving.value = false
            }
        }
    }

    private fun buildState(
        points: List<WorkingPoint>,
        hist: List<List<WorkingPoint>>,
        selected: Long?,
        currentMode: TrackCorrectionMode,
        isSaved: Boolean,
        isSaving: Boolean,
        measurementSystem: MeasurementSystem,
    ): TrackCorrectionState {
        val active = points.filter { !it.deleted }
        val currentDistance = activity?.let { currentActivity ->
            previewTrackCorrection(
                activity = currentActivity,
                points = originalPoints,
                segments = segments,
                removedPointIds = points.filter(WorkingPoint::deleted).map { it.point.id }.toSet(),
            )
        } ?: 0.0
        return TrackCorrectionState(
            isLoading = !loaded,
            mode = currentMode,
            points = points.map { point ->
                CorrectionPointUi(
                    id = point.point.id,
                    point = MapPoint(
                        latitude = point.point.latitude,
                        longitude = point.point.longitude,
                        isPaused = point.point.isStop || point.point.recordingStatus.isPause,
                    ),
                    isOutlier = point.point.isOutlier,
                    deleted = point.deleted,
                    selected = point.point.id == selected,
                )
            },
            originalDistanceText = context.formatDistance(originalDistanceMeters, measurementSystem).text,
            currentDistanceText = context.formatDistance(currentDistance, measurementSystem).text,
            removedCount = points.size - active.size,
            hasSelection = selected != null,
            canUndo = hist.isNotEmpty(),
            isSaving = isSaving,
            saved = isSaved,
        )
    }

}

private val RecordingStatus.isPause: Boolean
    get() = this == RecordingStatus.MANUAL_PAUSE || this == RecordingStatus.AUTO_PAUSE
