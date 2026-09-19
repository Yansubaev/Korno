package com.ians.korno

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ians.korno.core.domain.model.ActivityType
import com.ians.korno.core.domain.repository.UserPreferencesRepository
import com.ians.korno.feature.onboarding.domain.model.PermissionType
import com.ians.korno.feature.onboarding.domain.repository.PermissionRepository
import com.ians.korno.feature.tracking.domain.usecase.PrepareTrackingUseCase
import com.ians.korno.feature.tracking.domain.usecase.RecoverActiveSessionUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class KornoAppState(
    val isReady: Boolean = false,
    val onboardingCompleted: Boolean = false,
    // Совпадает со значением по умолчанию в настройках, чтобы тема не мигала,
    // пока читается DataStore.
    val dynamicColorEnabled: Boolean = true,
)

sealed interface KornoAppEvent {
    data object RequestForegroundLocation : KornoAppEvent
    data object LocationPermissionDenied : KornoAppEvent
    data class OpenTrackingSetup(val activityType: ActivityType) : KornoAppEvent
}

class KornoAppViewModel(
    private val preferencesRepository: UserPreferencesRepository,
    private val permissionRepository: PermissionRepository,
    private val prepareTracking: PrepareTrackingUseCase,
    private val recoverActiveSession: RecoverActiveSessionUseCase,
) : ViewModel() {

    private val _state = MutableStateFlow(KornoAppState())
    val state: StateFlow<KornoAppState> = _state.asStateFlow()
    private val eventChannel = Channel<KornoAppEvent>()
    val events = eventChannel.receiveAsFlow()
    private var pendingActivityType: ActivityType? = null

    init {
        // Если процесс был убит во время записи — незавершённая Activity (end_time IS NULL)
        // в Room служит сигналом восстановить foreground-сервис и продолжить ту же запись.
        // Здесь, а не в Application: только с видимым UI система разрешает старт foreground-сервиса.
        viewModelScope.launch(Dispatchers.IO) { recoverActiveSession() }
        viewModelScope.launch {
            preferencesRepository.initialize()
            preferencesRepository.observePreferences().collect { preferences ->
                _state.update { current ->
                    current.copy(
                        isReady = true,
                        // Значение фиксируется один раз: onboardingCompleted задаёт
                        // startDestination, а его смена под живым NavHost пересоздала бы
                        // граф и сбросила бэкстек ровно в момент завершения онбординга.
                        onboardingCompleted = if (current.isReady) {
                            current.onboardingCompleted
                        } else {
                            preferences.onboardingCompleted
                        },
                        dynamicColorEnabled = preferences.dynamicColorEnabled,
                    )
                }
            }
        }
    }

    fun onStartActivityRequested(activityType: ActivityType) {
        if (permissionRepository.isGranted(PermissionType.FOREGROUND_LOCATION)) {
            openTrackingSetup(activityType)
        } else {
            pendingActivityType = activityType
            viewModelScope.launch { eventChannel.send(KornoAppEvent.RequestForegroundLocation) }
        }
    }

    fun onForegroundPermissionResult(granted: Boolean) {
        val activityType = pendingActivityType ?: return
        pendingActivityType = null
        if (granted || permissionRepository.isGranted(PermissionType.FOREGROUND_LOCATION)) {
            openTrackingSetup(activityType)
        } else {
            viewModelScope.launch { eventChannel.send(KornoAppEvent.LocationPermissionDenied) }
        }
    }

    private fun openTrackingSetup(activityType: ActivityType) {
        prepareTracking()
        viewModelScope.launch { eventChannel.send(KornoAppEvent.OpenTrackingSetup(activityType)) }
    }
}
