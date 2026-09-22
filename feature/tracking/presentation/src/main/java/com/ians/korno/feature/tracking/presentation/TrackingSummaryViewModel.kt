package com.ians.korno.feature.tracking.presentation

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ians.korno.core.domain.error.Result
import com.ians.korno.core.domain.model.Activity
import com.ians.korno.core.domain.model.ActivitySegment
import com.ians.korno.core.domain.model.GeoPoint
import com.ians.korno.core.domain.model.RecordingMode
import com.ians.korno.core.domain.model.SegmentKind
import com.ians.korno.core.domain.model.dominantKind
import com.ians.korno.core.domain.model.MeasurementSystem
import com.ians.korno.core.domain.repository.UserPreferencesRepository
import com.ians.korno.core.logging.LogManager
import com.ians.korno.core.map.MapPoint
import com.ians.korno.core.presentation.formatDistance
import com.ians.korno.core.presentation.formatSpeed
import com.ians.korno.core.trackprocessing.ChangeActivitySegmentKindUseCase
import com.ians.korno.feature.tracking.domain.usecase.DiscardActivityUseCase
import com.ians.korno.feature.tracking.domain.usecase.ObserveActivityUseCase
import com.ians.korno.feature.tracking.domain.usecase.ObserveActivitySegmentsUseCase
import com.ians.korno.feature.tracking.domain.usecase.ObserveTrackPointsUseCase
import com.ians.korno.feature.tracking.domain.usecase.SaveActivitySummaryUseCase
import java.util.Calendar
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal class TrackingSummaryViewModel(
    private val context: Context,
    private val activityId: Long,
    observeActivity: ObserveActivityUseCase,
    observeSegments: ObserveActivitySegmentsUseCase,
    observeTrackPoints: ObserveTrackPointsUseCase,
    private val saveActivitySummary: SaveActivitySummaryUseCase,
    private val changeSegmentKind: ChangeActivitySegmentKindUseCase,
    private val discardActivity: DiscardActivityUseCase,
    private val logManager: LogManager,
    preferencesRepository: UserPreferencesRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(TrackingSummaryState())
    val state: StateFlow<TrackingSummaryState> = _state.asStateFlow()

    private var loadedActivity: Activity? = null
    private var loadedSegments: List<ActivitySegment> = emptyList()
    private var loadedPoints: List<GeoPoint> = emptyList()

    /** Пока пользователь не тронул название, оно следует за доминирующим видом записи. */
    private var titleEditedByUser = false

    init {
        viewModelScope.launch {
            combine(
                observeActivity(activityId),
                observeSegments(activityId),
                preferencesRepository.observePreferences(),
            ) { result, segments, preferences ->
                Triple(result, segments, preferences.measurementSystem)
            }
                .collect { (result, segments, measurementSystem) ->
                if (result is Result.Success) {
                    loadedActivity = result.data
                    loadedSegments = segments
                    val kind = segments.dominantKind() ?: SegmentKind.UNKNOWN
                    // Ряд чипов — только у фиксированной записи: участок там один, и правка
                    // однозначна. У авто-записи виды уже размечены детектором и правятся на
                    // экране активности, где видно, какой участок правишь (`docs/adr/0004`).
                    val editableKind = segments.singleOrNull()
                        ?.takeIf { result.data.recordingMode == RecordingMode.FIXED }
                        ?.kind
                    _state.update {
                        it.copy(
                            isLoading = false,
                            editableKind = editableKind,
                            title = if (titleEditedByUser) {
                                it.title
                            } else {
                                defaultTitle(result.data.startTimeEpochMillis, kind)
                            },
                        ) + result.data.toStats(measurementSystem)
                    }
                }
            }
        }
        viewModelScope.launch {
            observeTrackPoints(activityId).collect { points ->
                loadedPoints = points
                val route = points.map { MapPoint(it.latitude, it.longitude) }
                _state.update { it.copy(routePoints = route) }
            }
        }
    }

    fun onAction(action: TrackingSummaryAction) {
        when (action) {
            // Правка пишется сразу, а не при сохранении: трек пересчитывается порогами
            // нового вида, и итоги на экране должны показать уже пересчитанное.
            is TrackingSummaryAction.SelectSegmentKind -> viewModelScope.launch {
                val activity = loadedActivity ?: return@launch
                if (action.kind == _state.value.editableKind) return@launch
                changeSegmentKind(activity, loadedPoints, loadedSegments, segmentIndex = 0, kind = action.kind)
            }
            is TrackingSummaryAction.SetTitle -> {
                titleEditedByUser = true
                _state.update { it.copy(title = action.title) }
            }
            is TrackingSummaryAction.SetNote -> _state.update { it.copy(note = action.note) }
            TrackingSummaryAction.SaveClicked -> viewModelScope.launch {
                val activity = loadedActivity ?: return@launch
                val currentState = _state.value
                saveActivitySummary(
                    activity = activity,
                    title = currentState.title,
                    note = currentState.note,
                )
                _state.update { it.copy(saved = true) }
            }
            TrackingSummaryAction.DiscardClicked -> viewModelScope.launch {
                discardActivity(activityId)
                // Отменённая тренировка не должна оставлять после себя лог со своим маршрутом.
                logManager.deleteActivityLogs(activityId)
                _state.update { it.copy(discarded = true) }
            }
        }
    }

    private fun defaultTitle(startTimeEpochMillis: Long, kind: SegmentKind): String {
        val hour = Calendar.getInstance().apply {
            timeInMillis = startTimeEpochMillis
        }.get(Calendar.HOUR_OF_DAY)
        val timeOfDay = when {
            hour < 12 -> R.string.tracking_summary_morning
            hour < 18 -> R.string.tracking_summary_afternoon
            else -> R.string.tracking_summary_evening
        }
        val activityNoun = when (kind) {
            SegmentKind.RUNNING -> R.string.tracking_summary_activity_run
            SegmentKind.CYCLING -> R.string.tracking_summary_activity_ride
            SegmentKind.ON_FOOT -> R.string.tracking_summary_activity_walk
            SegmentKind.VEHICLE -> R.string.tracking_summary_activity_drive
            SegmentKind.HIKING -> R.string.tracking_summary_activity_hike
            SegmentKind.UNKNOWN, SegmentKind.PAUSE -> R.string.tracking_summary_activity_other
        }
        return context.getString(
            R.string.tracking_summary_default_title,
            context.getString(timeOfDay),
            context.getString(activityNoun),
        )
    }

    private fun Activity.toStats(measurementSystem: MeasurementSystem): TrackingSummaryState {
        return TrackingSummaryState(
            distanceText = context.formatDistance(distanceMeters, measurementSystem).text,
            durationText = formatDuration(elapsedTimeMillis),
            paceText = if (movingTimeMillis > 0) {
                // Сохранённая средняя, а не дистанция на время: в авторежиме транспорт в неё
                // не входит, хотя в дистанции есть.
                context.formatSpeed(averageSpeedMetersPerSecond, measurementSystem).text
            } else {
                "—"
            },
            stepsText = stepCount?.let {
                context.resources.getQuantityString(R.plurals.tracking_steps_count, it, it)
            },
        )
    }

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

    private operator fun TrackingSummaryState.plus(stats: TrackingSummaryState): TrackingSummaryState = copy(
        distanceText = stats.distanceText,
        durationText = stats.durationText,
        paceText = stats.paceText,
        stepsText = stats.stepsText,
    )
}
