package com.ians.korno.feature.home.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ians.korno.core.designsystem.theme.KornoTheme
import com.ians.korno.core.domain.model.SegmentKind
import com.ians.korno.core.presentation.icon
import org.koin.androidx.compose.koinViewModel

private val RECENT_LIST_BOTTOM_PADDING = 96.dp

@Composable
internal fun HomeScreen(
    onStartSetup: (SegmentKind) -> Unit,
    onSeeAllActivities: () -> Unit,
    onOpenActivity: (Long) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: HomeViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    HomeContent(
        state = state,
        onAction = viewModel::onAction,
        onStartSetup = { onStartSetup(state.selectedKind) },
        onSeeAllActivities = onSeeAllActivities,
        onOpenActivity = onOpenActivity,
        onOpenSettings = onOpenSettings,
        modifier = modifier,
    )
}

@Composable
private fun HomeContent(
    state: HomeState,
    onAction: (HomeAction) -> Unit,
    onStartSetup: () -> Unit,
    onSeeAllActivities: () -> Unit,
    onOpenActivity: (Long) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        floatingActionButton = {
            FloatingActionButton(onClick = onStartSetup) {
                Icon(
                    imageVector = Icons.Filled.PlayArrow,
                    contentDescription = stringResource(R.string.home_start_recording_button),
                )
            }
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().height(64.dp).padding(start = 20.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.home_title),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onOpenSettings) {
                    Icon(Icons.Filled.Settings, contentDescription = null)
                }
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp, 4.dp, 20.dp, RECENT_LIST_BOTTOM_PADDING),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                Column {
                    Text(
                        text = stringResource(R.string.home_this_week),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        WeekStatCard(stringResource(R.string.home_distance_label), state.weekDistanceText, Modifier.weight(1f))
                        WeekStatCard(stringResource(R.string.home_time_label), state.weekTimeText, Modifier.weight(1f))
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(R.string.home_recent_activity),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        TextButton(onClick = onSeeAllActivities) {
                            Text(stringResource(R.string.home_see_all))
                        }
                    }
                    state.recentActivities.forEach { activity ->
                        RecentActivityRow(activity = activity, onClick = { onOpenActivity(activity.id) })
                    }
                    if (state.hasMoreActivities) {
                        TextButton(onClick = onSeeAllActivities) {
                            Text(stringResource(R.string.home_see_all))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RecentActivityRow(activity: RecentActivityUi, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(16.dp))
            .then(Modifier.clickableChip(onClick))
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier
                .size(44.dp)
                .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(14.dp)),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = activity.kind.icon(),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = activity.title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "${activity.dateLabel} · ${activity.durationText}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = activity.distanceText,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun WeekStatCard(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(16.dp))
            .padding(16.dp),
    ) {
        Text(text = label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        Text(text = value, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface)
    }
}

private fun Modifier.clickableChip(onClick: () -> Unit): Modifier = this.clickable(onClick = onClick)

@Preview(showBackground = true)
@Composable
private fun HomeContentPreview() {
    KornoTheme {
        HomeContent(
            state = HomeState(
                isLoading = false,
                weekDistanceText = "12.4 km",
                weekTimeText = "1 h 48 min",
                recentActivities = listOf(
                    RecentActivityUi(
                        id = 1,
                        title = "Morning run",
                        dateLabel = "Today",
                        durationText = "29:40",
                        distanceText = "5.2 km",
                        kind = SegmentKind.RUNNING,
                    ),
                    RecentActivityUi(
                        id = 2,
                        title = "Evening ride",
                        dateLabel = "Yesterday",
                        durationText = "42:10",
                        distanceText = "18.3 km",
                        kind = SegmentKind.CYCLING,
                    ),
                ),
                hasMoreActivities = true,
            ),
            onAction = {},
            onStartSetup = {},
            onSeeAllActivities = {},
            onOpenActivity = {},
            onOpenSettings = {},
        )
    }
}
