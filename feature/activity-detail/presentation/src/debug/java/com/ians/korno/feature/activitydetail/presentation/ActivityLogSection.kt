package com.ians.korno.feature.activitydetail.presentation

import android.text.format.Formatter
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

/**
 * Диагностика одной активности: экспорт её лога и пересчёт участков движения.
 *
 * Лог — тот же архив, что и в настройках, но без записей всего остального приложения:
 * разбирать жалобу на конкретную тренировку по общему логу — значит вручную
 * отфильтровывать чужие сессии.
 */
@Composable
internal fun ActivityLogSection(
    state: ActivityDetailState,
    onAction: (ActivityDetailAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Секция не привязана к логу: пересчёт участков работает и у записи, которую сделали
    // с выключенным логированием.
    val log = state.activityLog

    Column(modifier = modifier.fillMaxWidth().padding(top = 24.dp)) {
        HorizontalDivider()
        Text(
            text = stringResource(R.string.activity_detail_diagnostics),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 16.dp),
        )
        if (log != null) Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp)
                .clickable(
                    enabled = log.hasRecords && !state.isPreparingLogArchive,
                    onClick = { onAction(ActivityDetailAction.ShareActivityLogsClicked) },
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Icon(
                Icons.Filled.BugReport,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.activity_detail_share_logs_title),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = logSubtitle(state),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp)
                .clickable(
                    enabled = !state.isRecalculating,
                    onClick = { onAction(ActivityDetailAction.RedecodeSegmentsClicked) },
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Icon(
                Icons.Filled.Refresh,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.activity_detail_redecode_title),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(
                        if (state.isRecalculating) {
                            R.string.activity_detail_redecode_running
                        } else {
                            R.string.activity_detail_redecode_subtitle
                        },
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun logSubtitle(state: ActivityDetailState): String {
    val log = state.activityLog ?: return ""
    return when {
        state.isPreparingLogArchive ->
            stringResource(R.string.activity_detail_share_logs_preparing)
        !log.hasRecords -> stringResource(R.string.activity_detail_share_logs_empty)
        else -> {
            val size = Formatter.formatShortFileSize(LocalContext.current, log.sizeBytes)
            val subtitle = pluralStringResource(
                id = R.plurals.activity_detail_share_logs_subtitle,
                count = log.recordCount,
                size,
                log.recordCount,
            )
            if (log.isTruncated) {
                subtitle + stringResource(R.string.activity_detail_share_logs_truncated)
            } else {
                subtitle
            }
        }
    }
}
