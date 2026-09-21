package com.ians.korno.feature.activitydetail.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ians.korno.core.domain.model.SegmentKind
import com.ians.korno.core.presentation.SELECTABLE_SEGMENT_KINDS
import com.ians.korno.core.presentation.icon
import com.ians.korno.core.presentation.labelRes

/**
 * Сколько пройдено, сколько проехано и на чём. Транспорт стоит здесь отдельной строкой
 * и в общие темп и скорость не входит.
 */
@Composable
internal fun KindBreakdownSection(breakdown: List<KindSummaryUi>, modifier: Modifier = Modifier) {
    if (breakdown.isEmpty()) return
    Column(modifier = modifier) {
        SectionTitle(stringResource(R.string.activity_detail_kinds))
        breakdown.forEach { summary ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                KindBadge(summary.kind)
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "${stringResource(summary.kind.labelRes())} · ${summary.distanceText}",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = listOfNotNull(summary.movingText, summary.movementText, summary.stepsText)
                            .joinToString(" · "),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** Участки записи в авторежиме. Нажатие — смена вида, если детектор ошибся. */
@Composable
internal fun SegmentsSection(
    segments: List<SegmentUi>,
    isRecalculating: Boolean,
    onAction: (ActivityDetailAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (segments.isEmpty()) return
    Column(modifier = modifier) {
        SectionTitle(
            stringResource(
                if (isRecalculating) R.string.activity_detail_segment_recalculating else R.string.activity_detail_segments,
            ),
        )
        segments.forEach { segment ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    // Пауза видом не является: менять его у неё нечем и незачем.
                    .clickable(enabled = !isRecalculating && segment.kind != SegmentKind.PAUSE) {
                        onAction(ActivityDetailAction.SelectSegment(segment.index))
                    }
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                KindBadge(segment.kind)
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(segment.kind.labelRes()),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = listOfNotNull(
                            segment.timeRangeText,
                            segment.distanceText,
                            stringResource(R.string.activity_detail_segment_manual).takeIf { segment.isManuallyEdited },
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
internal fun SegmentKindDialog(
    currentKind: SegmentKind,
    onAction: (ActivityDetailAction) -> Unit,
) {
    AlertDialog(
        onDismissRequest = { onAction(ActivityDetailAction.DismissSegmentKindChoice) },
        title = { Text(stringResource(R.string.activity_detail_segment_kind_title)) },
        text = {
            Column {
                Text(stringResource(R.string.activity_detail_segment_kind_body))
                SELECTABLE_SEGMENT_KINDS.forEach { kind ->
                    TextButton(
                        onClick = { onAction(ActivityDetailAction.ChangeSegmentKind(kind)) },
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    ) {
                        Icon(kind.icon(), contentDescription = null)
                        Text(
                            text = stringResource(kind.labelRes()),
                            modifier = Modifier.weight(1f).padding(start = 12.dp),
                        )
                        if (kind == currentKind) Icon(Icons.Filled.Check, contentDescription = null)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onAction(ActivityDetailAction.DismissSegmentKindChoice) }) {
                Text(stringResource(R.string.track_file_cancel))
            }
        },
    )
}

/** Иконка вида на кружке его цвета — тем же, каким вид окрашен на карте. */
@Composable
private fun KindBadge(kind: SegmentKind) {
    val color = kind.color() ?: MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = Modifier
            .size(36.dp)
            .background(color.copy(alpha = 0.18f), CircleShape),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(kind.icon(), contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
    }
}
