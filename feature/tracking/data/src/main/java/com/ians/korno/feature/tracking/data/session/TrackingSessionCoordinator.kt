package com.ians.korno.feature.tracking.data.session

import com.ians.korno.core.domain.error.onSuccess
import com.ians.korno.core.domain.model.Activity
import com.ians.korno.core.domain.model.ActivityType
import com.ians.korno.core.domain.model.GeoPoint
import com.ians.korno.core.domain.model.RecordingStatus
import com.ians.korno.core.domain.repository.TrackRepository
import com.ians.korno.core.domain.repository.UserPreferencesRepository
import com.ians.korno.core.logging.ActivityLogScope
import com.ians.korno.core.logging.AppLogger
import com.ians.korno.core.logging.LogCategory
import com.ians.korno.core.logging.NoOpActivityLogScope
import com.ians.korno.core.logging.NoOpAppLogger
import com.ians.korno.core.logging.debug
import com.ians.korno.core.logging.info
import com.ians.korno.core.logging.verbose
import com.ians.korno.core.logging.warn
import com.ians.korno.core.trackprocessing.TrackProcessingConfig
import com.ians.korno.core.trackprocessing.TrackProcessor
import com.ians.korno.core.trackprocessing.TrackStatistics
import com.ians.korno.core.trackprocessing.withAutoPauseSensitivity
import com.ians.korno.data.locationtracking.location.LocationSample
import com.ians.korno.feature.tracking.domain.model.RecordingSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update

private const val FLUSH_THRESHOLD = 10
private const val FLUSH_INTERVAL_MILLIS = 5_000L

/**
 * Насколько точной должна быть точка, чтобы считать сигнал пойманным.
 * Первые сэмплы после включения приёмника обычно приходят от сети с погрешностью
 * в сотни метров — начать по ним запись значит открыть трек ложным прыжком.
 */
private const val RELIABLE_FIX_ACCURACY_METERS = 30f

/**
 * Но и ждать идеальной точки бесконечно нельзя: во дворах и под плотной застройкой
 * её может не быть вовсе, и через это время подойдёт любая.
 */
private const val FIX_WAIT_RELAXATION_MILLIS = 30_000L

/**
 * Сколько точка, пойманная на прогреве (до нажатия «Начать»), считается свежей.
 * Пока она свежа, запись стартует сразу, без экрана ожидания.
 */
private const val WARM_FIX_VALIDITY_MILLIS = 20_000L

/**
 * Единственный держатель истины о текущей записи трека.
 * И [com.ians.korno.feature.tracking.data.service.LocationTrackingService], и
 * [com.ians.korno.feature.tracking.data.controller.DefaultTrackingController] читают/пишут
 * через один и тот же инстанс (Koin single).
 *
 * Сырые точки быстро сбрасываются в Room для живой карты, а состояние сессии считается через
 * `:core:track-processing`. При завершении записи Room получает финальные обработанные точки.
 */
internal class TrackingSessionCoordinator(
    private val trackRepository: TrackRepository,
    private val trackProcessor: TrackProcessor,
    private val preferencesRepository: UserPreferencesRepository,
    private val logger: AppLogger = NoOpAppLogger,
    private val activityLogScope: ActivityLogScope = NoOpActivityLogScope,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private val _session = MutableStateFlow(RecordingSession())
    val session: StateFlow<RecordingSession> = _session.asStateFlow()

    private val pointBuffer = mutableListOf<GeoPoint>()
    private val sessionPoints = mutableListOf<GeoPoint>()
    private var lastFlushAtMillis = 0L
    private var autoPauseEnabled = true
    private var processingConfig = TrackProcessingConfig.forActivityType(ActivityType.RUNNING)
    private var lastReliableFixAtMillis: Long? = null
    private var waitingForFixSinceMillis: Long? = null

    /**
     * Создаёт активность и открывает сессию. Если сигнала ещё нет — сессия начинается
     * в [RecordingStatus.WAITING_FOR_GPS]: секундомер стоит на нуле, а запись включится
     * сама, как только придёт достаточно точная точка (см. [applySample]).
     * Точка, пойманная на прогреве до нажатия «Начать», позволяет стартовать сразу.
     */
    suspend fun beginSession(activityType: ActivityType): Long? {
        val startTime = nowMillis()
        loadRecordingPreferences(activityType)
        val hasWarmFix = lastReliableFixAtMillis
            ?.let { startTime - it <= WARM_FIX_VALIDITY_MILLIS } == true
        val initialStatus = if (hasWarmFix) RecordingStatus.RECORDING else RecordingStatus.WAITING_FOR_GPS
        var newActivityId: Long? = null

        trackRepository.createActivity(
            Activity(
                startTimeEpochMillis = startTime,
                activityType = activityType,
            ),
        ).onSuccess { id ->
            newActivityId = id
            pointBuffer.clear()
            sessionPoints.clear()
            lastFlushAtMillis = startTime
            waitingForFixSinceMillis = if (hasWarmFix) null else startTime
            _session.value = RecordingSession(
                status = initialStatus,
                activityId = id,
                activityType = activityType,
                startTimeEpochMillis = startTime,
            )
        }

        val startedActivityId = newActivityId
        if (startedActivityId == null) {
            logger.warn(LogCategory.TRACKING, TAG) { "Failed to create a tracking session" }
        } else {
            // Область открывается до первой записи о старте: иначе она осталась бы
            // только в общем логе, а лог активности начинался бы с первой точки.
            activityLogScope.begin(startedActivityId)
            logger.info(
                category = LogCategory.TRACKING,
                tag = TAG,
                fields = mapOf(
                    "activityId" to startedActivityId.toString(),
                    "activityType" to activityType.name,
                    "status" to initialStatus.name,
                    "hasWarmFix" to hasWarmFix.toString(),
                ) + recordingConfigFields(),
            ) { "Tracking session started" }
        }

        return newActivityId
    }

    /**
     * Настройки, с которыми пошла запись. В логе активности они нужны первой же строкой:
     * без них цифры автопаузы и разброс точек не с чем сравнивать.
     */
    private fun recordingConfigFields(): Map<String, String> = mapOf(
        "autoPauseEnabled" to autoPauseEnabled.toString(),
        "stopSpeedMetersPerSecond" to processingConfig.stopSpeedMetersPerSecond.toString(),
        "resumeSpeedMetersPerSecond" to processingConfig.resumeSpeedMetersPerSecond.toString(),
        "stopWindowMillis" to processingConfig.stopWindowMillis.toString(),
        "stopRadiusMeters" to processingConfig.stopRadiusMeters.toString(),
        "maximumSpeedMetersPerSecond" to processingConfig.maximumSpeedMetersPerSecond.toString(),
    )

    suspend fun recoverActiveSessionIfNeeded(): Boolean {
        if (_session.value.activityId != null) return false

        var recovered = false
        trackRepository.findActiveActivity().onSuccess { activity ->
            if (activity != null) {
                loadRecordingPreferences(activity.activityType)
                pointBuffer.clear()
                val restoredPoints = when (val result = trackRepository.observeGeoPoints(activity.id).first()) {
                    is com.ians.korno.core.domain.error.Result.Success -> result.data
                    is com.ians.korno.core.domain.error.Result.Error -> emptyList()
                }
                sessionPoints.clear()
                sessionPoints += restoredPoints
                val processed = trackProcessor.process(restoredPoints, activity.activityType, processingConfig)
                lastFlushAtMillis = nowMillis()
                waitingForFixSinceMillis = null
                _session.value = RecordingSession(
                    status = RecordingStatus.RECORDING,
                    activityId = activity.id,
                    activityType = activity.activityType,
                    startTimeEpochMillis = activity.startTimeEpochMillis,
                    distanceMeters = processed.statistics.distanceMeters,
                    movingTimeMillis = processed.statistics.movingTimeMillis,
                    currentSpeedMetersPerSecond = processed.statistics.currentSpeedMetersPerSecond.toFloat(),
                    maxSpeedMetersPerSecond = processed.statistics.maxSpeedMetersPerSecond.toFloat(),
                    elevationGainMeters = processed.statistics.elevationGainMeters,
                    lastPoint = processed.points.lastOrNull { !it.isOutlier },
                )
                recovered = true
            }
        }
        if (recovered) {
            _session.value.activityId?.let(activityLogScope::begin)
            logger.info(
                category = LogCategory.TRACKING,
                tag = TAG,
                fields = mapOf(
                    "activityId" to _session.value.activityId.toString(),
                    "pointCount" to sessionPoints.size.toString(),
                ) + recordingConfigFields(),
            ) { "Active tracking session recovered" }
        }
        return recovered
    }

    suspend fun applySample(sample: LocationSample) {
        val now = nowMillis()
        // Сэмплы приходят и до старта — на прогреве GPS с экрана настройки.
        // Записывать их некуда, но по ним видно, что приёмник уже поймал сигнал.
        val isReliableFix = sample.isReliableFixAt(now)
        if (isReliableFix) lastReliableFixAtMillis = now

        if (_session.value.status == RecordingStatus.WAITING_FOR_GPS) {
            if (!isReliableFix) return
            beginRecordingAfterFix(now)
        }

        val current = _session.value
        val activityId = current.activityId
        if (
            current.status !in setOf(
                RecordingStatus.RECORDING,
                RecordingStatus.AUTO_PAUSE,
                RecordingStatus.MANUAL_PAUSE,
            ) || activityId == null
        ) {
            return
        }

        val point = GeoPoint(
            activityId = activityId,
            latitude = sample.latitude,
            longitude = sample.longitude,
            altitudeMeters = sample.altitudeMeters,
            timestampEpochMillis = sample.timestampEpochMillis,
            accuracyMeters = sample.accuracyMeters,
            speedMetersPerSecond = sample.speedMetersPerSecond,
            recordingStatus = current.status,
        )
        // Координаты — отдельной записью и только с явного согласия. Всё остальное о точке
        // безопасно и нужно всегда: по одному лишь числу точек нельзя понять, почему трек
        // рвётся, а по точности, шагу и скорости — можно.
        logger.debug(
            category = LogCategory.GPS,
            tag = TAG,
            fields = sampleFields(sample) + mapOf("status" to current.status.name),
        ) { "Location sample received" }
        logger.verbose(
            category = LogCategory.GPS,
            tag = TAG,
            fields = mapOf(
                "latitude" to sample.latitude.toString(),
                "longitude" to sample.longitude.toString(),
                "altitudeMeters" to sample.altitudeMeters.toString(),
            ),
            containsSensitiveLocation = true,
        ) { "Location sample coordinates" }

        sessionPoints += point
        if (current.status == RecordingStatus.MANUAL_PAUSE) {
            // На ручной паузе координата нужна живой карте и истории маршрута,
            // но значения активности должны оставаться полностью замороженными.
            _session.update {
                it.copy(
                    currentSpeedMetersPerSecond = 0f,
                    lastPoint = point,
                )
            }
            bufferPoint(point)
            return
        }

        val processed = trackProcessor.process(sessionPoints, current.activityType, processingConfig)
        val statistics = processed.statistics
        val latestValidPoint = processed.points.lastOrNull { !it.isOutlier }
        val nextStatus = if (autoPauseEnabled && latestValidPoint?.isStop == true) {
            RecordingStatus.AUTO_PAUSE
        } else {
            RecordingStatus.RECORDING
        }
        val storedPoint = point.copy(
            recordingStatus = nextStatus,
            isStop = processed.points.lastOrNull()?.isStop == true,
            isOutlier = processed.points.lastOrNull()?.isOutlier == true,
        )
        sessionPoints[sessionPoints.lastIndex] = storedPoint

        // По одному факту смены статуса разобрать залипшую автопаузу нельзя: решение
        // зависит от плотности точек и от того, отдаёт ли провайдер скорость. Поэтому
        // и переход, и каждый сэмпл на автопаузе несут метрики, по которым он принят.
        // Вердикт обработчика по каждой точке: без него в логе видно только вход
        // (сырой сэмпл) и выход (статистика), но не то, что процессор с точкой сделал.
        logger.debug(
            category = LogCategory.TRACKING,
            tag = TAG,
            fields = mapOf(
                "pointIndex" to sessionPoints.lastIndex.toString(),
                "isOutlier" to storedPoint.isOutlier.toString(),
                "isStop" to storedPoint.isStop.toString(),
                "status" to nextStatus.name,
                "currentSpeed" to statistics.currentSpeedMetersPerSecond.toString(),
                "distanceMeters" to statistics.distanceMeters.toString(),
                "movingTimeMillis" to statistics.movingTimeMillis.toString(),
            ),
        ) { "Track point processed" }

        if (nextStatus != current.status) {
            logger.info(
                category = LogCategory.TRACKING,
                tag = TAG,
                fields = autoPauseDecisionFields(sample, statistics) + mapOf(
                    "from" to current.status.name,
                    "to" to nextStatus.name,
                ),
            ) { "Automatic recording state changed" }
        } else if (nextStatus == RecordingStatus.AUTO_PAUSE) {
            logger.debug(
                category = LogCategory.TRACKING,
                tag = TAG,
                fields = autoPauseDecisionFields(sample, statistics),
            ) { "Auto pause held" }
        }

        _session.update {
            it.copy(
                status = nextStatus,
                distanceMeters = statistics.distanceMeters,
                movingTimeMillis = statistics.movingTimeMillis,
                currentSpeedMetersPerSecond = statistics.currentSpeedMetersPerSecond.toFloat(),
                maxSpeedMetersPerSecond = statistics.maxSpeedMetersPerSecond.toFloat(),
                elevationGainMeters = statistics.elevationGainMeters,
                lastPoint = latestValidPoint?.copy(recordingStatus = nextStatus),
            )
        }

        bufferPoint(storedPoint)
    }

    /**
     * Величины, по которым принято решение об автопаузе. Шаг между сэмплами здесь не менее
     * важен, чем скорость: детектор остановок рассуждает временными окнами, и разрежённый
     * поток точек ведёт себя совсем не так, как плотный.
     *
     * Вызывается уже после того, как текущая точка добавлена в [sessionPoints].
     */
    private fun autoPauseDecisionFields(
        sample: LocationSample,
        statistics: TrackStatistics,
    ): Map<String, String> = sampleFields(
        sample = sample,
        previousPoint = sessionPoints.getOrNull(sessionPoints.lastIndex - 1),
    ) + mapOf("currentSpeed" to statistics.currentSpeedMetersPerSecond.toString())

    /**
     * Характеристики сэмпла без координат: их можно писать в лог всегда, тогда как
     * широта и долгота требуют отдельного согласия пользователя.
     */
    private fun sampleFields(
        sample: LocationSample,
        previousPoint: GeoPoint? = sessionPoints.lastOrNull(),
    ): Map<String, String> = mapOf(
        "reportedSpeed" to sample.speedMetersPerSecond.toString(),
        "accuracyMeters" to sample.accuracyMeters.toString(),
        "sampleIntervalMillis" to previousPoint
            ?.let { sample.timestampEpochMillis - it.timestampEpochMillis }
            .toString(),
    )

    /**
     * Сигнал считается пойманным, если точность известна и укладывается в
     * [RELIABLE_FIX_ACCURACY_METERS]. Сэмпл без точности принимается как есть — судить не по чему,
     * а после [FIX_WAIT_RELAXATION_MILLIS] ожидания подходит любая точка: грубый трек
     * лучше, чем секундомер, который так и не тронулся с места.
     */
    private fun LocationSample.isReliableFixAt(now: Long): Boolean {
        val accuracy = accuracyMeters ?: return true
        if (accuracy <= RELIABLE_FIX_ACCURACY_METERS) return true
        return waitingForFixSinceMillis?.let { now - it >= FIX_WAIT_RELAXATION_MILLIS } == true
    }

    /**
     * Сигнал найден — с этого момента идёт запись, и отсюда же считается время активности:
     * ожидание сигнала в него не входит. Время старта переписывается и в БД, чтобы
     * восстановление после убийства процесса не засчитало ожидание как часть тренировки.
     */
    private suspend fun beginRecordingAfterFix(atMillis: Long) {
        val current = _session.value
        waitingForFixSinceMillis = null
        lastFlushAtMillis = atMillis
        _session.value = current.copy(
            status = RecordingStatus.RECORDING,
            startTimeEpochMillis = atMillis,
        )
        current.activityId?.let { activityId ->
            trackRepository.updateActivity(
                Activity(
                    id = activityId,
                    startTimeEpochMillis = atMillis,
                    activityType = current.activityType,
                ),
            )
        }
        logger.info(
            category = LogCategory.TRACKING,
            tag = TAG,
            fields = mapOf(
                "activityId" to current.activityId.toString(),
                "waitedMillis" to (atMillis - current.startTimeEpochMillis).toString(),
            ),
        ) { "GPS fix acquired, recording started" }
    }

    private suspend fun bufferPoint(point: GeoPoint) {
        pointBuffer += point
        // Карта на экране записи читает трек из БД, поэтому буфер сбрасывается
        // не только по объёму, но и по времени — иначе маршрут отрисовывался бы
        // рывками раз в FLUSH_THRESHOLD точек.
        val now = nowMillis()
        if (pointBuffer.size >= FLUSH_THRESHOLD || now - lastFlushAtMillis >= FLUSH_INTERVAL_MILLIS) {
            flushBuffer(now)
        }
    }

    fun pause() {
        val current = _session.value
        if (current.status !in setOf(RecordingStatus.RECORDING, RecordingStatus.AUTO_PAUSE)) return
        _session.value = current.copy(
            status = RecordingStatus.MANUAL_PAUSE,
            currentPauseStartMillis = nowMillis(),
        )
        logger.info(LogCategory.TRACKING, TAG) { "Tracking paused manually" }
    }

    fun resume() {
        val current = _session.value
        if (current.status != RecordingStatus.MANUAL_PAUSE) return
        val pauseStart = current.currentPauseStartMillis ?: nowMillis()
        _session.value = current.copy(
            status = RecordingStatus.RECORDING,
            pausedAccumulatedMillis = current.pausedAccumulatedMillis +
                (nowMillis() - pauseStart),
            currentPauseStartMillis = null,
        )
        logger.info(LogCategory.TRACKING, TAG) { "Tracking resumed manually" }
    }

    /**
     * @param pendingSummary запись остановлена не с экрана записи (кнопкой в уведомлении),
     * поэтому активность помечается как неподтверждённая: приложение покажет по ней
     * экран сохранения при возврате.
     */
    suspend fun endSession(pendingSummary: Boolean = false): Long? {
        val current = _session.value
        val activityId = current.activityId ?: return null

        flushBuffer()

        val endTime = nowMillis()
        val ongoingPauseMillis = current.currentPauseStartMillis
            ?.let { pauseStart -> endTime - pauseStart }
            ?: 0L
        // Запись, остановленная до появления сигнала, длилась ноль: всё это время
        // в startTimeEpochMillis лежит момент нажатия «Начать», а не начало трека.
        val elapsedTime = if (current.status == RecordingStatus.WAITING_FOR_GPS) {
            0L
        } else {
            (endTime - current.startTimeEpochMillis - current.pausedAccumulatedMillis - ongoingPauseMillis)
                .coerceAtLeast(0L)
        }
        val processed = trackProcessor.process(sessionPoints, current.activityType, processingConfig)
        val statistics = processed.statistics
        trackRepository.replaceTrack(
            activity = Activity(
                id = activityId,
                startTimeEpochMillis = current.startTimeEpochMillis,
                endTimeEpochMillis = endTime,
                activityType = current.activityType,
                distanceMeters = statistics.distanceMeters,
                movingTimeMillis = statistics.movingTimeMillis,
                elapsedTimeMillis = elapsedTime,
                averageSpeedMetersPerSecond = statistics.averageSpeedMetersPerSecond,
                maxSpeedMetersPerSecond = statistics.maxSpeedMetersPerSecond,
                elevationGainMeters = statistics.elevationGainMeters,
                isPendingSummary = pendingSummary,
            ),
            points = processed.points,
        )

        logger.info(
            category = LogCategory.TRACKING,
            tag = TAG,
            fields = mapOf(
                "activityId" to activityId.toString(),
                "pointCount" to processed.points.size.toString(),
                "elapsedTimeMillis" to elapsedTime.toString(),
                "pendingSummary" to pendingSummary.toString(),
            ),
        ) { "Tracking session stopped" }

        // Область закрывается последней: запись об остановке должна попасть в лог активности.
        activityLogScope.end()
        pointBuffer.clear()
        sessionPoints.clear()
        waitingForFixSinceMillis = null
        _session.value = RecordingSession(status = RecordingStatus.STOPPED)
        return activityId
    }

    private suspend fun flushBuffer(atMillis: Long = nowMillis()) {
        lastFlushAtMillis = atMillis
        if (pointBuffer.isEmpty()) return
        val pointsToFlush = pointBuffer.toList()
        trackRepository.addGeoPoints(pointsToFlush)
        pointBuffer.clear()
        logger.debug(
            category = LogCategory.DATABASE,
            tag = TAG,
            fields = mapOf("pointCount" to pointsToFlush.size.toString()),
        ) { "Tracking point buffer flushed" }
    }

    private suspend fun loadRecordingPreferences(activityType: ActivityType) {
        val preferences = preferencesRepository.observePreferences().first()
        autoPauseEnabled = preferences.autoPauseEnabled
        processingConfig = TrackProcessingConfig.forActivityType(activityType)
            .withAutoPauseSensitivity(preferences.autoPauseSensitivity)
    }

    private companion object {
        const val TAG = "TrackingSessionCoordinator"
    }
}
