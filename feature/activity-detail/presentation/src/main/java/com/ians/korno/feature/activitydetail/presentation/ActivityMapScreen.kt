package com.ians.korno.feature.activitydetail.presentation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
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

    Box(modifier = modifier.fillMaxSize()) {
        TrackMap(
            points = state.routePoints,
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
    }
}
