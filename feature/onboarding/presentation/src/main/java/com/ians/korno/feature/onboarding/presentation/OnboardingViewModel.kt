package com.ians.korno.feature.onboarding.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ians.korno.core.domain.repository.UserPreferencesRepository
import com.ians.korno.feature.onboarding.domain.model.PermissionType
import com.ians.korno.feature.onboarding.domain.repository.PermissionRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private val ONBOARDING_STEPS = listOf(
    PermissionType.FOREGROUND_LOCATION,
    PermissionType.BACKGROUND_LOCATION,
    PermissionType.ACTIVITY_RECOGNITION,
    PermissionType.POST_NOTIFICATIONS,
)

internal data class OnboardingUiState(
    val steps: List<PermissionType> = emptyList(),
    val grantedSteps: Set<PermissionType> = emptySet(),
    val isForegroundLocationDenied: Boolean = false,
    val isFinished: Boolean = false,
)

internal class OnboardingViewModel(
    private val permissionRepository: PermissionRepository,
    private val preferencesRepository: UserPreferencesRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(buildInitialState())
    val state: StateFlow<OnboardingUiState> = _state.asStateFlow()

    fun onPermissionResult(type: PermissionType, granted: Boolean) {
        _state.update { current ->
            if (type == PermissionType.FOREGROUND_LOCATION && !granted) {
                current.copy(isForegroundLocationDenied = true)
            } else {
                current.copy(
                    grantedSteps = current.grantedSteps + type,
                    isForegroundLocationDenied = if (type == PermissionType.FOREGROUND_LOCATION) {
                        false
                    } else {
                        current.isForegroundLocationDenied
                    },
                )
            }
        }
    }

    fun onContinueClicked() {
        viewModelScope.launch {
            preferencesRepository.setOnboardingCompleted(true)
            _state.update { it.copy(isFinished = true) }
        }
    }

    private fun buildInitialState(): OnboardingUiState {
        val granted = ONBOARDING_STEPS.filter(permissionRepository::isGranted).toSet()
        return OnboardingUiState(steps = ONBOARDING_STEPS, grantedSteps = granted)
    }
}
