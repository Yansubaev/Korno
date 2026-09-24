package com.ians.korno.feature.tracking.presentation

import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.ians.korno.core.domain.model.SegmentKind
import com.ians.korno.core.presentation.icon
import com.ians.korno.core.presentation.labelRes

/** Виды, которые различает декодер: эталон сравнивается с ними. */
private val GROUND_TRUTH_KINDS = listOf(
    SegmentKind.ON_FOOT,
    SegmentKind.RUNNING,
    SegmentKind.CYCLING,
    SegmentKind.VEHICLE,
)

/**
 * Эталонная метка «сейчас вид X» (`CONTEXT.md`, «Эталонная разметка»): по ним калибровка
 * проверяет автоопределение. Меню — не состояние экрана: ему нечего переживать.
 */
@Composable
internal fun GroundTruthMarkButton(
    onAction: (TrackingRecordingAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        IconButton(onClick = { menuOpen = true }) {
            Icon(Icons.Outlined.Flag, contentDescription = stringResource(R.string.tracking_cd_ground_truth_mark))
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            GROUND_TRUTH_KINDS.forEach { kind ->
                DropdownMenuItem(
                    text = { Text(stringResource(kind.labelRes())) },
                    leadingIcon = { Icon(kind.icon(), contentDescription = null) },
                    onClick = {
                        menuOpen = false
                        onAction(TrackingRecordingAction.MarkGroundTruth(kind))
                    },
                )
            }
        }
    }
}
