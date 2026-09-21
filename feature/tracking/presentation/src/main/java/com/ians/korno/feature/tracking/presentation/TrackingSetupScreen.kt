package com.ians.korno.feature.tracking.presentation

import android.Manifest
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ians.korno.core.designsystem.theme.KornoTheme
import com.ians.korno.core.domain.model.ActivityType
import com.ians.korno.core.domain.model.AutoPauseSensitivity
import com.ians.korno.core.domain.model.GpsAccuracy
import com.ians.korno.core.presentation.icon
import com.ians.korno.core.presentation.labelRes
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
internal fun TrackingSetupScreen(
    initialActivityType: ActivityType,
    onStartRecording: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: TrackingSetupViewModel = koinViewModel(parameters = { parametersOf(initialActivityType) }),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(state.started) {
        if (state.started) onStartRecording()
    }

    BackHandler {
        viewModel.onBack()
        onBack()
    }

    TrackingSetupContent(
        state = state,
        onAction = viewModel::onAction,
        onBack = {
            viewModel.onBack()
            onBack()
        },
        modifier = modifier,
    )
}

@Composable
private fun TrackingSetupContent(
    state: TrackingSetupState,
    onAction: (TrackingSetupAction) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface),
    ) {
        Row(
            modifier = Modifier.padding(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
            }
            Text(
                text = stringResource(R.string.tracking_setup_title),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Column {
                Text(
                    text = stringResource(R.string.tracking_setup_activity_type),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp)
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // Без разрешения «Авто» сначала просит его; ответ проверяется во ViewModel
                    // вместе с Play Services, которых разрешение не заменяет.
                    val permissionLauncher = rememberLauncherForActivityResult(
                        ActivityResultContracts.RequestPermission(),
                    ) { onAction(TrackingSetupAction.AutoModePermissionResult) }
                    FilterChip(
                        selected = state.autoMode,
                        onClick = {
                            if (state.autoModeAvailable) {
                                onAction(TrackingSetupAction.SelectAutoMode)
                            } else {
                                permissionLauncher.launch(Manifest.permission.ACTIVITY_RECOGNITION)
                            }
                        },
                        leadingIcon = {
                            Icon(Icons.Filled.Explore, contentDescription = null, modifier = Modifier.size(18.dp))
                        },
                        label = { Text(stringResource(R.string.tracking_setup_auto)) },
                    )
                    SETUP_ACTIVITY_TYPES.forEach { type ->
                        FilterChip(
                            selected = !state.autoMode && type == state.activityType,
                            onClick = { onAction(TrackingSetupAction.SelectActivityType(type)) },
                            leadingIcon = {
                                Icon(type.icon(), contentDescription = null, modifier = Modifier.size(18.dp))
                            },
                            label = { Text(stringResource(type.labelRes())) },
                        )
                    }
                }
                Text(
                    text = stringResource(
                        if (state.autoModeUnavailableHint) {
                            R.string.tracking_setup_auto_unavailable
                        } else if (state.autoMode) {
                            R.string.tracking_setup_auto_subtitle
                        } else {
                            R.string.tracking_setup_type_subtitle
                        },
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (state.autoModeUnavailableHint) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp),
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                    Text(
                        text = stringResource(R.string.tracking_setup_autopause_title),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = stringResource(R.string.tracking_setup_autopause_subtitle),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = state.autoPause,
                    onCheckedChange = { onAction(TrackingSetupAction.SetAutoPause(it)) },
                )
            }

            Column {
                Text(
                    text = stringResource(R.string.tracking_setup_autopause_sensitivity),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp)
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    SETUP_AUTO_PAUSE_SENSITIVITIES.forEach { sensitivity ->
                        FilterChip(
                            selected = sensitivity == state.autoPauseSensitivity,
                            enabled = state.autoPause,
                            onClick = {
                                onAction(TrackingSetupAction.SelectAutoPauseSensitivity(sensitivity))
                            },
                            label = { Text(stringResource(sensitivity.labelRes())) },
                        )
                    }
                }
            }

            Column {
                Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                    Text(
                        text = stringResource(R.string.tracking_setup_gps_accuracy),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = stringResource(R.string.tracking_setup_gps_subtitle),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp)
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    GpsAccuracy.entries.forEach { accuracy ->
                        FilterChip(
                            selected = accuracy == state.gpsAccuracy,
                            onClick = { onAction(TrackingSetupAction.SelectGpsAccuracy(accuracy)) },
                            label = { Text(stringResource(accuracy.labelRes())) },
                        )
                    }
                }
            }
        }

        Button(
            onClick = { onAction(TrackingSetupAction.StartClicked) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp, 12.dp, 20.dp, 28.dp)
                .height(56.dp),
        ) {
            Text(text = stringResource(R.string.tracking_setup_start))
        }
    }
}

private fun GpsAccuracy.labelRes(): Int = when (this) {
    GpsAccuracy.HIGH -> R.string.tracking_gps_high
    GpsAccuracy.BALANCED -> R.string.tracking_gps_balanced
    GpsAccuracy.BATTERY_SAVER -> R.string.tracking_gps_saver
}

private fun AutoPauseSensitivity.labelRes(): Int = when (this) {
    AutoPauseSensitivity.LOW -> R.string.tracking_autopause_sensitivity_low
    AutoPauseSensitivity.MEDIUM -> R.string.tracking_autopause_sensitivity_medium
    AutoPauseSensitivity.HIGH -> R.string.tracking_autopause_sensitivity_high
}

@Preview(showBackground = true)
@Composable
private fun TrackingSetupContentPreview() {
    KornoTheme {
        TrackingSetupContent(
            state = TrackingSetupState(),
            onAction = {},
            onBack = {},
        )
    }
}
