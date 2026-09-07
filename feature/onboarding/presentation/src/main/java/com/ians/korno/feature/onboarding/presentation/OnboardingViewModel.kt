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

/** Что произойдёт по кнопке на карточке разрешения. */
internal enum class PermissionAction {
    /** Обычный системный диалог. */
    REQUEST,

    /** Экран настроек приложения: диалога для этого разрешения уже не будет. */
    OPEN_SETTINGS,
}

internal data class OnboardingStep(
    val type: PermissionType,
    val isGranted: Boolean,
    val action: PermissionAction,
    /**
     * Шаг ждёт другого разрешения. Фоновую геолокацию система не выдаёт, пока нет
     * обычной, поэтому предлагать её первой значит вести пользователя в тупик.
     */
    val isBlocked: Boolean,
)

internal data class OnboardingUiState(
    val steps: List<OnboardingStep> = emptyList(),
    val isForegroundLocationDenied: Boolean = false,
    val isFinished: Boolean = false,
) {
    val grantedSteps: Set<PermissionType>
        get() = steps.filter(OnboardingStep::isGranted).map(OnboardingStep::type).toSet()
}

internal class OnboardingViewModel(
    private val permissionRepository: PermissionRepository,
    private val preferencesRepository: UserPreferencesRepository,
) : ViewModel() {

    /**
     * Шаги, для которых системный диалог уже исчерпан: запрос вернулся отказом, и
     * показать диалог второй раз система отказалась.
     */
    private val exhaustedSteps = mutableSetOf<PermissionType>()

    private val _state = MutableStateFlow(OnboardingUiState(steps = buildSteps()))
    val state: StateFlow<OnboardingUiState> = _state.asStateFlow()

    /**
     * @param canAskAgain система ещё готова показать диалог по этому разрешению
     *  (`shouldShowRequestPermissionRationale`). Отказ, после которого диалога больше
     *  не будет, переводит карточку на кнопку «Открыть настройки».
     */
    fun onPermissionResult(type: PermissionType, granted: Boolean, canAskAgain: Boolean = true) {
        if (granted) {
            exhaustedSteps -= type
        } else if (!canAskAgain) {
            exhaustedSteps += type
        }
        refresh(foregroundDenied = type == PermissionType.FOREGROUND_LOCATION && !granted)
    }

    /**
     * Разрешение могли включить мимо приложения — на системном экране, куда ведёт
     * [PermissionAction.OPEN_SETTINGS]. Возврат на экран это подхватывает.
     */
    fun onScreenResumed() {
        refresh()
    }

    fun onContinueClicked() {
        viewModelScope.launch {
            preferencesRepository.setOnboardingCompleted(true)
            _state.update { it.copy(isFinished = true) }
        }
    }

    private fun refresh(foregroundDenied: Boolean? = null) {
        _state.update { current ->
            val steps = buildSteps()
            current.copy(
                steps = steps,
                isForegroundLocationDenied = when {
                    steps.any { it.type == PermissionType.FOREGROUND_LOCATION && it.isGranted } -> false
                    foregroundDenied != null -> foregroundDenied
                    else -> current.isForegroundLocationDenied
                },
            )
        }
    }

    private fun buildSteps(): List<OnboardingStep> {
        val foregroundGranted = permissionRepository.isGranted(PermissionType.FOREGROUND_LOCATION)
        return ONBOARDING_STEPS.map { type ->
            OnboardingStep(
                type = type,
                isGranted = permissionRepository.isGranted(type),
                action = if (permissionRepository.isSettingsOnly(type) || type in exhaustedSteps) {
                    PermissionAction.OPEN_SETTINGS
                } else {
                    PermissionAction.REQUEST
                },
                isBlocked = type == PermissionType.BACKGROUND_LOCATION && !foregroundGranted,
            )
        }
    }
}
