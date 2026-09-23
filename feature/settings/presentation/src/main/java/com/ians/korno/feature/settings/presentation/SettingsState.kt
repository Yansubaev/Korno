package com.ians.korno.feature.settings.presentation

import com.ians.korno.core.domain.model.AutoPauseSensitivity
import com.ians.korno.core.domain.model.GpsAccuracy
import com.ians.korno.core.domain.model.MeasurementSystem
import com.ians.korno.core.domain.model.PrivacyZone
import com.ians.korno.core.domain.model.RecordingNotificationStyle
import com.ians.korno.core.logging.LogSettings
import com.ians.korno.core.logging.LogStorageInfo

internal data class SettingsState(
    val measurementSystem: MeasurementSystem = MeasurementSystem.METRIC,
    val autoPause: Boolean = true,
    val gpsAccuracy: GpsAccuracy = GpsAccuracy.BALANCED,
    val stopSensitivity: AutoPauseSensitivity = AutoPauseSensitivity.MEDIUM,
    val dynamicColor: Boolean = true,
    val privacyZone: PrivacyZone = PrivacyZone.OFF,
    val recordingNotificationStyle: RecordingNotificationStyle = RecordingNotificationStyle.AUTO,
    /** Занятый кэшем карт объём; `null` — ещё не измерен или недоступен. */
    val mapCacheSizeBytes: Long? = null,
    val isClearingMapCache: Boolean = false,
    val snackbarMessage: SettingsMessage? = null,
    val isExporting: Boolean = false,
    val logSettings: LogSettings = LogSettings(isAvailable = false, enabled = false),
    val logStorageInfo: LogStorageInfo = LogStorageInfo(),
    val isPreparingLogArchive: Boolean = false,
    val isClearingLogs: Boolean = false,
)

/**
 * Сообщение снекбара. Счётчик экспорта хранится числом, а не подставленным в строку:
 * форма слова зависит от количества, и выбрать её может только UI-слой.
 */
internal sealed interface SettingsMessage {
    data object MapCacheCleared : SettingsMessage

    data object MapCacheClearFailed : SettingsMessage

    data class Exported(val activityCount: Int) : SettingsMessage

    data object ExportFailed : SettingsMessage

    data object LogsCleared : SettingsMessage

    data object LogsClearFailed : SettingsMessage

    data object LogArchiveFailed : SettingsMessage

    data object NoLogsToShare : SettingsMessage
}

internal sealed interface SettingsEvent {
    data class ShareLogArchive(
        val uri: String,
        val mimeType: String,
    ) : SettingsEvent
}
