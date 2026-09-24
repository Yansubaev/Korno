package com.ians.korno.feature.activitylist.presentation

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ians.korno.core.domain.model.Activity
import com.ians.korno.core.domain.model.CalendarPeriodUnit
import com.ians.korno.core.domain.model.MeasurementSystem
import com.ians.korno.core.domain.model.SegmentKind
import com.ians.korno.core.domain.model.TrackFileOperation
import com.ians.korno.core.domain.model.startOfCalendarPeriodMillis
import com.ians.korno.core.domain.repository.UserPreferencesRepository
import com.ians.korno.core.map.MapPoint
import com.ians.korno.core.presentation.OngoingRecordingUi
import com.ians.korno.core.presentation.formatDistance
import com.ians.korno.core.presentation.formatDuration
import com.ians.korno.core.presentation.formatTimerDuration
import com.ians.korno.core.presentation.labelRes
import com.ians.korno.feature.activitylist.domain.usecase.ImportTracksUseCase
import com.ians.korno.feature.activitylist.domain.usecase.ObserveActivityListUseCase
import com.ians.korno.feature.activitylist.domain.usecase.ObserveRoutePreviewsUseCase
import com.ians.korno.feature.activitylist.domain.usecase.ObserveTrackImportUseCase
import com.ians.korno.feature.tracking.domain.usecase.ObserveOngoingRecordingUseCase
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal class ActivityListViewModel(
    private val context: Context,
    observeActivityList: ObserveActivityListUseCase,
    observeOngoingRecording: ObserveOngoingRecordingUseCase,
    private val importTracks: ImportTracksUseCase,
    observeRoutePreviews: ObserveRoutePreviewsUseCase,
    observeTrackImport: ObserveTrackImportUseCase,
    preferencesRepository: UserPreferencesRepository,
) : ViewModel() {

    private data class LocalUi(
        val isImporting: Boolean = false,
        val snackbarMessage: ImportMessage? = null,
    )

    private val searchQuery = MutableStateFlow("")
    private val filterKind = MutableStateFlow<SegmentKind?>(null)
    private val filterPeriod = MutableStateFlow(ActivityPeriod.ALL)
    private val local = MutableStateFlow(LocalUi())

    /** Сколько файлов пользователь выбрал — чтобы сообщить «импортировано N из M». */
    private var requestedFileCount = 0

    // В MapPoint — здесь, а не в общем combine: там перевод повторялся бы на каждый символ поиска.
    private val routePreviews = observeRoutePreviews().map { previews ->
        previews.mapValues { (_, points) -> points.map { MapPoint(it.latitude, it.longitude) } }
    }

    val state: StateFlow<ActivityListState> = combine(
        observeActivityList(),
        searchQuery,
        combine(filterKind, filterPeriod, ::Pair),
        local,
        combine(preferencesRepository.observePreferences(), observeOngoingRecording(), routePreviews, ::Triple),
    ) { snapshot, query, filters, localUi, sources ->
        val (kind, period) = filters
        val (preferences, ongoing, previews) = sources
        val selection = snapshot.select(kind) { activity ->
            (period == ActivityPeriod.ALL || activity.isWithin(period)) &&
                (query.isBlank() || (activity.title ?: "").contains(query, ignoreCase = true))
        }
        ActivityListState(
            isLoading = false,
            searchQuery = query,
            filterKind = kind,
            filterPeriod = period,
            availableKinds = snapshot.chipKinds,
            ongoing = ongoing?.let {
                OngoingRecordingUi(
                    kind = it.segmentKind,
                    status = it.status,
                    timeText = formatTimerDuration(it.elapsedMillis),
                    distanceText = context.formatDistance(it.distanceMeters, preferences.measurementSystem, 1).text,
                )
            },
            items = selection.activities.map {
                it.toUi(snapshot.dominantKindOf(it), preferences.measurementSystem)
            },
            statsDistanceText = context.formatDistance(
                selection.distanceMeters,
                preferences.measurementSystem,
                1,
            ).text,
            statsTimeText = context.formatDuration(selection.movingTimeMillis),
            routePreviews = previews,
            isImporting = localUi.isImporting,
            snackbarMessage = localUi.snackbarMessage,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = ActivityListState(),
    )

    init {
        viewModelScope.launch {
            observeTrackImport().collect { operation -> onImportOperation(operation) }
        }
    }

    fun onAction(action: ActivityListAction) {
        when (action) {
            is ActivityListAction.SetSearchQuery -> searchQuery.update { action.query }
            is ActivityListAction.SetFilterKind -> filterKind.update { action.kind }
            is ActivityListAction.SetFilterPeriod -> filterPeriod.update { action.period }
            is ActivityListAction.ImportFilesChosen -> {
                if (action.uris.isEmpty()) return
                requestedFileCount = action.uris.size
                local.update { it.copy(isImporting = true, snackbarMessage = null) }
                importTracks(action.uris)
            }
            ActivityListAction.DismissSnackbar -> local.update { it.copy(snackbarMessage = null) }
        }
    }

    private fun onImportOperation(operation: TrackFileOperation) {
        when (operation) {
            TrackFileOperation.Idle -> Unit
            TrackFileOperation.Running -> local.update { it.copy(isImporting = true) }
            is TrackFileOperation.Completed -> {
                local.update {
                    it.copy(
                        isImporting = false,
                        snackbarMessage = ImportMessage.Imported(
                            successCount = operation.successCount,
                            totalCount = maxOf(
                                requestedFileCount,
                                operation.successCount + operation.failureCount,
                            ),
                        ),
                    )
                }
            }
            is TrackFileOperation.Failed -> local.update {
                it.copy(
                    isImporting = false,
                    snackbarMessage = ImportMessage.Failed,
                )
            }
        }
    }

    private fun Activity.isWithin(period: ActivityPeriod): Boolean {
        val unit = when (period) {
            ActivityPeriod.WEEK -> CalendarPeriodUnit.WEEK
            ActivityPeriod.MONTH -> CalendarPeriodUnit.MONTH
            ActivityPeriod.YEAR -> CalendarPeriodUnit.YEAR
            ActivityPeriod.ALL -> return true
        }
        return startTimeEpochMillis >= startOfCalendarPeriodMillis(unit)
    }

    private fun Activity.toUi(kind: SegmentKind, measurementSystem: MeasurementSystem): ActivityListItemUi =
        ActivityListItemUi(
            id = id,
            title = title ?: context.getString(kind.labelRes()),
            supportingText = formatDuration(elapsedTimeMillis),
            distanceText = context.formatDistance(distanceMeters, measurementSystem, 1).text,
            kind = kind,
        )

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
