package com.ians.korno.feature.settings.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ians.korno.core.domain.model.PrivacyZone
import com.ians.korno.core.domain.model.TrackFileFormat
import com.ians.korno.core.domain.model.TrackFileOperation
import com.ians.korno.core.domain.repository.UserPreferencesRepository
import com.ians.korno.core.domain.error.Result
import com.ians.korno.core.logging.LogArchiveResult
import com.ians.korno.core.logging.LogManager
import com.ians.korno.feature.settings.domain.usecase.ClearMapCacheUseCase
import com.ians.korno.feature.settings.domain.usecase.ExportAllActivitiesUseCase
import com.ians.korno.feature.settings.domain.usecase.GetMapCacheSizeUseCase
import com.ians.korno.feature.settings.domain.usecase.ObserveTrackExportUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

internal class SettingsViewModel(
    private val preferencesRepository: UserPreferencesRepository,
    private val exportAllActivities: ExportAllActivitiesUseCase,
    private val getMapCacheSize: GetMapCacheSizeUseCase,
    private val clearMapCache: ClearMapCacheUseCase,
    private val logManager: LogManager,
    observeTrackExport: ObserveTrackExportUseCase,
) : ViewModel() {

    private val _state = MutableStateFlow(SettingsState())
    val state: StateFlow<SettingsState> = _state.asStateFlow()
    private val eventChannel = Channel<SettingsEvent>()
    val events = eventChannel.receiveAsFlow()

    init {
        viewModelScope.launch {
            preferencesRepository.observePreferences().collect { preferences ->
                _state.update {
                    it.copy(
                        measurementSystem = preferences.measurementSystem,
                        autoPause = preferences.autoPauseEnabled,
                        gpsAccuracy = preferences.gpsAccuracy,
                        stopSensitivity = preferences.autoPauseSensitivity,
                        dynamicColor = preferences.dynamicColorEnabled,
                        privacyZone = preferences.privacyZone,
                    )
                }
            }
        }
        viewModelScope.launch {
            observeTrackExport().collect(::onExportOperation)
        }
        viewModelScope.launch {
            logManager.observeSettings().collect { settings ->
                _state.update { it.copy(logSettings = settings) }
                if (settings.isAvailable) refreshLogStorageInfo()
            }
        }
        refreshMapCacheSize()
    }

    private fun refreshMapCacheSize() {
        viewModelScope.launch {
            val size = getMapCacheSize()
            _state.update { it.copy(mapCacheSizeBytes = size) }
        }
    }

    fun onAction(action: SettingsAction) {
        when (action) {
            is SettingsAction.SetDistanceUnit -> viewModelScope.launch {
                preferencesRepository.setMeasurementSystem(action.unit)
            }
            is SettingsAction.SetAutoPause -> viewModelScope.launch {
                preferencesRepository.setAutoPauseEnabled(action.enabled)
            }
            is SettingsAction.SetGpsAccuracy -> viewModelScope.launch {
                preferencesRepository.setGpsAccuracy(action.accuracy)
            }
            is SettingsAction.SetStopSensitivity -> viewModelScope.launch {
                preferencesRepository.setAutoPauseSensitivity(action.sensitivity)
            }
            is SettingsAction.SetDynamicColor -> viewModelScope.launch {
                preferencesRepository.setDynamicColorEnabled(action.enabled)
            }
            is SettingsAction.SetPrivacyZone -> viewModelScope.launch {
                preferencesRepository.setPrivacyZone(action.zone)
            }
            is SettingsAction.ExportFolderChosen -> {
                val treeUri = action.treeUri ?: return
                _state.update { it.copy(isExporting = true, snackbarMessage = null) }
                // Массовый экспорт всегда в GPX: это формат, который читают все
                // сервисы, а выбор на каждый файл здесь только мешал бы.
                exportAllActivities(treeUri, TrackFileFormat.GPX)
            }
            SettingsAction.ClearCacheClicked -> viewModelScope.launch {
                _state.update { it.copy(isClearingMapCache = true, snackbarMessage = null) }
                val message = when (clearMapCache()) {
                    is Result.Success -> SettingsMessage.MapCacheCleared
                    is Result.Error -> SettingsMessage.MapCacheClearFailed
                }
                _state.update { it.copy(isClearingMapCache = false, snackbarMessage = message) }
                refreshMapCacheSize()
            }
            is SettingsAction.SetLoggingEnabled -> viewModelScope.launch {
                logManager.setEnabled(action.enabled)
            }
            is SettingsAction.SetMinimumLogLevel -> viewModelScope.launch {
                logManager.setMinimumLevel(action.level)
            }
            is SettingsAction.SetLocationSampleLogging -> viewModelScope.launch {
                logManager.setIncludeLocationSamples(action.enabled)
            }
            SettingsAction.ShareLogsClicked -> shareLogs()
            SettingsAction.ClearLogsClicked -> clearLogs()
            SettingsAction.DismissSnackbar -> _state.update { it.copy(snackbarMessage = null) }
        }
    }

    private fun refreshLogStorageInfo() {
        viewModelScope.launch {
            _state.update { it.copy(logStorageInfo = logManager.getStorageInfo()) }
        }
    }

    private fun shareLogs() {
        viewModelScope.launch {
            _state.update { it.copy(isPreparingLogArchive = true, snackbarMessage = null) }
            when (val result = logManager.createArchive()) {
                is LogArchiveResult.Success -> eventChannel.send(
                    SettingsEvent.ShareLogArchive(
                        uri = result.archive.uri,
                        mimeType = result.archive.mimeType,
                    ),
                )
                LogArchiveResult.NoLogs -> _state.update {
                    it.copy(snackbarMessage = SettingsMessage.NoLogsToShare)
                }
                LogArchiveResult.Error -> _state.update {
                    it.copy(snackbarMessage = SettingsMessage.LogArchiveFailed)
                }
            }
            _state.update { it.copy(isPreparingLogArchive = false) }
            refreshLogStorageInfo()
        }
    }

    private fun clearLogs() {
        viewModelScope.launch {
            _state.update { it.copy(isClearingLogs = true, snackbarMessage = null) }
            val cleared = logManager.clearLogs()
            _state.update {
                it.copy(
                    isClearingLogs = false,
                    snackbarMessage = if (cleared) {
                        SettingsMessage.LogsCleared
                    } else {
                        SettingsMessage.LogsClearFailed
                    },
                )
            }
            refreshLogStorageInfo()
        }
    }

    private fun onExportOperation(operation: TrackFileOperation) {
        when (operation) {
            TrackFileOperation.Idle -> Unit
            TrackFileOperation.Running -> _state.update { it.copy(isExporting = true) }
            is TrackFileOperation.Completed -> _state.update {
                it.copy(
                    isExporting = false,
                    snackbarMessage = SettingsMessage.Exported(operation.successCount),
                )
            }
            is TrackFileOperation.Failed -> _state.update {
                it.copy(isExporting = false, snackbarMessage = SettingsMessage.ExportFailed)
            }
        }
    }
}
