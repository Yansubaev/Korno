package com.ians.korno.feature.home.presentation

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ians.korno.core.domain.error.Result
import com.ians.korno.core.domain.model.Activity
import com.ians.korno.core.domain.model.ActivityType
import com.ians.korno.core.domain.model.MeasurementSystem
import com.ians.korno.core.domain.repository.UserPreferencesRepository
import com.ians.korno.core.presentation.formatDistance
import com.ians.korno.core.presentation.R as CoreR
import com.ians.korno.feature.home.domain.model.HomeSummary
import com.ians.korno.feature.home.domain.usecase.ObserveHomeSummaryUseCase
import java.util.Calendar
import java.util.Locale
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

internal class HomeViewModel(
    private val context: Context,
    observeHomeSummary: ObserveHomeSummaryUseCase,
    private val preferencesRepository: UserPreferencesRepository,
) : ViewModel() {

    val state: StateFlow<HomeState> = combine(
        observeHomeSummary(),
        preferencesRepository.observePreferences(),
    ) { result, preferences ->
        result.toUiState(preferences.lastActivityType, preferences.measurementSystem)
    }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000L),
            initialValue = HomeState(),
        )

    fun onAction(action: HomeAction) {
        when (action) {
            // Выбор сохраняется сразу, а не по нажатию «Начать»: на следующем запуске
            // экран должен открыться с той же активностью, даже если запись не началась.
            is HomeAction.SelectActivityType -> viewModelScope.launch {
                preferencesRepository.setLastActivityType(action.type)
            }
            HomeAction.StartRecordingClicked -> Unit
        }
    }

    private fun Result<HomeSummary, *>.toUiState(
        type: ActivityType,
        measurementSystem: MeasurementSystem,
    ): HomeState =
        when (this) {
            is Result.Success -> HomeState(
                isLoading = false,
                weekDistanceText = context.formatDistance(data.weekDistanceMeters, measurementSystem, 1).text,
                weekTimeText = formatDuration(data.weekMovingTimeMillis),
                recentActivities = data.recentActivities.map { it.toUi(measurementSystem) },
                selectedType = type,
            )
            is Result.Error -> HomeState(isLoading = false, selectedType = type)
        }

    private fun Activity.toUi(measurementSystem: MeasurementSystem): RecentActivityUi = RecentActivityUi(
        id = id,
        title = title ?: activityType.name,
        dateLabel = dateLabel(startTimeEpochMillis),
        durationText = formatDuration(elapsedTimeMillis),
        distanceText = context.formatDistance(distanceMeters, measurementSystem, 1).text,
        type = activityType,
    )

    private fun formatDuration(millis: Long): String {
        val totalMinutes = millis / 60_000
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        return if (hours > 0) {
            context.getString(CoreR.string.duration_hours_minutes, hours, minutes)
        } else {
            context.getString(CoreR.string.duration_minutes, minutes)
        }
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
