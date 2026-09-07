package com.ians.korno.feature.tracking.data.session

import com.ians.korno.core.domain.error.onSuccess
import com.ians.korno.core.domain.model.Activity
import com.ians.korno.core.domain.model.ActivityType
import com.ians.korno.core.domain.model.AutoPauseSensitivity
import com.ians.korno.core.domain.model.GeoPoint
import com.ians.korno.core.domain.model.GpsAccuracy
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
import com.ians.korno.core.trackprocessing.IncrementalTrackProcessor
import com.ians.korno.core.trackprocessing.TrackProcessingConfig
import com.ians.korno.core.trackprocessing.TrackProcessor
import com.ians.korno.core.trackprocessing.TrackStatistics
import com.ians.korno.core.trackprocessing.withAutoPauseSensitivity
import com.ians.korno.data.locationtracking.location.LocationSample
import com.ians.korno.feature.tracking.domain.model.RecordingSession
import java.util.Locale
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
 * Насколько старым может быть фикс, чтобы описывать текущее положение.
 *
 * Fused Location Provider на первую подписку почти всегда отдаёт последнюю известную
 * координату из кэша, и по времени получения она неотличима от свежей. Возраст ей
 * может быть и час: точка ставится там, где телефон лежал до выхода, и трек
 * открывается прыжком через весь город. Батчинг выключен
 * (`setMaxUpdateDelayMillis(0)`), поэтому настоящий фикс при шаге опроса в секунду
 * доходит заметно быстрее этого порога, и запас взят с избытком.
 */
private const val MAXIMUM_FIX_AGE_MILLIS = 5_000L

/**
 * Провайдер, координаты которого не описывают движение: сетевой фикс считается по
 * вышкам и точкам доступа, его погрешность — сотни метров, и доплеровской скорости
 * у него нет вовсе. Запрос идёт с точной гранулярностью и высоким приоритетом, но
 * система вправе обслужить его чем угодно, и в разобранном логе прогулки обслуживала.
 */
private const val NETWORK_PROVIDER = "network"

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

    /**
     * Сырые точки сессии: вердикты фильтров сюда не записываются намеренно. Раньше вердикт
     * последней точки клался обратно в этот же список, а фильтр выбросов считает флаг
     * `isOutlier` липким — точка, один раз признанная выбросом, не могла им перестать быть,
     * даже когда следующие точки это опровергали.
     */
    private val sessionPoints = mutableListOf<GeoPoint>()
    private var lastFlushAtMillis = 0L
    private var autoPauseEnabled = true
    private var processingConfig = TrackProcessingConfig.forActivityType(ActivityType.RUNNING)
    private var liveProcessor = IncrementalTrackProcessor(processingConfig)
    private var gpsAccuracy = GpsAccuracy.BALANCED
    private var autoPauseSensitivity = AutoPauseSensitivity.MEDIUM
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
            liveProcessor.reset()
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
     *
     * Режим опроса GPS попадает сюда же, хотя запросом владеет сервис. Подписка на
     * геолокацию поднимается ещё на прогреве, до того как у активности появится id, и её
     * собственная запись `Location update policy changed` уходит только в общий лог —
     * по логу одной активности нельзя было понять, с какой точностью она вообще писалась.
     */
    private fun recordingConfigFields(): Map<String, String> = mapOf(
        "autoPauseEnabled" to autoPauseEnabled.toString(),
        "autoPauseSensitivity" to autoPauseSensitivity.name,
        "gpsAccuracy" to gpsAccuracy.name,
        "stopSpeedMetersPerSecond" to processingConfig.stopSpeedMetersPerSecond.toString(),
        "resumeSpeedMetersPerSecond" to processingConfig.resumeSpeedMetersPerSecond.toString(),
        "stopWindowMillis" to processingConfig.stopWindowMillis.toString(),
        "minimumStopDurationMillis" to processingConfig.minimumStopDurationMillis.toString(),
        "stopRadiusMeters" to processingConfig.stopRadiusMeters.toString(),
        "maximumSpeedMetersPerSecond" to processingConfig.maximumSpeedMetersPerSecond.toString(),
        "maximumAccuracyMeters" to processingConfig.maximumAccuracyMeters.toString(),
        "processNoiseAcceleration" to
            processingConfig.processNoiseAccelerationMetersPerSecondSquared.toString(),
        "outlierGateChiSquare" to processingConfig.outlierGateChiSquare.toString(),
        "positionNoiseFloorMeters" to processingConfig.positionNoiseFloorMeters.toString(),
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
                // Вердикты фильтров пересчитываются с нуля: `isOutlier` липкий, и
                // восстановленная с ним точка осталась бы выбросом навсегда.
                val rawPoints = restoredPoints.map { it.copy(isOutlier = false, isStop = false) }
                sessionPoints.clear()
                sessionPoints += rawPoints
                liveProcessor.reset(rawPoints)
                val processed = trackProcessor.process(rawPoints, activity.activityType, processingConfig)
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
        // Негодный фикс отбрасывается до всего остального, включая отметку о пойманном
        // сигнале: по кэшированной координате нельзя ни начать запись, ни считать, что
        // приёмник прогрелся.
        val rejection = sample.usabilityRejection()
        if (rejection != null) {
            logger.debug(
                category = LogCategory.GPS,
                tag = TAG,
                fields = sampleFields(sample) + mapOf("rejectedBecause" to rejection),
            ) { "Location sample rejected before processing" }
            return
        }

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
            speedAccuracyMetersPerSecond = sample.speedAccuracyMetersPerSecond,
            bearingDegrees = sample.bearingDegrees,
            bearingAccuracyDegrees = sample.bearingAccuracyDegrees,
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
            // Точка отдаётся обработчику и на паузе, хотя её значения никуда не идут:
            // иначе в накопленном треке на месте паузы образуется дыра во времени, и
            // возобновление после получаса стоянки он примет за разрыв записи —
            // с прямой линией в дистанции и лишним gapCount.
            liveProcessor.append(point)
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

        val update = liveProcessor.append(point)
        val statistics = update.statistics
        val latestValidPoint = update.lastValidPoint
        val nextStatus = if (autoPauseEnabled && latestValidPoint?.isStop == true) {
            RecordingStatus.AUTO_PAUSE
        } else {
            RecordingStatus.RECORDING
        }
        val storedPoint = point.copy(
            recordingStatus = nextStatus,
            isStop = update.point.isStop,
            isOutlier = update.point.isOutlier,
        )

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
                // Отброшенная точка ничего не добавляет к дистанции, и без причины отказа
                // это выглядит как потерянный сэмпл. Причина считается по тем же порогам,
                // по которым принято само решение.
                "outlierReason" to outlierReason(sample, storedPoint),
                // Доля времени, проведённая в разрывах: показывает, какой части трека
                // вообще есть чем верить.
                "unmeasuredTimeMillis" to statistics.unmeasuredTimeMillis.toString(),
                "gapCount" to statistics.gapCount.toString(),
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
    ) + mapOf(
        "currentSpeed" to statistics.currentSpeedMetersPerSecond.toString(),
        "stopSpeedMetersPerSecond" to processingConfig.stopSpeedMetersPerSecond.toString(),
        "resumeSpeedMetersPerSecond" to processingConfig.resumeSpeedMetersPerSecond.toString(),
        "stopWindowMillis" to processingConfig.stopWindowMillis.toString(),
    )

    /**
     * Почему точка признана выбросом. Пустая строка означает, что она принята.
     *
     * Причина восстанавливается по тем же порогам, по которым принято решение, а не
     * приходит из фильтра: тащить её через доменную модель ради одной строки лога
     * значило бы навсегда поселить диагностику в `GeoPoint`.
     */
    private fun outlierReason(sample: LocationSample, point: GeoPoint): String {
        if (!point.isOutlier) return ""
        val accuracy = sample.accuracyMeters?.toDouble()
        if (accuracy != null && accuracy > processingConfig.maximumAccuracyMeters) {
            return "ACCURACY"
        }
        val previous = sessionPoints.getOrNull(sessionPoints.lastIndex - 1) ?: return "UNKNOWN"
        val elapsedMillis = sample.timestampEpochMillis - previous.timestampEpochMillis
        return if (elapsedMillis <= 0L) "TIMESTAMP" else "SPEED"
    }

    /**
     * Характеристики сэмпла без координат: их можно писать в лог всегда, тогда как
     * широта и долгота требуют отдельного согласия пользователя.
     *
     * Провайдер и возраст фикса здесь важнее, чем кажется: сетевой фикс отличался от
     * GNSS-фикса только отсутствующей скоростью, и определить это можно было лишь
     * косвенно — а именно на нём и разваливались и трек, и автопауза.
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
        "provider" to (sample.provider ?: "unknown"),
        "ageMillis" to sample.ageMillis.toString(),
        "verticalAccuracyMeters" to sample.verticalAccuracyMeters.toString(),
        "speedAccuracyMetersPerSecond" to sample.speedAccuracyMetersPerSecond.toString(),
        "bearingDegrees" to sample.bearingDegrees.toString(),
        "bearingAccuracyDegrees" to sample.bearingAccuracyDegrees.toString(),
        "satelliteCount" to sample.satelliteCount.toString(),
    )

    /**
     * Сигнал считается пойманным, если точность известна и укладывается в
     * [RELIABLE_FIX_ACCURACY_METERS]. Сэмпл без точности принимается как есть — судить не по чему,
     * а после [FIX_WAIT_RELAXATION_MILLIS] ожидания подходит любая точка: грубый трек
     * лучше, чем секундомер, который так и не тронулся с места.
     */
    /**
     * Почему сэмпл не годится даже как сырое измерение; `null` — годится.
     *
     * Проверки намеренно стоят до фильтров трека, а не внутри них. Фильтр рассуждает о
     * правдоподобии движения и решает, верить ли измерению; здесь же отсекается то, что
     * измерением текущего положения не является вовсе — устаревший кэш и координата,
     * посчитанная не по спутникам. Такому фиксу нечего противопоставить: он выглядит
     * совершенно правдоподобно, просто относится к другому месту или другому времени.
     */
    private fun LocationSample.usabilityRejection(): String? = when {
        (ageMillis ?: 0L) > MAXIMUM_FIX_AGE_MILLIS -> "STALE"
        provider == NETWORK_PROVIDER -> "PROVIDER"
        else -> null
    }

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
            ) + trackQualityFields(processed.points, statistics, elapsedTime),
        ) { "Tracking session stopped" }

        // Область закрывается последней: запись об остановке должна попасть в лог активности.
        activityLogScope.end()
        pointBuffer.clear()
        sessionPoints.clear()
        liveProcessor.reset()
        waitingForFixSinceMillis = null
        _session.value = RecordingSession(status = RecordingStatus.STOPPED)
        return activityId
    }

    /**
     * Сводка качества записи одной строкой в конце лога активности.
     *
     * Раньше эти числа приходилось выводить вручную из сотни строк `Location sample
     * received`: медиана погрешности, доля отброшенных точек, реальный шаг опроса против
     * запрошенного. Именно с них начинается разбор любой жалобы на трек, и именно их
     * не хватало, чтобы за минуту понять, что записи просто нечего было обрабатывать.
     */
    private fun trackQualityFields(
        points: List<GeoPoint>,
        statistics: TrackStatistics,
        elapsedTimeMillis: Long,
    ): Map<String, String> {
        if (points.isEmpty()) return emptyMap()
        val accuracies = points.mapNotNull { it.accuracyMeters?.toDouble() }.sorted()
        val intervals = points.map(GeoPoint::timestampEpochMillis)
            .sorted()
            .zipWithNext { previous, next -> next - previous }
            .sorted()
        return mapOf(
            "outlierCount" to points.count(GeoPoint::isOutlier).toString(),
            "stopPointCount" to points.count(GeoPoint::isStop).toString(),
            "accuracyMedianMeters" to accuracies.percentile(0.5).format(),
            "accuracyP90Meters" to accuracies.percentile(0.9).format(),
            "sampleIntervalMedianMillis" to intervals.percentile(0.5).toString(),
            "sampleIntervalP90Millis" to intervals.percentile(0.9).toString(),
            "unmeasuredTimeMillis" to statistics.unmeasuredTimeMillis.toString(),
            "gapCount" to statistics.gapCount.toString(),
            "unmeasuredTimeShare" to if (elapsedTimeMillis > 0L) {
                (statistics.unmeasuredTimeMillis.toDouble() / elapsedTimeMillis).format()
            } else {
                "0"
            },
        )
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
        autoPauseSensitivity = preferences.autoPauseSensitivity
        gpsAccuracy = preferences.gpsAccuracy
        processingConfig = TrackProcessingConfig.forActivityType(activityType)
            .withAutoPauseSensitivity(preferences.autoPauseSensitivity)
        liveProcessor = IncrementalTrackProcessor(processingConfig)
    }

    private companion object {
        const val TAG = "TrackingSessionCoordinator"
    }
}

/** Перцентиль отсортированного списка; `null`, если считать не по чему. */
private fun <T : Comparable<T>> List<T>.percentile(fraction: Double): T? {
    if (isEmpty()) return null
    val index = ((size - 1) * fraction).toInt().coerceIn(0, size - 1)
    return this[index]
}

/** Одна цифра после запятой: в логе нужен порядок величины, а не все разряды Double. */
private fun Double?.format(): String =
    this?.let { String.format(Locale.US, "%.1f", it) } ?: "null"
