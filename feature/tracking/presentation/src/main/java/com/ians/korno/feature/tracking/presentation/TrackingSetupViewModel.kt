package com.ians.korno.feature.tracking.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ians.korno.core.domain.model.ActivityType
import com.ians.korno.core.domain.repository.UserPreferencesRepository
import com.ians.korno.feature.tracking.domain.usecase.StartTrackingUseCase
import com.ians.korno.feature.tracking.domain.usecase.CancelTrackingPreparationUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal class TrackingSetupViewModel(
    initialActivityType: ActivityType,
    private val startTracking: StartTrackingUseCase,
    private val preferencesRepository: UserPreferencesRepository,
    private val cancelTrackingPreparation: CancelTrackingPreparationUseCase,
) : ViewModel() {

    private val _state = MutableStateFlow(TrackingSetupState(activityType = initialActivityType))
    val state: StateFlow<TrackingSetupState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            preferencesRepository.observePreferences().collect { preferences ->
                _state.update {
                    it.copy(
                        autoPause = preferences.autoPauseEnabled,
                        gpsAccuracy = preferences.gpsAccuracy,
                        autoPauseSensitivity = preferences.autoPauseSensitivity,
                    )
                }
            }
        }
    }

    fun onAction(action: TrackingSetupAction) {
        when (action) {
            // Экран открыт с типом из маршрута, поэтому он живёт в локальном состоянии,
            // а в настройки уходит копией — чтобы следующая сессия начиналась с него же.
            is TrackingSetupAction.SelectActivityType -> {
                _state.update { it.copy(activityType = action.type) }
                viewModelScope.launch { preferencesRepository.setLastActivityType(action.type) }
            }
            is TrackingSetupAction.SetAutoPause -> viewModelScope.launch {
                preferencesRepository.setAutoPauseEnabled(action.enabled)
            }
            is TrackingSetupAction.SelectGpsAccuracy -> viewModelScope.launch {
                preferencesRepository.setGpsAccuracy(action.accuracy)
            }
            is TrackingSetupAction.SelectAutoPauseSensitivity -> viewModelScope.launch {
                preferencesRepository.setAutoPauseSensitivity(action.sensitivity)
            }
            TrackingSetupAction.StartClicked -> viewModelScope.launch {
                startTracking(_state.value.activityType)
                _state.update { it.copy(started = true) }
            }
        }
    }

    fun onBack() {
        cancelTrackingPreparation()
    }
}
