package com.ians.korno.feature.tracking.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ians.korno.core.designsystem.theme.KornoTheme
import com.ians.korno.core.domain.model.ActivityType
import com.ians.korno.core.map.TrackMap
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
internal fun TrackingSummaryScreen(
    onSaved: (activityId: Long) -> Unit,
    onDiscarded: () -> Unit,
    activityId: Long,
    modifier: Modifier = Modifier,
    viewModel: TrackingSummaryViewModel = koinViewModel(parameters = { parametersOf(activityId) }),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(state.saved) {
        if (state.saved) onSaved(activityId)
    }
    LaunchedEffect(state.discarded) {
        if (state.discarded) onDiscarded()
    }

    TrackingSummaryContent(
        state = state,
        onAction = viewModel::onAction,
        modifier = modifier,
    )
}

@Composable
private fun TrackingSummaryContent(
    state: TrackingSummaryState,
    onAction: (TrackingSummaryAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.padding(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { onAction(TrackingSummaryAction.DiscardClicked) }) {
                Icon(Icons.Filled.Close, contentDescription = null)
            }
            Text(
                text = stringResource(R.string.tracking_summary_title),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                SummaryStat(stringResource(R.string.tracking_distance_label), state.distanceText, Modifier.weight(1f))
                SummaryStat(stringResource(R.string.tracking_summary_time_label), state.durationText, Modifier.weight(1f))
                SummaryStat(stringResource(R.string.tracking_summary_pace_label), state.paceText, Modifier.weight(1f))
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainer),
                contentAlignment = Alignment.Center,
            ) {
                if (state.routePoints.isEmpty()) {
                    Text(
                        text = stringResource(R.string.tracking_summary_map_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    // Экран прокручивается, поэтому жесты карты выключены:
                    // иначе она перехватывала бы вертикальный скролл.
                    TrackMap(
                        points = state.routePoints,
                        interactive = false,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }

            Column {
                Text(
                    text = stringResource(R.string.tracking_setup_activity_type),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp)
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    SETUP_ACTIVITY_TYPES.forEach { type ->
                        FilterChip(
                            selected = type == state.activityType,
                            onClick = { onAction(TrackingSummaryAction.SelectActivityType(type)) },
                            leadingIcon = {
                                Icon(type.icon(), contentDescription = null, modifier = Modifier.size(18.dp))
                            },
                            label = { Text(stringResource(type.labelRes())) },
                        )
                    }
                }
            }

            OutlinedTextField(
                value = state.title,
                onValueChange = { onAction(TrackingSummaryAction.SetTitle(it)) },
                label = { Text(stringResource(R.string.tracking_summary_title_field)) },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = state.note,
                onValueChange = { onAction(TrackingSummaryAction.SetNote(it)) },
                label = { Text(stringResource(R.string.tracking_summary_note_field)) },
                placeholder = { Text(stringResource(R.string.tracking_summary_note_placeholder)) },
                minLines = 3,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(20.dp, 12.dp, 20.dp, 28.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OutlinedButton(
                onClick = { onAction(TrackingSummaryAction.DiscardClicked) },
                modifier = Modifier.weight(1f).height(56.dp),
            ) {
                Text(stringResource(R.string.tracking_summary_discard))
            }
            Button(
                onClick = { onAction(TrackingSummaryAction.SaveClicked) },
                modifier = Modifier.weight(1f).height(56.dp),
            ) {
                Text(stringResource(R.string.tracking_summary_save))
            }
        }
    }
}

@Composable
private fun SummaryStat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(16.dp))
            .padding(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text = label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text = value, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Preview(showBackground = true)
@Composable
private fun TrackingSummaryContentPreview() {
    KornoTheme {
        TrackingSummaryContent(
            state = TrackingSummaryState(
                isLoading = false,
                activityType = ActivityType.RUNNING,
                distanceText = "5.18 km",
                durationText = "29:40",
                paceText = "10.4 km/h",
                title = "Morning run",
            ),
            onAction = {},
        )
    }
}
