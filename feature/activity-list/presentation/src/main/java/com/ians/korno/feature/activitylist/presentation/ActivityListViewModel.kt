package com.ians.korno.feature.activitylist.presentation

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ians.korno.core.domain.error.Result
import com.ians.korno.core.domain.model.Activity
import com.ians.korno.core.domain.model.ActivityType
import com.ians.korno.core.domain.model.MeasurementSystem
import com.ians.korno.core.domain.model.TrackFileOperation
import com.ians.korno.core.domain.repository.UserPreferencesRepository
import com.ians.korno.core.map.MapPoint
import com.ians.korno.core.presentation.formatDistance
import com.ians.korno.feature.activitylist.domain.usecase.GetRoutePreviewUseCase
import com.ians.korno.feature.activitylist.domain.usecase.ImportTracksUseCase
import com.ians.korno.feature.activitylist.domain.usecase.ObserveActivitiesUseCase
import com.ians.korno.feature.activitylist.domain.usecase.ObserveTrackImportUseCase
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val DAY_MILLIS = 24L * 60 * 60 * 1000

internal class ActivityListViewModel(
    private val context: Context,
    observeActivities: ObserveActivitiesUseCase,
    private val importTracks: ImportTracksUseCase,
    private val getRoutePreview: GetRoutePreviewUseCase,
    observeTrackImport: ObserveTrackImportUseCase,
    preferencesRepository: UserPreferencesRepository,
) : ViewModel() {

    private data class LocalUi(
        val isImporting: Boolean = false,
        val snackbarMessage: ImportMessage? = null,
        val routePreviews: Map<Long, List<MapPoint>> = emptyMap(),
    )

    /**
     * Идентификаторы, для которых маршрут уже запрошен. Отдельно от карты в состоянии:
     * пустой трек тоже считается загруженным, иначе прокрутка запрашивала бы его снова.
     */
    private val requestedPreviews = mutableSetOf<Long>()

    private val searchQuery = MutableStateFlow("")
    private val filterType = MutableStateFlow<ActivityType?>(null)
    private val filterPeriod = MutableStateFlow(ActivityPeriod.ALL)
    private val local = MutableStateFlow(LocalUi())

    /** Сколько файлов пользователь выбрал — чтобы сообщить «импортировано N из M». */
    private var requestedFileCount = 0

    val state: StateFlow<ActivityListState> = combine(
        observeActivities(),
        searchQuery,
        combine(filterType, filterPeriod, ::Pair),
        local,
        preferencesRepository.observePreferences(),
    ) { result, query, filters, localUi, preferences ->
        val (type, period) = filters
        val activities = (result as? Result.Success)?.data.orEmpty()
        val filtered = activities
            .filter { type == null || it.activityType == type }
            .filter { period == ActivityPeriod.ALL || it.isWithin(period) }
            .filter { query.isBlank() || (it.title ?: "").contains(query, ignoreCase = true) }
        ActivityListState(
            isLoading = false,
            searchQuery = query,
            filterType = type,
            filterPeriod = period,
            availableTypes = ACTIVITY_LIST_TYPES.filter { candidate -> activities.any { it.activityType == candidate } },
            items = filtered.map { it.toUi(preferences.measurementSystem) },
            routePreviews = localUi.routePreviews,
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
            is ActivityListAction.SetFilterType -> filterType.update { action.type }
            is ActivityListAction.SetFilterPeriod -> filterPeriod.update { action.period }
            is ActivityListAction.RowShown -> loadRoutePreview(action.activityId)
            is ActivityListAction.ImportFilesChosen -> {
                if (action.uris.isEmpty()) return
                requestedFileCount = action.uris.size
                local.update { it.copy(isImporting = true, snackbarMessage = null) }
                importTracks(action.uris)
            }
            ActivityListAction.DismissSnackbar -> local.update { it.copy(snackbarMessage = null) }
        }
    }

    private fun loadRoutePreview(activityId: Long) {
        if (!requestedPreviews.add(activityId)) return
        viewModelScope.launch {
            val points = (getRoutePreview(activityId) as? Result.Success)?.data.orEmpty()
            if (points.isEmpty()) return@launch
            local.update { current ->
                current.copy(
                    routePreviews = current.routePreviews +
                        (activityId to points.map { MapPoint(it.latitude, it.longitude) }),
                )
            }
        }
    }

    private fun onImportOperation(operation: TrackFileOperation) {
        when (operation) {
            TrackFileOperation.Idle -> Unit
            TrackFileOperation.Running -> local.update { it.copy(isImporting = true) }
            is TrackFileOperation.Completed -> {
                // Импортированные активности — новые строки списка, и их миниатюры
                // ещё не запрашивались.
                requestedPreviews.clear()
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
        val windowMillis = when (period) {
            ActivityPeriod.WEEK -> 7 * DAY_MILLIS
            ActivityPeriod.MONTH -> 30 * DAY_MILLIS
            ActivityPeriod.YEAR -> 365 * DAY_MILLIS
            ActivityPeriod.ALL -> return true
        }
        return startTimeEpochMillis >= System.currentTimeMillis() - windowMillis
    }

    private fun Activity.toUi(measurementSystem: MeasurementSystem): ActivityListItemUi = ActivityListItemUi(
        id = id,
        title = title ?: activityType.name,
        supportingText = formatDuration(elapsedTimeMillis),
        distanceText = context.formatDistance(distanceMeters, measurementSystem, 1).text,
        type = activityType,
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
