package com.ians.korno.feature.activitylist.presentation

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ians.korno.core.designsystem.theme.KornoTheme
import com.ians.korno.core.domain.model.ActivityType
import com.ians.korno.core.domain.model.SegmentKind
import com.ians.korno.core.map.MapPoint
import com.ians.korno.core.map.TrackThumbnail
import com.ians.korno.core.presentation.R as CoreR
import com.ians.korno.core.presentation.icon
import com.ians.korno.core.presentation.labelRes
import org.koin.androidx.compose.koinViewModel

@Composable
internal fun ActivityListScreen(
    onOpenActivity: (Long) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ActivityListViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // Фильтр "*/*", а не GPX и TCX: системный провайдер узнаёт тип файла по таблице
    // MIME, где этих расширений нет, и строгий фильтр спрятал бы ровно те файлы,
    // ради которых открыт диалог. Содержимое всё равно проверяется при разборе.
    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        viewModel.onAction(ActivityListAction.ImportFilesChosen(uris.map { it.toString() }))
    }

    val snackbarHostState = remember { SnackbarHostState() }
    val message = when (val importMessage = state.snackbarMessage) {
        null -> null
        is ImportMessage.Imported -> pluralStringResource(
            id = R.plurals.activity_list_import_done,
            count = importMessage.totalCount,
            importMessage.successCount,
            importMessage.totalCount,
        )
        ImportMessage.Failed -> stringResource(R.string.activity_list_import_failed)
    }
    LaunchedEffect(message) {
        if (message != null) {
            snackbarHostState.showSnackbar(message)
            viewModel.onAction(ActivityListAction.DismissSnackbar)
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) { Snackbar(it) } },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        modifier = modifier,
    ) { padding ->
        ActivityListContent(
            state = state,
            onAction = viewModel::onAction,
            onOpenActivity = onOpenActivity,
            onImportClick = { importLauncher.launch(arrayOf("*/*")) },
            modifier = Modifier.padding(padding),
        )
    }
}

private fun ActivityPeriod.labelRes(): Int = when (this) {
    ActivityPeriod.ALL -> R.string.activity_list_period_all
    ActivityPeriod.WEEK -> R.string.activity_list_period_week
    ActivityPeriod.MONTH -> R.string.activity_list_period_month
    ActivityPeriod.YEAR -> R.string.activity_list_period_year
}

@Composable
internal fun ActivityListContent(
    state: ActivityListState,
    onAction: (ActivityListAction) -> Unit,
    onOpenActivity: (Long) -> Unit,
    onImportClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    // Смена фильтра даёт другой список — позиция от прежнего в нём бессмысленна.
    LaunchedEffect(state.filterKind, state.filterPeriod, state.searchQuery) {
        listState.scrollToItem(0)
    }

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().height(64.dp).padding(start = 20.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.activity_list_title),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            if (state.isImporting) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp))
            } else {
                IconButton(onClick = onImportClick) {
                    Icon(
                        Icons.Filled.FileUpload,
                        contentDescription = stringResource(R.string.activity_list_import),
                    )
                }
            }
        }

        OutlinedTextField(
            value = state.searchQuery,
            onValueChange = { onAction(ActivityListAction.SetSearchQuery(it)) },
            placeholder = { Text(stringResource(R.string.activity_list_search_placeholder)) },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            singleLine = true,
            shape = RoundedCornerShape(100),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(20.dp, 8.dp, 20.dp, 0.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(
                selected = state.filterKind == null,
                onClick = { onAction(ActivityListAction.SetFilterKind(null)) },
                label = { Text(stringResource(R.string.activity_list_filter_all)) },
            )
            state.availableKinds.forEach { kind ->
                FilterChip(
                    selected = state.filterKind == kind,
                    onClick = { onAction(ActivityListAction.SetFilterKind(kind)) },
                    leadingIcon = { Icon(kind.icon(), contentDescription = null, modifier = Modifier.size(18.dp)) },
                    label = { Text(stringResource(kind.chipLabelRes())) },
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(20.dp, 8.dp, 20.dp, 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ActivityPeriod.entries.forEach { period ->
                FilterChip(
                    selected = state.filterPeriod == period,
                    onClick = { onAction(ActivityListAction.SetFilterPeriod(period)) },
                    label = { Text(stringResource(period.labelRes())) },
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(20.dp, 4.dp, 20.dp, 4.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            StatCard(stringResource(R.string.activity_list_stats_distance), state.statsDistanceText, Modifier.weight(1f))
            StatCard(stringResource(R.string.activity_list_stats_time), state.statsTimeText, Modifier.weight(1f))
        }

        if (state.items.isEmpty() && !state.isLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource(R.string.activity_list_empty),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp, 4.dp, 20.dp, 20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(items = state.items, key = { it.id }) { item ->
                    // Маршрут запрашивается ровно для тех строк, которые LazyColumn
                    // действительно собрал: за списком в сотню активностей иначе
                    // тянулись бы все треки разом.
                    LaunchedEffect(item.id) { onAction(ActivityListAction.RowShown(item.id)) }
                    ActivityRow(
                        item = item,
                        routePreview = state.routePreviews[item.id].orEmpty(),
                        onClick = { onOpenActivity(item.id) },
                    )
                }
            }
        }
    }
}

/**
 * `UNKNOWN` в чипе — не «определяем…», как у живого сегмента записи, а «не определено»:
 * здесь это уже окончательный ответ детектора.
 */
private fun SegmentKind.chipLabelRes(): Int =
    if (this == SegmentKind.UNKNOWN) CoreR.string.activity_type_unknown else labelRes()

@Composable
private fun ActivityRow(
    item: ActivityListItemUi,
    routePreview: List<MapPoint>,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(16.dp))
            .padding(14.dp)
            .then(Modifier.clickableRow(onClick)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Пока трек не загружен — иконка типа активности: она же остаётся у записей
        // без точек, где рисовать попросту нечего.
        if (routePreview.isEmpty()) {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(14.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(item.type.icon(), contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
            }
        } else {
            TrackThumbnail(
                points = routePreview,
                modifier = Modifier.size(52.dp).clip(RoundedCornerShape(14.dp)),
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(text = item.title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            Text(text = item.supportingText, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(text = item.distanceText, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
    }
}

private fun Modifier.clickableRow(onClick: () -> Unit): Modifier = this.clickable(onClick = onClick)

@Composable
private fun StatCard(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(16.dp))
            .padding(16.dp),
    ) {
        Text(text = label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text = value, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Preview(showBackground = true)
@Composable
private fun ActivityListContentPreview() {
    KornoTheme {
        ActivityListContent(
            state = ActivityListState(
                isLoading = false,
                availableKinds = listOf(SegmentKind.RUNNING),
                items = listOf(
                    ActivityListItemUi(1, "Morning run", "29:40", "5.2 km", ActivityType.RUNNING),
                ),
                statsDistanceText = "5.2 km",
                statsTimeText = "29 min",
                routePreviews = mapOf(
                    1L to listOf(
                        MapPoint(55.7512, 37.6175),
                        MapPoint(55.7534, 37.6212),
                        MapPoint(55.7560, 37.6198),
                    ),
                ),
            ),
            onAction = {},
            onOpenActivity = {},
            onImportClick = {},
        )
    }
}
