package com.ians.korno.data.trackio.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.annotation.StringRes
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService
import androidx.work.ForegroundInfo
import com.ians.korno.data.trackio.R

private const val NOTIFICATION_CHANNEL_ID = "track_files_channel"

/** Операция, у которой есть свой прогресс и своё уведомление. */
internal enum class TrackFileNotification(
    val notificationId: Int,
    @get:StringRes val titleRes: Int,
) {
    EXPORT(notificationId = 4202, titleRes = R.string.track_file_notification_export_title),
    IMPORT(notificationId = 4203, titleRes = R.string.track_file_notification_import_title),
}

/**
 * Уведомление о ходе выгрузки и импорта.
 *
 * Экранный индикатор виден, только пока открыт экран, а выгрузка сотни активностей
 * переживает и уход из приложения. Перевод работы в foreground заодно защищает её от
 * того, что система усыпит процесс на середине папки.
 */
internal class TrackFileNotifier(private val context: Context) {

    init {
        // Один раз на процесс: прогресс обновляется на каждый файл, и пересоздавать
        // канал вместе с уведомлением незачем.
        createChannel()
    }

    fun progress(
        operation: TrackFileNotification,
        completed: Int,
        total: Int,
    ): ForegroundInfo {
        val notification = NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(context.getString(operation.titleRes))
            .setContentText(
                context.getString(R.string.track_file_notification_progress, completed, total),
            )
            .setSmallIcon(R.drawable.ic_notification_track_files)
            .setProgress(total, completed, false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()

        // Тип foreground-сервиса появился в Android 10; ниже его просто нет,
        // и WorkManager обходится без него.
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                operation.notificationId,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            ForegroundInfo(operation.notificationId, notification)
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val notificationChannel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            context.getString(R.string.track_file_notification_channel_name),
            // Прогресс не требует внимания прямо сейчас: звук и всплывающая карточка
            // на выгрузке файлов были бы навязчивы.
            NotificationManager.IMPORTANCE_LOW,
        )
        context.getSystemService<NotificationManager>()?.createNotificationChannel(notificationChannel)
    }
}
