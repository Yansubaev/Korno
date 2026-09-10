package com.ians.korno.feature.tracking.data.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.annotation.DrawableRes
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService
import androidx.core.net.toUri
import com.ians.korno.core.domain.model.ActivityType
import com.ians.korno.core.domain.model.MeasurementSystem
import com.ians.korno.core.domain.model.RecordingStatus
import com.ians.korno.core.domain.repository.UserPreferencesRepository
import com.ians.korno.core.logging.AppLogger
import com.ians.korno.core.logging.LogCategory
import com.ians.korno.core.logging.LogManager
import com.ians.korno.core.logging.info
import com.ians.korno.core.logging.error
import com.ians.korno.data.locationtracking.diagnostics.TrackingSignalSource
import com.ians.korno.data.locationtracking.location.LocationClient
import com.ians.korno.data.locationtracking.location.LocationUpdatePolicy
import com.ians.korno.feature.tracking.data.R
import com.ians.korno.feature.tracking.data.session.TrackingSessionCoordinator
import com.ians.korno.feature.tracking.domain.model.RecordingSession
import com.ians.korno.feature.tracking.domain.navigation.TrackingDeepLinks
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

private const val NOTIFICATION_ID = 4200
private const val NOTIFICATION_CHANNEL_ID = "tracking_channel"
private const val PREPARATION_TIMEOUT_MILLIS = 120_000L

/**
 * Foreground-сервис, который только держит подписку на [LocationClient] и уведомление.
 * Вся бизнес-логика (что считать точкой, дистанцией, паузой) живёт в [TrackingSessionCoordinator] —
 * сервис лишь дёргает её методы в ответ на команды (свои же кнопки в уведомлении или
 * [com.ians.korno.feature.tracking.data.controller.DefaultTrackingController]).
 */
internal class LocationTrackingService : Service() {

    private val locationClient: LocationClient by inject()
    private val coordinator: TrackingSessionCoordinator by inject()
    private val preferencesRepository: UserPreferencesRepository by inject()
    private val logger: AppLogger by inject()
    private val logManager: LogManager by inject()
    private val signalSource: TrackingSignalSource by inject()

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var locationJob: Job? = null
    private var signalJob: Job? = null
    private var preparationTimeoutJob: Job? = null
    private var isPreparing = false
    private var measurementSystem = MeasurementSystem.METRIC

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PREPARE -> prepareGps()
            ACTION_CANCEL_PREPARATION -> cancelPreparation()
            ACTION_START -> startRecording()
            ACTION_PAUSE -> {
                coordinator.pause()
                refreshNotification()
            }
            ACTION_RESUME -> {
                coordinator.resume()
                refreshNotification()
            }
            ACTION_STOP -> stopRecording(pendingSummary = false)
            // Кнопка «Стоп» в уведомлении: экран сохранения показать некому,
            // поэтому активность помечается как неподтверждённая.
            ACTION_STOP_FROM_NOTIFICATION -> stopRecording(pendingSummary = true)
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        locationJob?.cancel()
        signalJob?.cancel()
        preparationTimeoutJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun prepareGps() {
        if (coordinator.session.value.activityId != null) return
        isPreparing = true
        startForegroundCompat(buildPreparingNotification())
        startLocationUpdates()
        preparationTimeoutJob?.cancel()
        preparationTimeoutJob = serviceScope.launch {
            delay(PREPARATION_TIMEOUT_MILLIS)
            cancelPreparation()
        }
    }

    private fun cancelPreparation() {
        if (!isPreparing || coordinator.session.value.activityId != null) return
        isPreparing = false
        preparationTimeoutJob?.cancel()
        preparationTimeoutJob = null
        locationJob?.cancel()
        locationJob = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun startRecording() {
        isPreparing = false
        preparationTimeoutJob?.cancel()
        preparationTimeoutJob = null
        startForegroundCompat(buildNotification(coordinator.session.value))
        startLocationUpdates()
        startSignalCollection()
    }

    /**
     * Системные сигналы стоят батареи (особенно распознавание активности), а нужны только
     * для разбора логов. Поэтому подписка живёт под флагом логирования: в release-сборке
     * логгер недоступен, и она не включается никогда.
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private fun startSignalCollection() {
        if (signalJob?.isActive == true) return
        signalJob = serviceScope.launch {
            logManager.observeSettings()
                .map { settings -> settings.isAvailable && settings.enabled }
                .distinctUntilChanged()
                .flatMapLatest { loggingActive ->
                    if (loggingActive) signalSource.observeSignals() else emptyFlow()
                }
                .catch { error ->
                    logger.error(
                        category = LogCategory.SYSTEM,
                        tag = TAG,
                        throwable = error,
                    ) { "System signal stream failed" }
                }
                .collect { signal ->
                    logger.info(
                        category = LogCategory.SYSTEM,
                        tag = TAG,
                        fields = signal.fields,
                    ) { signal.name }
                }
        }
    }

    /**
     * Состояние записи вместе с признаком «автопауза уже затянулась».
     *
     * Отдельный поток нужен ровно ради этого признака: он меняется не от события, а от
     * времени. Короткая автопауза не должна трогать частоту опроса вовсе —
     * [LocationUpdatePolicy.AUTO_PAUSE_SETTLE_MILLIS] задаёт, сколько она считается
     * короткой. `flatMapLatest` снимает отложенное переключение сам, если запись
     * возобновилась раньше срока.
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private fun settledStatuses() = coordinator.session
        .map { it.status }
        .distinctUntilChanged()
        .flatMapLatest { status ->
            if (status == RecordingStatus.AUTO_PAUSE) {
                flow {
                    emit(status to false)
                    delay(LocationUpdatePolicy.AUTO_PAUSE_SETTLE_MILLIS)
                    emit(status to true)
                }
            } else {
                flowOf(status to false)
            }
        }

    /**
     * Частота опроса зависит не только от настройки точности, но и от состояния записи:
     * на паузе она снижается, а подписка сохраняется. Смена конфигурации пересоздаёт
     * запрос к Fused Location Provider, поэтому поток проходит через distinctUntilChanged —
     * иначе каждое обновление сессии перезапускало бы подписку.
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private fun startLocationUpdates() {
        if (locationJob?.isActive == true) return
        locationJob = serviceScope.launch {
            combine(
                preferencesRepository.observePreferences(),
                settledStatuses(),
                coordinator.session.map { it.activityType }.distinctUntilChanged(),
            ) { preferences, (status, settled), activityType ->
                measurementSystem = preferences.measurementSystem
                LocationUpdatePolicy.configFor(
                    accuracy = preferences.gpsAccuracy,
                    status = status,
                    activityType = activityType,
                    autoPauseSettled = settled,
                )
            }
                .distinctUntilChanged()
                .onEach { config ->
                    // Смена режима опроса — редкое и важное событие: по нему разбирают
                    // жалобы на расход батареи и на пропуски точек после паузы.
                    logger.info(
                        category = LogCategory.GPS,
                        tag = TAG,
                        fields = mapOf(
                            "intervalMillis" to config.intervalMillis.toString(),
                            "accuracy" to config.accuracy.name,
                        ),
                    ) { "Location update policy changed" }
                }
                .flatMapLatest(locationClient::locationUpdates)
                .catch { error ->
                    logger.error(
                        category = LogCategory.GPS,
                        tag = TAG,
                        throwable = error,
                    ) { "Location update stream failed" }
                    throw error
                }
                .collect { sample ->
                    coordinator.applySample(sample)
                    if (!isPreparing) refreshNotification()
                }
        }
    }

    private fun stopRecording(pendingSummary: Boolean) {
        serviceScope.launch {
            coordinator.endSession(pendingSummary)
            isPreparing = false
            preparationTimeoutJob?.cancel()
            locationJob?.cancel()
            signalJob?.cancel()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun refreshNotification() {
        getSystemService<NotificationManager>()
            ?.notify(NOTIFICATION_ID, buildNotification(coordinator.session.value))
    }

    private fun buildNotification(session: RecordingSession): Notification {
        // До первой точки паузе нечего останавливать: остаётся только «Стоп».
        if (session.status == RecordingStatus.WAITING_FOR_GPS) {
            return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
                .setContentTitle(getString(R.string.tracking_notification_title))
                .setContentText(getString(R.string.tracking_notification_status_waiting_gps))
                .setSmallIcon(session.activityType.notificationIconRes())
                .addExtras(Bundle().apply { putBoolean(Notification.EXTRA_PREFER_SMALL_ICON, true) })
                .setContentIntent(recordingScreenPendingIntent())
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .addAction(
                    NotificationCompat.Action(
                        android.R.drawable.ic_menu_close_clear_cancel,
                        getString(R.string.tracking_notification_stop),
                        servicePendingIntent(ACTION_STOP_FROM_NOTIFICATION),
                    ),
                )
                .build()
        }

        val isManuallyPaused = session.status == RecordingStatus.MANUAL_PAUSE
        val pauseResumeAction = if (isManuallyPaused) {
            NotificationCompat.Action(
                android.R.drawable.ic_media_play,
                getString(R.string.tracking_notification_resume),
                servicePendingIntent(ACTION_RESUME),
            )
        } else {
            NotificationCompat.Action(
                android.R.drawable.ic_media_pause,
                getString(R.string.tracking_notification_pause),
                servicePendingIntent(ACTION_PAUSE),
            )
        }
        val stopAction = NotificationCompat.Action(
            android.R.drawable.ic_menu_close_clear_cancel,
            getString(R.string.tracking_notification_stop),
            servicePendingIntent(ACTION_STOP_FROM_NOTIFICATION),
        )

        val distance = when (measurementSystem) {
            MeasurementSystem.METRIC -> session.distanceMeters / 1_000.0
            MeasurementSystem.IMPERIAL -> session.distanceMeters / 1_609.344
        }
        val distanceUnit = getString(
            if (measurementSystem == MeasurementSystem.METRIC) {
                R.string.tracking_notification_unit_km
            } else {
                R.string.tracking_notification_unit_mi
            },
        )
        val statusText = getString(
            when (session.status) {
                RecordingStatus.MANUAL_PAUSE -> R.string.tracking_notification_status_paused
                RecordingStatus.AUTO_PAUSE -> R.string.tracking_notification_status_auto_paused
                else -> R.string.tracking_notification_status_recording
            },
            String.format(Locale.getDefault(), "%.2f", distance),
            distanceUnit,
        )

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.tracking_notification_title))
            .setContentText(statusText)
            .setSmallIcon(session.activityType.notificationIconRes())
            // Начиная с Android 17 (API 37) в теле уведомления по умолчанию рисуется иконка
            // лаунчера, а small icon остаётся только в статус-баре. Здесь тип активности важнее
            // узнаваемости приложения — уведомление и так одно и всегда наше.
            .addExtras(Bundle().apply { putBoolean(Notification.EXTRA_PREFER_SMALL_ICON, true) })
            .setContentIntent(recordingScreenPendingIntent())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(pauseResumeAction)
            .addAction(stopAction)
            .build()
    }

    private fun buildPreparingNotification(): Notification =
        NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.tracking_notification_title))
            .setContentText(getString(R.string.tracking_notification_preparing))
            .setSmallIcon(R.drawable.ic_activity_running)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()

    /**
     * Маппинг повторяет иконки типов активности на экранах записи и списка,
     * чтобы уведомление читалось как продолжение того же экрана.
     */
    @DrawableRes
    private fun ActivityType.notificationIconRes(): Int = when (this) {
        ActivityType.RUNNING -> R.drawable.ic_activity_running
        ActivityType.CYCLING -> R.drawable.ic_activity_cycling
        ActivityType.DRIVING -> R.drawable.ic_activity_driving
        ActivityType.WALKING, ActivityType.HIKING, ActivityType.OTHER -> R.drawable.ic_activity_walking
    }

    /**
     * Тап по телу уведомления возвращает пользователя на экран текущей записи.
     *
     * Интент неявный (сервис не знает про Activity из `:app`), но ограничен своим пакетом,
     * поэтому уйти на сторону он не может. `CLEAR_TOP` вместе с `singleTop` у `MainActivity`
     * означает «поднять уже запущенную задачу и отдать ей интент», а не «создать вторую копию».
     */
    private fun recordingScreenPendingIntent(): PendingIntent {
        val intent = Intent(Intent.ACTION_VIEW, TrackingDeepLinks.RECORDING.toUri())
            .setPackage(packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            this,
            TrackingDeepLinks.RECORDING.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun servicePendingIntent(action: String): PendingIntent {
        val intent = Intent(this, LocationTrackingService::class.java).setAction(action)
        return PendingIntent.getService(
            this,
            action.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            getString(R.string.tracking_notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        )
        getSystemService<NotificationManager>()?.createNotificationChannel(channel)
    }

    companion object {
        private const val TAG = "LocationTrackingService"
        const val ACTION_PREPARE = "com.ians.korno.tracking.action.PREPARE"
        const val ACTION_CANCEL_PREPARATION = "com.ians.korno.tracking.action.CANCEL_PREPARATION"
        const val ACTION_START = "com.ians.korno.tracking.action.START"
        const val ACTION_PAUSE = "com.ians.korno.tracking.action.PAUSE"
        const val ACTION_RESUME = "com.ians.korno.tracking.action.RESUME"
        const val ACTION_STOP = "com.ians.korno.tracking.action.STOP"
        const val ACTION_STOP_FROM_NOTIFICATION = "com.ians.korno.tracking.action.STOP_FROM_NOTIFICATION"
    }
}
