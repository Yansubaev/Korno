package com.ians.korno.feature.activitydetail.presentation

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DirectionsBike
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.DirectionsRun
import androidx.compose.material.icons.filled.DirectionsWalk
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ians.korno.core.designsystem.theme.KornoTheme
import com.ians.korno.core.domain.model.ActivityType
import com.ians.korno.core.domain.model.TrackFileFormat
import com.ians.korno.core.map.MapPoint
import com.ians.korno.core.map.TrackMap
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
internal fun ActivityDetailScreen(
    activityId: Long,
    onBack: () -> Unit,
    onEditTrack: (Long) -> Unit,
    onOpenMap: (Long) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ActivityDetailViewModel = koinViewModel(parameters = { parametersOf(activityId) }),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(state.deleted) {
        if (state.deleted) onBack()
    }

    // Формат документа фиксируется при создании контракта, поэтому на каждый формат
    // свой launcher: подменить mime type в момент запуска система не даёт.
    val exportGpxLauncher = rememberExportLauncher(TrackFileFormat.GPX, viewModel::onAction)
    val exportTcxLauncher = rememberExportLauncher(TrackFileFormat.TCX, viewModel::onAction)

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is ActivityDetailEvent.PickExportDestination -> when (event.format) {
                    TrackFileFormat.GPX -> exportGpxLauncher.launch(event.fileName)
                    TrackFileFormat.TCX -> exportTcxLauncher.launch(event.fileName)
                }
                is ActivityDetailEvent.ShareTrackFile -> context.startActivity(
                    Intent.createChooser(shareIntent(event.uri, event.mimeType), null),
                )
                is ActivityDetailEvent.ShareLogArchive -> context.startActivity(
                    Intent.createChooser(shareIntent(event.uri, event.mimeType), null),
                )
            }
        }
    }

    val snackbarHostState = remember { SnackbarHostState() }
    val message = state.snackbarMessageRes?.let { stringResource(it) }
    LaunchedEffect(message) {
        if (message != null) {
            snackbarHostState.showSnackbar(message)
            viewModel.onAction(ActivityDetailAction.DismissSnackbar)
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) { Snackbar(it) } },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        modifier = modifier,
    ) { padding ->
        ActivityDetailContent(
            state = state,
            onAction = viewModel::onAction,
            onBack = onBack,
            onEditTrack = { onEditTrack(activityId) },
            onOpenMap = { onOpenMap(activityId) },
            modifier = Modifier.padding(padding),
        )
    }
}

internal fun ActivityType.icon(): ImageVector = when (this) {
    ActivityType.RUNNING -> Icons.Filled.DirectionsRun
    ActivityType.CYCLING -> Icons.Filled.DirectionsBike
    ActivityType.WALKING -> Icons.Filled.DirectionsWalk
    ActivityType.DRIVING -> Icons.Filled.DirectionsCar
    ActivityType.HIKING, ActivityType.OTHER -> Icons.Filled.DirectionsWalk
}

@Composable
private fun ActivityDetailContent(
    state: ActivityDetailState,
    onAction: (ActivityDetailAction) -> Unit,
    onBack: () -> Unit,
    onEditTrack: () -> Unit,
    onOpenMap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val route = rememberLensRoute(state)
    val splitSection = rememberSplitSection(state.insights, state.selectedSplitNumber)
    val overlay = lensOverlay(
        insights = state.insights,
        lens = state.lens,
        selection = state.selection,
        highlightedSection = splitSection,
    )

    Column(modifier = modifier.fillMaxSize()) {
        Row(modifier = Modifier.padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = null) }
            Text(
                text = state.title,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
                maxLines = 1,
            )
            IconButton(
                onClick = { onAction(ActivityDetailAction.ShareClicked) },
                enabled = !state.isExporting,
            ) {
                Icon(
                    Icons.Filled.Share,
                    contentDescription = stringResource(R.string.activity_detail_share),
                )
            }
            IconButton(
                onClick = { onAction(ActivityDetailAction.ExportClicked) },
                enabled = !state.isExporting,
            ) {
                Icon(
                    Icons.Filled.Download,
                    contentDescription = stringResource(R.string.activity_detail_export),
                )
            }
            IconButton(onClick = { onAction(ActivityDetailAction.RequestDelete) }) {
                Icon(Icons.Filled.Delete, contentDescription = null)
            }
        }

        Column(
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
        ) {
            // Карта прокручивается вместе с содержимым: без неё на экране помещается
            // ещё один график, а разглядывать трек всё равно уводит на полный экран.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(240.dp)
                    .padding(horizontal = 20.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainer),
            ) {
                // Жесты выключены: внутри прокрутки карта перехватывала бы вертикальный
                // скролл. Разглядывать трек можно на отдельном полноэкранном экране —
                // туда и ведёт нажатие.
                TrackMap(
                    points = route,
                    overlay = overlay,
                    modifier = Modifier.fillMaxSize(),
                    interactive = false,
                )
                Row(
                    modifier = Modifier
                        .padding(12.dp)
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(100))
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(state.type.icon(), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Text(state.dateLabel, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
                }

                // Прозрачный слой поверх карты: MapView забирает касания себе даже с
                // выключенными жестами, и без него нажатие на карточку не доходило бы
                // до обработчика.
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .clickable(
                            onClickLabel = stringResource(R.string.activity_detail_open_map),
                            onClick = onOpenMap,
                        ),
                )
            }

            // Переключатель линз и легенда стоят вплотную к карте: они объясняют именно
            // её, и уезжать за экран должны вместе с ней.
            state.insights?.let { insights ->
                Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
                    MapLensSelector(
                        lenses = insights.availableLenses,
                        selected = state.lens,
                        speedDisplay = insights.speedDisplay,
                        onSelect = { onAction(ActivityDetailAction.SelectLens(it)) },
                    )
                    LensLegend(
                        lens = state.lens,
                        insights = insights,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
            }

            Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                state.selection?.let { selection ->
                    SelectionCard(
                        selection = selection,
                        speedDisplay = state.speedDisplay,
                        onClear = { onAction(ActivityDetailAction.ClearSelection) },
                        modifier = Modifier.padding(bottom = 16.dp),
                    )
                }

                SummaryGrid(state = state)

                state.insights?.let { insights ->
                    InsightsSections(
                        state = state,
                        insights = insights,
                        onAction = onAction,
                    )
                }

                OutlinedButton(
                    onClick = onEditTrack,
                    modifier = Modifier.fillMaxWidth().padding(top = 24.dp).height(56.dp),
                ) {
                    Text(stringResource(R.string.activity_detail_edit_track))
                }

                ActivityLogSection(state = state, onAction = onAction)
            }
        }
    }

    if (state.formatChoice != null) {
        TrackFileFormatDialog(intent = state.formatChoice, onAction = onAction)
    }

    if (state.deleteDialogOpen) {
        AlertDialog(
            onDismissRequest = { onAction(ActivityDetailAction.CancelDelete) },
            title = { Text(stringResource(R.string.activity_detail_delete_dialog_title)) },
            text = { Text(stringResource(R.string.activity_detail_delete_dialog_body)) },
            confirmButton = {
                TextButton(onClick = { onAction(ActivityDetailAction.ConfirmDelete) }) {
                    Text(stringResource(R.string.activity_detail_delete_dialog_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { onAction(ActivityDetailAction.CancelDelete) }) {
                    Text(stringResource(R.string.activity_detail_delete_dialog_cancel))
                }
            },
        )
    }
}

/** Итоговые показатели активности. Названия зависят от того, чем меряется эта активность. */
@Composable
private fun SummaryGrid(state: ActivityDetailState, modifier: Modifier = Modifier) {
    val isPace = state.speedDisplay == SpeedDisplay.PACE
    val stats = listOf(
        stringResource(R.string.activity_detail_distance) to state.distanceText,
        stringResource(R.string.activity_detail_duration) to state.durationText,
        stringResource(R.string.activity_detail_moving) to state.movingText,
        stringResource(
            if (isPace) R.string.activity_detail_avg_pace else R.string.activity_detail_avg_speed,
        ) to state.avgSpeedText,
        stringResource(
            if (isPace) R.string.activity_detail_best_pace else R.string.activity_detail_max_speed,
        ) to state.maxSpeedText,
        stringResource(R.string.activity_detail_elevation) to state.elevationText,
    )
    Column(modifier = modifier.padding(top = 20.dp)) {
        stats.chunked(3).forEach { row ->
            Row(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
                row.forEach { (label, value) ->
                    Column(modifier = Modifier.weight(1f)) {
                        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(value, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
        }
    }
}

/** Графики, сплиты и остановки. Каждый раздел появляется только там, где для него есть данные. */
@Composable
private fun InsightsSections(
    state: ActivityDetailState,
    insights: ActivityInsightsUi,
    onAction: (ActivityDetailAction) -> Unit,
) {
    insights.speedChart?.let { chart ->
        InsightChart(
            chart = chart,
            title = stringResource(
                if (insights.speedDisplay == SpeedDisplay.PACE) {
                    R.string.activity_detail_pace_chart
                } else {
                    R.string.activity_detail_speed_chart
                },
            ),
            selectionX = state.selection?.x,
            onSelect = { onAction(ActivityDetailAction.SelectTrackPosition(it)) },
            modifier = Modifier.padding(top = 8.dp),
        )
    }

    insights.elevationChart?.let { chart ->
        InsightChart(
            chart = chart,
            title = stringResource(R.string.activity_detail_elevation_chart),
            filled = true,
            selectionX = state.selection?.x,
            onSelect = { onAction(ActivityDetailAction.SelectTrackPosition(it)) },
            modifier = Modifier.padding(top = 20.dp),
        )
    }

    insights.elevation?.let { elevation ->
        ElevationSummary(elevation = elevation, modifier = Modifier.padding(top = 16.dp))
    }

    if (insights.splits.isNotEmpty()) {
        SectionTitle(stringResource(R.string.activity_detail_splits))
        SplitsSection(
            splits = insights.splits,
            selectedSplitNumber = state.selectedSplitNumber,
            speedDisplay = insights.speedDisplay,
            onSplitClick = { onAction(ActivityDetailAction.ToggleSplit(it)) },
        )
    }

    insights.stops?.let { stops ->
        SectionTitle(stringResource(R.string.activity_detail_stops))
        StopsSection(stops = stops)
    }
}

@Composable
private fun rememberExportLauncher(
    format: TrackFileFormat,
    onAction: (ActivityDetailAction) -> Unit,
) = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.CreateDocument(format.mimeType),
) { uri ->
    onAction(
        if (uri == null) {
            ActivityDetailAction.ExportDestinationCancelled
        } else {
            ActivityDetailAction.ExportDestinationChosen(uri.toString())
        },
    )
}

private fun shareIntent(uri: String, mimeType: String): Intent =
    Intent(Intent.ACTION_SEND).apply {
        type = mimeType
        putExtra(Intent.EXTRA_STREAM, uri.toUri())
        // Файл лежит во внутреннем кэше и виден только через FileProvider,
        // поэтому доступ выдаётся вместе с интентом и живёт ровно столько же.
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

@Composable
private fun TrackFileFormatDialog(
    intent: TrackFileIntent,
    onAction: (ActivityDetailAction) -> Unit,
) {
    AlertDialog(
        onDismissRequest = { onAction(ActivityDetailAction.DismissFormatChoice) },
        title = {
            Text(
                stringResource(
                    when (intent) {
                        TrackFileIntent.EXPORT -> R.string.track_file_export_title
                        TrackFileIntent.SHARE -> R.string.track_file_share_title
                    },
                ),
            )
        },
        text = {
            Column {
                Text(stringResource(R.string.track_file_format_prompt))
                TrackFileFormat.entries.forEach { format ->
                    TextButton(
                        onClick = { onAction(ActivityDetailAction.SelectFileFormat(format)) },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    ) {
                        Text(format.extension.uppercase(), modifier = Modifier.weight(1f))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onAction(ActivityDetailAction.DismissFormatChoice) }) {
                Text(stringResource(R.string.track_file_cancel))
            }
        },
    )
}

@Preview(showBackground = true)
@Composable
private fun ActivityDetailContentPreview() {
    KornoTheme {
        ActivityDetailContent(
            state = ActivityDetailState(
                isLoading = false,
                title = "Morning run",
                dateLabel = "Today",
                distanceText = "5.18 km",
                durationText = "29:40",
                movingText = "28:14",
                avgSpeedText = "5:27",
                maxSpeedText = "4:48",
                elevationText = "42 m",
                insights = previewInsights(),
                routePoints = previewRoute,
            ),
            onAction = {},
            onBack = {},
            onEditTrack = {},
            onOpenMap = {},
        )
    }
}

private val previewRoute = listOf(
    MapPoint(55.7512, 37.6175),
    MapPoint(55.7534, 37.6212),
    MapPoint(55.7560, 37.6198),
    MapPoint(55.7578, 37.6240),
)

private fun previewInsights(): ActivityInsightsUi {
    val samples = previewRoute.mapIndexed { index, point ->
        RouteSampleUi(
            point = point,
            distanceMeters = index * 1_300.0,
            speedFraction = index / 3f,
            elevationFraction = 1f - index / 3f,
        )
    }
    val chart = InsightChartUi(
        values = List(40) { step ->
            ChartValueUi(x = step / 39f, y = 320f + 30f * kotlin.math.sin(step / 4f))
        },
        minY = 280f,
        maxY = 370f,
        averageY = 327f,
        invertY = true,
        topLabel = "4:40 min/km",
        bottomLabel = "6:10 min/km",
        averageLabel = "5:27 min/km",
        stopMarks = listOf(0.35f, 0.72f),
    )
    return ActivityInsightsUi(
        samples = samples,
        totalDistanceMeters = 5_180.0,
        speedDisplay = SpeedDisplay.PACE,
        availableLenses = listOf(MapLens.ROUTE, MapLens.SPEED, MapLens.ELEVATION, MapLens.STOPS),
        speedChart = chart,
        elevationChart = chart.copy(invertY = false, averageY = null, averageLabel = null),
        speedLegend = LensLegendUi("6:10 min/km", "4:40 min/km"),
        elevationLegend = LensLegendUi("128 m", "171 m"),
        stopMarkers = emptyList(),
        distanceMarkers = emptyList(),
        splits = listOf(
            SplitUi(1, "1", "5:34 min/km", "5:34", "+8 m", 0.6f, false, false, 0.0, 1_000.0),
            SplitUi(2, "2", "5:21 min/km", "5:21", "−3 m", 1f, true, false, 1_000.0, 2_000.0),
            SplitUi(3, "0.18 km", "5:48 min/km", "1:03", "+19 m", 0.3f, false, true, 2_000.0, 5_180.0),
        ),
        fastestSplitNumber = 2,
        stops = StopsUi(
            movingText = "28:14",
            stoppedText = "1:26",
            countText = "3",
            longestText = "0:48",
            movingShareText = "95%",
            movingFraction = 0.95f,
            autoStopFraction = 0.04f,
            pauseFraction = 0.01f,
            hasManualPause = true,
        ),
        elevation = ElevationSummaryUi("42 m", "39 m", "128 – 171 m"),
    )
}
