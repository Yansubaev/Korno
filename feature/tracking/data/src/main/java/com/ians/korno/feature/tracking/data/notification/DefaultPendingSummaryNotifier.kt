package com.ians.korno.feature.tracking.data.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService
import com.ians.korno.feature.tracking.data.R
import com.ians.korno.feature.tracking.domain.repository.PendingSummaryNotifier
import com.ians.korno.feature.tracking.domain.usecase.ObservePendingSummaryActivitiesUseCase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

private const val NOTIFICATION_ID = 4201
private const val NOTIFICATION_CHANNEL_ID = "pending_summary_channel"

/**
 * Уведомление — производное от состояния БД, а не от факта нажатия «Стоп»:
 * так оно само исчезает, когда последняя неподтверждённая запись сохранена или удалена,
 * даже если пользователь пришёл в приложение мимо уведомления.
 */
internal class DefaultPendingSummaryNotifier(
    private val context: Context,
    private val observePendingSummaryActivities: ObservePendingSummaryActivitiesUseCase,
) : PendingSummaryNotifier {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var watching = false

    override fun startWatching() {
        if (watching) return
        watching = true

        createNotificationChannel()
        scope.launch {
            observePendingSummaryActivities()
                .map { it.size }
                .distinctUntilChanged()
                .collect { pendingCount ->
                    if (pendingCount == 0) cancelNotification() else showNotification(pendingCount)
                }
        }
    }

    private fun showNotification(pendingCount: Int) {
        val notification = NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(context.getString(R.string.pending_summary_notification_title))
            .setContentText(
                context.resources.getQuantityString(
                    R.plurals.pending_summary_notification_text,
                    pendingCount,
                    pendingCount,
                ),
            )
            .setSmallIcon(R.drawable.ic_notification_korno)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openAppIntent())
            .build()

        context.getSystemService<NotificationManager>()?.notify(NOTIFICATION_ID, notification)
    }

    private fun cancelNotification() {
        context.getSystemService<NotificationManager>()?.cancel(NOTIFICATION_ID)
    }

    /**
     * Экран сохранения открывает само приложение по тому же признаку в БД,
     * поэтому уведомлению достаточно поднять задачу на передний план.
     */
    private fun openAppIntent(): PendingIntent? {
        val launchIntent = context.packageManager
            .getLaunchIntentForPackage(context.packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ?: return null

        return PendingIntent.getActivity(
            context,
            NOTIFICATION_ID,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            context.getString(R.string.pending_summary_notification_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        )
        context.getSystemService<NotificationManager>()?.createNotificationChannel(channel)
    }
}
