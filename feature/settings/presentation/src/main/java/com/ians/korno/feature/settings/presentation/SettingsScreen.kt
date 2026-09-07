package com.ians.korno.feature.settings.presentation

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.content.Intent
import android.text.format.Formatter
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.net.toUri
import com.ians.korno.core.designsystem.theme.KornoTheme
import com.ians.korno.core.designsystem.theme.isDynamicColorAvailable
import com.ians.korno.core.domain.model.AutoPauseSensitivity
import com.ians.korno.core.domain.model.GpsAccuracy
import com.ians.korno.core.domain.model.MeasurementSystem
import com.ians.korno.core.domain.model.PrivacyZone
import com.ians.korno.core.presentation.formatShortDistance
import org.koin.androidx.compose.koinViewModel

@Composable
internal fun SettingsScreen(
    onOpenPermissions: () -> Unit,
    onViewOnboarding: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    // OpenDocumentTree, а не CreateDocument: выгружается сразу много файлов,
    // и спрашивать место для каждого пришлось бы столько же раз.
    val exportFolderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { treeUri ->
        viewModel.onAction(SettingsAction.ExportFolderChosen(treeUri?.toString()))
    }

    val message = when (val snackbarMessage = state.snackbarMessage) {
        null -> null
        SettingsMessage.MapCacheCleared -> stringResource(R.string.settings_clear_cache_snackbar)
        SettingsMessage.MapCacheClearFailed -> stringResource(R.string.settings_clear_cache_failed)
        SettingsMessage.ExportFailed -> stringResource(R.string.settings_export_all_failed)
        SettingsMessage.LogsCleared -> stringResource(R.string.settings_logs_cleared)
        SettingsMessage.LogsClearFailed -> stringResource(R.string.settings_logs_clear_failed)
        SettingsMessage.LogArchiveFailed -> stringResource(R.string.settings_log_archive_failed)
        SettingsMessage.NoLogsToShare -> stringResource(R.string.settings_no_logs_to_share)
        is SettingsMessage.Exported -> pluralStringResource(
            id = R.plurals.settings_export_all_done,
            count = snackbarMessage.activityCount,
            snackbarMessage.activityCount,
        )
    }
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is SettingsEvent.ShareLogArchive -> context.startActivity(
                    Intent.createChooser(
                        Intent(Intent.ACTION_SEND).apply {
                            type = event.mimeType
                            putExtra(Intent.EXTRA_STREAM, event.uri.toUri())
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        },
                        null,
                    ),
                )
            }
        }
    }
    LaunchedEffect(message) {
        if (message != null) {
            snackbarHostState.showSnackbar(message)
            viewModel.onAction(SettingsAction.DismissSnackbar)
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) { Snackbar(it) } },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        modifier = modifier,
    ) { padding ->
        SettingsContent(
            state = state,
            onAction = viewModel::onAction,
            onExportAllClick = { exportFolderLauncher.launch(null) },
            onOpenPermissions = onOpenPermissions,
            onViewOnboarding = onViewOnboarding,
            modifier = Modifier.padding(padding),
        )
    }
}

@Composable
internal fun SettingsContent(
    state: SettingsState,
    onAction: (SettingsAction) -> Unit,
    onExportAllClick: () -> Unit,
    onOpenPermissions: () -> Unit,
    onViewOnboarding: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        Box(modifier = Modifier.fillMaxWidth().height(64.dp).padding(start = 20.dp), contentAlignment = Alignment.CenterStart) {
            Text(
                text = stringResource(R.string.settings_title),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            SectionLabel(stringResource(R.string.settings_units), modifier = Modifier.padding(horizontal = 20.dp))
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.settings_distance_units), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ToggleButton(
                        text = stringResource(R.string.settings_unit_km),
                        selected = state.measurementSystem == MeasurementSystem.METRIC,
                        onClick = { onAction(SettingsAction.SetDistanceUnit(MeasurementSystem.METRIC)) },
                    )
                    ToggleButton(
                        text = stringResource(R.string.settings_unit_mi),
                        selected = state.measurementSystem == MeasurementSystem.IMPERIAL,
                        onClick = { onAction(SettingsAction.SetDistanceUnit(MeasurementSystem.IMPERIAL)) },
                    )
                }
            }

            // На Android 11 и ниже системной палитры просто нет — переключатель,
            // который ничего не меняет, показывать незачем.
            if (isDynamicColorAvailable()) {
                Column {
                    SectionLabel(stringResource(R.string.settings_appearance), modifier = Modifier.padding(horizontal = 20.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(top = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                            Text(stringResource(R.string.settings_dynamic_color_title), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                            Text(stringResource(R.string.settings_dynamic_color_subtitle), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = state.dynamicColor, onCheckedChange = { onAction(SettingsAction.SetDynamicColor(it)) })
                    }
                }
            }

            Column {
                SectionLabel(stringResource(R.string.settings_recording), modifier = Modifier.padding(horizontal = 20.dp))
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(top = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                        Text(stringResource(R.string.settings_autopause_title), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                        Text(stringResource(R.string.settings_autopause_subtitle), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = state.autoPause, onCheckedChange = { onAction(SettingsAction.SetAutoPause(it)) })
                }
                Text(
                    stringResource(R.string.settings_gps_accuracy),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = 20.dp).padding(top = 18.dp, bottom = 8.dp),
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(selected = state.gpsAccuracy == GpsAccuracy.HIGH, onClick = { onAction(SettingsAction.SetGpsAccuracy(GpsAccuracy.HIGH)) }, label = { Text(stringResource(R.string.settings_gps_high)) })
                    FilterChip(selected = state.gpsAccuracy == GpsAccuracy.BALANCED, onClick = { onAction(SettingsAction.SetGpsAccuracy(GpsAccuracy.BALANCED)) }, label = { Text(stringResource(R.string.settings_gps_balanced)) })
                    FilterChip(selected = state.gpsAccuracy == GpsAccuracy.BATTERY_SAVER, onClick = { onAction(SettingsAction.SetGpsAccuracy(GpsAccuracy.BATTERY_SAVER)) }, label = { Text(stringResource(R.string.settings_gps_saver)) })
                }
                Text(
                    stringResource(R.string.settings_stop_sensitivity),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = 20.dp).padding(top = 18.dp, bottom = 8.dp),
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(selected = state.stopSensitivity == AutoPauseSensitivity.LOW, onClick = { onAction(SettingsAction.SetStopSensitivity(AutoPauseSensitivity.LOW)) }, label = { Text(stringResource(R.string.settings_stop_low)) })
                    FilterChip(selected = state.stopSensitivity == AutoPauseSensitivity.MEDIUM, onClick = { onAction(SettingsAction.SetStopSensitivity(AutoPauseSensitivity.MEDIUM)) }, label = { Text(stringResource(R.string.settings_stop_medium)) })
                    FilterChip(selected = state.stopSensitivity == AutoPauseSensitivity.HIGH, onClick = { onAction(SettingsAction.SetStopSensitivity(AutoPauseSensitivity.HIGH)) }, label = { Text(stringResource(R.string.settings_stop_high)) })
                }
            }

            Column {
                SectionLabel(
                    stringResource(R.string.settings_privacy),
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
                Text(
                    stringResource(R.string.settings_privacy_zone_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp).padding(top = 8.dp, bottom = 8.dp),
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val context = LocalContext.current
                    PrivacyZone.entries.forEach { zone ->
                        FilterChip(
                            selected = state.privacyZone == zone,
                            onClick = { onAction(SettingsAction.SetPrivacyZone(zone)) },
                            label = {
                                Text(
                                    if (zone == PrivacyZone.OFF) {
                                        stringResource(R.string.settings_privacy_zone_off)
                                    } else {
                                        context.formatShortDistance(
                                            zone.radiusMeters,
                                            state.measurementSystem,
                                        ).text
                                    },
                                )
                            },
                        )
                    }
                }
            }

            Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                SectionLabel(stringResource(R.string.settings_data))
                SettingsRow(
                    icon = Icons.Filled.Download,
                    title = stringResource(R.string.settings_export_all_title),
                    subtitle = if (state.isExporting) {
                        stringResource(R.string.settings_export_all_running)
                    } else {
                        stringResource(R.string.settings_export_all_subtitle)
                    },
                    enabled = !state.isExporting,
                    onClick = onExportAllClick,
                )
                HorizontalDivider()
                SettingsRow(
                    icon = Icons.Filled.Delete,
                    title = stringResource(R.string.settings_clear_cache_title),
                    // Размер показывается, только когда он известен: хранилище карты
                    // создаётся при первом показе, и до этого сообщать нечего.
                    subtitle = when {
                        state.isClearingMapCache -> stringResource(R.string.settings_clear_cache_running)
                        state.mapCacheSizeBytes != null -> stringResource(
                            R.string.settings_clear_cache_size,
                            Formatter.formatShortFileSize(LocalContext.current, state.mapCacheSizeBytes),
                        )
                        else -> stringResource(R.string.settings_clear_cache_subtitle)
                    },
                    enabled = !state.isClearingMapCache,
                    onClick = { onAction(SettingsAction.ClearCacheClicked) },
                )
            }

            Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                SectionLabel(stringResource(R.string.settings_permissions))
                SettingsRow(
                    icon = Icons.Filled.LocationOn,
                    title = stringResource(R.string.settings_location_access_title),
                    subtitle = stringResource(R.string.settings_location_access_subtitle),
                    trailing = true,
                    onClick = onOpenPermissions,
                )
            }

            DebugLoggingSection(
                state = state,
                onAction = onAction,
            )

            Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                SectionLabel(stringResource(R.string.settings_about))
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(stringResource(R.string.settings_version), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                    Text(stringResource(R.string.settings_version_value), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp).clickable(onClick = onViewOnboarding),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(R.string.settings_view_onboarding), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                    Icon(Icons.Filled.ArrowForward, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.height(20.dp))
                }
            }
        }
    }
}

@Composable
internal fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = modifier)
}

@Composable
private fun ToggleButton(text: String, selected: Boolean, onClick: () -> Unit) {
    androidx.compose.material3.Button(
        onClick = onClick,
        colors = if (selected) {
            ButtonDefaults.buttonColors()
        } else {
            ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
    ) {
        Text(text)
    }
}

@Composable
internal fun SettingsRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    trailing: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp)
            .clickable(enabled = enabled, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (trailing) {
            Icon(Icons.Filled.ArrowForward, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.height(20.dp))
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun SettingsContentPreview() {
    KornoTheme {
        SettingsContent(
            state = SettingsState(),
            onAction = {},
            onExportAllClick = {},
            onOpenPermissions = {},
            onViewOnboarding = {},
        )
    }
}
