package com.ians.korno.core.presentation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource

/**
 * Одна кнопка на главной и в списке: «Начать», а во время записи — «● 12:34», которая
 * возвращает на экран записи. Раскрывается и сворачивается сама, без смены кнопки.
 *
 * @param timeText `null` — записи нет.
 */
@Composable
fun RecordingFab(
    timeText: String?,
    isPulsing: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ExtendedFloatingActionButton(
        onClick = onClick,
        expanded = timeText != null,
        modifier = modifier,
        icon = {
            if (timeText == null) {
                Icon(Icons.Filled.PlayArrow, contentDescription = stringResource(R.string.recording_fab_start))
            } else {
                RecordingDot(pulsing = isPulsing, color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
        },
        text = { Text(timeText.orEmpty()) },
    )
}
