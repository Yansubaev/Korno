package com.ians.korno.core.presentation

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ians.korno.core.domain.model.RecordingStatus
import com.ians.korno.core.domain.model.SegmentKind

/** То, что показывает карточка идущей записи; текст времени и дистанции готов заранее. */
data class OngoingRecordingUi(
    val kind: SegmentKind,
    val status: RecordingStatus,
    val timeText: String,
    val distanceText: String,
) {
    /** Точка пульсирует, только пока запись реально идёт. */
    val isPulsing: Boolean get() = status == RecordingStatus.RECORDING
}

/**
 * Идущая запись на главной и в списке. Отдельная карточка, а не строка: запись ещё
 * не история, и цвет `primaryContainer` не даёт спутать её с сохранённой активностью.
 */
@Composable
fun OngoingRecordingCard(
    ongoing: OngoingRecordingUi,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.primaryContainer)
            .clickable(onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(14.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(ongoing.kind.icon(), contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary)
        }
        Column(modifier = Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                RecordingDot(pulsing = ongoing.isPulsing, color = MaterialTheme.colorScheme.primary)
                Text(
                    text = stringResource(ongoing.status.ongoingLabelRes()),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            Text(
                text = ongoing.timeText,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
        Text(
            text = ongoing.distanceText,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
}

/** Точка перед статусом: мигает во время записи, на паузе и при ожидании GPS стоит. */
@Composable
fun RecordingDot(pulsing: Boolean, color: Color, modifier: Modifier = Modifier) {
    val pulse = if (pulsing) {
        rememberInfiniteTransition(label = "recordingDot").animateFloat(
            initialValue = 1f,
            targetValue = 0.25f,
            animationSpec = infiniteRepeatable(tween(800, easing = LinearEasing), RepeatMode.Reverse),
            label = "recordingDotAlpha",
        )
    } else {
        null
    }
    // Прозрачность читается на этапе отрисовки: иначе точка (а их две на главной — в карточке
    // и в кнопке) рекомпозировалась бы каждый кадр, пока идёт запись.
    Box(
        modifier = modifier
            .size(8.dp)
            .graphicsLayer { alpha = pulse?.value ?: 1f }
            .background(color, CircleShape),
    )
}

private fun RecordingStatus.ongoingLabelRes(): Int = when (this) {
    RecordingStatus.WAITING_FOR_GPS -> R.string.ongoing_recording_waiting_gps
    RecordingStatus.MANUAL_PAUSE, RecordingStatus.AUTO_PAUSE -> R.string.ongoing_recording_paused
    else -> R.string.ongoing_recording_recording
}
