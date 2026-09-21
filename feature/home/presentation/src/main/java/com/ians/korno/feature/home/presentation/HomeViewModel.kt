package com.ians.korno.feature.home.presentation

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ians.korno.core.domain.error.Result
import com.ians.korno.core.domain.model.Activity
import com.ians.korno.core.domain.model.SegmentKind
import com.ians.korno.core.domain.model.MeasurementSystem
import com.ians.korno.core.domain.repository.UserPreferencesRepository
import com.ians.korno.core.presentation.formatDistance
import com.ians.korno.core.presentation.formatDuration
import com.ians.korno.core.presentation.labelRes
import com.ians.korno.core.presentation.R as CoreR
import com.ians.korno.feature.home.domain.model.HomeSummary
import com.ians.korno.feature.home.domain.usecase.ObserveHomeSummaryUseCase
import java.util.Calendar
import java.util.Locale
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

internal class HomeViewModel(
    private val context: Context,
    observeHomeSummary: ObserveHomeSummaryUseCase,
    private val preferencesRepository: UserPreferencesRepository,
) : ViewModel() {

    val state: StateFlow<HomeState> = combine(
        observeHomeSummary(),
        preferencesRepository.observePreferences(),
    ) { result, preferences ->
        result.toUiState(preferences.lastSegmentKind, preferences.measurementSystem)
    }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000L),
            initialValue = HomeState(),
        )

    fun onAction(action: HomeAction) {
        when (action) {
            HomeAction.StartRecordingClicked -> Unit
        }
    }

    private fun Result<HomeSummary, *>.toUiState(
        kind: SegmentKind,
        measurementSystem: MeasurementSystem,
    ): HomeState =
        when (this) {
            is Result.Success -> HomeState(
                isLoading = false,
                weekDistanceText = context.formatDistance(data.weekDistanceMeters, measurementSystem, 1).text,
                weekTimeText = context.formatDuration(data.weekMovingTimeMillis),
                recentActivities = data.recentActivities.map {
                    it.toUi(data.dominantKinds[it.id] ?: SegmentKind.UNKNOWN, measurementSystem)
                },
                hasMoreActivities = data.hasMoreActivitiesThisWeek,
                selectedKind = kind,
            )
            is Result.Error -> HomeState(isLoading = false, selectedKind = kind)
        }

    private fun Activity.toUi(kind: SegmentKind, measurementSystem: MeasurementSystem): RecentActivityUi =
        RecentActivityUi(
            id = id,
            title = title ?: context.getString(kind.labelRes()),
            dateLabel = dateLabel(startTimeEpochMillis),
            durationText = context.formatDuration(elapsedTimeMillis),
            distanceText = context.formatDistance(distanceMeters, measurementSystem, 1).text,
            kind = kind,
        )

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
