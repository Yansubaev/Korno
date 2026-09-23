package com.ians.korno.feature.tracking.data.service

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.text.format.DateUtils
import android.view.View
import android.widget.RemoteViews
import androidx.annotation.DrawableRes
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.net.toUri
import com.ians.korno.core.domain.model.MeasurementSystem
import com.ians.korno.core.domain.model.RecordingNotificationStyle
import com.ians.korno.core.domain.model.RecordingStatus
import com.ians.korno.core.domain.model.SegmentKind
import com.ians.korno.core.domain.model.countsSteps
import com.ians.korno.core.presentation.FormattedMeasurement
import com.ians.korno.core.presentation.formatDistance
import com.ians.korno.core.presentation.formatPace
import com.ians.korno.core.presentation.formatSpeed
import com.ians.korno.feature.tracking.data.R
import com.ians.korno.feature.tracking.domain.model.RecordingSession
import com.ians.korno.feature.tracking.domain.navigation.TrackingDeepLinks
import com.ians.korno.core.presentation.R as CoreR

internal const val RECORDING_NOTIFICATION_CHANNEL_ID = "tracking_live"

/**
 * Live Update или своя разметка. Продвинуть уведомление со своей разметкой система не даёт,
 * поэтому при запрете продвижения (Android до 16 или выключено в настройках приложения)
 * «Авто» берёт свою разметку — там хотя бы кнопки-иконки вместо текстовых.
 */
internal fun usesLiveUpdate(style: RecordingNotificationStyle, canPromote: Boolean): Boolean = when (style) {
    RecordingNotificationStyle.AUTO -> canPromote
    RecordingNotificationStyle.LIVE_UPDATE -> true
    RecordingNotificationStyle.CUSTOM -> false
}

/**
 * Уведомление идущей записи в двух видах — см. [RecordingNotificationStyle].
 *
 * Оба видны на экране блокировки целиком (VISIBILITY_PUBLIC): кроме цифр тренировки там
 * нечего прятать. Остановить запись с экрана блокировки можно только после разблокировки:
 * случайно нажатый «Стоп» не отменить.
 */
internal class RecordingNotificationBuilder(private val context: Context) {

    fun build(
        session: RecordingSession,
        system: MeasurementSystem,
        style: RecordingNotificationStyle,
    ): Notification {
        val canPromote = NotificationManagerCompat.from(context).canPostPromotedNotifications()
        return if (usesLiveUpdate(style, canPromote)) liveUpdate(session, system) else custom(session, system)
    }

    fun buildPreparing(style: RecordingNotificationStyle): Notification {
        val canPromote = NotificationManagerCompat.from(context).canPostPromotedNotifications()
        return base(R.drawable.ic_activity_running)
            .setContentTitle(context.getString(R.string.tracking_notification_title))
            .setContentText(context.getString(R.string.tracking_notification_preparing))
            .setRequestPromotedOngoing(usesLiveUpdate(style, canPromote))
            .build()
    }

    private fun liveUpdate(session: RecordingSession, system: MeasurementSystem): Notification {
        val waiting = session.status == RecordingStatus.WAITING_FOR_GPS
        val elapsed = session.elapsedMillisAt(System.currentTimeMillis())
        val builder = base(session.notificationIconRes())
            .setContentIntent(recordingScreenIntent(TrackingDeepLinks.RECORDING))
            .setRequestPromotedOngoing(true)

        if (waiting) {
            builder
                .setContentTitle(context.getString(R.string.tracking_notification_title))
                .setContentText(statusText(session))
        } else {
            builder
                .setContentTitle("${distance(session, system).text} · ${pace(session, system).text}")
                .addAction(pauseResumeAction(session))
            if (session.ticks()) {
                // Время тикает система, без перестройки уведомления каждую секунду; оно же
                // попадает в чип статус-бара.
                builder
                    .setContentText(statusText(session))
                    .setWhen(System.currentTimeMillis() - elapsed)
                    .setShowWhen(true)
                    .setUsesChronometer(true)
            } else {
                // Системный секундомер не умеет стоять на месте — на паузе время застывшим текстом.
                val frozen = DateUtils.formatElapsedTime(elapsed / 1_000)
                builder
                    .setContentText("${statusText(session)} · $frozen")
                    .setShowWhen(false)
                    .setShortCriticalText(frozen)
            }
        }
        return builder
            .addAction(
                NotificationCompat.Action.Builder(
                    R.drawable.ic_notification_stop,
                    context.getString(R.string.tracking_notification_stop),
                    serviceIntent(LocationTrackingService.ACTION_STOP_FROM_NOTIFICATION),
                )
                    .setAuthenticationRequired(true)
                    .build(),
            )
            .build()
    }

    private fun custom(session: RecordingSession, system: MeasurementSystem): Notification {
        val distance = distance(session, system)
        val pace = pace(session, system)
        val paused = session.status == RecordingStatus.MANUAL_PAUSE
        val pauseLabel = context.getString(
            if (paused) R.string.tracking_notification_resume else R.string.tracking_notification_pause,
        )
        val collapsed = customViews(R.layout.notification_recording_collapsed, session).apply {
            setTextViewText(R.id.recording_summary, "${distance.text} · ${pace.text}")
            // Свёрнутому по высоте хватает только на иконки, развёрнутому — на чипы с текстом.
            setImageViewResource(
                R.id.recording_pause,
                if (paused) R.drawable.ic_notification_resume else R.drawable.ic_notification_pause,
            )
            setContentDescription(R.id.recording_pause, pauseLabel)
        }
        val expanded = customViews(R.layout.notification_recording_expanded, session).apply {
            setTextViewText(R.id.recording_pause, pauseLabel)
            setTextViewText(R.id.recording_distance, distance.value)
            setTextViewText(R.id.recording_distance_unit, distance.unit)
            setTextViewText(R.id.recording_pace, pace.value)
            setTextViewText(R.id.recording_pace_unit, pace.unit)
        }
        return base(session.notificationIconRes())
            .setContentIntent(recordingScreenIntent(TrackingDeepLinks.RECORDING))
            .setSubText(statusText(session))
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .setCustomContentView(collapsed)
            .setCustomBigContentView(expanded)
            .build()
    }

    /** Время, видимость и действия кнопок — общие у свёрнутой и развёрнутой разметки. */
    private fun customViews(layout: Int, session: RecordingSession): RemoteViews =
        RemoteViews(context.packageName, layout).apply {
            val elapsed = session.elapsedMillisAt(System.currentTimeMillis())
            setChronometer(R.id.recording_time, SystemClock.elapsedRealtime() - elapsed, null, session.ticks())

            // До первой точки паузе нечего останавливать.
            val waiting = session.status == RecordingStatus.WAITING_FOR_GPS
            setViewVisibility(R.id.recording_pause, if (waiting) View.GONE else View.VISIBLE)
            val paused = session.status == RecordingStatus.MANUAL_PAUSE
            setOnClickPendingIntent(
                R.id.recording_pause,
                serviceIntent(if (paused) LocationTrackingService.ACTION_RESUME else LocationTrackingService.ACTION_PAUSE),
            )
            // У кнопок RemoteViews нет флага «только после разблокировки»: «Стоп» открывает
            // экран записи с диалогом завершения, а Activity с экрана блокировки и так
            // запускается только после разблокировки.
            setOnClickPendingIntent(R.id.recording_stop, recordingScreenIntent(TrackingDeepLinks.RECORDING_FINISH))
        }

    private fun base(@DrawableRes icon: Int): NotificationCompat.Builder =
        NotificationCompat.Builder(context, RECORDING_NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(icon)
            // Начиная с Android 17 (API 37) в теле уведомления по умолчанию рисуется иконка
            // лаунчера, а small icon остаётся только в статус-баре. Здесь тип активности важнее
            // узнаваемости приложения — уведомление и так одно и всегда наше.
            .addExtras(Bundle().apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.CINNAMON_BUN) {
                    putBoolean(Notification.EXTRA_PREFER_SMALL_ICON, true)
                }
            })
            .setCategory(NotificationCompat.CATEGORY_WORKOUT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)

    private fun pauseResumeAction(session: RecordingSession): NotificationCompat.Action =
        if (session.status == RecordingStatus.MANUAL_PAUSE) {
            NotificationCompat.Action(
                R.drawable.ic_notification_resume,
                context.getString(R.string.tracking_notification_resume),
                serviceIntent(LocationTrackingService.ACTION_RESUME),
            )
        } else {
            NotificationCompat.Action(
                R.drawable.ic_notification_pause,
                context.getString(R.string.tracking_notification_pause),
                serviceIntent(LocationTrackingService.ACTION_PAUSE),
            )
        }

    private fun statusText(session: RecordingSession): String = context.getString(
        when (session.status) {
            RecordingStatus.WAITING_FOR_GPS -> R.string.tracking_notification_status_waiting_gps
            RecordingStatus.MANUAL_PAUSE -> R.string.tracking_notification_status_paused
            RecordingStatus.AUTO_PAUSE -> R.string.tracking_notification_status_auto_paused
            else -> R.string.tracking_notification_status_recording
        },
    )

    private fun distance(session: RecordingSession, system: MeasurementSystem): FormattedMeasurement =
        context.formatDistance(session.distanceMeters, system)

    /** Как на экране записи: пешим — текущий темп, колёсам — текущая скорость. */
    private fun pace(session: RecordingSession, system: MeasurementSystem): FormattedMeasurement {
        val speed = session.currentSpeedMetersPerSecond.toDouble()
        if (!session.segmentKind.countsSteps) return context.formatSpeed(speed, system)
        return context.formatPace(speed, system) ?: FormattedMeasurement(
            value = "—",
            unit = context.getString(
                if (system == MeasurementSystem.METRIC) CoreR.string.unit_pace_km else CoreR.string.unit_pace_mi,
            ),
        )
    }

    /**
     * Тап по телу уведомления возвращает пользователя на экран текущей записи.
     *
     * Интент неявный (сервис не знает про Activity из `:app`), но ограничен своим пакетом,
     * поэтому уйти на сторону он не может. `CLEAR_TOP` вместе с `singleTop` у `MainActivity`
     * означает «поднять уже запущенную задачу и отдать ей интент», а не «создать вторую копию».
     */
    private fun recordingScreenIntent(uri: String): PendingIntent {
        val intent = Intent(Intent.ACTION_VIEW, uri.toUri())
            .setPackage(context.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            context,
            uri.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun serviceIntent(action: String): PendingIntent {
        val intent = Intent(context, LocationTrackingService::class.java).setAction(action)
        return PendingIntent.getService(
            context,
            action.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}

/** Идёт ли время записи: на ручной паузе и до первой точки оно стоит, на автопаузе — нет. */
private fun RecordingSession.ticks(): Boolean =
    status == RecordingStatus.RECORDING || status == RecordingStatus.AUTO_PAUSE

/**
 * Маппинг повторяет иконки видов движения на экранах записи и списка,
 * чтобы уведомление читалось как продолжение того же экрана.
 */
@DrawableRes
private fun RecordingSession.notificationIconRes(): Int = when (segmentKind) {
    SegmentKind.RUNNING -> R.drawable.ic_activity_running
    SegmentKind.CYCLING -> R.drawable.ic_activity_cycling
    SegmentKind.VEHICLE -> R.drawable.ic_activity_driving
    SegmentKind.ON_FOOT, SegmentKind.HIKING -> R.drawable.ic_activity_walking
    // Вид ещё не определён (авторежим до первого вердикта) или его нет — компас.
    SegmentKind.UNKNOWN, SegmentKind.PAUSE -> R.drawable.ic_activity_auto
}
