package com.ians.korno.feature.activitydetail.presentation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ians.korno.core.map.TrackMap
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * Трек во весь экран.
 *
 * Отдельный маршрут, а не разворачивающаяся карточка: на экране деталей карта живёт
 * над прокруткой и с выключенными жестами, иначе она перехватывала бы скролл. Здесь
 * прокручивать нечего, поэтому жесты включены целиком.
 */
@Composable
internal fun ActivityMapScreen(
    activityId: Long,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ActivityDetailViewModel = koinViewModel(parameters = { parametersOf(activityId) }),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val route = rememberLensRoute(state)
    val splitSection = rememberSplitSection(state.insights, state.selectedSplitNumber)
    val overlay = lensOverlay(
        insights = state.insights,
        lens = state.lens,
        selection = state.selection,
        highlightedSection = splitSection,
    )

    Box(modifier = modifier.fillMaxSize()) {
        TrackMap(
            points = route,
            overlay = overlay,
            modifier = Modifier.fillMaxSize(),
            interactive = true,
        )
        Row(
            modifier = Modifier
                .systemBarsPadding()
                .padding(12.dp)
                .align(Alignment.TopStart),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilledTonalIconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.activity_map_back),
                )
            }
            if (state.title.isNotEmpty()) {
                Text(
                    text = state.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    modifier = Modifier
                        .padding(start = 8.dp)
                        .background(
                            MaterialTheme.colorScheme.surfaceContainerHigh,
                            RoundedCornerShape(100),
                        )
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }

        // Переключатель линз внизу: сверху уже стоит кнопка возврата с названием, а
        // разглядывать трек удобнее, когда управление не закрывает его середину.
        state.insights?.let { insights ->
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .systemBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 16.dp)
                    .fillMaxWidth()
                    .background(
                        MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.92f),
                        RoundedCornerShape(20.dp),
                    )
                    .padding(16.dp),
            ) {
                MapLensSelector(
                    lenses = insights.availableLenses,
                    selected = state.lens,
                    speedDisplay = insights.speedDisplay,
                    onSelect = { viewModel.onAction(ActivityDetailAction.SelectLens(it)) },
                )
                LensLegend(
                    lens = state.lens,
                    insights = insights,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        }
    }
}
