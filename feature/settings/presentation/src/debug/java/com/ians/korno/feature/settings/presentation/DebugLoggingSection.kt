package com.ians.korno.feature.settings.presentation

import android.text.format.Formatter
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ians.korno.core.domain.model.RecordingNotificationStyle
import com.ians.korno.core.logging.LogLevel

@Composable
internal fun DebugLoggingSection(
    state: SettingsState,
    onAction: (SettingsAction) -> Unit,
) {
    if (!state.logSettings.isAvailable) return

    Column(modifier = Modifier.padding(horizontal = 20.dp)) {
        SectionLabel(stringResource(R.string.settings_diagnostics))
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                Text(
                    stringResource(R.string.settings_logging_enabled_title),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    stringResource(R.string.settings_logging_enabled_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = state.logSettings.enabled,
                onCheckedChange = { onAction(SettingsAction.SetLoggingEnabled(it)) },
            )
        }

        Text(
            stringResource(R.string.settings_log_level),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            LogLevel.entries.forEach { level ->
                FilterChip(
                    selected = state.logSettings.minimumLevel == level,
                    enabled = state.logSettings.enabled,
                    onClick = { onAction(SettingsAction.SetMinimumLogLevel(level)) },
                    label = { Text(stringResource(level.labelResource())) },
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                Text(
                    stringResource(R.string.settings_location_samples_title),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    stringResource(R.string.settings_location_samples_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = state.logSettings.includeLocationSamples,
                enabled = state.logSettings.enabled,
                onCheckedChange = { onAction(SettingsAction.SetLocationSampleLogging(it)) },
            )
        }

        val storage = state.logStorageInfo
        val formattedSize = Formatter.formatShortFileSize(LocalContext.current, storage.sizeBytes)
        SettingsRow(
            icon = Icons.Filled.Share,
            title = stringResource(R.string.settings_share_logs_title),
            subtitle = if (state.isPreparingLogArchive) {
                stringResource(R.string.settings_share_logs_preparing)
            } else {
                pluralStringResource(
                    id = R.plurals.settings_share_logs_subtitle,
                    count = storage.fileCount,
                    formattedSize,
                    storage.fileCount,
                    storage.sessionId,
                )
            },
            enabled = !state.isPreparingLogArchive && storage.fileCount > 0,
            onClick = { onAction(SettingsAction.ShareLogsClicked) },
        )
        HorizontalDivider()
        SettingsRow(
            icon = Icons.Filled.Delete,
            title = stringResource(R.string.settings_clear_logs_title),
            subtitle = if (state.isClearingLogs) {
                stringResource(R.string.settings_clear_logs_running)
            } else {
                stringResource(R.string.settings_clear_logs_subtitle)
            },
            enabled = !state.isClearingLogs && storage.fileCount > 0,
            onClick = { onAction(SettingsAction.ClearLogsClicked) },
        )
        HorizontalDivider()

        Text(
            stringResource(R.string.settings_notification_style),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = 10.dp, bottom = 8.dp),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            RecordingNotificationStyle.entries.forEach { style ->
                FilterChip(
                    selected = state.recordingNotificationStyle == style,
                    onClick = { onAction(SettingsAction.SetRecordingNotificationStyle(style)) },
                    label = { Text(stringResource(style.labelResource())) },
                )
            }
        }
    }
}

private fun RecordingNotificationStyle.labelResource(): Int = when (this) {
    RecordingNotificationStyle.AUTO -> R.string.settings_notification_style_auto
    RecordingNotificationStyle.LIVE_UPDATE -> R.string.settings_notification_style_live_update
    RecordingNotificationStyle.CUSTOM -> R.string.settings_notification_style_custom
}

private fun LogLevel.labelResource(): Int = when (this) {
    LogLevel.VERBOSE -> R.string.settings_log_level_verbose
    LogLevel.DEBUG -> R.string.settings_log_level_debug
    LogLevel.INFO -> R.string.settings_log_level_info
    LogLevel.WARN -> R.string.settings_log_level_warn
    LogLevel.ERROR -> R.string.settings_log_level_error
}
