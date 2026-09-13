package com.ians.korno.feature.tracking.data.session

import com.ians.korno.core.domain.error.Result
import com.ians.korno.core.domain.error.onSuccess
import com.ians.korno.core.domain.model.Activity
import com.ians.korno.core.domain.model.ActivitySegment
import com.ians.korno.core.domain.model.ActivityType
import com.ians.korno.core.domain.model.AutoPauseSensitivity
import com.ians.korno.core.domain.model.countsSteps
import com.ians.korno.core.domain.model.GeoPoint
import com.ians.korno.core.domain.model.GpsAccuracy
import com.ians.korno.core.domain.model.RecognitionSample
import com.ians.korno.core.domain.model.RecognizedActivity
import com.ians.korno.core.domain.model.RecordingStatus
import com.ians.korno.core.domain.model.SegmentKind
import com.ians.korno.core.domain.model.activityType
import com.ians.korno.core.domain.model.toSingleSegment
import com.ians.korno.core.domain.repository.TrackRepository
import com.ians.korno.core.domain.repository.UserPreferencesRepository
import com.ians.korno.core.logging.ActivityLogScope
import com.ians.korno.core.logging.AppLogger
import com.ians.korno.core.logging.LogCategory
import com.ians.korno.core.logging.NoOpActivityLogScope
import com.ians.korno.core.logging.NoOpAppLogger
import com.ians.korno.core.logging.debug
import com.ians.korno.core.logging.info
import com.ians.korno.core.logging.warn
import com.ians.korno.core.trackprocessing.ActivityModeDecoder
import com.ians.korno.core.trackprocessing.DetectorEvent
import com.ians.korno.core.trackprocessing.DetectorSignal
import com.ians.korno.core.trackprocessing.FinalizeTrackUseCase
import com.ians.korno.core.trackprocessing.IncrementalTrackProcessor
import com.ians.korno.core.trackprocessing.LiveTrackUpdate
import com.ians.korno.core.trackprocessing.ModeDecoderInput
import com.ians.korno.core.trackprocessing.SegmentDetector
import com.ians.korno.core.trackprocessing.SegmentDetectorState
import com.ians.korno.core.trackprocessing.TrackProcessingConfig
import com.ians.korno.core.trackprocessing.TrackProcessor
import com.ians.korno.core.trackprocessing.TrackStatistics
import com.ians.korno.core.trackprocessing.profileActivityType
import com.ians.korno.core.trackprocessing.withAutoPauseSensitivity
import com.ians.korno.data.locationtracking.diagnostics.TrackingSignal
import com.ians.korno.data.locationtracking.location.LocationSample
import com.ians.korno.feature.tracking.domain.model.RecordingSession
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val FLUSH_THRESHOLD = 10
private const val FLUSH_INTERVAL_MILLIS = 5_000L

/**
 * Как часто показание шагомера попадает в лог. Датчик шлёт событие на каждый шаг, а
 * счётчик кумулятивен: каденс по логу восстанавливается и из редких показаний.
 */
private const val STEP_LOG_INTERVAL_MILLIS = 5_000L

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
    private val segmentDetector: SegmentDetector = SegmentDetector(),
    private val modeDecoder: ActivityModeDecoder = ActivityModeDecoder(),
    private val finalizeTrack: FinalizeTrackUseCase = FinalizeTrackUseCase(trackProcessor, segmentDetector),
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
     * Последнее показание TYPE_STEP_COUNTER — кумулятивного с последней перезагрузки
     * устройства, а не за сессию. `null` — база ещё не поймана: следующее показание ляжет
     * в неё, а не в счётчик шагов.
     */
    private var lastStepCounter: Long? = null
    private var lastStepLoggedAtMillis: Long? = null

    /**
     * Время последнего принятого фикса — ключ, по которому отсеиваются повторы.
     *
     * Провайдер регулярно отдаёт один и тот же фикс дважды: в разобранных логах так
     * пришли 4 сэмпла из 720, 12 из 341 и 8 из 99. До конвейера они доходили целиком и
     * там же браковались как выбросы по времени — на трек это не влияло, но счётчик
     * выбросов раздувало втрое, и разбирать по нему записи становилось нечем.
     */
    private var lastAcceptedSampleTimestampMillis: Long? = null

    /**
     * Состояние детектора и сегменты авторежима меняются из трёх потоков сразу — точки,
     * шагомер, распознавание, — поэтому только под [segmentLock]. Запись в БД — под
     * [segmentWriteMutex] в том порядке, в каком границы подтверждены.
     */
    private val segmentLock = Any()
    private val segmentWriteMutex = Mutex()
    private var detectorState = SegmentDetectorState()

    /** Сегменты авторежима по порядку, последний открыт. В фиксированном режиме пусто. */
    private val sessionSegments = mutableListOf<ActivitySegment>()
    private val pendingSegmentWrites = ArrayDeque<Pair<ActivitySegment, ActivitySegment?>>()

    /**
     * Счёт шагов сессии во времени: граница сегмента ставится задним числом, и шаги до
     * неё нужно отделить от шагов после.
     *
     * ponytail: растёт на каждое показание всю запись (порядка мегабайта за несколько
     * часов); обрезать до начала открытого сегмента — если записи станут многочасовыми.
     */
    private val stepTimeline = mutableListOf<Pair<Long, Int>>()

    /** То же для живой дистанции: по ней режется дистанция закрываемого сегмента. */
    private val distanceTimeline = mutableListOf<Pair<Long, Double>>()

    /**
     * Создаёт активность и открывает сессию. Если сигнала ещё нет — сессия начинается
     * в [RecordingStatus.WAITING_FOR_GPS]: секундомер стоит на нуле, а запись включится
     * сама, как только придёт достаточно точная точка (см. [applySample]).
     * Точка, пойманная на прогреве до нажатия «Начать», позволяет стартовать сразу.
     */
    /**
     * @param autoMode вид движения определяет детектор: [activityType] не учитывается,
     *   запись начинается с сегмента `UNKNOWN` и самого терпимого профиля порогов.
     */
    suspend fun beginSession(activityType: ActivityType, autoMode: Boolean = false): Long? {
        val startTime = nowMillis()
        val sessionType = if (autoMode) ActivityType.OTHER else activityType
        loadRecordingPreferences(if (autoMode) SegmentKind.UNKNOWN.profileActivityType else activityType)
        val hasWarmFix = lastReliableFixAtMillis
            ?.let { startTime - it <= WARM_FIX_VALIDITY_MILLIS } == true
        val initialStatus = if (hasWarmFix) RecordingStatus.RECORDING else RecordingStatus.WAITING_FOR_GPS
        var newActivityId: Long? = null

        val activity = Activity(
            startTimeEpochMillis = startTime,
            activityType = sessionType,
            isAutoMode = autoMode,
            autoPauseSensitivity = autoPauseSensitivity,
            isTypeChosenByUser = !autoMode,
        )
        // Запись начинается с одного открытого сегмента: в фиксированном режиме он так
        // и останется единственным, в авторежиме его вид уточнит детектор.
        val firstSegment = activity.toSingleSegment()
        trackRepository.createActivity(activity, listOf(firstSegment)).onSuccess { id ->
            newActivityId = id
            pointBuffer.clear()
            sessionPoints.clear()
            liveProcessor.reset()
            lastFlushAtMillis = startTime
            waitingForFixSinceMillis = if (hasWarmFix) null else startTime
            lastStepCounter = null
            resetSegments(if (autoMode) listOf(firstSegment.copy(activityId = id)) else emptyList(), stepCount = null)
            _session.value = RecordingSession(
                status = initialStatus,
                activityId = id,
                activityType = sessionType,
                startTimeEpochMillis = startTime,
                // В авторежиме шаги считаются всю запись, но появятся с первым показанием:
                // без датчика их нет вовсе.
                stepCount = if (!autoMode && activityType.countsSteps) 0 else null,
                isAutoMode = autoMode,
                segmentKind = if (autoMode) SegmentKind.UNKNOWN else null,
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
                    "activityType" to sessionType.name,
                    "autoMode" to autoMode.toString(),
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
                // Открытый сегмент авторежима продолжается: его вид задаёт и профиль порогов,
                // и то, от чего детектор отсчитывает смену.
                val segments = if (activity.isAutoMode) {
                    (trackRepository.getSegments(activity.id) as? Result.Success)?.data.orEmpty()
                        .ifEmpty { listOf(activity.toSingleSegment()) }
                } else {
                    emptyList()
                }
                val segmentKind = segments.lastOrNull()?.kind
                // Чувствительность — та, с которой запись началась, а не текущая настройка.
                loadRecordingPreferences(
                    activityType = segmentKind?.profileActivityType ?: activity.activityType,
                    sensitivity = activity.autoPauseSensitivity,
                )
                resetSegments(segments, stepCount = activity.stepCount)
                pointBuffer.clear()
                val restoredPoints = when (val result = trackRepository.observeGeoPoints(activity.id).first()) {
                    is Result.Success -> result.data
                    is Result.Error -> emptyList()
                }
                // Вердикты фильтров пересчитываются с нуля: `isOutlier` липкий, и
                // восстановленная с ним точка осталась бы выбросом навсегда.
                val rawPoints = restoredPoints.map { it.copy(isOutlier = false, isStop = false) }
                sessionPoints.clear()
                sessionPoints += rawPoints
                liveProcessor.reset(rawPoints)
                // ponytail: весь восстановленный трек пересчитывается профилем текущего
                // сегмента; по профилю на сегмент — если восстановление смешанных записей
                // начнёт терять дистанцию.
                val processed = trackProcessor.process(rawPoints, activity.activityType, processingConfig)
                lastFlushAtMillis = nowMillis()
                waitingForFixSinceMillis = null
                // Шаги не флашатся в БД по ходу записи (см. applyStepCounter), поэтому
                // после восстановления процесса счёт продолжается с последнего
                // подтверждённого значения активности, а не с накопленного в памяти.
                lastStepCounter = null
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
                    stepCount = activity.stepCount,
                    isAutoMode = activity.isAutoMode,
                    segmentKind = segmentKind,
                    segmentDistancesMeters = if (activity.isAutoMode) {
                        synchronized(segmentLock) { segmentDistances(processed.statistics.distanceMeters) }
                    } else {
                        emptyMap()
                    },
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
                    "autoMode" to _session.value.isAutoMode.toString(),
                    "segmentKind" to _session.value.segmentKind.toString(),
                ) + recordingConfigFields(),
            ) { "Active tracking session recovered" }
        }
        return recovered
    }

    /**
     * Показание шагомера. Датчик кумулятивен с последней перезагрузки, а не за сессию:
     * шаги активности — это разница между первым увиденным значением (базой) и всеми
     * последующими. Разница засчитывается только в [RecordingStatus.RECORDING] и
     * [RecordingStatus.AUTO_PAUSE] — так же, как и время: ручная пауза и ожидание сигнала
     * из зачёта исключены.
     *
     * ponytail: значение не сохраняется в БД по ходу записи (в отличие от точек трека),
     * поэтому при убийстве процесса счёт после восстановления сессии продолжается с
     * последнего подтверждённого значения активности, а не с того, что было в памяти —
     * см. [recoverActiveSessionIfNeeded]. Апгрейд, если это станет заметно на практике: то
     * же периодическое сохранение, что и у точек ([flushBuffer]), но точечным UPDATE
     * колонки `step_count`, а не перезаписью всей строки активности.
     */
    suspend fun applyStepCounter(counter: Long) {
        val current = _session.value
        if (current.activityId == null) return
        logStepCounter(counter)
        if (current.isAutoMode) feedDetector(DetectorSignal.StepCounter(nowMillis(), counter))
        // В авторежиме шаги считаются всю запись: делятся они по сегментам, а в сводку
        // попадают только пешие.
        if (!current.isAutoMode && !current.activityType.countsSteps) return

        val previous = lastStepCounter
        lastStepCounter = counter
        if (previous == null) {
            // Первое показание — база, а не шаги: сама по себе она ничего не говорит о
            // пройденном за сессию расстоянии.
            _session.update { it.copy(stepCount = it.stepCount ?: 0) }
            return
        }

        // Перезагрузка обнуляет датчик: отрицательная разница ничего не значит, а
        // следующее показание само станет новой базой.
        val delta = (counter - previous).coerceAtLeast(0L).toInt()
        if (delta == 0 || current.status !in setOf(RecordingStatus.RECORDING, RecordingStatus.AUTO_PAUSE)) {
            return
        }
        val total = _session.updateAndGet { it.copy(stepCount = (it.stepCount ?: 0) + delta) }.stepCount ?: 0
        if (current.isAutoMode) synchronized(segmentLock) { stepTimeline += nowMillis() to total }
    }

    /**
     * Вердикт Activity Recognition. Нужен только авторежиму: живому детектору — лучший
     * вариант, декодеру при сохранении — ответ целиком, поэтому он сразу пишется в БД и
     * переживает восстановление после смерти процесса.
     */
    suspend fun applyActivityRecognition(signal: TrackingSignal.ActivityRecognized) {
        val current = _session.value
        if (!current.isAutoMode) return
        val now = nowMillis()
        val activity = signal.activityType.toRecognizedActivity() ?: RecognizedActivity.UNKNOWN
        current.activityId?.let { activityId ->
            trackRepository.addRecognitionSample(
                RecognitionSample(
                    activityId = activityId,
                    timestampEpochMillis = now,
                    confidences = signal.alternatives.mapNotNull { (name, confidence) ->
                        name.toRecognizedActivity()?.let { it to confidence }
                    }.toMap() + (activity to signal.confidencePercent),
                ),
            )
        }
        feedDetector(DetectorSignal.Recognized(now, activity, signal.confidencePercent))
    }

    private fun String.toRecognizedActivity(): RecognizedActivity? =
        RecognizedActivity.entries.firstOrNull { it.name == this }

    private fun resetSegments(segments: List<ActivitySegment>, stepCount: Int?) = synchronized(segmentLock) {
        sessionSegments.clear()
        sessionSegments += segments
        pendingSegmentWrites.clear()
        stepTimeline.clear()
        distanceTimeline.clear()
        stepTimelineBase = stepCount ?: 0
        detectorState = SegmentDetectorState(currentKind = segments.lastOrNull()?.kind?.takeIf { it != SegmentKind.UNKNOWN })
    }

    /** Счёт шагов до начала [stepTimeline] — то, с чем сессия стартовала или восстановилась. */
    private var stepTimelineBase = 0

    private fun stepsAt(timeMillis: Long): Int =
        stepTimeline.lastOrNull { it.first <= timeMillis }?.second ?: stepTimelineBase

    /** Живая дистанция на момент [timeMillis]; до первой точки — сумма закрытых сегментов. */
    private fun distanceAt(timeMillis: Long): Double =
        distanceTimeline.lastOrNull { it.first <= timeMillis }?.second ?: closedSegmentsDistance()

    private fun closedSegmentsDistance(): Double =
        sessionSegments.filter { it.endTimeEpochMillis != null }.sumOf(ActivitySegment::distanceMeters)

    /**
     * Живая разбивка дистанции по видам: закрытые сегменты — как были закрыты, открытому —
     * всё, что сверх них. Вызывается под [segmentLock].
     */
    private fun segmentDistances(totalMeters: Double): Map<SegmentKind, Double> {
        val open = sessionSegments.lastOrNull() ?: return emptyMap()
        val closed = sessionSegments.filter { it.endTimeEpochMillis != null }
        return (closed.map { it.kind to it.distanceMeters } +
            (open.kind to (totalMeters - closed.sumOf(ActivitySegment::distanceMeters)).coerceAtLeast(0.0)))
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, distances) -> distances.sum() }
    }

    /**
     * Ручная поправка вида текущего сегмента: граница — «сейчас», и детектор какое-то время
     * не спорит с пользователем. Работает и до первого фикса — вид можно выбрать сразу.
     */
    suspend fun changeSegmentKind(kind: SegmentKind) {
        val current = _session.value
        if (!current.isAutoMode || current.segmentKind == kind) return
        feedDetector(DetectorSignal.ManualKind(nowMillis(), kind))
    }

    /**
     * Сигнал детектору. Решения — в лог; подтверждённая смена вида режет сегмент в памяти
     * сразу, а в БД — следом, по очереди.
     */
    private suspend fun feedDetector(signal: DetectorSignal) {
        val status = _session.value.status
        if (status == RecordingStatus.STOPPED) return
        if (status == RecordingStatus.WAITING_FOR_GPS && signal !is DetectorSignal.ManualKind) return
        synchronized(segmentLock) {
            if (sessionSegments.isEmpty()) return
            val step = segmentDetector.reduce(detectorState, signal)
            detectorState = step.state
            step.events.forEach { event ->
                logDetectorEvent(event)
                if (event is DetectorEvent.SegmentConfirmed) pendingSegmentWrites += confirmSegment(event)
            }
        }
        segmentWriteMutex.withLock {
            while (true) {
                val (current, next) = synchronized(segmentLock) { pendingSegmentWrites.removeFirstOrNull() } ?: break
                trackRepository.saveOpenSegment(current, next)
            }
        }
    }

    /**
     * Первый подтверждённый вид не режет запись, а достаётся открытому `UNKNOWN`-сегменту:
     * до подтверждения человек двигался так же. Дальше граница ставится задним числом,
     * шаги до неё уходят закрываемому сегменту. Вместе с видом меняется профиль порогов
     * трека — без сброса обработчика.
     *
     * Вызывается под [segmentLock]; возвращает запись для БД.
     */
    private fun confirmSegment(event: DetectorEvent.SegmentConfirmed): Pair<ActivitySegment, ActivitySegment?> {
        val open = sessionSegments.last()
        val countsSteps = _session.value.stepCount != null
        val write = if (open.kind == SegmentKind.UNKNOWN) {
            val updated = open.copy(kind = event.to, isManuallyEdited = event.isManual)
            sessionSegments[sessionSegments.lastIndex] = updated
            updated to null
        } else {
            val boundary = event.boundaryMillis.coerceAtLeast(open.startTimeEpochMillis)
            val closed = open.copy(
                endTimeEpochMillis = boundary,
                stepCount = if (countsSteps) stepsAt(boundary) - stepsAt(open.startTimeEpochMillis) else null,
                distanceMeters = (distanceAt(boundary) - closedSegmentsDistance()).coerceAtLeast(0.0),
            )
            val next = ActivitySegment(
                activityId = open.activityId,
                kind = event.to,
                startTimeEpochMillis = boundary,
                isManuallyEdited = event.isManual,
            )
            sessionSegments[sessionSegments.lastIndex] = closed
            sessionSegments += next
            closed to next
        }
        processingConfig = TrackProcessingConfig.forActivityType(event.to.profileActivityType)
            .withAutoPauseSensitivity(autoPauseSensitivity)
        liveProcessor.switchConfig(processingConfig)
        _session.update {
            it.copy(
                activityType = event.to.activityType,
                segmentKind = event.to,
                segmentDistancesMeters = segmentDistances(it.distanceMeters),
            )
        }
        return write
    }

    private fun logDetectorEvent(event: DetectorEvent) {
        val fields = when (event) {
            is DetectorEvent.CandidateStarted -> mapOf("decision" to "CANDIDATE", "kind" to event.kind.name)
            is DetectorEvent.CandidateDropped -> mapOf("decision" to "DROPPED", "kind" to event.kind.name)
            is DetectorEvent.VerdictRejected -> mapOf(
                "decision" to "REJECTED",
                "detectedActivity" to event.activity.name,
                "confidencePercent" to event.confidencePercent.toString(),
                "reason" to event.reason.name,
            )
            is DetectorEvent.SegmentConfirmed -> mapOf(
                "decision" to "CONFIRMED",
                "from" to event.from.toString(),
                "to" to event.to.name,
                "boundaryTimestamp" to event.boundaryMillis.toString(),
                "manual" to event.isManual.toString(),
            )
        }
        val allFields = fields + ("signalTimestamp" to event.timestampMillis.toString())
        if (event is DetectorEvent.SegmentConfirmed) {
            logger.info(LogCategory.TRACKING, TAG, fields = allFields) { "Segment detector decision" }
        } else {
            logger.debug(LogCategory.TRACKING, TAG, fields = allFields) { "Segment detector decision" }
        }
    }

    /**
     * Показание пишется для любого типа активности: по логам калибруется детектор вида
     * движения, и нужно проверить, считает ли шагомер шаги при педалировании.
     */
    private fun logStepCounter(counter: Long) {
        val now = nowMillis()
        if (lastStepLoggedAtMillis?.let { now - it < STEP_LOG_INTERVAL_MILLIS } == true) return
        lastStepLoggedAtMillis = now
        logger.debug(
            category = LogCategory.TRACKING,
            tag = TAG,
            fields = mapOf(
                "readingTimestamp" to now.toString(),
                "stepCounter" to counter.toString(),
            ),
        ) { "Step counter reading" }
    }

    suspend fun applySample(sample: LocationSample) {
        val now = nowMillis()
        // Негодный фикс отбрасывается до всего остального, включая отметку о пойманном
        // сигнале: по кэшированной координате нельзя ни начать запись, ни считать, что
        // приёмник прогрелся.
        val rejection = sample.usabilityRejection()
        if (rejection == null && sample.timestampEpochMillis == lastAcceptedSampleTimestampMillis) {
            logger.debug(
                category = LogCategory.GPS,
                tag = TAG,
                fields = sampleFields(sample) + mapOf("rejectedBecause" to "DUPLICATE"),
            ) { "Location sample rejected before processing" }
            return
        }
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
        lastAcceptedSampleTimestampMillis = sample.timestampEpochMillis
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
        //
        // Обе записи несут `sampleTimestamp` — время самого фикса, а не время попадания
        // в лог. Оно же служит ключом, по которому калибровка сшивает координаты с
        // остальными полями сэмпла, и оно же задаёт ось времени всему конвейеру.
        logger.debug(
            category = LogCategory.GPS,
            tag = TAG,
            fields = sampleFields(sample) + mapOf("status" to current.status.name),
        ) { "Location sample received" }
        // Уровень здесь именно DEBUG, а не VERBOSE. Запись и так закрыта отдельным
        // согласием (`containsSensitiveLocation`), и требовать сверх него ещё и самый
        // подробный уровень значило бы молча выбрасывать координаты у всех, кто согласие
        // дал, но уровень не тронул, — а по умолчанию он как раз DEBUG. Именно так и
        // получались логи, по которым нельзя восстановить трек.
        logger.debug(
            category = LogCategory.GPS,
            tag = TAG,
            fields = mapOf(
                "sampleTimestamp" to sample.timestampEpochMillis.toString(),
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
        if (current.isAutoMode) {
            update.filterSpeed?.let { feedDetector(DetectorSignal.Speed(sample.timestampEpochMillis, it.metersPerSecond)) }
        }
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
                fields = autoPauseDecisionFields(sample, statistics, update) + mapOf(
                    "from" to current.status.name,
                    "to" to nextStatus.name,
                ),
            ) { "Automatic recording state changed" }
        } else if (nextStatus == RecordingStatus.AUTO_PAUSE) {
            logger.debug(
                category = LogCategory.TRACKING,
                tag = TAG,
                fields = autoPauseDecisionFields(sample, statistics, update),
            ) { "Auto pause held" }
        }

        val segmentDistances = if (current.isAutoMode) {
            synchronized(segmentLock) {
                distanceTimeline += sample.timestampEpochMillis to statistics.distanceMeters
                segmentDistances(statistics.distanceMeters)
            }
        } else {
            emptyMap()
        }
        _session.update {
            it.copy(
                status = nextStatus,
                segmentDistancesMeters = segmentDistances,
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
        update: LiveTrackUpdate,
    ): Map<String, String> = sampleFields(
        sample = sample,
        previousPoint = sessionPoints.getOrNull(sessionPoints.lastIndex - 1),
    ) + mapOf(
        "currentSpeed" to statistics.currentSpeedMetersPerSecond.toString(),
        // Оценка фильтра — не то же самое, что `currentSpeed`: та на стоянке обнуляется
        // и показывает уже принятое решение. Без этих двух полей по логу нельзя понять,
        // почему сработала пауза, — разбирать приходилось, повторяя цикл детектора
        // отдельным прогоном.
        "filterSpeed" to (update.filterSpeed?.metersPerSecond?.toString() ?: ""),
        "filterSpeedUncertainty" to (update.filterSpeed?.uncertaintyMetersPerSecond?.toString() ?: ""),
        // Медиана доплера по окну детектора за вычетом его же погрешности: ровно та
        // величина, которая теперь и решает, отменять ли стоянку.
        "windowDopplerLowerBound" to (windowDopplerLowerBound()?.toString() ?: ""),
        "windowPointCount" to stopWindowPoints().size.toString(),
        "stopSpeedMetersPerSecond" to processingConfig.stopSpeedMetersPerSecond.toString(),
        "resumeSpeedMetersPerSecond" to processingConfig.resumeSpeedMetersPerSecond.toString(),
        "stopWindowMillis" to processingConfig.stopWindowMillis.toString(),
    )

    /** Точки, попадающие в окно детектора остановок на текущий момент. */
    private fun stopWindowPoints(): List<GeoPoint> {
        val last = sessionPoints.lastOrNull() ?: return emptyList()
        return sessionPoints.filter {
            last.timestampEpochMillis - it.timestampEpochMillis <= processingConfig.stopWindowMillis
        }
    }

    private fun windowDopplerLowerBound(): Double? {
        val bounds = stopWindowPoints().mapNotNull { point ->
            val speed = point.speedMetersPerSecond?.toDouble()?.takeIf { it.isFinite() && it >= 0.0 }
                ?: return@mapNotNull null
            val sigma = point.speedAccuracyMetersPerSecond?.toDouble()?.takeIf { it.isFinite() && it > 0.0 }
                ?: processingConfig.defaultSpeedAccuracyMetersPerSecond
            speed - sigma
        }.sorted()
        return bounds.getOrNull(bounds.size / 2)
    }

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
        // Время фикса, а не время записи в лог: между ними лежит очередь логгера, и по
        // второму нельзя ни восстановить шаг опроса, ни сшить запись с координатами.
        "sampleTimestamp" to sample.timestampEpochMillis.toString(),
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
                    isAutoMode = current.isAutoMode,
                    autoPauseSensitivity = autoPauseSensitivity,
                    isTypeChosenByUser = !current.isAutoMode,
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
        if (current.isAutoMode) reduceDetectorWithoutWrites(DetectorSignal.Paused(nowMillis()))
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
        if (current.isAutoMode) reduceDetectorWithoutWrites(DetectorSignal.Resumed(nowMillis()))
    }

    /** Пауза и возобновление смены вида не подтверждают — писать в БД нечего. */
    private fun reduceDetectorWithoutWrites(signal: DetectorSignal) = synchronized(segmentLock) {
        val step = segmentDetector.reduce(detectorState, signal)
        detectorState = step.state
        step.events.forEach(::logDetectorEvent)
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
        // Под тем же мьютексом, что и границы сегментов: отложенная запись границы, выполненная
        // после перезаписи, вставила бы лишний открытый сегмент в уже закрытую запись.
        val finalized = segmentWriteMutex.withLock {
            val activity = Activity(
                id = activityId,
                startTimeEpochMillis = current.startTimeEpochMillis,
                endTimeEpochMillis = endTime,
                activityType = current.activityType,
                elapsedTimeMillis = elapsedTime,
                isPendingSummary = pendingSummary,
                stepCount = current.stepCount,
                isAutoMode = current.isAutoMode,
                autoPauseSensitivity = autoPauseSensitivity,
                isTypeChosenByUser = !current.isAutoMode,
            )
            // Открытый сегмент перезаписывается закрытым целиком из сессии: так он
            // переживает и восстановление после смерти процесса, и сдвиг старта к фиксу.
            val segments = if (current.isAutoMode) {
                decodedSessionSegments(current, activityId, endTime)
            } else {
                listOf(activity.toSingleSegment())
            }
            finalizeTrack(activity, sessionPoints, segments).also { track ->
                trackRepository.replaceTrack(track.activity, track.points, track.segments)
                resetSegments(emptyList(), stepCount = null)
            }
        }

        logger.info(
            category = LogCategory.TRACKING,
            tag = TAG,
            fields = mapOf(
                "activityId" to activityId.toString(),
                "pointCount" to finalized.points.size.toString(),
                "elapsedTimeMillis" to elapsedTime.toString(),
                "pendingSummary" to pendingSummary.toString(),
            ) + trackQualityFields(finalized.points, finalized.statistics, elapsedTime),
        ) { "Tracking session stopped" }

        // Область закрывается последней: запись об остановке должна попасть в лог активности.
        activityLogScope.end()
        pointBuffer.clear()
        sessionPoints.clear()
        liveProcessor.reset()
        waitingForFixSinceMillis = null
        lastStepCounter = null
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

    /**
     * Итоговые сегменты авторежима. Вид по всей записи определяет декодер: живой детектор
     * решал, не зная будущего, и принимал, например, минуту «машины» посреди велопоездки.
     * Ручные поправки пользователя декодер не оспаривает. Если распознавание так и не
     * сказало ничего о движении, остаются живые сегменты.
     */
    private suspend fun decodedSessionSegments(
        session: RecordingSession,
        activityId: Long,
        endTime: Long,
    ): List<ActivitySegment> {
        val live = closedSessionSegments(session, endTime)
        val recognitions = (trackRepository.getRecognitionSamples(activityId) as? Result.Success)?.data.orEmpty()
        val steps = synchronized(segmentLock) {
            stepTimeline.map { (time, total) -> DetectorSignal.StepCounter(time, total.toLong()) }
        }
        val decoded = modeDecoder.decode(
            ModeDecoderInput(
                startMillis = session.startTimeEpochMillis,
                endMillis = endTime,
                points = sessionPoints.toList(),
                recognitions = recognitions,
                steps = steps,
                manualSegments = live.filter(ActivitySegment::isManuallyEdited),
            ),
        )
        logger.info(
            category = LogCategory.TRACKING,
            tag = TAG,
            fields = mapOf(
                "recognitionCount" to recognitions.size.toString(),
                "liveSegments" to live.describe(session.startTimeEpochMillis),
                "decodedSegments" to decoded.describe(session.startTimeEpochMillis),
            ),
        ) { "Activity mode decoded" }
        if (decoded.isEmpty()) return live
        return synchronized(segmentLock) {
            decoded.map { segment ->
                val end = segment.endTimeEpochMillis ?: endTime
                segment.copy(
                    activityId = activityId,
                    stepCount = session.stepCount?.let { stepsAt(end) - stepsAt(segment.startTimeEpochMillis) },
                )
            }
        }
    }

    /** `CYCLING@0:00-39:30,ON_FOOT@39:30-71:34` — по логу видно, где встали границы. */
    private fun List<ActivitySegment>.describe(startMillis: Long): String = joinToString(",") { segment ->
        fun offset(millis: Long?) = millis?.let {
            val seconds = (it - startMillis) / 1_000
            String.format(Locale.US, "%d:%02d", seconds / 60, seconds % 60)
        } ?: "open"
        "${segment.kind}@${offset(segment.startTimeEpochMillis)}-${offset(segment.endTimeEpochMillis)}"
    }

    /**
     * Сегменты авторежима на момент остановки: последний закрывается, первый начинается
     * вместе с активностью (старт сдвигается к первому фиксу). Дистанция живая — по ней
     * решается слияние коротких, итоговую посчитает обработка по сегментам.
     */
    private fun closedSessionSegments(session: RecordingSession, endTime: Long): List<ActivitySegment> =
        synchronized(segmentLock) {
            val closedDistance = closedSegmentsDistance()
            sessionSegments.mapIndexed { index, segment ->
                val start = if (index == 0) session.startTimeEpochMillis else segment.startTimeEpochMillis
                val end = segment.endTimeEpochMillis ?: endTime
                segment.copy(
                    startTimeEpochMillis = start,
                    endTimeEpochMillis = end,
                    stepCount = when {
                        session.stepCount == null -> null
                        segment.endTimeEpochMillis == null -> stepsAt(end) - stepsAt(segment.startTimeEpochMillis)
                        else -> segment.stepCount
                    },
                    distanceMeters = if (segment.endTimeEpochMillis == null) {
                        (session.distanceMeters - closedDistance).coerceAtLeast(0.0)
                    } else {
                        segment.distanceMeters
                    },
                )
            }
        }

    private suspend fun loadRecordingPreferences(
        activityType: ActivityType,
        sensitivity: AutoPauseSensitivity? = null,
    ) {
        val preferences = preferencesRepository.observePreferences().first()
        autoPauseEnabled = preferences.autoPauseEnabled
        autoPauseSensitivity = sensitivity ?: preferences.autoPauseSensitivity
        gpsAccuracy = preferences.gpsAccuracy
        processingConfig = TrackProcessingConfig.forActivityType(activityType)
            .withAutoPauseSensitivity(autoPauseSensitivity)
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
