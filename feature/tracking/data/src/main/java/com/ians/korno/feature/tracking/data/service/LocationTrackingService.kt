package com.ians.korno.feature.tracking.data.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.content.getSystemService
import com.ians.korno.core.domain.model.MeasurementSystem
import com.ians.korno.core.domain.model.RecordingNotificationStyle
import com.ians.korno.core.domain.model.RecordingStatus
import com.ians.korno.core.domain.model.countsSteps
import com.ians.korno.core.domain.repository.UserPreferencesRepository
import com.ians.korno.core.logging.AppLogger
import com.ians.korno.core.logging.LogCategory
import com.ians.korno.core.logging.LogManager
import com.ians.korno.core.logging.info
import com.ians.korno.core.logging.error
import com.ians.korno.data.locationtracking.diagnostics.ActivityRecognitionSignalSource
import com.ians.korno.data.locationtracking.diagnostics.TrackingSignal
import com.ians.korno.data.locationtracking.diagnostics.TrackingSignalSource
import com.ians.korno.data.locationtracking.location.LocationClient
import com.ians.korno.data.locationtracking.location.LocationUpdatePolicy
import com.ians.korno.data.locationtracking.steps.StepCounterClient
import com.ians.korno.feature.tracking.data.R
import com.ians.korno.feature.tracking.data.session.TrackingSessionCoordinator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
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
import kotlin.time.Duration.Companion.milliseconds

private const val NOTIFICATION_ID = 4200
private const val LEGACY_NOTIFICATION_CHANNEL_ID = "tracking_channel"
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
    private val activityRecognitionSource: ActivityRecognitionSignalSource by inject()
    private val stepCounterClient: StepCounterClient by inject()

    // Без обработчика любое исключение в корутине сервиса роняет весь процесс, а упавшая
    // посреди записи активность при следующем запуске восстанавливается и снова включает GPS.
    private val serviceScope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, error ->
            logger.error(category = LogCategory.SYSTEM, tag = TAG, throwable = error) {
                "Unhandled error in tracking service"
            }
        },
    )
    private var locationJob: Job? = null
    private var stepJob: Job? = null
    private var signalJob: Job? = null
    private var recognitionJob: Job? = null
    private var preparationTimeoutJob: Job? = null
    private var isPreparing = false
    private var measurementSystem = MeasurementSystem.METRIC
    private var notificationStyle = RecordingNotificationStyle.AUTO
    private val notificationBuilder by lazy { RecordingNotificationBuilder(this) }

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
            ACTION_PAUSE -> if (isIdle()) {
                stopSelf()
            } else {
                coordinator.pause()
                refreshNotification()
            }

            ACTION_RESUME -> if (isIdle()) {
                stopSelf()
            } else {
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
        stepJob?.cancel()
        signalJob?.cancel()
        recognitionJob?.cancel()
        preparationTimeoutJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun prepareGps() {
        if (coordinator.session.value.activityId != null) return
        isPreparing = true
        startForegroundCompat(notificationBuilder.buildPreparing(notificationStyle))
        startLocationUpdates()
        preparationTimeoutJob?.cancel()
        preparationTimeoutJob = serviceScope.launch {
            delay(PREPARATION_TIMEOUT_MILLIS.milliseconds)
            cancelPreparation()
        }
    }

    /**
     * Команда пришла в сервис, которому нечего делать: `startService` поднял его заново уже
     * после остановки. Без `stopSelf` он так и висел бы, удерживая процесс.
     */
    private fun isIdle(): Boolean = !isPreparing && coordinator.session.value.activityId == null

    private fun cancelPreparation() {
        if (isIdle()) {
            stopSelf()
            return
        }
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
        startForegroundCompat(buildNotification())
        startLocationUpdates()
        startStepCollection()
        startSignalCollection()
        startRecognitionCollection()
    }

    /**
     * Шагомер подписывается только тогда, когда он чем-то полезен: шаги считаются у пеших
     * типов и всю запись в авторежиме, а при диагностическом логировании показания пишутся
     * для всех — по ним калибруется детектор вида движения. Держать датчик ради машины без
     * лога — просто лишний расход батареи.
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private fun startStepCollection() {
        if (stepJob?.isActive == true) return
        stepJob = serviceScope.launch {
            combine(
                coordinator.session.map { it.isAutoMode || it.segmentKind.countsSteps },
                logManager.observeSettings()
                    .map { settings -> settings.isAvailable && settings.enabled },
            ) { counts, loggingActive -> counts || loggingActive }
                .distinctUntilChanged()
                .flatMapLatest { needed -> if (needed) stepCounterClient.stepCounts() else emptyFlow() }
                .catch { error ->
                    logger.error(
                        category = LogCategory.SYSTEM,
                        tag = TAG,
                        throwable = error,
                    ) { "Step counter stream failed" }
                }
                .collect(coordinator::applyStepCounter)
        }
    }

    /**
     * Распознавание активности нужно авторежиму всегда, а фиксированному — только для
     * разбора логов. Вердикт пишется в лог (логгер сам молчит, если логирование выключено)
     * и отдаётся координатору, который без авторежима его игнорирует.
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private fun startRecognitionCollection() {
        if (recognitionJob?.isActive == true) return
        recognitionJob = serviceScope.launch {
            combine(
                coordinator.session.map { it.isAutoMode },
                logManager.observeSettings()
                    .map { settings -> settings.isAvailable && settings.enabled },
            ) { autoMode, loggingActive -> autoMode || loggingActive }
                .distinctUntilChanged()
                .flatMapLatest { needed -> if (needed) activityRecognitionSource.observeSignals() else emptyFlow() }
                .catch { error ->
                    logger.error(
                        category = LogCategory.SYSTEM,
                        tag = TAG,
                        throwable = error,
                    ) { "Activity recognition stream failed" }
                }
                .collect { signal ->
                    logger.info(
                        category = LogCategory.SYSTEM,
                        tag = TAG,
                        fields = signal.fields
                    ) { signal.name }
                    if (signal is TrackingSignal.ActivityRecognized) coordinator.applyActivityRecognition(
                        signal
                    )
                }
        }
    }

    /**
     * Системные сигналы нужны только для разбора логов. Поэтому подписка живёт под флагом логирования: в release-сборке
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
                    delay(LocationUpdatePolicy.AUTO_PAUSE_SETTLE_MILLIS.milliseconds)
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
                coordinator.session.map { it.segmentKind }.distinctUntilChanged(),
                coordinator.autoPauseMovementHint,
            ) { preferences, (status, settled), segmentKind, movementHint ->
                measurementSystem = preferences.measurementSystem
                // Вид уведомления переключают в debug-настройках прямо во время записи —
                // перестраиваем сразу, не дожидаясь следующей точки.
                if (preferences.recordingNotificationStyle != notificationStyle) {
                    notificationStyle = preferences.recordingNotificationStyle
                    if (!isPreparing) refreshNotification()
                }
                LocationUpdatePolicy.configFor(
                    accuracy = preferences.gpsAccuracy,
                    status = status,
                    kind = segmentKind,
                    autoPauseSettled = settled,
                    autoPauseMovementHint = movementHint,
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
                    // Обычно это отозванное разрешение: записывать больше нечем. Трек
                    // сохраняется как неподтверждённый, вместо падения процесса.
                    stopRecording(pendingSummary = true)
                }
                .collect { sample ->
                    coordinator.applySample(sample)
                    if (!isPreparing) refreshNotification()
                }
        }
    }

    private fun stopRecording(pendingSummary: Boolean) {
        serviceScope.launch {
            // Сервис останавливается в любом случае: сбой сохранения не должен оставлять
            // включённым GPS. Незакрытая активность восстановится при следующем открытии.
            try {
                coordinator.endSession(pendingSummary)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                logger.error(category = LogCategory.TRACKING, tag = TAG, throwable = error) {
                    "Failed to end tracking session"
                }
            }
            isPreparing = false
            preparationTimeoutJob?.cancel()
            locationJob?.cancel()
            stepJob?.cancel()
            signalJob?.cancel()
            recognitionJob?.cancel()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun refreshNotification() {
        getSystemService<NotificationManager>()
            ?.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): Notification =
        notificationBuilder.build(coordinator.session.value, measurementSystem, notificationStyle)

    /**
     * Канал с `IMPORTANCE_DEFAULT`, но без звука: беззвучные (`LOW`) уведомления Pixel по
     * умолчанию прячет с экрана блокировки, а запись должна быть видна там. Важность канала
     * после создания не меняется, поэтому канал новый, а прежний `tracking_channel` удаляется.
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            RECORDING_NOTIFICATION_CHANNEL_ID,
            getString(R.string.tracking_notification_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            setSound(null, null)
            enableVibration(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        getSystemService<NotificationManager>()?.run {
            deleteNotificationChannel(LEGACY_NOTIFICATION_CHANNEL_ID)
            createNotificationChannel(channel)
        }
    }

    companion object {
        private const val TAG = "LocationTrackingService"
        const val ACTION_PREPARE = "com.ians.korno.tracking.action.PREPARE"
        const val ACTION_CANCEL_PREPARATION = "com.ians.korno.tracking.action.CANCEL_PREPARATION"
        const val ACTION_START = "com.ians.korno.tracking.action.START"
        const val ACTION_PAUSE = "com.ians.korno.tracking.action.PAUSE"
        const val ACTION_RESUME = "com.ians.korno.tracking.action.RESUME"
        const val ACTION_STOP = "com.ians.korno.tracking.action.STOP"
        const val ACTION_STOP_FROM_NOTIFICATION =
            "com.ians.korno.tracking.action.STOP_FROM_NOTIFICATION"
    }
}
