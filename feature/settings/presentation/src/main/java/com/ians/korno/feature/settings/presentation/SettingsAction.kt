package com.ians.korno.feature.settings.presentation

import com.ians.korno.core.domain.model.AutoPauseSensitivity
import com.ians.korno.core.domain.model.GpsAccuracy
import com.ians.korno.core.domain.model.MeasurementSystem
import com.ians.korno.core.domain.model.PrivacyZone
import com.ians.korno.core.logging.LogLevel

internal sealed interface SettingsAction {
    data class SetDistanceUnit(val unit: MeasurementSystem) : SettingsAction

    data class SetAutoPause(val enabled: Boolean) : SettingsAction

    data class SetGpsAccuracy(val accuracy: GpsAccuracy) : SettingsAction

    data class SetStopSensitivity(val sensitivity: AutoPauseSensitivity) : SettingsAction

    data class SetDynamicColor(val enabled: Boolean) : SettingsAction

    data class SetPrivacyZone(val zone: PrivacyZone) : SettingsAction

    /** Пользователь выбрал папку в системном диалоге; `null` — закрыл его без выбора. */
    data class ExportFolderChosen(val treeUri: String?) : SettingsAction

    data object ClearCacheClicked : SettingsAction

    data class SetLoggingEnabled(val enabled: Boolean) : SettingsAction

    data class SetMinimumLogLevel(val level: LogLevel) : SettingsAction

    data class SetLocationSampleLogging(val enabled: Boolean) : SettingsAction

    data object ShareLogsClicked : SettingsAction

    data object ClearLogsClicked : SettingsAction

    data object DismissSnackbar : SettingsAction
}
